package org.opengb.http

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.network.tls.addKeyStore
import org.opengb.config.AppConfig
import org.opengb.config.ClientAuthConfig
import org.opengb.utility.UtilityProfile
import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.util.Base64

/**
 * Owns the outbound [HttpClient]s used to talk to utility endpoints and resolves which one a given
 * utility should use.
 *
 * mTLS client certificates are negotiated per TLS connection, and a CIO client pins one keystore
 * for its whole connection pool — so utilities that must present *different* certs cannot share a
 * single client. We therefore keep:
 *
 *  - one [default] client, built from [AppConfig.clientAuth] (the self-signed cert, or plain TLS
 *    when no default material is configured), shared by every utility without its own override, and
 *  - one client per utility that declares its own [UtilityProfile.clientAuth] material.
 *
 * [forUtility] does the default/override lookup the whole feature is about: a utility's own client
 * if it has one, else the default.
 */
class UtilityHttpClients private constructor(
  private val default: HttpClient,
  private val perUtility: Map<String, HttpClient>,
  /** Clients we constructed and are responsible for closing (excludes an injected test client). */
  private val owned: List<HttpClient>,
) : AutoCloseable {
  /** The client a connection to [utility] should use: its own override if present, else the default. */
  fun forUtility(utility: UtilityProfile): HttpClient = perUtility[utility.id] ?: default

  override fun close() {
    owned.forEach { runCatching { it.close() } }
  }

  companion object {
    /**
     * Test seam: route every utility through one caller-supplied client (e.g. a Ktor MockEngine).
     * We don't own it, so [close] leaves it alone.
     */
    fun singleClient(http: HttpClient): UtilityHttpClients =
      UtilityHttpClients(default = http, perUtility = emptyMap(), owned = emptyList())

    /**
     * Build the real clients from config: a default client plus one per utility that carries its
     * own client-auth material.
     */
    fun from(config: AppConfig): UtilityHttpClients {
      val default = buildClient(config.clientAuth)
      val perUtility =
        config.utilities
          .filter { it.clientAuth?.hasMaterial() == true }
          .associate { it.id to buildClient(it.clientAuth) }
      return UtilityHttpClients(default, perUtility, owned = listOf(default) + perUtility.values)
    }

    private fun buildClient(auth: ClientAuthConfig?): HttpClient {
      if (auth == null || !auth.hasMaterial()) return HttpClient(CIO)
      val storePassword =
        requireNotNull(auth.keystorePassword?.value) {
          "clientAuth.keystorePassword is required when a keystore is configured"
        }.toCharArray()
      val keyStore = loadKeyStore(auth, storePassword)
      // A separately-encrypted key entry can carry its own password; otherwise it shares the
      // keystore password (the common case for an `openssl pkcs12 -export` bundle).
      val keyPassword = auth.keyPassword?.value?.toCharArray() ?: storePassword
      return HttpClient(CIO) {
        engine {
          https {
            // Presents the private key + cert chain from the keystore as our client certificate
            // during the TLS handshake. `keyAlias == null` selects the sole key entry.
            addKeyStore(keyStore, keyPassword, auth.keyAlias)
          }
        }
      }
    }

    private fun loadKeyStore(
      auth: ClientAuthConfig,
      password: CharArray,
    ): KeyStore {
      val bytes =
        try {
          Base64.getDecoder().decode(auth.keystoreBase64?.value?.trim().orEmpty())
        } catch (e: IllegalArgumentException) {
          throw IllegalArgumentException("clientAuth.keystoreBase64 is not valid base64", e)
        }
      return KeyStore.getInstance(auth.keystoreType).apply {
        ByteArrayInputStream(bytes).use { load(it, password) }
      }
    }
  }
}
