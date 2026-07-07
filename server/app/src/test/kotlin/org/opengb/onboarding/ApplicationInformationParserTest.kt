package org.opengb.onboarding

import de.infix.testBalloon.framework.core.testSuite

/**
 * The parser is deliberately schema-agnostic (a flat multimap of whatever the DC sent). These tests
 * pin the behaviour the onboarding driver relies on: it anchors on the ApplicationInformation node
 * inside the Atom wrapper, strips namespaces, and preserves repeated ESPI elements.
 */
val ApplicationInformationParserTest by testSuite {
  test("extracts fields from the ApplicationInformation node inside an Atom wrapper") {
    val info = ApplicationInformationParser.parse(SAMPLE_APP_INFO)

    assert(info.first("client_id") == "opengreenbutton-milton") { info.fields.toString() }
    assert(info.first("client_secret") == "s3cr3t-value") { info.fields.toString() }
    assert(
      info.first("authorizationServerTokenEndpoint") ==
        "https://sandboxdc.savagedata.com:4243/connect/token",
    ) { info.fields.toString() }
    assert(
      info.first("dataCustodianResourceEndpoint") ==
        "https://sandboxdc.savagedata.com:4243/espi/1_1/resource",
    ) { info.fields.toString() }
  }

  test("preserves repeated elements in document order") {
    val info = ApplicationInformationParser.parse(SAMPLE_APP_INFO)
    assert(info.all("grant_types") == listOf("authorization_code", "client_credentials", "refresh_token")) {
      info.all("grant_types").toString()
    }
    assert(info.all("redirect_uri") == listOf("https://api.opengreenbutton.org/connect/milton_hydro/callback")) {
      info.all("redirect_uri").toString()
    }
  }

  test("does not surface Atom wrapper metadata as ApplicationInformation fields") {
    val info = ApplicationInformationParser.parse(SAMPLE_APP_INFO)
    // The feed <title>Atom feed title</title> lives outside ApplicationInformation and must not leak in.
    assert(info.all("title").none { it == "Atom feed title" }) { info.all("title").toString() }
  }
}

// Representative ESPI ApplicationInformation resource wrapped in Atom, with the espi namespace and a
// couple of repeated elements. Field names are illustrative — the real savagedata names are what the
// driver will actually print; the parser doesn't hard-code any of them.
private val SAMPLE_APP_INFO =
  """
  <?xml version="1.0" encoding="UTF-8"?>
  <feed xmlns="http://www.w3.org/2005/Atom" xmlns:espi="http://naesb.org/espi">
    <title>Atom feed title</title>
    <entry>
      <content>
        <espi:ApplicationInformation>
          <espi:client_id>opengreenbutton-milton</espi:client_id>
          <espi:client_secret>s3cr3t-value</espi:client_secret>
          <espi:authorizationServerAuthorizationEndpoint>https://sandboxdc.savagedata.com:4243/connect/authorize</espi:authorizationServerAuthorizationEndpoint>
          <espi:authorizationServerTokenEndpoint>https://sandboxdc.savagedata.com:4243/connect/token</espi:authorizationServerTokenEndpoint>
          <espi:dataCustodianResourceEndpoint>https://sandboxdc.savagedata.com:4243/espi/1_1/resource</espi:dataCustodianResourceEndpoint>
          <espi:grant_types>authorization_code</espi:grant_types>
          <espi:grant_types>client_credentials</espi:grant_types>
          <espi:grant_types>refresh_token</espi:grant_types>
          <espi:redirect_uri>https://api.opengreenbutton.org/connect/milton_hydro/callback</espi:redirect_uri>
          <espi:scope>FB=1_3_4_5</espi:scope>
        </espi:ApplicationInformation>
      </content>
    </entry>
  </feed>
  """.trimIndent()
