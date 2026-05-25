package org.opengb.espi

import kotlin.math.pow
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Collapses a raw ESPI usage feed into the domain shape declared in [NormalizedUsage].
 *
 * ESPI's Atom-link graph means each UsagePoint, MeterReading, ReadingType, and IntervalBlock
 * is a sibling entry; the relationships are encoded in `<link rel="up">` and
 * `<link rel="related">` URLs. We resolve those by extracting the trailing path segment after
 * `/<Type>/` markers — the URL structure is mandated by NAESB ESPI 1.1 so this is stable.
 */
object EspiNormalizer {
  fun normalizeUsage(feed: EspiAtomFeed): NormalizedUsage {
    val usagePointEntries = feed.entries.byPayload<EspiPayload.UsagePoint>()
    val meterReadingEntries = feed.entries.byPayload<EspiPayload.MeterReading>()
    val intervalBlockEntries = feed.entries.byPayload<EspiPayload.IntervalBlock>()
    val readingTypesById =
      feed.entries.byPayload<EspiPayload.ReadingType>()
        .mapNotNull { (entry, rt) -> entry.selfId("ReadingType")?.let { it to rt } }
        .toMap()
    val blocksByMeterReadingId =
      intervalBlockEntries
        .mapNotNull { (entry, block) -> entry.upId("MeterReading")?.let { it to block } }
        .groupBy({ it.first }, { it.second })

    val usagePoints =
      usagePointEntries.mapNotNull { (upEntry, upBody) ->
        val upId = upEntry.selfId("UsagePoint") ?: return@mapNotNull null
        val series =
          meterReadingEntries
            .filter { (mrEntry, _) -> mrEntry.referencesUsagePoint(upId) }
            .mapNotNull { (mrEntry, _) -> buildSeries(mrEntry, readingTypesById, blocksByMeterReadingId) }
        UsagePointData(
          usagePointId = upId,
          serviceKind = serviceKindOf(upBody.serviceCategory?.kind),
          series = series,
        )
      }

    return NormalizedUsage(
      updated = feed.updated?.let(::parseInstantOrNull),
      usagePoints = usagePoints,
    )
  }

  private fun buildSeries(
    mrEntry: EspiAtomEntry,
    readingTypesById: Map<String, EspiPayload.ReadingType>,
    blocksByMeterReadingId: Map<String, List<EspiPayload.IntervalBlock>>,
  ): MeterReadingSeries? {
    val mrId = mrEntry.selfId("MeterReading") ?: return null
    val rtId =
      mrEntry.links
        .firstOrNull { it.type == "espi-entry/ReadingType" }
        ?.href?.let { extractId(it, "ReadingType") }
    val rt = rtId?.let { readingTypesById[it] }
    val multiplier = rt?.powerOfTenMultiplier ?: 0
    val scale = if (multiplier == 0) 1.0 else 10.0.pow(multiplier)

    val readings =
      blocksByMeterReadingId[mrId]
        .orEmpty()
        .asSequence()
        .flatMap { it.readings.asSequence() }
        .map { r ->
          UsageReading(
            start = Instant.fromEpochSeconds(r.timePeriod.start),
            duration = r.timePeriod.duration.seconds,
            value = r.value.toDouble() * scale,
          )
        }
        .sortedBy { it.start }
        .toList()

    return MeterReadingSeries(
      meterReadingId = mrId,
      readingType =
        NormalizedReadingType(
          commodity = commodityOf(rt?.commodity),
          flowDirection = flowDirectionOf(rt?.flowDirection),
          accumulationBehaviour = accumulationBehaviourOf(rt?.accumulationBehaviour),
          intervalLength = (rt?.intervalLength ?: 0L).seconds,
          unitOfMeasure = unitOfMeasureOf(rt?.uom),
          powerOfTenMultiplier = multiplier,
          currencyNumericCode = rt?.currency,
        ),
      readings = readings,
    )
  }
}

private inline fun <reified T : EspiPayload> List<EspiAtomEntry>.byPayload(): List<Pair<EspiAtomEntry, T>> =
  mapNotNull { entry -> (entry.content.body as? T)?.let { entry to it } }

private fun EspiAtomEntry.selfId(segment: String): String? =
  links.firstOrNull { it.rel == "self" }?.href?.let { extractId(it, segment) }

private fun EspiAtomEntry.upId(segment: String): String? =
  links.firstOrNull { it.rel == "up" }?.href?.let { extractId(it, segment) }

private fun EspiAtomEntry.referencesUsagePoint(upId: String): Boolean =
  links.any { l ->
    l.rel == "related" && l.type == "espi-entry/UsagePoint" && extractId(l.href, "UsagePoint") == upId
  }

private fun extractId(
  url: String,
  segment: String,
): String? {
  val marker = "/$segment/"
  val idx = url.indexOf(marker)
  if (idx < 0) return null
  val after = url.substring(idx + marker.length)
  val end = after.indexOf('/')
  return if (end < 0) after else after.substring(0, end)
}

private fun parseInstantOrNull(value: String): Instant? = runCatching { Instant.parse(value) }.getOrNull()

// Code → enum mappers below. Integer literals come straight from the NAESB ESPI spec
// (ServiceCategory.kind, ReadingType.commodity / flowDirection / accumulationBehavior / uom);
// they are the spec, not magic, so MagicNumber is suppressed here.

@Suppress("MagicNumber")
private fun serviceKindOf(kind: Int?): ServiceKind =
  when (kind) {
    0 -> ServiceKind.ELECTRICITY
    1 -> ServiceKind.GAS
    2 -> ServiceKind.WATER
    4 -> ServiceKind.HEAT
    5 -> ServiceKind.COLD
    else -> ServiceKind.UNKNOWN
  }

@Suppress("MagicNumber")
private fun commodityOf(c: Int?): Commodity =
  when (c) {
    1 -> Commodity.ELECTRICITY_SECONDARY_METERED
    2 -> Commodity.ELECTRICITY_PRIMARY_METERED
    7 -> Commodity.NATURAL_GAS
    9 -> Commodity.WATER
    else -> Commodity.OTHER
  }

@Suppress("MagicNumber")
private fun flowDirectionOf(f: Int?): FlowDirection =
  when (f) {
    1 -> FlowDirection.FORWARD
    4 -> FlowDirection.TOTAL
    19 -> FlowDirection.REVERSE
    20 -> FlowDirection.NET
    else -> FlowDirection.OTHER
  }

@Suppress("MagicNumber")
private fun accumulationBehaviourOf(a: Int?): AccumulationBehaviour =
  when (a) {
    1 -> AccumulationBehaviour.BULK_QUANTITY
    3 -> AccumulationBehaviour.CUMULATIVE
    4 -> AccumulationBehaviour.DELTA_DATA
    12 -> AccumulationBehaviour.INSTANTANEOUS
    else -> AccumulationBehaviour.OTHER
  }

@Suppress("MagicNumber")
private fun unitOfMeasureOf(u: Int?): UnitOfMeasure =
  when (u) {
    38 -> UnitOfMeasure.WATTS
    72 -> UnitOfMeasure.WATT_HOURS
    119 -> UnitOfMeasure.CUBIC_METERS
    else -> UnitOfMeasure.OTHER
  }
