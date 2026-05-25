package org.opengb.espi

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlElement
import nl.adaptivity.xmlutil.serialization.XmlSerialName
import org.opengb.espi.EspiNamespaces.ATOM
import org.opengb.espi.EspiNamespaces.ESPI
import org.opengb.espi.EspiNamespaces.ESPI_CUSTOMER

// Raw wire model. Mirrors what utilities actually emit; normalization (unit conversion,
// epoch-second → Instant, currency, flow direction) happens in a separate pass downstream.

@Serializable
@XmlSerialName(value = "feed", namespace = ATOM, prefix = "")
data class EspiAtomFeed(
  @XmlElement(true) @XmlSerialName("id", ATOM, "") val id: String,
  @XmlElement(true) @XmlSerialName("title", ATOM, "") val title: String? = null,
  @XmlElement(true) @XmlSerialName("updated", ATOM, "") val updated: String? = null,
  val links: List<EspiLink> = emptyList(),
  val entries: List<EspiAtomEntry> = emptyList(),
)

@Serializable
@XmlSerialName(value = "link", namespace = ATOM, prefix = "")
data class EspiLink(
  val href: String,
  val rel: String? = null,
  val type: String? = null,
)

@Serializable
@XmlSerialName(value = "entry", namespace = ATOM, prefix = "")
data class EspiAtomEntry(
  @XmlElement(true) @XmlSerialName("id", ATOM, "") val id: String,
  @XmlElement(true) @XmlSerialName("title", ATOM, "") val title: String? = null,
  @XmlElement(true) @XmlSerialName("published", ATOM, "") val published: String? = null,
  @XmlElement(true) @XmlSerialName("updated", ATOM, "") val updated: String? = null,
  val links: List<EspiLink> = emptyList(),
  val content: EspiAtomContent,
)

@Serializable
@XmlSerialName(value = "content", namespace = ATOM, prefix = "")
data class EspiAtomContent(
  val body: EspiPayload? = null,
)

/**
 * Closed set of payload bodies the parser recognizes. Dispatched at parse time by qualified
 * element name (xmlutil `autoPolymorphic = true`). Unknown payload elements deserialize to
 * `null` so a feed mixing recognized and unrecognized content still round-trips.
 */
@Serializable
sealed interface EspiPayload {
  // Usage payloads (namespace: http://naesb.org/espi) -----------------------------------

  @Serializable
  @XmlSerialName(value = "UsagePoint", namespace = ESPI, prefix = "espi")
  data class UsagePoint(
    @XmlElement(true) val serviceCategory: EspiServiceCategory? = null,
    @XmlElement(true) @XmlSerialName("status", ESPI, "espi") val status: Int? = null,
  ) : EspiPayload

  @Serializable
  @XmlSerialName(value = "MeterReading", namespace = ESPI, prefix = "espi")
  data object MeterReading : EspiPayload

  @Serializable
  @XmlSerialName(value = "IntervalBlock", namespace = ESPI, prefix = "espi")
  data class IntervalBlock(
    @XmlElement(true) val interval: EspiDateTimeInterval,
    val readings: List<EspiIntervalReading> = emptyList(),
  ) : EspiPayload

  @Serializable
  @XmlSerialName(value = "ReadingType", namespace = ESPI, prefix = "espi")
  data class ReadingType(
    @XmlElement(true) @XmlSerialName("accumulationBehaviour", ESPI, "espi") val accumulationBehaviour: Int? = null,
    @XmlElement(true) @XmlSerialName("commodity", ESPI, "espi") val commodity: Int? = null,
    @XmlElement(true) @XmlSerialName("currency", ESPI, "espi") val currency: Int? = null,
    @XmlElement(true) @XmlSerialName("dataQualifier", ESPI, "espi") val dataQualifier: Int? = null,
    @XmlElement(true) @XmlSerialName("defaultQuality", ESPI, "espi") val defaultQuality: Int? = null,
    @XmlElement(true) @XmlSerialName("flowDirection", ESPI, "espi") val flowDirection: Int? = null,
    @XmlElement(true) @XmlSerialName("intervalLength", ESPI, "espi") val intervalLength: Long? = null,
    @XmlElement(true) @XmlSerialName("kind", ESPI, "espi") val kind: Int? = null,
    @XmlElement(true) @XmlSerialName("phase", ESPI, "espi") val phase: Int? = null,
    @XmlElement(true) @XmlSerialName("powerOfTenMultiplier", ESPI, "espi") val powerOfTenMultiplier: Int = 0,
    @XmlElement(true) @XmlSerialName("timeAttribute", ESPI, "espi") val timeAttribute: Int? = null,
    @XmlElement(true) @XmlSerialName("uom", ESPI, "espi") val uom: Int? = null,
  ) : EspiPayload

  @Serializable
  @XmlSerialName(value = "LocalTimeParameters", namespace = ESPI, prefix = "espi")
  data class LocalTimeParameters(
    @XmlElement(true) @XmlSerialName("dstEndRule", ESPI, "espi") val dstEndRule: String? = null,
    @XmlElement(true) @XmlSerialName("dstOffset", ESPI, "espi") val dstOffset: Int? = null,
    @XmlElement(true) @XmlSerialName("dstStartRule", ESPI, "espi") val dstStartRule: String? = null,
    @XmlElement(true) @XmlSerialName("tzOffset", ESPI, "espi") val tzOffset: Int? = null,
  ) : EspiPayload

  // Customer payloads (namespace: http://naesb.org/espi/customer) -----------------------

  @Serializable
  @XmlSerialName(value = "Customer", namespace = ESPI_CUSTOMER, prefix = "cust")
  data class Customer(
    @XmlElement(true) val organisation: CustOrganisation? = null,
    @XmlElement(true) val status: CustStatus? = null,
  ) : EspiPayload

  @Serializable
  @XmlSerialName(value = "CustomerAccount", namespace = ESPI_CUSTOMER, prefix = "cust")
  data class CustomerAccount(
    @XmlElement(true) val contactInfo: CustContactInfo? = null,
    @XmlElement(true) @XmlSerialName("accountId", ESPI_CUSTOMER, "cust") val accountId: String? = null,
  ) : EspiPayload

  @Serializable
  @XmlSerialName(value = "CustomerAgreement", namespace = ESPI_CUSTOMER, prefix = "cust")
  data class CustomerAgreement(
    @XmlElement(true) val validityInterval: CustValidityInterval? = null,
    @XmlElement(true) @XmlSerialName("PricingStructures", ESPI_CUSTOMER, "cust") val pricingStructures: String? = null,
    @XmlElement(true) @XmlSerialName("agreementId", ESPI_CUSTOMER, "cust") val agreementId: String? = null,
  ) : EspiPayload

  @Serializable
  @XmlSerialName(value = "ServiceLocation", namespace = ESPI_CUSTOMER, prefix = "cust")
  data class ServiceLocation(
    @XmlElement(true) @XmlSerialName("type", ESPI_CUSTOMER, "cust") val type: String? = null,
    @XmlElement(true) val mainAddress: CustStreetAddress? = null,
    @XmlElement(true) val status: CustStatus? = null,
    @XmlElement(true) val usagePoints: CustUsagePoints? = null,
  ) : EspiPayload

  @Serializable
  @XmlSerialName(value = "EndDevice", namespace = ESPI_CUSTOMER, prefix = "cust")
  data class EndDevice(
    @XmlElement(true) val status: CustStatus? = null,
  ) : EspiPayload

  @Serializable
  @XmlSerialName(value = "Meter", namespace = ESPI_CUSTOMER, prefix = "cust")
  data class CustMeter(
    @XmlElement(true) val status: CustStatus? = null,
  ) : EspiPayload

  // Same logical type as [LocalTimeParameters] above but emitted under the cust namespace
  // when the carrying feed is a customer feed. ESPI uses the same content shape in both.
  @Serializable
  @XmlSerialName(value = "LocalTimeParameters", namespace = ESPI_CUSTOMER, prefix = "cust")
  data class CustLocalTimeParameters(
    @XmlElement(true) @XmlSerialName("dstEndRule", ESPI_CUSTOMER, "cust") val dstEndRule: String? = null,
    @XmlElement(true) @XmlSerialName("dstOffset", ESPI_CUSTOMER, "cust") val dstOffset: Int? = null,
    @XmlElement(true) @XmlSerialName("dstStartRule", ESPI_CUSTOMER, "cust") val dstStartRule: String? = null,
    @XmlElement(true) @XmlSerialName("tzOffset", ESPI_CUSTOMER, "cust") val tzOffset: Int? = null,
  ) : EspiPayload
}

// Nested ESPI types -----------------------------------------------------------------------

@Serializable
@XmlSerialName(value = "ServiceCategory", namespace = ESPI, prefix = "espi")
data class EspiServiceCategory(
  @XmlElement(true) @XmlSerialName("kind", ESPI, "espi") val kind: Int,
)

@Serializable
@XmlSerialName(value = "interval", namespace = ESPI, prefix = "espi")
data class EspiDateTimeInterval(
  @XmlElement(true) @XmlSerialName("duration", ESPI, "espi") val duration: Long,
  @XmlElement(true) @XmlSerialName("start", ESPI, "espi") val start: Long,
)

@Serializable
@XmlSerialName(value = "IntervalReading", namespace = ESPI, prefix = "espi")
data class EspiIntervalReading(
  @XmlElement(true) val timePeriod: EspiTimePeriod,
  @XmlElement(true) @XmlSerialName("value", ESPI, "espi") val value: Long,
)

@Serializable
@XmlSerialName(value = "timePeriod", namespace = ESPI, prefix = "espi")
data class EspiTimePeriod(
  @XmlElement(true) @XmlSerialName("duration", ESPI, "espi") val duration: Long,
  @XmlElement(true) @XmlSerialName("start", ESPI, "espi") val start: Long,
)

// Nested customer types -------------------------------------------------------------------

@Serializable
@XmlSerialName(value = "Organisation", namespace = ESPI_CUSTOMER, prefix = "cust")
data class CustOrganisation(
  @XmlElement(true) val streetAddress: CustStreetAddress? = null,
)

@Serializable
@XmlSerialName(value = "contactInfo", namespace = ESPI_CUSTOMER, prefix = "cust")
data class CustContactInfo(
  @XmlElement(true) val streetAddress: CustStreetAddress? = null,
)

@Serializable
@XmlSerialName(value = "streetAddress", namespace = ESPI_CUSTOMER, prefix = "cust")
data class CustStreetAddress(
  @XmlElement(true) val streetDetail: CustStreetDetail? = null,
  @XmlElement(true) val townDetail: CustTownDetail? = null,
  @XmlElement(true) val status: CustStatus? = null,
  @XmlElement(true) @XmlSerialName("postalCode", ESPI_CUSTOMER, "cust") val postalCode: String? = null,
)

@Serializable
@XmlSerialName(value = "mainAddress", namespace = ESPI_CUSTOMER, prefix = "cust")
data class CustMainAddress(
  @XmlElement(true) val streetDetail: CustStreetDetail? = null,
  @XmlElement(true) val townDetail: CustTownDetail? = null,
  @XmlElement(true) val status: CustStatus? = null,
  @XmlElement(true) @XmlSerialName("postalCode", ESPI_CUSTOMER, "cust") val postalCode: String? = null,
)

@Serializable
@XmlSerialName(value = "streetDetail", namespace = ESPI_CUSTOMER, prefix = "cust")
data class CustStreetDetail(
  @XmlElement(true) @XmlSerialName("number", ESPI_CUSTOMER, "cust") val number: String? = null,
  @XmlElement(true) @XmlSerialName("name", ESPI_CUSTOMER, "cust") val name: String? = null,
  @XmlElement(true) @XmlSerialName("addressGeneral", ESPI_CUSTOMER, "cust") val addressGeneral: String? = null,
  @XmlElement(true) @XmlSerialName("suiteNumber", ESPI_CUSTOMER, "cust") val suiteNumber: String? = null,
)

@Serializable
@XmlSerialName(value = "townDetail", namespace = ESPI_CUSTOMER, prefix = "cust")
data class CustTownDetail(
  @XmlElement(true) @XmlSerialName("name", ESPI_CUSTOMER, "cust") val name: String? = null,
  @XmlElement(true) @XmlSerialName("stateOrProvince", ESPI_CUSTOMER, "cust") val stateOrProvince: String? = null,
  @XmlElement(true) @XmlSerialName("country", ESPI_CUSTOMER, "cust") val country: String? = null,
)

@Serializable
@XmlSerialName(value = "status", namespace = ESPI_CUSTOMER, prefix = "cust")
data class CustStatus(
  @XmlElement(true) @XmlSerialName("value", ESPI_CUSTOMER, "cust") val value: String? = null,
)

@Serializable
@XmlSerialName(value = "validityInterval", namespace = ESPI_CUSTOMER, prefix = "cust")
data class CustValidityInterval(
  @XmlElement(true) @XmlSerialName("duration", ESPI_CUSTOMER, "cust") val duration: Long? = null,
  @XmlElement(true) @XmlSerialName("start", ESPI_CUSTOMER, "cust") val start: Long? = null,
)

@Serializable
@XmlSerialName(value = "UsagePoints", namespace = ESPI_CUSTOMER, prefix = "cust")
data class CustUsagePoints(
  @XmlElement(true) @XmlSerialName("UsagePoint", ESPI_CUSTOMER, "cust") val refs: List<String> = emptyList(),
)
