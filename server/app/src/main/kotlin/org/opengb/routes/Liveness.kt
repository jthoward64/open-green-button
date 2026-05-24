package org.opengb.routes

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

fun Application.installLiveness() {
  routing {
    get("/health") {
      call.respondText("ok", ContentType.Text.Plain, HttpStatusCode.OK)
    }
    get("/ready") {
      call.respondText("ok", ContentType.Text.Plain, HttpStatusCode.OK)
    }
  }
}
