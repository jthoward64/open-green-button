package org.opengb.proxy

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder

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
    publishedMin: Long? = null,
    publishedMax: Long? = null,
  ): String {
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
      throw UsageClientException(
        "Resource server returned ${response.status.value} for $subscriptionUri: $body",
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
