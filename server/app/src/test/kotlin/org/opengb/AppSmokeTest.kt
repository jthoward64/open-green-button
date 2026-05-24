package org.opengb

import com.sksamuel.hoplite.Masked
import de.infix.testBalloon.framework.core.testSuite
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
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
      assertContainsIgnoreCase(body, "Privacy is built in")
    }
  }

  // Fly's edge accepts certs for three hostnames pointing at this app — the naked domain,
  // the www subdomain, and the api subdomain. With canonical-host redirects DISABLED
  // (the default, used here), any Host header serves the landing directly. This test locks
  // in the host-agnostic behavior so a stray host-filter doesn't regress it.
  for (host in listOf("opengreenbutton.org", "www.opengreenbutton.org", "api.opengreenbutton.org")) {
    test("landing renders for Host: $host (no canonical redirect)") {
      testApplication {
        application { appModule(buildAppDeps(testConfig(mode = LandingMode.LIVE), inertHttp())) }
        val response =
          client.config { followRedirects = false }.get("/") {
            header(HttpHeaders.Host, host)
          }
        assert(response.status == HttpStatusCode.OK)
        assertContainsIgnoreCase(response.bodyAsText(), "Open Green Button")
      }
    }
  }

  // With canonicalHost set + a www alternate in redirectFrom, www.* should 301 to naked
  // (preserving path + query); naked stays 200; api.* stays 200 (it's not in redirectFrom
  // because its `redirect_uri` is registered with the utility).
  test("www.* 301s to canonical naked host, preserving path") {
    testApplication {
      application {
        appModule(
          buildAppDeps(testConfig(mode = LandingMode.LIVE, withRedirect = true), inertHttp()),
        )
      }
      val response =
        client.config { followRedirects = false }.get("/some/path?q=1") {
          header(HttpHeaders.Host, "www.opengreenbutton.org")
        }
      assert(response.status == HttpStatusCode.MovedPermanently)
      assert(response.headers["Location"] == "https://opengreenbutton.org/some/path?q=1")
    }
  }

  test("naked host serves landing directly when canonical is set") {
    testApplication {
      application {
        appModule(
          buildAppDeps(testConfig(mode = LandingMode.LIVE, withRedirect = true), inertHttp()),
        )
      }
      val response =
        client.config { followRedirects = false }.get("/") {
          header(HttpHeaders.Host, "opengreenbutton.org")
        }
      assert(response.status == HttpStatusCode.OK)
      assertContainsIgnoreCase(response.bodyAsText(), "Privacy is built in")
    }
  }

  test("api.* is not redirected (preserves OAuth callback domain)") {
    testApplication {
      application {
        appModule(
          buildAppDeps(testConfig(mode = LandingMode.LIVE, withRedirect = true), inertHttp()),
        )
      }
      val response =
        client.config { followRedirects = false }.get("/connect/foo/callback") {
          header(HttpHeaders.Host, "api.opengreenbutton.org")
        }
      // Not a 301 — landed in the actual /connect handler (404 for unknown utility is fine;
      // the point is "no redirect").
      assert(response.status != HttpStatusCode.MovedPermanently)
    }
  }
}

private fun inertHttp(): HttpClient =
  HttpClient(MockEngine) {
    engine { addHandler { respondError(HttpStatusCode.NotImplemented) } }
  }

private fun testConfig(
  mode: LandingMode = LandingMode.COMING_SOON,
  withRedirect: Boolean = false,
): AppConfig {
  val key32 = Base64.getEncoder().encodeToString(ByteArray(32) { (it + 1).toByte() })
  val pepper32 = Base64.getEncoder().encodeToString(ByteArray(32) { ((it + 1) * 7).toByte() })
  val server =
    if (withRedirect) {
      ServerConfig(
        publicBaseUrl = "https://api.opengreenbutton.org",
        canonicalHost = "opengreenbutton.org",
        redirectFrom = listOf("www.opengreenbutton.org"),
      )
    } else {
      ServerConfig(publicBaseUrl = "http://test.local")
    }
  return AppConfig(
    server = server,
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
