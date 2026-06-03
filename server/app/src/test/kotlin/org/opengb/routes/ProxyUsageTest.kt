package org.opengb.routes

import com.sksamuel.hoplite.Masked
import de.infix.testBalloon.framework.core.testSuite
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.opengb.appModule
import org.opengb.buildAppDeps
import org.opengb.config.AppConfig
import org.opengb.config.CryptoConfig
import org.opengb.config.LandingConfig
import org.opengb.config.ServerConfig
import org.opengb.config.StateConfig
import org.opengb.proxy.ProxyUsageResponse
import org.opengb.proxy.RefreshBlob
import org.opengb.proxy.TokenCrypto
import org.opengb.utility.TokenAuthStyle
import org.opengb.utility.UtilityProfile
import java.util.Base64

/**
 * End-to-end test of `POST /proxy/usage` against a Ktor MockEngine that fakes:
 *   - the utility's token endpoint (refresh grant)
 *   - the utility's resource endpoint (returns a small ESPI Atom feed)
 *
 * The point isn't to re-cover what [org.opengb.espi.EspiParserTest] / EspiNormalizerTest
 * already prove about parsing — it's the wire glue: auth verification, decrypt → refresh →
 * fetch → normalize → DTO mapping, plus the refresh-token-rotation surface.
 */
val ProxyUsageTest by testSuite {
  test("happy path: returns normalized usage DTO for a valid blob + proxy token") {
    runProxyUsage { client, ctx ->
      val resp = client.postProxyUsage(ctx.proxyToken, ctx.encryptedBlob)
      assert(resp.status == HttpStatusCode.OK) { "got ${resp.status}: ${resp.bodyAsText()}" }
      val payload = decodePayload(resp.bodyAsText())

      assert(payload.updated == "2026-06-02T20:00:00Z") { payload.updated.toString() }
      assert(payload.usagePoints.size == 1) { payload.usagePoints.size.toString() }

      val up = payload.usagePoints.single()
      assert(up.usagePointId == "up1") { up.usagePointId }
      assert(up.serviceKind == "ELECTRICITY")
      assert(up.series.size == 1)

      val series = up.series.single()
      assert(series.meterReadingId == "mr1")
      assert(series.readingType.commodity == "ELECTRICITY_SECONDARY_METERED")
      assert(series.readingType.flowDirection == "FORWARD")
      assert(series.readingType.accumulationBehaviour == "DELTA_DATA")
      assert(series.readingType.unitOfMeasure == "WATT_HOURS")
      assert(series.readingType.unitOfMeasureSymbol == "Wh")
      assert(series.readingType.intervalLengthSeconds == 3600L)
      assert(series.readingType.powerOfTenMultiplier == 0)

      assert(series.readings.size == 2)
      assert(series.readings[0].start == "2026-02-24T05:00:00Z") { series.readings[0].start }
      assert(series.readings[0].durationSeconds == 3600L)
      assert(series.readings[0].value == 1000.0)
      assert(series.readings[1].value == 1500.0)

      // No rotation in the happy-path mock token response, so newCredentials is absent.
      assert(payload.newCredentials == null)
    }
  }

  test("returns newCredentials when the utility rotates the refresh token") {
    runProxyUsage(refreshTokenInResponse = "rt_rotated_value") { client, ctx ->
      val resp = client.postProxyUsage(ctx.proxyToken, ctx.encryptedBlob)
      assert(resp.status == HttpStatusCode.OK)
      val payload = decodePayload(resp.bodyAsText())
      val rotated =
        payload.newCredentials
          ?: error("expected newCredentials when the refresh token rotated; payload=$payload")

      // The rotated blob decrypts to the new refresh token, and the new proxy_token derives
      // from it — so HA can swap to the new pair without coordinating with the server.
      val crypto = TokenCrypto(ctx.config.crypto)
      val newBlob = crypto.decrypt(rotated.encryptedRefreshBlob)
      assert(newBlob.refreshToken == "rt_rotated_value")
      assert(newBlob.utilityId == ctx.utility.id)
      assert(newBlob.subscriptionUri == ctx.subscriptionUri)
      assert(crypto.deriveProxyToken(newBlob) == rotated.proxyToken)
    }
  }

  test("401 invalid_credentials when the proxy token doesn't match the blob") {
    runProxyUsage { client, ctx ->
      val resp = client.postProxyUsage(presentedToken = "definitely-not-the-right-token", ctx.encryptedBlob)
      assert(resp.status == HttpStatusCode.Unauthorized) { resp.bodyAsText() }
      assert(resp.bodyAsText().contains("invalid_credentials"))
    }
  }

  test("401 utility_auth_expired when the utility rejects the refresh token") {
    runProxyUsage(tokenEndpointStatus = HttpStatusCode.Unauthorized) { client, ctx ->
      val resp = client.postProxyUsage(ctx.proxyToken, ctx.encryptedBlob)
      assert(resp.status == HttpStatusCode.Unauthorized) { resp.bodyAsText() }
      assert(resp.bodyAsText().contains("utility_auth_expired"))
    }
  }

  test("502 utility_upstream_error when the resource server returns 500") {
    runProxyUsage(resourceStatus = HttpStatusCode.InternalServerError) { client, ctx ->
      val resp = client.postProxyUsage(ctx.proxyToken, ctx.encryptedBlob)
      assert(resp.status == HttpStatusCode.BadGateway) { resp.bodyAsText() }
      assert(resp.bodyAsText().contains("utility_upstream_error"))
    }
  }

  test("forwards published-min/max to the resource server") {
    var capturedUrl: String? = null
    runProxyUsage(captureResourceRequest = { capturedUrl = it.url.toString() }) { client, ctx ->
      val resp =
        client.postProxyUsage(
          ctx.proxyToken,
          ctx.encryptedBlob,
          publishedMin = 1771900000L,
          publishedMax = 1772000000L,
        )
      assert(resp.status == HttpStatusCode.OK)
    }
    val url = capturedUrl ?: error("resource server was not called")
    assert(url.contains("published-min=1771900000")) { url }
    assert(url.contains("published-max=1772000000")) { url }
  }
}

private data class ProxyUsageCtx(
  val config: AppConfig,
  val utility: UtilityProfile,
  val subscriptionUri: String,
  val refreshBlob: RefreshBlob,
  val encryptedBlob: String,
  val proxyToken: String,
)

private suspend fun HttpClient.postProxyUsage(
  presentedToken: String,
  encryptedBlob: String,
  publishedMin: Long? = null,
  publishedMax: Long? = null,
): HttpResponse =
  post("/proxy/usage") {
    header(HttpHeaders.Authorization, "Bearer $presentedToken")
    contentType(ContentType.Application.Json)
    val sinceClause = publishedMin?.let { ",\"publishedMin\":$it" } ?: ""
    val untilClause = publishedMax?.let { ",\"publishedMax\":$it" } ?: ""
    setBody("""{"encryptedRefreshBlob":"$encryptedBlob"$sinceClause$untilClause}""")
  }

private val responseJson = Json { ignoreUnknownKeys = true }

private fun decodePayload(body: String): ProxyUsageResponse =
  responseJson.decodeFromString(ProxyUsageResponse.serializer(), body)

@Suppress("LongMethod", "LongParameterList")
private fun runProxyUsage(
  tokenEndpointStatus: HttpStatusCode = HttpStatusCode.OK,
  resourceStatus: HttpStatusCode = HttpStatusCode.OK,
  // Default matches the blob's refresh token, so happy-path tests see no rotation.
  refreshTokenInResponse: String = "rt_mock_value",
  captureResourceRequest: ((HttpRequestData) -> Unit)? = null,
  block: suspend (io.ktor.client.HttpClient, ProxyUsageCtx) -> Unit,
) {
  val subscriptionUri = "https://utility.mock/espi/1_1/resource/Batch/Subscription/42"
  val utility =
    UtilityProfile(
      id = "mock",
      displayName = "Mock Utility",
      authorizeUrl = "https://utility.mock/authorize",
      tokenUrl = "https://utility.mock/token",
      clientId = "client_id_xyz",
      clientSecret = Masked("client_secret_xyz"),
      defaultScope = "FB=1;IntervalDuration=900",
      tokenAuthStyle = TokenAuthStyle.HTTP_BASIC,
    )

  val tokenResponseJson =
    """
    {"access_token":"at_mock","refresh_token":"$refreshTokenInResponse",
    "expires_in":3600,"token_type":"Bearer"}
    """.trimIndent()
  val mockHttp =
    HttpClient(MockEngine) {
      engine {
        addHandler { request ->
          handleMockRequest(
            request,
            tokenEndpointStatus,
            resourceStatus,
            tokenResponseJson,
            captureResourceRequest,
          )
        }
      }
    }

  val config =
    AppConfig(
      server = ServerConfig(publicBaseUrl = "http://test.local"),
      crypto =
        CryptoConfig(
          aesKeyBase64 = Masked(Base64.getEncoder().encodeToString(ByteArray(32) { (it + 1).toByte() })),
          hmacPepperBase64 =
            Masked(
              Base64.getEncoder().encodeToString(ByteArray(32) { ((it + 1) * 11).toByte() }),
            ),
        ),
      state = StateConfig(),
      landing = LandingConfig(),
      utilities = listOf(utility),
    )
  val deps = buildAppDeps(config, mockHttp)
  val crypto = TokenCrypto(config.crypto)
  val refreshBlob =
    RefreshBlob(
      utilityId = utility.id,
      refreshToken = "rt_mock_value",
      subscriptionUri = subscriptionUri,
      scope = utility.defaultScope,
    )
  val ctx =
    ProxyUsageCtx(
      config = config,
      utility = utility,
      subscriptionUri = subscriptionUri,
      refreshBlob = refreshBlob,
      encryptedBlob = crypto.encrypt(refreshBlob),
      proxyToken = crypto.deriveProxyToken(refreshBlob),
    )

  runBlocking {
    testApplication {
      application { appModule(deps) }
      val httpClient =
        client.config {
          install(ContentNegotiation) { json() }
        }
      httpClient.use { block(it, ctx) }
    }
  }
}

private fun MockRequestHandleScope.handleMockRequest(
  request: HttpRequestData,
  tokenEndpointStatus: HttpStatusCode,
  resourceStatus: HttpStatusCode,
  tokenResponseJson: String,
  captureResourceRequest: ((HttpRequestData) -> Unit)?,
) = when {
  request.url.toString().startsWith("https://utility.mock/token") -> {
    if (tokenEndpointStatus == HttpStatusCode.OK) {
      respond(
        content = ByteReadChannel(tokenResponseJson),
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, "application/json"),
      )
    } else {
      respond(content = "utility rejected refresh", status = tokenEndpointStatus)
    }
  }
  request.url.toString().startsWith("https://utility.mock/espi/1_1/resource/Batch/Subscription/") -> {
    captureResourceRequest?.invoke(request)
    if (resourceStatus == HttpStatusCode.OK) {
      respond(
        content = ByteReadChannel(MOCK_USAGE_FEED),
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, "application/atom+xml"),
      )
    } else {
      respond(content = "utility resource server down", status = resourceStatus)
    }
  }
  else -> respondError(HttpStatusCode.NotImplemented)
}

// Minimal ESPI usage feed — UsagePoint (electricity) + MeterReading + IntervalBlock (2 hourly
// readings) + ReadingType. Just enough to round-trip through the parser + normalizer + DTO
// mapper. The Burlington Hydro fixture in core/test/resources already covers more entries.
private val MOCK_USAGE_FEED =
  """
  <?xml version="1.0" encoding="UTF-8"?>
  <feed xmlns="http://www.w3.org/2005/Atom">
    <id>urn:uuid:test-feed</id>
    <title>Mock</title>
    <updated>2026-06-02T20:00:00Z</updated>
    <entry xmlns:espi="http://naesb.org/espi">
      <id>urn:uuid:up1</id>
      <link rel="self" href="https://utility.mock/UsagePoint/up1"/>
      <content><espi:UsagePoint><espi:ServiceCategory><espi:kind>0</espi:kind></espi:ServiceCategory><espi:status>1</espi:status></espi:UsagePoint></content>
    </entry>
    <entry xmlns:espi="http://naesb.org/espi">
      <id>urn:uuid:mr1</id>
      <link rel="self" href="https://utility.mock/UsagePoint/up1/MeterReading/mr1"/>
      <link rel="related" type="espi-entry/UsagePoint" href="https://utility.mock/UsagePoint/up1"/>
      <link rel="related" type="espi-entry/ReadingType" href="https://utility.mock/ReadingType/rt1"/>
      <content><espi:MeterReading/></content>
    </entry>
    <entry xmlns:espi="http://naesb.org/espi">
      <id>urn:uuid:ib1</id>
      <link rel="up" type="espi-feed/IntervalBlock" href="https://utility.mock/UsagePoint/up1/MeterReading/mr1/IntervalBlock"/>
      <link rel="self" href="https://utility.mock/UsagePoint/up1/MeterReading/mr1/IntervalBlock/ib1"/>
      <content><espi:IntervalBlock>
        <espi:interval><espi:duration>7200</espi:duration><espi:start>1771909200</espi:start></espi:interval>
        <espi:IntervalReading>
          <espi:timePeriod><espi:duration>3600</espi:duration><espi:start>1771909200</espi:start></espi:timePeriod>
          <espi:value>1000</espi:value>
        </espi:IntervalReading>
        <espi:IntervalReading>
          <espi:timePeriod><espi:duration>3600</espi:duration><espi:start>1771912800</espi:start></espi:timePeriod>
          <espi:value>1500</espi:value>
        </espi:IntervalReading>
      </espi:IntervalBlock></content>
    </entry>
    <entry xmlns:espi="http://naesb.org/espi">
      <id>urn:uuid:rt1</id>
      <link rel="self" href="https://utility.mock/ReadingType/rt1"/>
      <content><espi:ReadingType>
        <espi:accumulationBehaviour>4</espi:accumulationBehaviour>
        <espi:commodity>1</espi:commodity>
        <espi:flowDirection>1</espi:flowDirection>
        <espi:intervalLength>3600</espi:intervalLength>
        <espi:powerOfTenMultiplier>0</espi:powerOfTenMultiplier>
        <espi:uom>72</espi:uom>
      </espi:ReadingType></content>
    </entry>
  </feed>
  """.trimIndent()
