package org.opengb.utility

import com.sksamuel.hoplite.Masked

/**
 * Static, per-utility configuration. One profile per data custodian we integrate with.
 *
 * All URLs and credentials are global (shared across every Home Assistant instance using this
 * proxy for the given utility), which is consistent with the stateless invariant: the only state
 * that lives on the server is what all users share, not what any individual user owns.
 */
data class UtilityProfile(
  val id: String,
  val displayName: String,
  val authorizeUrl: String,
  val tokenUrl: String,
  val clientId: String,
  val clientSecret: Masked,
  val defaultScope: String,
  /** Where the utility POSTs notifications. The proxy registers this URL at app submission time. */
  val notificationPath: String = "/notify/$id",
  val tokenAuthStyle: TokenAuthStyle = TokenAuthStyle.HTTP_BASIC,
  val quirks: UtilityQuirks = UtilityQuirks(),
)

enum class TokenAuthStyle {
  /** HTTP Basic header carrying client_id:client_secret. */
  HTTP_BASIC,

  /** client_id and client_secret as form params in the body. */
  FORM_BODY,
}

/**
 * Per-utility flags that escape-hatch around spec deviations. Add booleans here as we encounter
 * non-conforming behaviour in the wild; do NOT add behaviour-changing logic to the registry itself.
 */
data class UtilityQuirks(
  val sendsRefreshTokenOnRefresh: Boolean = true,
  val requiresClientCredentialsForMetadata: Boolean = false,
)

data class UtilitiesConfig(val utilities: List<UtilityProfile> = emptyList())
