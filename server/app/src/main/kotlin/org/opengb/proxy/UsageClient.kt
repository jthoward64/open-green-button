package org.opengb.proxy

import io.ktor.client.request.headers
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpStatement
import io.ktor.http.HttpHeaders
import io.ktor.http.URLBuilder
import org.opengb.http.UtilityHttpClients
import org.opengb.utility.UtilityProfile
import kotlin.time.Instant

/**
 * Prepares a streaming GET against an ESPI resource server — typically the
 * `subscription_uri` returned in the OAuth token response, e.g.
 * `https://.../espi/1_1/resource/Batch/Subscription/<uuid>`.
 *
 * Returns an unfinalized [HttpStatement] rather than the body itself; the caller is expected
 * to `.execute { response -> ... }` and stream `response.bodyAsChannel()` directly into the
 * outbound proxy response without materializing it in memory. This is what lets the proxy
 * stay tiny even when a utility returns multi-MB Atom feeds.
 */
class UsageClient(private val clients: UtilityHttpClients) {
  @Suppress("LongParameterList")
  suspend fun fetch(
    utility: UtilityProfile,
    subscriptionUri: String,
    accessToken: String,
    publishedMin: Instant? = null,
    publishedMax: Instant? = null,
    dateFilterParam: String? = null,
  ): HttpStatement {
    // Base name of the ESPI date-range query params (`published` → published-min/published-max).
    // Overridable per request for diagnosing a non-conforming custodian (e.g. `updated`).
    val base = dateFilterParam?.takeIf { it.isNotBlank() } ?: "published"
    // ESPI expects ISO 8601 (Instant.toString(), e.g. 2026-06-08T00:00:00Z) for these params.
    // Clamp published-max to now: no utility publishes future-dated readings, and savagedata
    // rejects a future published-max with a bare 400 (the Home Assistant client sends now + a
    // 1-day lookahead margin, which is what tripped this).
    val effectiveMax = publishedMax?.let { minOf(it, nowInstant()) }
    val url =
      URLBuilder(subscriptionUri)
        .apply {
          publishedMin?.let { parameters.append("$base-min", it.toString()) }
          effectiveMax?.let { parameters.append("$base-max", it.toString()) }
        }.buildString()

    return clients.forUtility(utility).prepareGet(url) {
      headers {
        append(HttpHeaders.Authorization, "Bearer $accessToken")
        append(HttpHeaders.Accept, "application/atom+xml, application/xml")
      }
    }
  }

  // Whole-second precision, on purpose: Burlington's Green Button platform rejects a published-max
  // that carries sub-second precision with a bare 400, and the raw millis from currentTimeMillis()
  // would leak into the ISO string (…:31.383Z) when we clamp a future max down to now.
  private fun nowInstant(): Instant = Instant.fromEpochSeconds(System.currentTimeMillis() / MILLIS_PER_SECOND)

  private companion object {
    const val MILLIS_PER_SECOND = 1000L
  }
}
