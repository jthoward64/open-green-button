package org.opengb.config

import com.sksamuel.hoplite.Masked

/**
 * TLS **client** authentication (mTLS) material: the private key + certificate chain we present to
 * a utility's endpoints so it can validate that the connection is really us.
 *
 * There are two levels of this config (see [AppConfig.clientAuth] and
 * [org.opengb.utility.UtilityProfile.clientAuth]):
 *
 *  - a **default** block, not tied to any utility — typically the self-signed cert we upload to
 *    utilities that accept one, and
 *  - optional **per-utility** overrides for custodians with different requirements (e.g. a cert the
 *    utility itself issues).
 *
 * A connection to a utility uses that utility's own block when present, otherwise the default (see
 * [org.opengb.http.UtilityHttpClients]). A block with no [keystoreBase64] means "no client auth" —
 * so leaving the default unset simply disables mTLS everywhere, and per-utility material is opt-in.
 *
 * The keystore itself never lives in the repo: [keystoreBase64] and the passwords are injected from
 * Fly secrets / env vars (see `application.conf`). Binary can't ride an env var intact, so the
 * PKCS12/JKS bytes are base64-encoded.
 */
data class ClientAuthConfig(
  /**
   * Base64-encoded keystore bytes ([keystoreType]) holding our private key and client-cert chain.
   * Null/blank ⇒ no client authentication for whatever this block governs.
   */
  val keystoreBase64: Masked? = null,
  /** Password protecting the keystore. Required when [keystoreBase64] is set. */
  val keystorePassword: Masked? = null,
  /** Keystore format: `PKCS12` (preferred) or `JKS`. */
  val keystoreType: String = "PKCS12",
  /**
   * Alias of the key entry to present when the keystore holds more than one. Null ⇒ the sole key
   * entry (what `openssl ... -export` produces).
   */
  val keyAlias: String? = null,
  /**
   * Password protecting the private-key entry, when it differs from [keystorePassword]. For a
   * PKCS12 produced by `openssl pkcs12 -export` the two are identical, so this is normally null.
   */
  val keyPassword: Masked? = null,
) {
  /** True when this block actually carries key material to present. */
  fun hasMaterial(): Boolean = !keystoreBase64?.value.isNullOrBlank()
}
