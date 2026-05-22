package org.opengb.routes

import io.ktor.server.application.Application
import io.ktor.server.html.respondHtml
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.html.BODY
import kotlinx.html.a
import kotlinx.html.body
import kotlinx.html.h1
import kotlinx.html.head
import kotlinx.html.li
import kotlinx.html.meta
import kotlinx.html.p
import kotlinx.html.style
import kotlinx.html.title
import kotlinx.html.ul
import kotlinx.html.unsafe
import org.opengb.config.AppConfig
import org.opengb.config.LandingMode

fun Application.installLanding(config: AppConfig) {
    routing {
        get("/") {
            call.respondHtml {
                head {
                    title { +"Open Green Button" }
                    meta(charset = "utf-8")
                    meta(name = "viewport", content = "width=device-width,initial-scale=1")
                    style {
                        unsafe {
                            raw(
                                """
                                body { font-family: system-ui, sans-serif; max-width: 720px; margin: 4rem auto; padding: 0 1.5rem; color: #1a1a1a; line-height: 1.6; }
                                h1 { font-size: 2rem; margin-bottom: 0.25rem; }
                                .tag { color: #4a4a4a; margin-bottom: 2rem; }
                                .panel { background: #f4f7f4; border-left: 4px solid #4a9c4a; padding: 1rem 1.25rem; border-radius: 4px; margin: 1.5rem 0; }
                                ul { padding-left: 1.25rem; }
                                code { background: #eee; padding: 0.1rem 0.3rem; border-radius: 3px; font-size: 0.95em; }
                                a { color: #4a9c4a; }
                                """.trimIndent(),
                            )
                        }
                    }
                }
                body {
                    h1 { +"Open Green Button" }
                    p("tag") { +config.landing.tagline }
                    when (config.landing.mode) {
                        LandingMode.COMING_SOON -> comingSoon()
                        LandingMode.LIVE -> live()
                    }
                }
            }
        }
    }
}

private fun BODY.comingSoon() {
    p {
        +"This service is under active development. "
        +"The public API and Home Assistant integration are not yet available."
    }
    p {
        +"For the project plan and progress, see "
        a(href = "https://github.com/rocketraman/open-green-button") { +"GitHub" }
        +"."
    }
}

private fun BODY.live() {
    p { +"This is a stateless proxy for the Green Button (NAESB ESPI) protocol." }
    ul {
        li { +"Hosted server holds zero per-user data." }
        li {
            +"Refresh tokens live encrypted on your Home Assistant instance. "
            +"This server only proxies authenticated requests to your utility."
        }
        li {
            +"Source code: "
            a(href = "https://github.com/rocketraman/open-green-button") {
                +"github.com/rocketraman/open-green-button"
            }
        }
    }
}
