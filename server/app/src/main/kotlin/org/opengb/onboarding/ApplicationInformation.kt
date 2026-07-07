package org.opengb.onboarding

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import org.w3c.dom.Element
import org.w3c.dom.Node
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The parsed contents of an ESPI ApplicationInformation resource: every leaf element under the
 * `ApplicationInformation` node, keyed by its local (namespace-stripped) name, preserving document
 * order and repeats (ESPI repeats `grant_types`, `scope`, `redirect_uri`, `response_types`, …).
 *
 * We keep this deliberately schema-agnostic — a flat multimap of whatever the DC actually sent —
 * so the onboarding driver can dump the full resource and we can see the exact field names savagedata
 * uses before hard-coding a mapping into a UtilityProfile.
 */
data class ApplicationInformation(
  val fields: Map<String, List<String>>,
  val rawXml: String,
) {
  fun first(name: String): String? = fields[name]?.firstOrNull()

  fun all(name: String): List<String> = fields[name].orEmpty()
}

/** Parses an ApplicationInformation Atom/XML document into a flat [ApplicationInformation]. */
object ApplicationInformationParser {
  private const val APP_INFO_ELEMENT = "ApplicationInformation"

  fun parse(xml: String): ApplicationInformation {
    val doc =
      newSecureFactory()
        .newDocumentBuilder()
        .parse(xml.byteInputStream(Charsets.UTF_8))
    doc.documentElement.normalize()

    // ESPI wraps the resource in Atom (feed/entry/content/ApplicationInformation). Anchor on the
    // ApplicationInformation element if present; otherwise fall back to the document root so the
    // driver still surfaces *something* for an unexpected shape.
    val root = findByLocalName(doc.documentElement, APP_INFO_ELEMENT) ?: doc.documentElement

    val fields = LinkedHashMap<String, MutableList<String>>()
    collectLeaves(root, fields)
    return ApplicationInformation(fields = fields, rawXml = xml)
  }

  private fun findByLocalName(
    element: Element,
    localName: String,
  ): Element? {
    if (element.localName == localName || element.tagName.substringAfter(':') == localName) {
      return element
    }
    val children = element.childNodes
    for (i in 0 until children.length) {
      val child = children.item(i)
      if (child is Element) {
        findByLocalName(child, localName)?.let { return it }
      }
    }
    return null
  }

  private fun collectLeaves(
    element: Element,
    into: MutableMap<String, MutableList<String>>,
  ) {
    val childElements = element.childNodes.toElementList()
    if (childElements.isEmpty()) {
      val text = element.textContent?.trim().orEmpty()
      if (text.isNotEmpty()) {
        val key = element.localName ?: element.tagName.substringAfter(':')
        into.getOrPut(key) { mutableListOf() }.add(text)
      }
      return
    }
    childElements.forEach { collectLeaves(it, into) }
  }

  private fun org.w3c.dom.NodeList.toElementList(): List<Element> =
    (0 until length).mapNotNull { item(it) as? Element }.filter { it.nodeType == Node.ELEMENT_NODE }

  private fun newSecureFactory(): DocumentBuilderFactory =
    DocumentBuilderFactory.newInstance().apply {
      isNamespaceAware = true
      isXIncludeAware = false
      isExpandEntityReferences = false
      // Harden against XXE — the document comes off the network from the Data Custodian.
      setFeatureQuietly("http://apache.org/xml/features/disallow-doctype-decl", true)
      setFeatureQuietly("http://xml.org/sax/features/external-general-entities", false)
      setFeatureQuietly("http://xml.org/sax/features/external-parameter-entities", false)
    }

  private fun DocumentBuilderFactory.setFeatureQuietly(
    feature: String,
    value: Boolean,
  ) {
    runCatching { setFeature(feature, value) }
  }
}

/** Fetches an ApplicationInformation resource from a Data Custodian using a bearer access token. */
class AppInfoClient {
  /**
   * GETs [appInfoUrl] presenting [accessToken] as a bearer token, over [httpClient] (an mTLS client
   * for savagedata). Returns the parsed resource. Throws [AppInfoException] on a non-2xx response.
   */
  suspend fun fetch(
    httpClient: HttpClient,
    appInfoUrl: String,
    accessToken: String,
  ): ApplicationInformation {
    val response =
      httpClient.get(appInfoUrl) {
        headers {
          append(HttpHeaders.Authorization, "Bearer $accessToken")
          append(HttpHeaders.Accept, "application/atom+xml, application/xml")
        }
      }
    val body = response.bodyAsText()
    if (response.status != HttpStatusCode.OK) {
      throw AppInfoException(
        "ApplicationInformation endpoint returned ${response.status.value}: $body",
        statusCode = response.status.value,
      )
    }
    return ApplicationInformationParser.parse(body)
  }
}

class AppInfoException(
  message: String,
  val statusCode: Int? = null,
  cause: Throwable? = null,
) : RuntimeException(message, cause)
