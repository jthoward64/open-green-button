package org.opengb.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.request.contentLength
import io.ktor.server.request.contentType
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import org.apache.logging.log4j.kotlin.logger
import org.apache.logging.log4j.message.StringMapMessage
import org.opengb.utility.UtilityRegistry

private val notifyLog = logger("opengb.notify")

/**
 * NotificationURI endpoint that data custodians (utilities) POST to when new data is available
 * for a subscription. Registered at utility submission time so the OAuth registration can be
 * provisioned, but we **discard** notifications in v1 — the Home Assistant client polls on its
 * own cadence rather than reacting to notifications. See Phase 6 of the implementation plan
 * for the eventual push-to-HA design.
 *
 * Returns 204 No Content on success so the utility's notification machinery sees a clean ack
 * without a body to parse.
 */
fun Application.installNotify(registry: UtilityRegistry) {
    routing {
        post("/notify/{utility}") {
            val utilityId = call.parameters["utility"].orEmpty()
            val known = registry[utilityId] != null
            val event =
                StringMapMessage().apply {
                    put("utility.id", utilityId)
                    put("utility.known", known.toString())
                    call.request.contentType().toString().takeIf { it.isNotBlank() }?.let {
                        put("http.request.body.mime_type", it)
                    }
                    call.request.contentLength()?.let { put("http.request.body.bytes", it.toString()) }
                }
            notifyLog.info(event)
            if (!known) {
                call.respond(HttpStatusCode.NotFound)
                return@post
            }
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
