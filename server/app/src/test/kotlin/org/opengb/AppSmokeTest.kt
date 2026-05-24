package org.opengb

import com.sksamuel.hoplite.Masked
import de.infix.testBalloon.framework.core.testSuite
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.opengb.config.AppConfig
import org.opengb.config.CryptoConfig
import org.opengb.config.LandingConfig
import org.opengb.config.LandingMode
import org.opengb.config.ServerConfig
import org.opengb.config.StateConfig
import java.util.Base64

val AppSmokeTest by testSuite {
    test("/health returns ok") {
        testApplication {
            application { appModule(buildAppDeps(testConfig(), inertHttp())) }
            val response = client.get("/health")
            assert(response.status == HttpStatusCode.OK)
            assert(response.bodyAsText() == "ok")
        }
    }

    test("/ renders the landing page") {
        testApplication {
            application { appModule(buildAppDeps(testConfig(), inertHttp())) }
            val response = client.get("/")
            assert(response.status == HttpStatusCode.OK)
            val body = response.bodyAsText()
            assert(body.contains("Open Green Button"))
        }
    }

    test("/ in COMING_SOON mode shows under-development text") {
        testApplication {
            application { appModule(buildAppDeps(testConfig(mode = LandingMode.COMING_SOON), inertHttp())) }
            val body = client.get("/").bodyAsText()
            assertContainsIgnoreCase(body, "under active development")
        }
    }

    test("/ in LIVE mode shows privacy story") {
        testApplication {
            application { appModule(buildAppDeps(testConfig(mode = LandingMode.LIVE), inertHttp())) }
            val body = client.get("/").bodyAsText()
            assertContainsIgnoreCase(body, "zero per-user data")
        }
    }
}

private fun inertHttp(): HttpClient =
    HttpClient(MockEngine) {
        engine { addHandler { respondError(HttpStatusCode.NotImplemented) } }
    }

private fun testConfig(mode: LandingMode = LandingMode.COMING_SOON): AppConfig {
    val key32 = Base64.getEncoder().encodeToString(ByteArray(32) { (it + 1).toByte() })
    val pepper32 = Base64.getEncoder().encodeToString(ByteArray(32) { ((it + 1) * 7).toByte() })
    return AppConfig(
        server = ServerConfig(publicBaseUrl = "http://test.local"),
        crypto =
            CryptoConfig(
                aesKeyBase64 = Masked(key32),
                hmacPepperBase64 = Masked(pepper32),
            ),
        state = StateConfig(),
        landing = LandingConfig(mode = mode),
        utilities = emptyList(),
    )
}

private fun assertContainsIgnoreCase(
    haystack: String,
    needle: String,
) {
    assert(haystack.contains(needle, ignoreCase = true)) {
        "expected to contain '$needle': $haystack"
    }
}
