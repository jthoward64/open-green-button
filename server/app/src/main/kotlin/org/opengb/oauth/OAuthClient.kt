package org.opengb.oauth

import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.headers
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.ParametersBuilder
import io.ktor.http.parameters
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.opengb.http.UtilityHttpClients
import org.opengb.utility.TokenAuthStyle
import org.opengb.utility.UtilityProfile
import java.util.Base64

/**
 * Authorization Code grant response per RFC 6749 §5.1, extended with the ESPI-specific resource
 * pointer fields that Green Button adds.
 */
@Serializable
data class TokenResponse(
  @SerialName("access_token") val accessToken: String,
  @SerialName("token_type") val tokenType: String? = null,
  @SerialName("refresh_token") val refreshToken: String? = null,
  @SerialName("expires_in") val expiresIn: Long? = null,
  val scope: String? = null,
  // ESPI-specific:
  @SerialName("resourceURI") val resourceUri: String? = null,
  @SerialName("authorizationURI") val authorizationUri: String? = null,
)

class OAuthClient(private val clients: UtilityHttpClients, private val json: Json = DEFAULT_JSON) {
  /**
   * Exchanges an OAuth authorization code for tokens at the utility's token endpoint.
   *
   * @throws OAuthException if the utility returns a non-2xx response or a malformed body.
   */
  suspend fun exchangeCode(
    utility: UtilityProfile,
    code: String,
    redirectUri: String,
  ): TokenResponse =
    post(utility) {
      append("grant_type", "authorization_code")
      append("code", code)
      append("redirect_uri", redirectUri)
    }

  suspend fun refresh(
    utility: UtilityProfile,
    refreshToken: String,
  ): TokenResponse =
    post(utility) {
      append("grant_type", "refresh_token")
      append("refresh_token", refreshToken)
    }

  private suspend fun post(
    utility: UtilityProfile,
    addParams: ParametersBuilder.() -> Unit,
  ): TokenResponse {
    val (formParams, basic) = buildAuth(utility, addParams)
    val response: HttpResponse =
      clients.forUtility(utility).submitForm(
        url = utility.tokenUrl,
        formParameters = formParams,
      ) {
        headers {
          if (basic != null) append(HttpHeaders.Authorization, "Basic $basic")
          append(HttpHeaders.Accept, "application/json")
        }
      }
    return parse(response, utility)
  }

  private fun buildAuth(
    utility: UtilityProfile,
    addParams: ParametersBuilder.() -> Unit,
  ): Pair<Parameters, String?> {
    return when (utility.tokenAuthStyle) {
      TokenAuthStyle.HTTP_BASIC -> {
        val basic =
          Base64.getEncoder().encodeToString(
            "${utility.clientId}:${utility.clientSecret.value}".toByteArray(Charsets.UTF_8),
          )
        parameters { addParams() } to basic
      }
      TokenAuthStyle.FORM_BODY -> {
        val withCreds =
          parameters {
            addParams()
            append("client_id", utility.clientId)
            append("client_secret", utility.clientSecret.value)
          }
        withCreds to null
      }
    }
  }

  private suspend fun parse(
    response: HttpResponse,
    utility: UtilityProfile,
  ): TokenResponse {
    val bodyText = response.bodyAsText()
    if (response.status != HttpStatusCode.OK) {
      throw OAuthException(
        "Utility '${utility.id}' returned ${response.status.value} from token endpoint: $bodyText",
        statusCode = response.status.value,
      )
    }
    return try {
      json.decodeFromString(TokenResponse.serializer(), bodyText)
    } catch (e: SerializationException) {
      throw OAuthException(
        "Utility '${utility.id}' returned a malformed token response: $bodyText",
        cause = e,
      )
    }
  }

  companion object {
    val DEFAULT_JSON =
      Json {
        ignoreUnknownKeys = true
        isLenient = true
      }
  }
}

class OAuthException(
  message: String,
  val statusCode: Int? = null,
  cause: Throwable? = null,
) : RuntimeException(message, cause)
