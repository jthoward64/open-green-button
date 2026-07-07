package org.opengb.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.request.contentLength
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import org.apache.logging.log4j.kotlin.logger
import org.apache.logging.log4j.message.StringMapMessage
import org.opengb.utility.UtilityRegistry

private val notifyLog = logger("opengb.notify")

// Pull http(s) URLs out of the notification body. An ESPI BatchList lists the changed resource
// URIs (as <resources> hrefs); for an Application Information Notification that href points at the
// ApplicationInformation resource we must GET next. A lenient regex avoids pulling in an XML parser
// (and its native-image reflection metadata) just to surface the URL for an operator.
private val urlRegex = Regex("""https?://[^\s"'<>]+""")

/**
 * NotificationURI endpoint that data custodians (utilities) POST to. Two kinds of notification
 * arrive here:
 *
 *  - **Application Information Notification** (ESPI 3.3 dynamic client registration onboarding):
 *    the DC pushes a BatchList whose resource href points at the ApplicationInformation resource
 *    we must then GET (see the milton_hydro / savagedata onboarding). We capture the body so the
 *    App Info URL can be recovered from logs — the server is stateless and Fly scales to zero, so
 *    there is no in-memory place to stash it.
 *  - **Data-available notifications** for an existing subscription — discarded in v1 (the Home
 *    Assistant client polls on its own cadence rather than reacting to notifications).
 *
 * We **accept and log every** notification (returning 200 OK) rather than 404ing unknown ids: an
 * id may be mid-onboarding and not yet present in `utilities.conf` (its OAuth credentials don't
 * exist until we fetch the ApplicationInformation from this very notification). Bouncing the
 * notification just makes the DC retry and email an error. The BatchList body carries only resource
 * URIs — no PII, no client secrets (those live in the ApplicationInformation resource we fetch with
 * an access token) — so logging it verbatim is safe.
 */
fun Application.installNotify(registry: UtilityRegistry) {
  routing {
    post("/notify/{utility}") {
      val utilityId = call.parameters["utility"].orEmpty()
      val known = registry[utilityId] != null
      val body = runCatching { call.receiveText() }.getOrDefault("")
      val resources = urlRegex.findAll(body).map { it.value }.distinct().toList()
      val event =
        StringMapMessage().apply {
          put("utility.id", utilityId)
          put("utility.known", known.toString())
          call.request.contentType().toString().takeIf { it.isNotBlank() }?.let {
            put("http.request.body.mime_type", it)
          }
          call.request.contentLength()?.let { put("http.request.body.bytes", it.toString()) }
          if (body.isNotBlank()) put("notify.body", body)
          if (resources.isNotEmpty()) put("notify.resources", resources.joinToString(" "))
        }
      notifyLog.info(event)
      call.respond(HttpStatusCode.OK)
    }
  }
}
