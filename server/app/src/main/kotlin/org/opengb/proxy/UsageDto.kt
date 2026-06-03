package org.opengb.proxy

import kotlinx.serialization.Serializable
import org.opengb.espi.MeterReadingSeries
import org.opengb.espi.NormalizedReadingType
import org.opengb.espi.NormalizedUsage
import org.opengb.espi.UsagePointData
import org.opengb.espi.UsageReading

/**
 * Wire shape of `POST /proxy/usage`. HA persists `newCredentials` (when present) into the
 * config entry, replacing the stored blob + proxy token — utilities are allowed to rotate
 * refresh tokens per RFC 6749 §6, and we surface that to keep the client in sync.
 */
@Serializable
data class ProxyUsageResponse(
  val updated: String?,
  val usagePoints: List<UsagePointDto>,
  val newCredentials: NewCredentialsDto? = null,
)

@Serializable
data class NewCredentialsDto(
  val encryptedRefreshBlob: String,
  val proxyToken: String,
)

@Serializable
data class UsagePointDto(
  val usagePointId: String,
  val serviceKind: String,
  val series: List<MeterReadingSeriesDto>,
)

@Serializable
data class MeterReadingSeriesDto(
  val meterReadingId: String,
  val readingType: NormalizedReadingTypeDto,
  val readings: List<UsageReadingDto>,
)

@Serializable
data class NormalizedReadingTypeDto(
  val commodity: String,
  val flowDirection: String,
  val accumulationBehaviour: String,
  val intervalLengthSeconds: Long,
  val unitOfMeasure: String,
  val unitOfMeasureSymbol: String,
  val powerOfTenMultiplier: Int,
  val currencyNumericCode: Int? = null,
)

@Serializable
data class UsageReadingDto(
  /** ISO-8601 UTC instant — Python's `datetime.fromisoformat` parses this directly. */
  val start: String,
  val durationSeconds: Long,
  val value: Double,
)

/**
 * [NormalizedUsage] → wire DTO. We flatten `kotlin.time.Duration` to seconds and
 * `kotlin.time.Instant` to ISO strings because Python lacks a stdlib ISO 8601 duration
 * parser, and ISO 8601 instants are trivial for Python to consume.
 */
fun NormalizedUsage.toResponse(newCredentials: NewCredentialsDto? = null): ProxyUsageResponse =
  ProxyUsageResponse(
    updated = updated?.toString(),
    usagePoints = usagePoints.map { it.toDto() },
    newCredentials = newCredentials,
  )

private fun UsagePointData.toDto(): UsagePointDto =
  UsagePointDto(
    usagePointId = usagePointId,
    serviceKind = serviceKind.name,
    series = series.map { it.toDto() },
  )

private fun MeterReadingSeries.toDto(): MeterReadingSeriesDto =
  MeterReadingSeriesDto(
    meterReadingId = meterReadingId,
    readingType = readingType.toDto(),
    readings = readings.map { it.toDto() },
  )

private fun NormalizedReadingType.toDto(): NormalizedReadingTypeDto =
  NormalizedReadingTypeDto(
    commodity = commodity.name,
    flowDirection = flowDirection.name,
    accumulationBehaviour = accumulationBehaviour.name,
    intervalLengthSeconds = intervalLength.inWholeSeconds,
    unitOfMeasure = unitOfMeasure.name,
    unitOfMeasureSymbol = unitOfMeasure.symbol,
    powerOfTenMultiplier = powerOfTenMultiplier,
    currencyNumericCode = currencyNumericCode,
  )

private fun UsageReading.toDto(): UsageReadingDto =
  UsageReadingDto(
    start = start.toString(),
    durationSeconds = duration.inWholeSeconds,
    value = value,
  )
