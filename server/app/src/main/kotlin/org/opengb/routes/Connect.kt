package org.opengb.routes

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.server.application.Application
import io.ktor.server.html.respondHtml
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.html.body
import kotlinx.html.code
import kotlinx.html.div
import kotlinx.html.h1
import kotlinx.html.head
import kotlinx.html.meta
import kotlinx.html.p
import kotlinx.html.style
import kotlinx.html.title
import kotlinx.html.unsafe
import org.opengb.config.AppConfig
import org.opengb.oauth.ClaimRecord
import org.opengb.oauth.ClaimStore
import org.opengb.oauth.OAuthClient
import org.opengb.oauth.OAuthException
import org.opengb.oauth.PendingOAuth
import org.opengb.oauth.StateStore
import org.opengb.proxy.RefreshBlob
import org.opengb.proxy.TokenCrypto
import org.opengb.utility.UnknownUtilityException
import org.opengb.utility.UtilityRegistry

/**
 * Routes:
 *  - GET /connect/{utility}/start[?ha_nonce=...] — generates CSRF state, 302s the user to the
 *    utility's authorize URL.
 *  - GET /connect/{utility}/callback?code=...&state=... — exchanges code for tokens, encrypts the
 *    refresh blob, stashes it behind a one-time claim code, and renders an HTML page showing the
 *    code to the user.
 */
fun Application.installConnect(
    config: AppConfig,
    registry: UtilityRegistry,
    stateStore: StateStore,
    claimStore: ClaimStore,
    crypto: TokenCrypto,
    oauth: OAuthClient,
) {
    routing {
        get("/connect/{utility}/start") {
            val utilityId = call.parameters["utility"].orEmpty()
            val utility =
                try {
                    registry.require(utilityId)
                } catch (_: UnknownUtilityException) {
                    call.respondText(
                        text = "Unknown utility '$utilityId'.",
                        contentType = ContentType.Text.Plain,
                        status = HttpStatusCode.NotFound,
                    )
                    return@get
                }

            val state =
                stateStore.create(
                    PendingOAuth(
                        utilityId = utility.id,
                        haNonce = call.request.queryParameters["ha_nonce"],
                    ),
                )
            val redirectUri = "${config.server.publicBaseUrl}/connect/${utility.id}/callback"

            val url =
                URLBuilder(utility.authorizeUrl).apply {
                    parameters.append("response_type", "code")
                    parameters.append("client_id", utility.clientId)
                    parameters.append("redirect_uri", redirectUri)
                    parameters.append("scope", utility.defaultScope)
                    parameters.append("state", state)
                }.buildString()

            call.respondRedirect(url, permanent = false)
        }

        get("/connect/{utility}/callback") {
            val utilityId = call.parameters["utility"].orEmpty()
            val utility =
                try {
                    registry.require(utilityId)
                } catch (_: UnknownUtilityException) {
                    call.respondText(
                        "Unknown utility '$utilityId'.",
                        ContentType.Text.Plain,
                        HttpStatusCode.NotFound,
                    )
                    return@get
                }

            val state = call.request.queryParameters["state"]
            val code = call.request.queryParameters["code"]
            val error = call.request.queryParameters["error"]

            if (error != null) {
                call.respondCallbackError("Utility returned error: $error")
                return@get
            }
            if (state.isNullOrBlank() || code.isNullOrBlank()) {
                call.respondCallbackError("Missing 'state' or 'code' query parameter.")
                return@get
            }

            val pending = stateStore.consume(state)
            if (pending == null) {
                call.respondCallbackError("OAuth state is unknown, expired, or already used.")
                return@get
            }
            if (pending.utilityId != utility.id) {
                call.respondCallbackError("OAuth state does not match this utility.")
                return@get
            }

            val redirectUri = "${config.server.publicBaseUrl}/connect/${utility.id}/callback"
            val tokens =
                try {
                    oauth.exchangeCode(utility, code, redirectUri)
                } catch (e: OAuthException) {
                    call.application.environment.log.warn(
                        "Token exchange failed for utility=${utility.id}: ${e.message}",
                    )
                    call.respondCallbackError("Could not exchange authorization code with utility.")
                    return@get
                }

            val refreshToken = tokens.refreshToken
            if (refreshToken.isNullOrBlank()) {
                call.respondCallbackError("Utility did not issue a refresh token.")
                return@get
            }

            val refreshBlob =
                RefreshBlob(
                    utilityId = utility.id,
                    refreshToken = refreshToken,
                    subscriptionUri = tokens.resourceUri,
                    authorizationUri = tokens.authorizationUri,
                    scope = tokens.scope ?: utility.defaultScope,
                )
            val encrypted = crypto.encrypt(refreshBlob)
            val proxyToken = crypto.deriveProxyToken(refreshBlob)

            val claimCode =
                claimStore.create(
                    ClaimRecord(
                        utilityId = utility.id,
                        encryptedRefreshBlob = encrypted,
                        proxyToken = proxyToken,
                        subscriptionUri = refreshBlob.subscriptionUri,
                        scope = refreshBlob.scope,
                    ),
                )

            call.respondHtml { renderClaimPage(utilityDisplayName = utility.displayName, claimCode = claimCode) }
        }
    }
}

private suspend fun io.ktor.server.application.ApplicationCall.respondCallbackError(message: String) {
    respondHtml(HttpStatusCode.BadRequest) {
        head {
            title { +"Authorization failed" }
            meta(charset = "utf-8")
            style { unsafe { raw(CSS) } }
        }
        body {
            h1 { +"Authorization failed" }
            p { +message }
            p { +"You can close this window and try again from Home Assistant." }
        }
    }
}

private fun kotlinx.html.HTML.renderClaimPage(
    utilityDisplayName: String,
    claimCode: String,
) {
    head {
        title { +"Connected — paste your claim code" }
        meta(charset = "utf-8")
        meta(name = "viewport", content = "width=device-width,initial-scale=1")
        style { unsafe { raw(CSS) } }
    }
    body {
        h1 { +"Connected to $utilityDisplayName" }
        p { +"Paste this code into Home Assistant within 10 minutes:" }
        div("code-box") {
            code { +claimCode }
        }
        p("muted") {
            +"Single-use. Lost it? Restart the connect flow from Home Assistant."
        }
    }
}

private const val CSS = """
body { font-family: system-ui, sans-serif; max-width: 560px; margin: 4rem auto; padding: 0 1.5rem; color: #1a1a1a; line-height: 1.55; }
h1 { font-size: 1.6rem; margin-bottom: 0.5rem; }
.muted { color: #666; font-size: 0.92em; }
.code-box { background: #f4f7f4; border: 1px solid #d5e2d5; padding: 1rem 1.25rem; border-radius: 6px; margin: 1.5rem 0; text-align: center; }
.code-box code { font-size: 1.4rem; letter-spacing: 0.03em; color: #2d662d; word-break: break-all; user-select: all; }
"""
