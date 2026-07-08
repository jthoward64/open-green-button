package org.opengb.routes

import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import org.opengb.AppDeps
import org.opengb.oauth.OAuthException
import org.opengb.proxy.BlobDecryptionException
import org.opengb.proxy.RefreshBlob
import org.opengb.proxy.TokenCrypto
import org.opengb.proxy.UsageClient
import org.opengb.utility.UnknownUtilityException
import org.opengb.utility.UtilityProfile
import org.opengb.utility.UtilityRegistry
import kotlin.time.Instant

/**
 * `POST /proxy/usage` — pure streaming pass-through.
 *
 * The proxy decrypts the refresh blob, refreshes the access token at the utility's token
 * endpoint, GETs the subscription URI, and streams the upstream Atom feed body **byte for
 * byte** into the response. No XML parsing, no normalization — those happen on the HA
 * client. This keeps the proxy's memory footprint at O(network buffer) regardless of
 * how much history the utility returns.
 *
 * Rotated credentials (RFC 6749 §6) are surfaced via response **headers** rather than a
 * JSON envelope:
 *
 *   - `OpenGB-New-Encrypted-Refresh-Blob`
 *   - `OpenGB-New-Proxy-Token`
 *
 * The HA client checks for these on every successful response and updates its config entry
 * when present.
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
  /** ESPI `published-min` filter. JSON wire form is ISO 8601 with `Z` suffix, e.g.
   *  `2026-02-24T05:00:00Z` — kotlinx-serialization's built-in [Instant] serializer
   *  parses/emits that format. */
  val publishedMin: Instant? = null,
  /** ESPI `published-max` filter — same wire format. */
  val publishedMax: Instant? = null,
  /**
   * DIAGNOSTIC override of the ESPI date-filter query-parameter base name. Default (`null`) sends
   * `published-min`/`published-max`; set e.g. `"updated"` to send `updated-min`/`updated-max`.
   * Exists to probe what a non-conforming Data Custodian (savagedata) actually accepts without a
   * redeploy per experiment. The normal HA client never sends it.
   */
  val dateFilterParam: String? = null,
)

const val HEADER_NEW_ENCRYPTED_REFRESH_BLOB: String = "OpenGB-New-Encrypted-Refresh-Blob"
const val HEADER_NEW_PROXY_TOKEN: String = "OpenGB-New-Proxy-Token"

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

  // The refresh above may have redeemed a ONE-TIME refresh token (e.g. OpenIddict, which savagedata
  // runs), invalidating the blob the client still holds. Emit the rotated credentials NOW — before
  // the resource fetch — so they reach the client on *every* outcome, including a fetch timeout or
  // upstream error, not just the success path. Otherwise a post-refresh failure strands the client
  // with a dead refresh token and forces a full re-authorization. (Headers set here are committed
  // with whatever response we ultimately send, error or stream.)
  val newCredentials = rotatedCredentials(deps.crypto, blob, refreshed.refreshToken)
  newCredentials?.let {
    call.response.header(HEADER_NEW_ENCRYPTED_REFRESH_BLOB, it.encryptedRefreshBlob)
    call.response.header(HEADER_NEW_PROXY_TOKEN, it.proxyToken)
  }

  call.streamUsage(usageClient, utility, subscriptionUri, refreshed.accessToken, request)
}

private fun ApplicationCall.bearerToken(): String? {
  val header = request.headers["Authorization"]?.trim() ?: return null
  if (!header.startsWith("Bearer ", ignoreCase = true)) return null
  return header.substring("Bearer ".length).trim().takeIf { it.isNotEmpty() }
}

private suspend fun ApplicationCall.parseRequest(): ProxyUsageRequest? =
  try {
    receive<ProxyUsageRequest>()
  } catch (e: BadRequestException) {
    // ContentNegotiation wraps the converter's failure (kotlinx-serialization throwing on
    // a malformed body, missing required field, or type mismatch like a JSON number where
    // an Instant was expected) in BadRequestException — the original SerializationException
    // is the `cause`. Surface its message so the client sees what was actually wrong.
    respondError(HttpStatusCode.BadRequest, "invalid_request", e.cause?.message ?: e.message)
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

private data class NewCredentials(val encryptedRefreshBlob: String, val proxyToken: String)

private fun rotatedCredentials(
  crypto: TokenCrypto,
  oldBlob: RefreshBlob,
  newRefreshToken: String?,
): NewCredentials? {
  if (newRefreshToken == null || newRefreshToken == oldBlob.refreshToken) return null
  val updated = oldBlob.copy(refreshToken = newRefreshToken)
  return NewCredentials(
    encryptedRefreshBlob = crypto.encrypt(updated),
    proxyToken = crypto.deriveProxyToken(updated),
  )
}

@Suppress("TooGenericExceptionCaught")
private suspend fun ApplicationCall.streamUsage(
  client: UsageClient,
  utility: UtilityProfile,
  subscriptionUri: String,
  accessToken: String,
  request: ProxyUsageRequest,
) {
  // TRUE zero-copy streaming: run the whole response inside the client's `execute { }` block (so the
  // upstream body channel is never buffered), and respond with a pull-based [ByteReadChannelContent].
  // The engine consumes that channel *as part of* `respond(...)`, so the copy finishes before the
  // block returns — the upstream stays alive throughout, and memory is O(engine buffer), not O(feed).
  // (The no-block `execute()` reads the whole body into memory → OOM on a multi-MB ESPI feed;
  // `respondBytesWriter` defers its producer until after the block returns → ClosedByteChannelException.)
  var responseStarted = false
  try {
    client
      .fetch(
        utility = utility,
        subscriptionUri = subscriptionUri,
        accessToken = accessToken,
        publishedMin = request.publishedMin,
        publishedMax = request.publishedMax,
        dateFilterParam = request.dateFilterParam,
      ).execute { upstream ->
        when {
          upstream.status == HttpStatusCode.Accepted -> handleUpstreamAccepted(upstream)
          upstream.status != HttpStatusCode.OK -> handleUpstreamFailure(upstream)
          else -> {
            val upstreamContentType =
              upstream.headers[HttpHeaders.ContentType]?.let { ContentType.parse(it) } ?: ESPI_ATOM_XML
            responseStarted = true
            respond(ByteReadChannelContent(upstream.bodyAsChannel(), upstreamContentType))
          }
        }
      }
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    // A failure BEFORE we started responding (timeout / connection reset / TLS) is a clean, retryable
    // upstream error — the rotated-credentials headers set by the caller ride along, so the client
    // keeps its refreshed token. Once streaming has begun the headers are committed, so we can't send
    // an error body; let the exception surface as a truncated stream.
    if (responseStarted) throw e
    respondError(
      HttpStatusCode.BadGateway,
      "utility_upstream_error",
      "Resource fetch failed for $subscriptionUri: ${e.message}",
    )
  }
}

private suspend fun ApplicationCall.handleUpstreamAccepted(upstream: HttpResponse) {
  // ESPI asynchronous batch delivery: the utility accepted the request but the dataset is
  // large enough that it's being assembled out-of-band. Per spec it will later POST an ESPI
  // Notification (a BatchList of resource URIs) to our registered NotificationURI — which we
  // currently discard (see Notify.kt). Until that retrieval flow exists, surface a DISTINCT,
  // machine-readable signal — passing the utility's 202 semantics through with a dedicated
  // `utility_data_pending` error key — so the HA client can guide the user instead of looping
  // on a generic upstream error.
  respondError(
    HttpStatusCode.Accepted,
    "utility_data_pending",
    "Utility returned 202 Accepted for ${upstream.call.request.url}: the dataset is being " +
      "prepared asynchronously and background (async batch) delivery is not yet supported",
  )
}

private suspend fun ApplicationCall.handleUpstreamFailure(upstream: HttpResponse) {
  val body = upstream.bodyAsText().take(MAX_UPSTREAM_ERROR_SNIPPET)
  // Forward the DC's RAW response detail — status, headers, and body — so an upstream failure is
  // diagnosable without a live reproduction. The full URL (including any date filter we appended)
  // shows exactly what we asked for; the response headers carry the real signal when the body is
  // empty (e.g. savagedata returns a bare 400, but an `x-response-time-ms`/`server` header proves
  // the request reached their app rather than being bounced at the edge).
  val responseHeaders =
    upstream.headers.entries().joinToString(", ") { (name, values) -> "$name: ${values.joinToString(",")}" }
  respondError(
    HttpStatusCode.BadGateway,
    "utility_upstream_error",
    "Resource server returned ${upstream.status.value} for ${upstream.call.request.url} | " +
      "response-headers: [$responseHeaders] | body: $body",
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

private val ESPI_ATOM_XML = ContentType("application", "atom+xml")
private const val MAX_UPSTREAM_ERROR_SNIPPET = 500
