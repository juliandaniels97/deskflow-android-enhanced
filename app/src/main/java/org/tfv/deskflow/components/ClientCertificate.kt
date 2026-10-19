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

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager
import javax.security.auth.x500.X500Principal

object ClientCertificate {

  private const val KEYSTORE = "AndroidKeyStore"
  private const val ALIAS = "deskflow-client-certificate"
  private const val VALIDITY_YEARS = 25L

  private data class Material(val key: PrivateKey, val certificate: X509Certificate)

  private val material by lazy {
    val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
    if (!keyStore.containsAlias(ALIAS)) generate()

    Material(
      keyStore.getKey(ALIAS, null) as PrivateKey,
      keyStore.getCertificate(ALIAS) as X509Certificate,
    )
  }

  private fun generate() {
    val now = System.currentTimeMillis()
    val day = 24L * 60 * 60 * 1000
    val generator =
      KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE)
    generator.initialize(
      KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
        .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
        .setDigests(
          KeyProperties.DIGEST_NONE,
          KeyProperties.DIGEST_SHA256,
          KeyProperties.DIGEST_SHA384,
          KeyProperties.DIGEST_SHA512,
        )
        .setCertificateSubject(X500Principal("CN=Deskflow-Android"))
        .setCertificateSerialNumber(BigInteger.valueOf(now))
        .setCertificateNotBefore(Date(now - day))
        .setCertificateNotAfter(Date(now + VALIDITY_YEARS * 365 * day))
        .build()
    )
    generator.generateKeyPair()
  }

  fun fingerprint(): String =
    MessageDigest.getInstance("SHA-256").digest(material.certificate.encoded).joinToString(":") {
      "%02X".format(it)
    }

  fun keyManagers(): Array<KeyManager> {
    val (key, certificate) = material
    return arrayOf(SingleKeyManager(key, arrayOf(certificate)))
  }

  private class SingleKeyManager(
    private val key: PrivateKey,
    private val chain: Array<X509Certificate>,
  ) : X509ExtendedKeyManager() {
    override fun getClientAliases(keyType: String?, issuers: Array<Principal>?): Array<String> =
      arrayOf(ALIAS)

    override fun chooseClientAlias(
      keyType: Array<String>?,
      issuers: Array<Principal>?,
      socket: Socket?,
    ): String = ALIAS

    override fun chooseEngineClientAlias(
      keyType: Array<String>?,
      issuers: Array<Principal>?,
      engine: SSLEngine?,
    ): String = ALIAS

    override fun getServerAliases(keyType: String?, issuers: Array<Principal>?): Array<String>? =
      null

    override fun chooseServerAlias(
      keyType: String?,
      issuers: Array<Principal>?,
      socket: Socket?,
    ): String? = null

    override fun getCertificateChain(alias: String?): Array<X509Certificate> = chain

    override fun getPrivateKey(alias: String?): PrivateKey = key
  }
}
