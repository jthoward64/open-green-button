package org.opengb.onboarding

import com.sksamuel.hoplite.Masked
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.opengb.config.ClientAuthConfig
import org.opengb.http.UtilityHttpClients
import org.opengb.oauth.ClientCredentialsRequest
import org.opengb.oauth.OAuthClient
import org.opengb.utility.TokenAuthStyle
import java.io.File

/**
 * Operator driver for ESPI 3.3 dynamic-client-registration onboarding — **not** part of the server
 * (and not reachable from the native image). Given a utility id, it:
 *
 *   1. runs an OAuth2 client_credentials grant against the Data Custodian token endpoint to obtain a
 *      registration_access_token (presenting our mTLS client certificate), then
 *   2. GETs the ApplicationInformation resource (whose URL we captured from the notification) and
 *      dumps every field, so we can read off the real client_id/client_secret + endpoints and wire
 *      them into `utilities.conf`.
 *
 * Config comes from a gitignored `.env` (see `.env.template`) via utility-specific keys so the same
 * driver serves every utility we onboard:
 *
 *   ./gradlew :app:onboardFetchAppInfo --args="milton_hydro"
 *
 * Reads `OPENGB_ONBOARD_<ID>_*` where `<ID>` is the upper-cased utility id (milton_hydro → MILTON_HYDRO).
 */
private const val ENV_PREFIX = "OPENGB_ONBOARD_"

fun main(args: Array<String>) {
  val utilityId =
    args.getOrNull(0)?.takeIf { it.isNotBlank() }
      ?: error("usage: onboardFetchAppInfo <utilityId>  (e.g. milton_hydro)")
  val env = OnboardingEnv(utilityId, dotenv = loadDotenv(args.getOrNull(1)))

  val id = utilityId.uppercase().replace('-', '_')
  val tokenUrl = env.require("${id}_TOKEN_URL")
  val appInfoUrl = env.require("${id}_APP_INFO_URL")
  val regClientId = env.require("${id}_REG_CLIENT_ID")
  val regClientSecret = env.require("${id}_REG_CLIENT_SECRET")
  val regScope = env.optional("${id}_REG_SCOPE")
  val authStyle =
    env.optional("${id}_TOKEN_AUTH_STYLE")?.let { TokenAuthStyle.valueOf(it.trim().uppercase()) }
      ?: TokenAuthStyle.HTTP_BASIC

  val clientAuth =
    ClientAuthConfig(
      keystoreBase64 = Masked(env.require("${id}_CLIENTAUTH_KEYSTORE_BASE64")),
      keystorePassword = Masked(env.require("${id}_CLIENTAUTH_KEYSTORE_PASSWORD")),
      keystoreType = env.optional("${id}_CLIENTAUTH_KEYSTORE_TYPE") ?: "PKCS12",
      keyAlias = env.optional("${id}_CLIENTAUTH_KEY_ALIAS"),
      keyPassword = env.optional("${id}_CLIENTAUTH_KEY_PASSWORD")?.let { Masked(it) },
    )

  val http: HttpClient = UtilityHttpClients.mtlsClient(clientAuth)
  http.use {
    val oauth = OAuthClient(UtilityHttpClients.singleClient(http))
    val appInfoClient = AppInfoClient()

    println("== ESPI onboarding: $utilityId ==")
    println("token endpoint : $tokenUrl  (auth=$authStyle, scope=${regScope ?: "<none>"})")
    println("app info URL   : $appInfoUrl")
    println("mTLS keystore  : ${clientAuth.keystoreType}, key material present=${clientAuth.hasMaterial()}")
    println()

    runBlocking {
      // Print the DC's OpenID discovery up front so, when the token endpoint rejects the scope, the
      // valid values are already on screen (IdentityServer requires a scope for client_credentials).
      printDiscovery(http, tokenUrl)

      print("→ requesting registration_access_token via client_credentials … ")
      val token =
        oauth.clientCredentials(
          http,
          ClientCredentialsRequest(
            tokenUrl = tokenUrl,
            clientId = regClientId,
            clientSecret = regClientSecret,
            scope = regScope,
            authStyle = authStyle,
          ),
        )
      println("ok (expires_in=${token.expiresIn ?: "?"}, scope=${token.scope ?: "?"})")
      println("  token: ${mask(token.accessToken)}")
      println()

      print("→ fetching ApplicationInformation … ")
      val appInfo = appInfoClient.fetch(http, appInfoUrl, token.accessToken)
      println("ok (${appInfo.fields.size} distinct fields)")
      println()
      printAppInfo(appInfo)
    }
  }
}

private val discoveryJson = Json { ignoreUnknownKeys = true }

/**
 * Fetches the DC's OpenID Connect discovery document and prints the advertised scopes + grant types,
 * so we know exactly what `scope` the client_credentials grant will accept. Best-effort: a failure
 * here (endpoint not published, non-JSON) is logged and ignored, not fatal.
 */
private suspend fun printDiscovery(
  http: HttpClient,
  tokenUrl: String,
) {
  val base = tokenUrl.substringBefore("/connect/token").trimEnd('/')
  val discoveryUrl = "$base/.well-known/openid-configuration"
  runCatching {
    val obj = discoveryJson.parseToJsonElement(http.get(discoveryUrl).bodyAsText()).jsonObject

    fun list(key: String): String =
      obj[key]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }?.joinToString(", ") ?: "?"
    println("discovery: $discoveryUrl")
    println("  scopes_supported      : ${list("scopes_supported")}")
    println("  grant_types_supported : ${list("grant_types_supported")}")
  }.onFailure { println("discovery: fetch failed (${it.message}) — set REG_SCOPE from savagedata's docs") }
  println()
}

private fun printAppInfo(appInfo: ApplicationInformation) {
  println("── ApplicationInformation fields ──")
  appInfo.fields.forEach { (name, values) ->
    values.forEach { println("  $name = $it") }
  }
  println()
  println("── raw XML ──")
  println(appInfo.rawXml)
}

/** Truncate a secret for console echo — enough to eyeball, not enough to be useful if it leaks. */
private fun mask(value: String): String = if (value.length <= 12) "***" else "${value.take(8)}…(${value.length} chars)"

private class OnboardingEnv(
  val utilityId: String,
  private val dotenv: Map<String, String>,
) {
  // Real process environment wins over .env, matching conventional dotenv precedence.
  private fun raw(key: String): String? =
    System.getenv(ENV_PREFIX + key)?.takeIf { it.isNotBlank() }
      ?: dotenv[ENV_PREFIX + key]?.takeIf { it.isNotBlank() }

  fun optional(key: String): String? = raw(key)

  fun require(key: String): String =
    raw(key)
      ?: error(
        "missing required config for '$utilityId': set $ENV_PREFIX$key in the environment or .env",
      )
}

/**
 * Loads a `.env` into a map. If [explicitPath] is given it must exist; otherwise search the working
 * directory and its ancestors for the first `.env`. Missing/absent file ⇒ empty map (values may then
 * come from the real environment). Supports `KEY=VALUE`, `export KEY=VALUE`, `#` comments, and
 * single/double-quoted values.
 */
private fun loadDotenv(explicitPath: String?): Map<String, String> {
  val file =
    if (explicitPath != null) {
      File(explicitPath).also { require(it.isFile) { "no .env at $explicitPath" } }
    } else {
      generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, ".env") }
        .firstOrNull { it.isFile }
    } ?: return emptyMap()

  return file.readLines().mapNotNull { line ->
    val trimmed = line.trim().removePrefix("export ").trim()
    if (trimmed.isEmpty() || trimmed.startsWith("#")) return@mapNotNull null
    val eq = trimmed.indexOf('=').takeIf { it > 0 } ?: return@mapNotNull null
    val key = trimmed.substring(0, eq).trim()
    val value = trimmed.substring(eq + 1).trim().trim('"', '\'')
    key to value
  }.toMap()
}
