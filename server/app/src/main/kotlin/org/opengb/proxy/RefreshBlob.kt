package org.opengb.proxy

import kotlinx.serialization.Serializable

/**
 * Plaintext payload stored inside the encrypted refresh blob that travels HA ↔ server on every proxy call.
 *
 * The server never persists this. It encrypts it on the OAuth callback, hands the ciphertext to HA via
 * the claim code, and decrypts each subsequent request body to recover it just long enough to call the
 * utility's token / data endpoints.
 */
@Serializable
data class RefreshBlob(
  val utilityId: String,
  val refreshToken: String,
  val subscriptionUri: String? = null,
  val authorizationUri: String? = null,
  val scope: String? = null,
  /** Issued-at epoch seconds — used only for diagnostic surfacing in the dashboard. */
  val issuedAt: Long = System.currentTimeMillis() / 1000,
)
