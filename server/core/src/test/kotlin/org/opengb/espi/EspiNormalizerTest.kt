package org.opengb.espi

import de.infix.testBalloon.framework.core.testSuite
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private fun loadFeed(path: String): EspiAtomFeed =
  EspiParser.parseFeed(
    requireNotNull(EspiNormalizer::class.java.getResource(path)) { "Missing test resource: $path" }
      .readText(Charsets.UTF_8),
  )

val EspiNormalizerTest by testSuite {
  test("normalizes the Burlington Hydro 60-minute usage feed into a single ELECTRICITY series") {
    val feed = loadFeed("/fixtures/burlington_hydro/usage_60min.xml")
    val normalized = EspiNormalizer.normalizeUsage(feed)

    assert(normalized.updated == Instant.parse("2026-03-02T22:11:10Z"))
    assert(normalized.usagePoints.size == 1)

    val up = normalized.usagePoints.single()
    assert(up.usagePointId == "00000000-0000-4000-8000-000000000002")
    assert(up.serviceKind == ServiceKind.ELECTRICITY)
    assert(up.series.size == 1)

    val series = up.series.single()
    assert(series.meterReadingId == "00000000-0000-4000-8000-000000000003")
    with(series.readingType) {
      assert(commodity == Commodity.ELECTRICITY_SECONDARY_METERED)
      assert(flowDirection == FlowDirection.FORWARD)
      assert(accumulationBehaviour == AccumulationBehaviour.DELTA_DATA)
      assert(intervalLength == 1.hours)
      assert(unitOfMeasure == UnitOfMeasure.WATT_HOURS)
      assert(powerOfTenMultiplier == 0)
      assert(currencyNumericCode == 124)
    }

    // Two IntervalBlocks × 3 IntervalReadings, flattened and sorted by start.
    assert(series.readings.size == 6)
    val first = series.readings.first()
    // 1771909200 epoch seconds == 2026-02-24T05:00:00Z (midnight local EST).
    assert(first.start == Instant.fromEpochSeconds(1771909200L))
    assert(first.duration == 3600.seconds)
    assert(first.value == 1000.0)

    // Spot-check the first block's last reading and the second block's first reading
    // to confirm cross-block flattening and ordering.
    assert(series.readings[2].start == Instant.fromEpochSeconds(1771916400L))
    assert(series.readings[2].value == 800.0)
    assert(series.readings[3].start == Instant.fromEpochSeconds(1771995600L))
    assert(series.readings[3].value == 1600.0)
  }

  test("applies powerOfTenMultiplier to scale reading values") {
    val readingType =
      EspiPayload.ReadingType(
        uom = 72,
        powerOfTenMultiplier = 3,
        commodity = 1,
        flowDirection = 1,
        intervalLength = 3600L,
      )
    val rtEntry =
      EspiAtomEntry(
        id = "urn:uuid:rt",
        links =
          listOf(
            EspiLink(href = "https://h/r/ReadingType/X", rel = "self", type = "espi-entry/ReadingType"),
          ),
        content = EspiAtomContent(body = readingType),
      )
    val mrEntry =
      EspiAtomEntry(
        id = "urn:uuid:mr",
        links =
          listOf(
            EspiLink(href = "https://h/r/MeterReading/M", rel = "self", type = "espi-entry/MeterReading"),
            EspiLink(href = "https://h/r/UsagePoint/U", rel = "related", type = "espi-entry/UsagePoint"),
            EspiLink(href = "https://h/r/ReadingType/X", rel = "related", type = "espi-entry/ReadingType"),
          ),
        content = EspiAtomContent(body = EspiPayload.MeterReading),
      )
    val ibEntry =
      EspiAtomEntry(
        id = "urn:uuid:ib",
        links =
          listOf(
            EspiLink(href = "https://h/r/MeterReading/M/IntervalBlock", rel = "up", type = "espi-feed/IntervalBlock"),
            EspiLink(
              href = "https://h/r/MeterReading/M/IntervalBlock/B",
              rel = "self",
              type = "espi-entry/IntervalBlock",
            ),
          ),
        content =
          EspiAtomContent(
            body =
              EspiPayload.IntervalBlock(
                interval = EspiDateTimeInterval(duration = 3600L, start = 1771909200L),
                readings =
                  listOf(
                    EspiIntervalReading(
                      timePeriod = EspiTimePeriod(duration = 3600L, start = 1771909200L),
                      value = 2L,
                    ),
                  ),
              ),
          ),
      )
    val upEntry =
      EspiAtomEntry(
        id = "urn:uuid:up",
        links = listOf(EspiLink(href = "https://h/r/UsagePoint/U", rel = "self", type = "espi-entry/UsagePoint")),
        content =
          EspiAtomContent(
            body = EspiPayload.UsagePoint(serviceCategory = EspiServiceCategory(kind = 0)),
          ),
      )

    val feed =
      EspiAtomFeed(
        id = "urn:uuid:f",
        entries = listOf(upEntry, mrEntry, rtEntry, ibEntry),
      )
    val normalized = EspiNormalizer.normalizeUsage(feed)
    val reading = normalized.usagePoints.single().series.single().readings.single()
    // raw 2 × 10^3 = 2000 Wh
    assert(reading.value == 2000.0)
  }
}
