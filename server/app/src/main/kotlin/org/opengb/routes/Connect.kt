package org.opengb.routes

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.html.respondHtml
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.html.HTML
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
import org.opengb.AppDeps
import org.opengb.oauth.ClaimRecord
import org.opengb.oauth.OAuthException
import org.opengb.oauth.PendingOAuth
import org.opengb.proxy.RefreshBlob
import org.opengb.utility.UnknownUtilityException
import org.opengb.utility.UtilityProfile

/**
 * Routes:
 *  - GET /connect/{utility}/start[?ha_nonce=...] — generates CSRF state, 302s the user to the
 *    utility's authorize URL.
 *  - GET /connect/{utility}/callback?code=...&state=... — exchanges code for tokens, encrypts the
 *    refresh blob, stashes it behind a one-time claim code, and renders an HTML page showing the
 *    code to the user.
 */
fun Application.installConnect(deps: AppDeps) {
  routing {
    get("/connect/{utility}/start") { handleStart(deps) }
    get("/connect/{utility}/callback") { handleCallback(deps) }
  }
}

private suspend fun io.ktor.server.routing.RoutingContext.handleStart(deps: AppDeps) {
  val utility = resolveUtility(deps) ?: return
  val state =
    deps.stateStore.create(
      PendingOAuth(
        utilityId = utility.id,
        haNonce = call.request.queryParameters["ha_nonce"],
      ),
    )
  val redirectUri = "${deps.config.server.publicBaseUrl}/connect/${utility.id}/callback"
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

private suspend fun io.ktor.server.routing.RoutingContext.handleCallback(deps: AppDeps) {
  val utility = resolveUtility(deps) ?: return
  val state = call.request.queryParameters["state"]
  val code = call.request.queryParameters["code"]
  val error = call.request.queryParameters["error"]

  if (error != null) {
    call.respondCallbackError("Utility returned error: $error")
    return
  }
  if (state.isNullOrBlank() || code.isNullOrBlank()) {
    call.respondCallbackError("Missing 'state' or 'code' query parameter.")
    return
  }

  val pending = deps.stateStore.consume(state)
  if (pending == null) {
    call.respondCallbackError("OAuth state is unknown, expired, or already used.")
    return
  }
  if (pending.utilityId != utility.id) {
    call.respondCallbackError("OAuth state does not match this utility.")
    return
  }

  val refreshBlob = exchangeAndBuildBlob(deps, utility, code) ?: return
  val encrypted = deps.crypto.encrypt(refreshBlob)
  val proxyToken = deps.crypto.deriveProxyToken(refreshBlob)
  val claimCode =
    deps.claimStore.create(
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

/** 404s on unknown utility and returns null so the caller can `return`. */
private suspend fun io.ktor.server.routing.RoutingContext.resolveUtility(deps: AppDeps): UtilityProfile? {
  val utilityId = call.parameters["utility"].orEmpty()
  return try {
    deps.registry.require(utilityId)
  } catch (_: UnknownUtilityException) {
    call.respondText("Unknown utility '$utilityId'.", ContentType.Text.Plain, HttpStatusCode.NotFound)
    null
  }
}

private suspend fun io.ktor.server.routing.RoutingContext.exchangeAndBuildBlob(
  deps: AppDeps,
  utility: UtilityProfile,
  code: String,
): RefreshBlob? {
  val redirectUri = "${deps.config.server.publicBaseUrl}/connect/${utility.id}/callback"
  val tokens =
    try {
      deps.oauth.exchangeCode(utility, code, redirectUri)
    } catch (e: OAuthException) {
      call.application.environment.log.warn(
        "Token exchange failed for utility=${utility.id}: ${e.message}",
      )
      call.respondCallbackError("Could not exchange authorization code with utility.")
      return null
    }
  val refreshToken = tokens.refreshToken
  if (refreshToken.isNullOrBlank()) {
    call.respondCallbackError("Utility did not issue a refresh token.")
    return null
  }
  // ESPI authorization comes in two flavours and we support both:
  //   - FB_14 "legacy" / pre-negotiated scope: scope is fixed at registration time; the
  //     utility may omit `scope` from the token response entirely. Fall back to the requested
  //     scope so downstream code always has something to persist.
  //   - FB_31 "modern" / scope negotiated at OAuth time: the customer can edit the scope on
  //     the consent screen, and the utility returns the *granted* scope in the token response,
  //     possibly a subset of what we asked for. Persist that.
  return RefreshBlob(
    utilityId = utility.id,
    refreshToken = refreshToken,
    subscriptionUri = tokens.resourceUri,
    authorizationUri = tokens.authorizationUri,
    scope = tokens.scope ?: utility.defaultScope,
  )
}

private suspend fun ApplicationCall.respondCallbackError(message: String) {
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

private fun HTML.renderClaimPage(
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
