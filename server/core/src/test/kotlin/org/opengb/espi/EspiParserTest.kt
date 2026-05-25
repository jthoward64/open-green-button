package org.opengb.espi

import de.infix.testBalloon.framework.core.testSuite

private fun resource(path: String): String =
  requireNotNull(EspiParser::class.java.getResource(path)) { "Missing test resource: $path" }
    .readText(Charsets.UTF_8)

val EspiParserTest by testSuite {
  test("parses the Burlington Hydro 60-minute usage feed") {
    val feed = EspiParser.parseFeed(resource("/fixtures/burlington_hydro/usage_60min.xml"))

    assert(feed.id == "urn:uuid:00000000-0000-4000-8000-00000000000a")
    assert(feed.title == "Green Button Usage Feed")
    assert(feed.updated == "2026-03-02T22:11:10Z")

    // One UsagePoint, one LocalTimeParameters, one MeterReading, two IntervalBlocks, one ReadingType.
    assert(feed.entries.size == 6)
    val bodies = feed.entries.map { it.content.body }

    val usagePoint = bodies.filterIsInstance<EspiPayload.UsagePoint>().single()
    assert(usagePoint.status == 1)
    assert(usagePoint.serviceCategory?.kind == 0)

    val tz = bodies.filterIsInstance<EspiPayload.LocalTimeParameters>().single()
    assert(tz.tzOffset == -18000)
    assert(tz.dstOffset == 3600)
    assert(tz.dstStartRule == "360E2000")
    assert(tz.dstEndRule == "B40E2000")

    // MeterReading present (empty content body).
    bodies.filterIsInstance<EspiPayload.MeterReading>().single()

    val blocks = bodies.filterIsInstance<EspiPayload.IntervalBlock>()
    assert(blocks.size == 2)
    val firstBlock = blocks.first()
    assert(firstBlock.interval.start == 1771909200L)
    assert(firstBlock.interval.duration == 86400L)
    assert(firstBlock.readings.size == 3)
    assert(firstBlock.readings.map { it.value } == listOf(1000L, 900L, 800L))
    assert(firstBlock.readings.all { it.timePeriod.duration == 3600L })
    assert(firstBlock.readings.first().timePeriod.start == 1771909200L)
    assert(firstBlock.readings.last().timePeriod.start == 1771916400L)

    val rt = bodies.filterIsInstance<EspiPayload.ReadingType>().single()
    // accumulationBehaviour=4 deltaData, commodity=1 electricity_secondary_metered, currency=124 CAD,
    // flowDirection=1 forward (consumption), kind=12 energy, uom=72 Wh.
    assert(rt.accumulationBehaviour == 4)
    assert(rt.commodity == 1)
    assert(rt.currency == 124)
    assert(rt.flowDirection == 1)
    assert(rt.intervalLength == 3600L)
    assert(rt.kind == 12)
    assert(rt.powerOfTenMultiplier == 0)
    assert(rt.uom == 72)
  }

  test("parses the Burlington Hydro customer feed") {
    val feed = EspiParser.parseFeed(resource("/fixtures/burlington_hydro/customer.xml"))

    assert(feed.title == "RetailCustomer - Customer")
    assert(feed.entries.size == 5)
    val bodies = feed.entries.map { it.content.body }

    val customer = bodies.filterIsInstance<EspiPayload.Customer>().single()
    val streetAddress = customer.organisation?.streetAddress
    assert(streetAddress?.streetDetail?.addressGeneral == "123 EXAMPLE ST")
    assert(streetAddress?.townDetail?.name == "BURLINGTON")
    assert(streetAddress?.townDetail?.stateOrProvince == "ON")
    assert(streetAddress?.postalCode == "L0L 0L0")
    assert(customer.status?.value == "Active")

    val account = bodies.filterIsInstance<EspiPayload.CustomerAccount>().single()
    assert(account.accountId == "100001-0000001")

    val agreement = bodies.filterIsInstance<EspiPayload.CustomerAgreement>().single()
    assert(agreement.agreementId == "0000001-E-20151126")
    assert(agreement.pricingStructures == "RES-TOU")
    assert(agreement.validityInterval?.start == 1448514000L)

    val location = bodies.filterIsInstance<EspiPayload.ServiceLocation>().single()
    assert(location.type == "ServiceLocation")
    val expectedUsagePointRef =
      "/espi/1_1/resource/RetailCustomer/00000000-0000-4000-8000-000000000001" +
        "/UsagePoint/00000000-0000-4000-8000-000000000002"
    assert(location.usagePoints?.refs?.single() == expectedUsagePointRef)

    // Customer feeds carry LocalTimeParameters under the cust namespace; usage feeds use the
    // espi namespace. Confirm the cust-namespaced variant dispatches.
    val tz = bodies.filterIsInstance<EspiPayload.CustLocalTimeParameters>().single()
    assert(tz.tzOffset == -18000)
    assert(tz.dstOffset == 3600)
  }

  test("preserves link metadata on every entry") {
    val feed = EspiParser.parseFeed(resource("/fixtures/burlington_hydro/usage_60min.xml"))

    val readingTypeEntry = feed.entries.single { it.content.body is EspiPayload.ReadingType }
    val selfLink = readingTypeEntry.links.single { it.rel == "self" }
    assert(selfLink.href.endsWith("/ReadingType/100001"))
    assert(selfLink.type == "espi-entry/ReadingType")
  }
}
