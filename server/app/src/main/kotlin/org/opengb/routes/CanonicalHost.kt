package org.opengb.routes

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.host
import io.ktor.server.request.uri
import io.ktor.server.response.respondRedirect

/**
 * Permanent (301) redirect from alternate hostnames to the canonical one, preserving path and
 * query. Used so search engines and shared URLs converge on a single canonical host.
 *
 * Triggered when [canonicalHost] is set and the incoming Host header (case-insensitive,
 * port-stripped) is in [redirectFrom]. The redirect must run early — before route matching —
 * so it pre-empts the landing/connect/claim/notify handlers without doing any work.
 *
 * Important: the OAuth host (api.*) **must not** be in [redirectFrom]. Burlington's
 * registered `redirect_uri` points at api.*; redirecting it would break the OAuth callback.
 */
fun Application.installCanonicalHost(
  canonicalHost: String?,
  redirectFrom: List<String>,
) {
  if (canonicalHost.isNullOrBlank() || redirectFrom.isEmpty()) return
  val alternates = redirectFrom.map { it.lowercase() }.toSet()
  intercept(ApplicationCallPipeline.Plugins) {
    val host = call.request.host().lowercase()
    if (host in alternates) {
      call.respondRedirect("https://$canonicalHost${call.request.uri}", permanent = true)
      finish()
    }
  }
}
