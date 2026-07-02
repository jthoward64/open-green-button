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
  /**
   * How far back the client backfills usage on the *initial* authorization, expressed as a
   * human-friendly window (`2y`, `6m`, `90d`). Surfaced to the Home Assistant client in the claim
   * response (as seconds); the client uses it to compute `published-min` on the first fetch.
   *
   * Per-utility because retention and the volume a utility will serve on a first pull vary — some
   * may not support 2 years. This is the single source of truth for the backfill window; the
   * client only falls back to its own default when an entry predates this field. Distinct from the
   * OAuth-scope `HistoryLength` preference (which asks the *utility* how much to authorize) — see
   * utilities.conf; for the test-lab harness the scope value isn't accepted, so this is the
   * effective control.
   */
  val initialHistory: String = "2y",
  /** Where the utility POSTs notifications. The proxy registers this URL at app submission time. */
  val notificationPath: String = "/notify/$id",
  val tokenAuthStyle: TokenAuthStyle = TokenAuthStyle.HTTP_BASIC,
  val quirks: UtilityQuirks = UtilityQuirks(),
) {
  /** [initialHistory] parsed to seconds. Throws if the configured spec is malformed. */
  val initialHistorySeconds: Long
    get() = parseHistoryWindowSeconds(initialHistory)
}

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
