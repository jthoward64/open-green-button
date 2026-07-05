package org.opengb.routes

import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.discardRemaining
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
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.copyTo
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

  // Compute the rotated credentials BEFORE we start streaming. Once we begin writing the
  // response body, headers are committed and we can no longer add OpenGB-New-* headers.
  val newCredentials = rotatedCredentials(deps.crypto, blob, refreshed.refreshToken)

  call.streamUsage(
    usageClient,
    utility,
    subscriptionUri,
    refreshed.accessToken,
    request,
    newCredentials,
  )
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

@Suppress("LongParameterList")
private suspend fun ApplicationCall.streamUsage(
  client: UsageClient,
  utility: UtilityProfile,
  subscriptionUri: String,
  accessToken: String,
  request: ProxyUsageRequest,
  newCredentials: NewCredentials?,
) {
  // `execute()` (no block variant) returns an HttpResponse we own — its body channel stays
  // open until *we* cancel it, not when an enclosing block returns. That's the lifecycle
  // shape `respondBytesWriter` needs: it commits headers and runs its producer (which reads
  // from upstream) as part of the engine's body-writing phase, which can run after this
  // function's syntactic body has handed control back to Ktor. With the `execute { block }`
  // form, the block returns before body writing finishes and upstream gets torn down mid-
  // copy → ClosedByteChannelException.
  val upstream: HttpResponse =
    client
      .fetch(
        utility = utility,
        subscriptionUri = subscriptionUri,
        accessToken = accessToken,
        publishedMin = request.publishedMin,
        publishedMax = request.publishedMax,
      ).execute()
  try {
    if (upstream.status == HttpStatusCode.Accepted) {
      handleUpstreamAccepted(upstream)
      return
    }
    if (upstream.status != HttpStatusCode.OK) {
      handleUpstreamFailure(upstream)
      return
    }
    newCredentials?.let {
      response.header(HEADER_NEW_ENCRYPTED_REFRESH_BLOB, it.encryptedRefreshBlob)
      response.header(HEADER_NEW_PROXY_TOKEN, it.proxyToken)
    }
    val upstreamContentType =
      upstream.headers[HttpHeaders.ContentType]?.let { ContentType.parse(it) }
        ?: ESPI_ATOM_XML

    // True zero-buffer streaming. respondBytesWriter suspends until its producer block
    // completes, and copyTo only returns when upstream EOFs. Combined with the explicit
    // `execute()` (no auto-close) above, the upstream channel is alive throughout the copy.
    respondBytesWriter(upstreamContentType) {
      upstream.bodyAsChannel().copyTo(this)
    }
  } finally {
    // Explicitly release the upstream — `discardRemaining` reads and drops any unread bytes
    // (none in the happy path; only the 4xx/5xx error-body snippet path leaves anything),
    // returning the connection to the client's pool. Without it the HTTP/2 stream slot
    // leaks and a busy server eventually starves on the client connection pool.
    runCatching { upstream.discardRemaining() }
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
  // Include the full URL we actually sent (including any published-min/max we appended) so
  // a 4xx body that's empty or non-informative still tells the caller exactly what shape we
  // asked for. Without this, debugging the test lab's strict timestamp parser is guesswork.
  respondError(
    HttpStatusCode.BadGateway,
    "utility_upstream_error",
    "Resource server returned ${upstream.status.value} for ${upstream.call.request.url}: $body",
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
