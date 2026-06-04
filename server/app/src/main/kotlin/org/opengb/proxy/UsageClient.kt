package org.opengb.proxy

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import kotlin.time.Instant

/**
 * Thin GET against an ESPI resource server — typically the `subscription_uri` returned in
 * the OAuth token response, which on ESPI 1.1 looks like
 * `https://.../espi/1_1/resource/Batch/Subscription/<uuid>`. One request, one Atom feed back.
 *
 * Per FB_37 (in our requested scope), the resource server accepts `published-min` /
 * `published-max` epoch-second query params to filter readings to a window — we surface
 * those so the HA coordinator can avoid re-pulling history on every wake-up.
 */
class UsageClient(private val http: HttpClient) {
  suspend fun fetch(
    subscriptionUri: String,
    accessToken: String,
    publishedMin: Instant? = null,
    publishedMax: Instant? = null,
  ): String {
    // Burlington Hydro's test-lab harness wants ISO 8601 with a `Z` suffix
    // (e.g. `2026-02-24T05:00:00Z`); `Instant.toString()` produces exactly that for
    // UTC instants, which is all kotlin.time.Instant ever is.
    val url =
      URLBuilder(subscriptionUri)
        .apply {
          publishedMin?.let { parameters.append("published-min", it.toString()) }
          publishedMax?.let { parameters.append("published-max", it.toString()) }
        }.buildString()

    val response: HttpResponse =
      http.get(url) {
        headers {
          append(HttpHeaders.Authorization, "Bearer $accessToken")
          append(HttpHeaders.Accept, "application/atom+xml, application/xml")
        }
      }

    if (response.status != HttpStatusCode.OK) {
      val body = response.bodyAsText().take(MAX_ERROR_SNIPPET)
      // Surface the full URL — including any published-min/max we appended — so a 4xx body
      // that's empty or non-informative still tells us exactly what shape we asked for.
      throw UsageClientException(
        "Resource server returned ${response.status.value} for $url: $body",
        statusCode = response.status.value,
      )
    }
    return response.bodyAsText()
  }

  companion object {
    private const val MAX_ERROR_SNIPPET = 500
  }
}

class UsageClientException(
  message: String,
  val statusCode: Int? = null,
  cause: Throwable? = null,
) : RuntimeException(message, cause)
