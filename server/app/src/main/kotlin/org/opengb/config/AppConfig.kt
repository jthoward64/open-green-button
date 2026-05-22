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
    val utilities: List<UtilityProfile> = emptyList(),
)

data class ServerConfig(
    override val hostPort: String = "0.0.0.0:8080",
    val publicBaseUrl: String,
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
    val mode: LandingMode = LandingMode.COMING_SOON,
    val tagline: String = "Open Green Button — bridge utility energy data into Home Assistant.",
)

enum class LandingMode { COMING_SOON, LIVE }

fun loadConfig(): AppConfig =
    ConfigLoaderBuilder.default()
        .addResourceSource("/application.conf")
        .addResourceSource("/utilities.conf")
        .build()
        .loadConfigOrThrow<AppConfig>()
