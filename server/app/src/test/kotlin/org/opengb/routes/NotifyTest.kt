package org.opengb.routes

import com.sksamuel.hoplite.Masked
import de.infix.testBalloon.framework.core.testSuite
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.opengb.appModule
import org.opengb.buildAppDeps
import org.opengb.config.AppConfig
import org.opengb.config.CryptoConfig
import org.opengb.config.LandingConfig
import org.opengb.config.ServerConfig
import org.opengb.config.StateConfig
import org.opengb.utility.UtilityProfile
import java.util.Base64

/**
 * Tests for the `POST /notify/{utility}` endpoint. The critical property during ESPI 3.3
 * onboarding is that a notification for an id that is **not yet configured** (its credentials
 * don't exist until we fetch the ApplicationInformation this notification points at) is still
 * accepted with a 2xx — a 404 here makes the Data Custodian retry and email an error.
 */
val NotifyTest by testSuite {
  test("accepts a notification for an unconfigured (mid-onboarding) utility with 200") {
    runNotify {
      val resp =
        client.post("/notify/milton_hydro") {
          contentType(ContentType.Application.Xml)
          setBody(APP_INFO_NOTIFICATION)
        }
      assert(resp.status == HttpStatusCode.OK) { "got ${resp.status}: ${resp.bodyAsText()}" }
    }
  }

  test("accepts a notification for a configured utility with 200") {
    runNotify {
      val resp =
        client.post("/notify/mock") {
          contentType(ContentType.Application.Xml)
          setBody(APP_INFO_NOTIFICATION)
        }
      assert(resp.status == HttpStatusCode.OK) { "got ${resp.status}: ${resp.bodyAsText()}" }
    }
  }

  test("accepts an empty-body notification") {
    runNotify {
      val resp = client.post("/notify/milton_hydro")
      assert(resp.status == HttpStatusCode.OK) { "got ${resp.status}: ${resp.bodyAsText()}" }
    }
  }
}

private fun runNotify(block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit) {
  val utility =
    UtilityProfile(
      id = "mock",
      displayName = "Mock Utility",
      authorizeUrl = "https://utility.mock/authorize",
      tokenUrl = "https://utility.mock/token",
      clientId = "client_id_xyz",
      clientSecret = Masked("client_secret_xyz"),
      defaultScope = "FB=1;IntervalDuration=900",
    )
  val config =
    AppConfig(
      server = ServerConfig(publicBaseUrl = "http://test.local"),
      crypto =
        CryptoConfig(
          aesKeyBase64 = Masked(Base64.getEncoder().encodeToString(ByteArray(32) { (it + 1).toByte() })),
          hmacPepperBase64 =
            Masked(Base64.getEncoder().encodeToString(ByteArray(32) { ((it + 1) * 11).toByte() })),
        ),
      state = StateConfig(),
      landing = LandingConfig(),
      utilities = listOf(utility),
    )
  val deps = buildAppDeps(config)
  runBlocking {
    testApplication {
      application { appModule(deps) }
      block()
    }
  }
}

// A minimal ESPI BatchList whose resource href points at the ApplicationInformation resource the
// third party must GET next. Only URIs — no secrets — as in a real notification.
private val APP_INFO_NOTIFICATION =
  """
  <?xml version="1.0" encoding="UTF-8"?>
  <ns1:BatchList xmlns:ns1="http://naesb.org/espi">
    <ns1:resources>https://sandboxdc.savagedata.com:4243/DataCustodian/espi/1_1/resource/ApplicationInformation/42</ns1:resources>
  </ns1:BatchList>
  """.trimIndent()
