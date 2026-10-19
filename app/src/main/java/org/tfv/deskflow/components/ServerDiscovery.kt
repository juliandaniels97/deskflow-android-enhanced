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
import java.util.concurrent.Executor
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
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

data class DiscoveredServer(
  val address: String,
  val port: Int,
  val name: String?,
  val verified: Boolean,
  val useTls: Boolean,
) {
  val displayName: String
    get() = name?.takeIf { it.isNotBlank() } ?: address
}

class ServerDiscovery(context: Context) {

  private val connectivityManager =
    context.getSystemService(ConnectivityManager::class.java)

  private val nsdManager = context.getSystemService(NsdManager::class.java)

  suspend fun discover(port: Int = SERVER_DEFAULT_PORT): List<DiscoveredServer> =
    coroutineScope {
      val effectivePort = if (port in 1..65535) port else SERVER_DEFAULT_PORT
      val mdnsJob = async { browseMdns() }

      val semaphore = Semaphore(PROBE_CONCURRENCY)
      val probedKeys = ConcurrentHashMap.newKeySet<String>()
      val found = ConcurrentHashMap<String, DiscoveredServer>()

      suspend fun probeAll(candidates: List<Pair<String, Int>>) =
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

      probeAll(localSubnetHosts().map { it to effectivePort })

      val mdnsHits = mdnsJob.await()
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
          log.info {
            "Discovered servers: ${servers.joinToString { "${it.displayName}(${it.address}:${it.port})" }}"
          }
        }
    }

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

    val isTlsServer =
      try {
        probeTls(address)
      } catch (ignored: Exception) {
        false
      }
    return DiscoveredServer(host, port, null, verified = isTlsServer, useTls = isTlsServer)
  }

  private fun probeTls(address: InetSocketAddress): Boolean {
    val sslContext = SSLContext.getInstance("TLS")
    sslContext.init(ClientCertificate.keyManagers(), arrayOf(TrustAllManager), null)

    val raw = Socket()
    try {
      raw.connect(address, CONNECT_TIMEOUT_MS)
      val sslSocket =
        sslContext.socketFactory.createSocket(raw, address.hostString, address.port, true)
          as SSLSocket
      return sslSocket.use {
        it.soTimeout = TLS_TIMEOUT_MS
        it.startHandshake()
        runCatching { readHello(it.inputStream) }
        true
      }
    } finally {
      closeQuietly(raw)
    }
  }

  private fun readHello(input: InputStream): Boolean {
    val din = DataInputStream(input)
    val length = din.readInt()
    if (length < HELLO_MIN_SIZE || length > HELLO_MAX_SIZE) return false

    val name = ByteArray(HELLO_PROTOCOL_NAME_SIZE)
    din.readFully(name)
    return name.all { it.toInt().toChar().isLetter() }
  }

  private fun localSubnetHosts(): List<String> {
    val network = connectivityManager.activeNetwork ?: return emptyList()
    val caps = connectivityManager.getNetworkCapabilities(network) ?: return emptyList()
    if (
      !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
        !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    ) {
      return emptyList()
    }

    val linkAddress =
      connectivityManager.getLinkProperties(network)?.linkAddresses?.firstOrNull {
        it.address is Inet4Address && !it.address.isLoopbackAddress
      } ?: return emptyList()

    val own = linkAddress.address.address.fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }
    val prefix = linkAddress.prefixLength.let { if (it < MIN_SCAN_PREFIX) 24 else it }
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

  private data class MdnsHit(val address: String, val name: String, val deskflowPort: Int?)

  @SuppressLint("NewApi")
  private suspend fun browseMdns(): List<MdnsHit> {
    val hits = ConcurrentHashMap<String, MdnsHit>()
    val executor = Executor { command ->
      try {
        command.run()
      } catch (err: Exception) {
        log.warn { "mDNS callback failed: ${err.message}" }
      }
    }
    val discoveryListeners = mutableListOf<NsdManager.DiscoveryListener>()
    val infoCallbacks = ConcurrentHashMap<String, NsdManager.ServiceInfoCallback>()

    fun onResolved(info: NsdServiceInfo, isDeskflowType: Boolean) {
      val name = info.serviceName?.substringAfter('@')?.trim() ?: return
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
              try {
                nsdManager.registerServiceInfoCallback(serviceInfo, executor, callback)
                infoCallbacks[key] = callback
              } catch (ignored: Exception) {}
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}

            override fun onDiscoveryStarted(serviceType: String) {}

            override fun onDiscoveryStopped(serviceType: String) {}

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
          }

        try {
          nsdManager.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)
          discoveryListeners.add(listener)
        } catch (ignored: Exception) {}
      }

      delay(MDNS_BROWSE_MS)
    } finally {
      withContext(NonCancellable) {
        discoveryListeners.forEach {
          try {
            nsdManager.stopServiceDiscovery(it)
          } catch (ignored: Exception) {}
        }
        infoCallbacks.values.forEach {
          try {
            nsdManager.unregisterServiceInfoCallback(it)
          } catch (ignored: Exception) {}
        }
      }
    }

    return hits.values.toList()
  }

  private suspend fun reverseLookup(address: String): String? =
    withTimeoutOrNull(REVERSE_LOOKUP_TIMEOUT_MS) {
      withContext(Dispatchers.IO) {
        try {
          InetAddress.getByName(address).canonicalHostName.takeIf { it != address }
        } catch (ignored: Exception) {
          null
        }
      }
    }

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

    private val MDNS_DESKFLOW_SERVICE_TYPES =
      setOf("_deskflow._tcp", "_synergyServerZeroconf._tcp", "_barrier._tcp")

    private val MDNS_SERVICE_TYPES =
      MDNS_DESKFLOW_SERVICE_TYPES +
        setOf("_companion-link._tcp", "_rfb._tcp", "_ssh._tcp", "_smb._tcp", "_airplay._tcp")
  }
}
