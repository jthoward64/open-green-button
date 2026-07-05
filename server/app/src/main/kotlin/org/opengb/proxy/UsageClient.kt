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
  suspend fun fetch(
    utility: UtilityProfile,
    subscriptionUri: String,
    accessToken: String,
    publishedMin: Instant? = null,
    publishedMax: Instant? = null,
  ): HttpStatement {
    val url =
      URLBuilder(subscriptionUri)
        .apply {
          publishedMin?.let { parameters.append("published-min", it.toString()) }
          publishedMax?.let { parameters.append("published-max", it.toString()) }
        }.buildString()

    return clients.forUtility(utility).prepareGet(url) {
      headers {
        append(HttpHeaders.Authorization, "Bearer $accessToken")
        append(HttpHeaders.Accept, "application/atom+xml, application/xml")
      }
    }
  }
}
