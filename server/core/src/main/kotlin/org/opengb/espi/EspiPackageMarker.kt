package org.opengb.espi

/**
 * ESPI (NAESB REQ.21) Green Button data model and parser.
 *
 * Implementation lands in Phase 2 of the project roadmap. This package will house:
 *  - @Serializable data classes for UsagePoint, MeterReading, ReadingType, IntervalBlock, UsageSummary
 *  - xmlutil-based parser for Atom feeds carrying ESPI XML
 *  - UnitNormalizer for ESPI uom codes + powerOfTenMultiplier
 *
 * This sentinel object exists only so the package compiles before Phase 2 code lands; remove it
 * once real types are introduced.
 */
internal object EspiPackageMarker
