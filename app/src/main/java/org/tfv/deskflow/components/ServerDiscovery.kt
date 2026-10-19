/*
 * MIT License
 *
 * Copyright (c) 2025 Jonathan Glanz
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */


package org.tfv.deskflow.components

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.tfv.deskflow.client.models.SERVER_DEFAULT_PORT
import org.tfv.deskflow.client.util.logging.KLoggingManager

/** A Deskflow server found on the local network. */
data class DiscoveredServer(
  val address: String,
  val port: Int,
  /** Host / computer name, if known (e.g. "Julians-MacBook-Pro") */
  val name: String?,
  /** The server answered with a Deskflow (Synergy/Barrier) hello */
  val verified: Boolean,
  /** The hello was only received via TLS */
  val useTls: Boolean,
) {
  val displayName: String
    get() = name?.takeIf { it.isNotBlank() } ?: address
}

/**
 * Finds Deskflow servers on the local network, so no hostname or IP address
 * has to be entered manually.
 *
 * Two strategies are combined:
 * 1. mDNS / DNS-SD (Bonjour): Deskflow/Synergy service types and services
 *    that macOS announces by default (to learn computer names & addresses).
 * 2. A scan of the local (Wi-Fi/Ethernet) subnet on the Deskflow port.
 *
 * Every candidate is verified by reading the server hello (plain or TLS).
 */
class ServerDiscovery(context: Context) {

  private val appContext = context.applicationContext

  private val connectivityManager =
    appContext.getSystemService(ConnectivityManager::class.java)

  private val nsdManager = appContext.getSystemService(NsdManager::class.java)

  /**
   * Search the local network.
   *
   * @param port the port to scan, in addition to ports announced via mDNS
   * @return found servers, best match first
   */
  suspend fun discover(port: Int = SERVER_DEFAULT_PORT): List<DiscoveredServer> =
    coroutineScope {
      val effectivePort = if (port in 1..65535) port else SERVER_DEFAULT_PORT
      val mdnsJob = async { browseMdns(MDNS_BROWSE_MS) }

      val semaphore = Semaphore(PROBE_CONCURRENCY)
      val probedKeys = ConcurrentHashMap.newKeySet<String>()
      val found = ConcurrentHashMap<String, DiscoveredServer>()

      suspend fun probeAll(candidates: Collection<Pair<String, Int>>) =
        candidates
          .filter { (host, p) -> probedKeys.add("$host:$p") }
          .map { (host, p) ->
            async(Dispatchers.IO) {
              semaphore.withPermit {
                probe(host, p)?.let { server -> found["$host:$p"] = server }
              }
            }
          }
          .awaitAll()

      val subnetHosts = localSubnetHosts()
      log.info { "Scanning ${subnetHosts.size} hosts on port $effectivePort" }
      probeAll(subnetHosts.map { it to effectivePort })

      val mdnsHits = mdnsJob.await()
      log.info { "mDNS found ${mdnsHits.size} hosts" }
      probeAll(
        mdnsHits.flatMap { hit ->
          listOfNotNull(
            hit.address to effectivePort,
            hit.deskflowPort?.let { hit.address to it },
          )
        }
      )

      val names = mdnsHits.associate { it.address to it.name }

      found.values
        .map { server ->
          async(Dispatchers.IO) {
            server.copy(name = names[server.address] ?: reverseLookup(server.address))
          }
        }
        .awaitAll()
        .sortedWith(
          compareByDescending<DiscoveredServer> { it.verified }
            .thenByDescending { it.name != null }
            .thenBy { it.address }
        )
        .also { servers ->
          log.info { "Discovered servers: ${servers.joinToString { "${it.displayName}(${it.address}:${it.port})" }}" }
        }
    }

  /** Check whether a Deskflow server is listening at [host]:[port]. */
  private fun probe(host: String, port: Int): DiscoveredServer? {
    val address = InetSocketAddress(host, port)
    val socket = Socket()
    try {
      socket.connect(address, CONNECT_TIMEOUT_MS)
    } catch (ignored: IOException) {
      closeQuietly(socket)
      return null
    }

    val plainHello =
      try {
        socket.soTimeout = HELLO_TIMEOUT_MS
        readHello(socket.getInputStream())
      } catch (ignored: IOException) {
        false
      } finally {
        closeQuietly(socket)
      }

    if (plainHello) {
      return DiscoveredServer(host, port, null, verified = true, useTls = false)
    }

    // TLS enabled servers only talk after a handshake
    val tlsHello =
      try {
        probeTls(address)
      } catch (ignored: Exception) {
        false
      }

    // An open Deskflow port without a hello is still a candidate
    return DiscoveredServer(host, port, null, verified = tlsHello, useTls = tlsHello)
  }

  private fun probeTls(address: InetSocketAddress): Boolean {
    val sslContext = SSLContext.getInstance("TLS")
    sslContext.init(null, arrayOf(TrustAllManager), null)

    val raw = Socket()
    try {
      raw.connect(address, CONNECT_TIMEOUT_MS)
      raw.soTimeout = TLS_TIMEOUT_MS
      val sslSocket =
        sslContext.socketFactory.createSocket(
          raw,
          address.hostString,
          address.port,
          true,
        ) as SSLSocket
      return sslSocket.use {
        it.soTimeout = TLS_TIMEOUT_MS
        it.startHandshake()
        readHello(it.inputStream)
      }
    } finally {
      closeQuietly(raw)
    }
  }

  /**
   * Read the server hello: `int32 length`, protocol name (e.g. "Synergy" or
   * "Barrier"), `int16 major`, `int16 minor`.
   */
  private fun readHello(input: InputStream): Boolean {
    val din = DataInputStream(input)
    val length = din.readInt()
    if (length < HELLO_MIN_SIZE || length > HELLO_MAX_SIZE) return false

    val name = ByteArray(HELLO_PROTOCOL_NAME_SIZE)
    din.readFully(name)
    return name.all { it.toInt().toChar().isLetter() }
  }

  /** All IPv4 host addresses of the local Wi-Fi/Ethernet subnet. */
  private fun localSubnetHosts(): List<String> {
    val network = connectivityManager.activeNetwork ?: return emptyList()
    val caps = connectivityManager.getNetworkCapabilities(network)
    if (
      caps == null ||
        !(caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
          caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
    ) {
      log.warn { "Not connected to Wi-Fi/Ethernet, skipping subnet scan" }
      return emptyList()
    }

    val linkProperties =
      connectivityManager.getLinkProperties(network) ?: return emptyList()
    val linkAddress =
      linkProperties.linkAddresses.firstOrNull {
        it.address is Inet4Address && !it.address.isLoopbackAddress
      } ?: return emptyList()

    val ownBytes = linkAddress.address.address
    val own =
      ownBytes.fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }

    // Large networks: only scan the /24 around our own address
    val prefix =
      linkAddress.prefixLength.let { if (it < MIN_SCAN_PREFIX) 24 else it }
    if (prefix > 30) return emptyList()

    val mask = (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
    val networkAddress = own and mask
    val broadcast = networkAddress or (mask.inv() and 0xFFFFFFFFL)

    return ((networkAddress + 1) until broadcast)
      .filter { it != own }
      .map { ip ->
        "${(ip shr 24) and 0xFF}.${(ip shr 16) and 0xFF}.${(ip shr 8) and 0xFF}.${ip and 0xFF}"
      }
  }

  private data class MdnsHit(
    val address: String,
    val name: String,
    /** Port, if the service is a Deskflow/Synergy service */
    val deskflowPort: Int?,
  )

  /** Browse well known mDNS service types for [durationMs]. */
  @SuppressLint("NewApi")
  private suspend fun browseMdns(durationMs: Long): List<MdnsHit> {
    val hits = ConcurrentHashMap<String, MdnsHit>()
    val executor = Executors.newSingleThreadExecutor()
    val discoveryListeners = mutableListOf<NsdManager.DiscoveryListener>()
    val infoCallbacks = ConcurrentHashMap<String, NsdManager.ServiceInfoCallback>()

    fun onResolved(info: NsdServiceInfo, isDeskflowType: Boolean) {
      val name = info.serviceName?.let { cleanServiceName(it) } ?: return
      info.hostAddresses
        .filterIsInstance<Inet4Address>()
        .mapNotNull { it.hostAddress }
        .forEach { address ->
          val existing = hits[address]
          if (existing == null || (isDeskflowType && existing.deskflowPort == null)) {
            hits[address] =
              MdnsHit(address, name, if (isDeskflowType) info.port else existing?.deskflowPort)
          }
        }
    }

    try {
      for (type in MDNS_SERVICE_TYPES) {
        val isDeskflowType = type in MDNS_DESKFLOW_SERVICE_TYPES
        val listener =
          object : NsdManager.DiscoveryListener {
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
              val key = "${serviceInfo.serviceType}/${serviceInfo.serviceName}"
              if (infoCallbacks.containsKey(key)) return
              val callback =
                object : NsdManager.ServiceInfoCallback {
                  override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {}

                  override fun onServiceUpdated(serviceInfo: NsdServiceInfo) {
                    onResolved(serviceInfo, isDeskflowType)
                  }

                  override fun onServiceLost() {}

                  override fun onServiceInfoCallbackUnregistered() {}
                }
              infoCallbacks[key] = callback
              try {
                nsdManager.registerServiceInfoCallback(serviceInfo, executor, callback)
              } catch (err: Exception) {
                log.debug { "Unable to resolve $key: ${err.message}" }
                infoCallbacks.remove(key)
              }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}

            override fun onDiscoveryStarted(serviceType: String) {}

            override fun onDiscoveryStopped(serviceType: String) {}

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
              log.debug { "mDNS discovery failed for $serviceType ($errorCode)" }
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
          }

        try {
          nsdManager.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)
          discoveryListeners.add(listener)
        } catch (err: Exception) {
          log.debug { "Unable to browse $type: ${err.message}" }
        }
      }

      delay(durationMs)
    } finally {
      withContext(kotlinx.coroutines.NonCancellable) {
        discoveryListeners.forEach { listener ->
          try {
            nsdManager.stopServiceDiscovery(listener)
          } catch (ignored: Exception) {}
        }
        infoCallbacks.values.forEach { callback ->
          try {
            nsdManager.unregisterServiceInfoCallback(callback)
          } catch (ignored: Exception) {}
        }
        executor.shutdown()
      }
    }

    return hits.values.toList()
  }

  /** Reverse DNS lookup, many routers know the computer names. */
  private suspend fun reverseLookup(address: String): String? =
    withTimeoutOrNull(REVERSE_LOOKUP_TIMEOUT_MS) {
      withContext(Dispatchers.IO) {
        try {
          InetAddress.getByName(address).canonicalHostName.takeIf {
            it != address
          }
        } catch (ignored: Exception) {
          null
        }
      }
    }

  private fun cleanServiceName(name: String): String =
    // e.g. "Julians MacBook Pro" or "AA:BB:CC:DD:EE:FF@Julians MacBook Pro"
    name.substringAfter('@').trim()

  private fun closeQuietly(socket: Socket) {
    try {
      socket.close()
    } catch (ignored: IOException) {}
  }

  @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
  private object TrustAllManager : X509TrustManager {
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
  }

  companion object {
    private val log = KLoggingManager.logger(ServerDiscovery::class.java.simpleName)

    private const val CONNECT_TIMEOUT_MS = 400
    private const val HELLO_TIMEOUT_MS = 800
    private const val TLS_TIMEOUT_MS = 1500
    private const val PROBE_CONCURRENCY = 64
    private const val MDNS_BROWSE_MS = 3000L
    private const val REVERSE_LOOKUP_TIMEOUT_MS = 1500L
    private const val MIN_SCAN_PREFIX = 22

    private const val HELLO_PROTOCOL_NAME_SIZE = 7
    private const val HELLO_MIN_SIZE = HELLO_PROTOCOL_NAME_SIZE + 4
    private const val HELLO_MAX_SIZE = 64

    /** Service types that announce a Deskflow/Synergy server directly */
    private val MDNS_DESKFLOW_SERVICE_TYPES =
      setOf("_deskflow._tcp", "_synergyServerZeroconf._tcp", "_barrier._tcp")

    /**
     * Service types announced by macOS (and often other desktops) by default.
     * Used to learn computer names & addresses, the Deskflow port is probed.
     */
    private val MDNS_HOST_SERVICE_TYPES =
      setOf(
        "_companion-link._tcp",
        "_rfb._tcp",
        "_ssh._tcp",
        "_smb._tcp",
        "_airplay._tcp",
      )

    private val MDNS_SERVICE_TYPES =
      MDNS_DESKFLOW_SERVICE_TYPES + MDNS_HOST_SERVICE_TYPES
  }
}
