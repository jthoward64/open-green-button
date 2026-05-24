package org.opengb.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import org.opengb.utility.UtilityRegistry

/**
 * `GET /utilities` — lightweight discovery endpoint the Home Assistant config flow calls to
 * populate the utility picker before the user has any credentials. Returns the static list of
 * utilities configured in `utilities.conf`, with their public-facing display names.
 *
 * Returns a JSON array: `[{"id": "burlington_hydro", "displayName": "Burlington Hydro"}, ...]`.
 * Empty array when no utilities are configured.
 *
 * No authentication — utility ids and display names are non-sensitive.
 */
fun Application.installUtilities(registry: UtilityRegistry) {
  routing {
    get("/utilities") {
      call.respond(HttpStatusCode.OK, registry.summary())
    }
  }
}
