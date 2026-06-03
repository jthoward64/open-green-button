package org.opengb.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import org.opengb.AppDeps
import org.opengb.espi.EspiNormalizer
import org.opengb.espi.EspiParser
import org.opengb.oauth.OAuthException
import org.opengb.proxy.BlobDecryptionException
import org.opengb.proxy.NewCredentialsDto
import org.opengb.proxy.RefreshBlob
import org.opengb.proxy.TokenCrypto
import org.opengb.proxy.UsageClient
import org.opengb.proxy.UsageClientException
import org.opengb.proxy.toResponse
import org.opengb.utility.UnknownUtilityException
import org.opengb.utility.UtilityProfile
import org.opengb.utility.UtilityRegistry

/**
 * `POST /proxy/usage` — pulls a window of ESPI usage data on behalf of the HA client.
 *
 * The request carries the encrypted refresh blob and a bearer-token proof-of-possession
 * (`proxy_token`). The server is fully stateless: each call decrypts the blob, verifies the
 * token in constant time, refreshes the utility access token, fetches the Atom feed from the
 * utility's resource server, parses + normalizes it, and returns the result.
 *
 * Refresh-token rotation (RFC 6749 §6) is surfaced via `newCredentials` in the response —
 * the HA client replaces its stored values when present so the next call uses the rotated
 * token.
 */
fun Application.installProxyUsage(
  deps: AppDeps,
  usageClient: UsageClient,
) {
  routing {
    post("/proxy/usage") { handleProxyUsage(deps, usageClient) }
  }
}

@Serializable
data class ProxyUsageRequest(
  val encryptedRefreshBlob: String,
  /** ESPI `published-min` filter, epoch seconds. */
  val publishedMin: Long? = null,
  /** ESPI `published-max` filter, epoch seconds. */
  val publishedMax: Long? = null,
)

private suspend fun RoutingContext.handleProxyUsage(
  deps: AppDeps,
  usageClient: UsageClient,
) {
  val presentedToken =
    call.bearerToken() ?: return call.respondError(
      HttpStatusCode.Unauthorized,
      "missing_bearer_token",
    )
  val request = call.parseRequest() ?: return
  val blob = call.decryptBlob(deps.crypto, request.encryptedRefreshBlob) ?: return
  if (!deps.crypto.verifyProxyToken(blob, presentedToken)) {
    return call.respondError(HttpStatusCode.Unauthorized, "invalid_credentials")
  }
  val utility = call.resolveUtility(deps.registry, blob.utilityId) ?: return
  val subscriptionUri = blob.subscriptionUri
  if (subscriptionUri.isNullOrBlank()) {
    return call.respondError(HttpStatusCode.BadRequest, "no_subscription_uri")
  }

  val refreshed = call.refreshAccessToken(deps, utility, blob.refreshToken) ?: return
  val xml = call.fetchUsage(usageClient, subscriptionUri, refreshed.accessToken, request) ?: return
  val normalized = call.parseAndNormalize(xml) ?: return

  val newCredentials = rotatedCredentials(deps.crypto, blob, refreshed.refreshToken)
  call.respond(HttpStatusCode.OK, normalized.toResponse(newCredentials))
}

private fun ApplicationCall.bearerToken(): String? {
  val header = request.headers["Authorization"]?.trim() ?: return null
  if (!header.startsWith("Bearer ", ignoreCase = true)) return null
  return header.substring("Bearer ".length).trim().takeIf { it.isNotEmpty() }
}

private suspend fun ApplicationCall.parseRequest(): ProxyUsageRequest? =
  try {
    receive<ProxyUsageRequest>()
  } catch (e: SerializationException) {
    respondError(HttpStatusCode.BadRequest, "invalid_request", e.message)
    null
  }

private suspend fun ApplicationCall.decryptBlob(
  crypto: TokenCrypto,
  encrypted: String,
): RefreshBlob? =
  try {
    crypto.decrypt(encrypted)
  } catch (e: BlobDecryptionException) {
    respondError(HttpStatusCode.BadRequest, "invalid_blob", e.message)
    null
  }

private suspend fun ApplicationCall.resolveUtility(
  registry: UtilityRegistry,
  utilityId: String,
): UtilityProfile? =
  try {
    registry.require(utilityId)
  } catch (_: UnknownUtilityException) {
    // The blob's utility id refers to a profile the server no longer knows. Treat as a
    // permanent state mismatch — HA should re-run the connect flow against a current utility.
    respondError(HttpStatusCode.BadRequest, "unknown_utility", "utility_id=$utilityId")
    null
  }

private data class RefreshOutcome(val accessToken: String, val refreshToken: String?)

private suspend fun ApplicationCall.refreshAccessToken(
  deps: AppDeps,
  utility: UtilityProfile,
  refreshToken: String,
): RefreshOutcome? =
  try {
    val tokens = deps.oauth.refresh(utility, refreshToken)
    RefreshOutcome(accessToken = tokens.accessToken, refreshToken = tokens.refreshToken)
  } catch (e: OAuthException) {
    // 4xx on the token endpoint = utility rejected our refresh token (expired, revoked,
    // scope changed). HA should observe `utility_auth_expired` and trigger the reauth flow.
    // 5xx or no status = upstream transient — HA should retry, not reauth.
    val authRejected = e.statusCode in AUTH_REJECTED_STATUSES
    val status = if (authRejected) HttpStatusCode.Unauthorized else HttpStatusCode.BadGateway
    val errorKey = if (authRejected) "utility_auth_expired" else "utility_upstream_error"
    respondError(status, errorKey, e.message)
    null
  }

private suspend fun ApplicationCall.fetchUsage(
  client: UsageClient,
  subscriptionUri: String,
  accessToken: String,
  request: ProxyUsageRequest,
): String? =
  try {
    client.fetch(
      subscriptionUri = subscriptionUri,
      accessToken = accessToken,
      publishedMin = request.publishedMin,
      publishedMax = request.publishedMax,
    )
  } catch (e: UsageClientException) {
    respondError(HttpStatusCode.BadGateway, "utility_upstream_error", e.message)
    null
  }

// xmlutil and our normalizer can throw a wide variety of RuntimeException subtypes —
// SerializationException, XmlException, IllegalArgumentException, IndexOutOfBoundsException.
// We never want a parse failure to escape as 500: that hides the real problem (utility
// returned non-ESPI XML) and tells HA the proxy itself is broken when it isn't.
@Suppress("TooGenericExceptionCaught")
private suspend fun ApplicationCall.parseAndNormalize(xml: String): org.opengb.espi.NormalizedUsage? =
  try {
    EspiNormalizer.normalizeUsage(EspiParser.parseFeed(xml))
  } catch (e: RuntimeException) {
    respondError(HttpStatusCode.BadGateway, "utility_response_unparseable", e.message)
    null
  }

private fun rotatedCredentials(
  crypto: TokenCrypto,
  oldBlob: RefreshBlob,
  newRefreshToken: String?,
): NewCredentialsDto? {
  if (newRefreshToken == null || newRefreshToken == oldBlob.refreshToken) return null
  val updated = oldBlob.copy(refreshToken = newRefreshToken)
  return NewCredentialsDto(
    encryptedRefreshBlob = crypto.encrypt(updated),
    proxyToken = crypto.deriveProxyToken(updated),
  )
}

private suspend fun ApplicationCall.respondError(
  status: HttpStatusCode,
  error: String,
  message: String? = null,
) = respond(status, ErrorBody(error = error, message = message))

// HTTP status codes from the utility token endpoint that mean "your refresh token is no
// good." RFC 6749 §5.2 maps these to invalid_grant/invalid_client/access_denied.
@Suppress("MagicNumber")
private val AUTH_REJECTED_STATUSES = setOf(400, 401, 403)
