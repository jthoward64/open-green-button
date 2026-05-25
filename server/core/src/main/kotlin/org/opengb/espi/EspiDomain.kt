package org.opengb.espi

import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Normalized representation of an ESPI usage feed — the shape downstream consumers
 * (the HA statistics writer, `/proxy/usage` JSON response) see.
 *
 * Instants are absolute (UTC); `value` is already scaled by the ReadingType's
 * `powerOfTenMultiplier` and expressed in the per-series [UnitOfMeasure]. The wire-level
 * Atom/ESPI link graph is collapsed: IntervalBlocks are flattened into a single sorted list
 * of readings under their parent MeterReading, and ReadingType metadata is inlined onto the
 * series rather than left as a sibling entry to be looked up.
 */
data class NormalizedUsage(
  val updated: Instant?,
  val usagePoints: List<UsagePointData>,
)

data class UsagePointData(
  val usagePointId: String,
  val serviceKind: ServiceKind,
  val series: List<MeterReadingSeries>,
)

data class MeterReadingSeries(
  val meterReadingId: String,
  val readingType: NormalizedReadingType,
  val readings: List<UsageReading>,
)

data class NormalizedReadingType(
  val commodity: Commodity,
  val flowDirection: FlowDirection,
  val accumulationBehaviour: AccumulationBehaviour,
  val intervalLength: Duration,
  val unitOfMeasure: UnitOfMeasure,
  val powerOfTenMultiplier: Int,
  val currencyNumericCode: Int?,
)

data class UsageReading(
  val start: Instant,
  val duration: Duration,
  val value: Double,
)

enum class ServiceKind {
  ELECTRICITY,
  GAS,
  WATER,
  HEAT,
  COLD,
  UNKNOWN,
}

enum class Commodity {
  ELECTRICITY_SECONDARY_METERED,
  ELECTRICITY_PRIMARY_METERED,
  NATURAL_GAS,
  WATER,
  OTHER,
}

enum class FlowDirection {
  FORWARD,
  REVERSE,
  NET,
  TOTAL,
  OTHER,
}

enum class AccumulationBehaviour {
  BULK_QUANTITY,
  CUMULATIVE,
  DELTA_DATA,
  INSTANTANEOUS,
  OTHER,
}

enum class UnitOfMeasure(val symbol: String) {
  WATT_HOURS("Wh"),
  WATTS("W"),
  CUBIC_METERS("m³"),
  OTHER("?"),
}
