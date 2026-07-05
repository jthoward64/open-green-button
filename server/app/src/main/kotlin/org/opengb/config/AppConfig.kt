package org.opengb.config

import com.github.rocketraman.bootable.config.common.HostPort
import com.sksamuel.hoplite.ConfigLoaderBuilder
import com.sksamuel.hoplite.Masked
import com.sksamuel.hoplite.addResourceSource
import org.opengb.utility.UtilityProfile

data class AppConfig(
  val server: ServerConfig,
  val crypto: CryptoConfig,
  val state: StateConfig,
  val landing: LandingConfig,
  /**
   * Default TLS client-authentication (mTLS) material — not tied to any utility. Applied to every
   * utility that doesn't declare its own [UtilityProfile.clientAuth]. Null / no keystore ⇒ mTLS
   * off by default. See [ClientAuthConfig].
   */
  val clientAuth: ClientAuthConfig? = null,
  val utilities: List<UtilityProfile> = emptyList(),
)

data class ServerConfig(
  override val hostPort: String = "0.0.0.0:8080",
  val publicBaseUrl: String,
  /**
   * Canonical marketing hostname (e.g. `opengreenbutton.org`). When set, the landing page emits
   * `<link rel="canonical">` pointing here, and requests from [redirectFrom] hosts 301 to here.
   * Distinct from [publicBaseUrl] — that one is the OAuth domain where the utility's
   * `redirect_uri` is registered and **must not** be redirected.
   */
  val canonicalHost: String? = null,
  /**
   * Hostnames that should 301 to [canonicalHost], preserving path + query. Typically just the
   * `www.` variant. The OAuth host (`api.*`) must not appear here or the OAuth callback breaks.
   */
  val redirectFrom: List<String> = emptyList(),
) : HostPort

data class CryptoConfig(
  /** Base64-encoded 32-byte AES-GCM key. */
  val aesKeyBase64: Masked,
  /** Base64-encoded HMAC pepper for proxy_token derivation. */
  val hmacPepperBase64: Masked,
)

data class StateConfig(
  val oauthStateTtlSeconds: Long = 600,
  val claimCodeTtlSeconds: Long = 600,
  val requestLogPerClient: Int = 50,
)

data class LandingConfig(
  val tagline: String = "Open Green Button — bridge utility energy data into Home Assistant.",
)

fun loadConfig(): AppConfig =
  ConfigLoaderBuilder.default()
    .addResourceSource("/application.conf")
    .addResourceSource("/utilities.conf")
    .build()
    .loadConfigOrThrow<AppConfig>()
