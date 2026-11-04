package com.metavirtuoso.conjunction.core.chart

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Everything the engines need about a birth. Birth data never leaves the phone; only the derived
 * [com.metavirtuoso.conjunction.core.compat.CompatProfile] is uploaded when Nearby is on.
 *
 * Time-zone rules: [zoneId] is an IANA id and is resolved with the tz rules valid at [date], so
 * historical DST and local-mean-time offsets are right. A [time] that falls in a DST gap is moved
 * forward by the gap length; an ambiguous time in a DST overlap uses the earlier offset (the
 * first occurrence). Both are java.time's `ZonedDateTime.of` behaviour and are mirrored by the
 * Swift engine and the golden generator.
 */
data class BirthData(
    val date: LocalDate,
    /** Exact local clock time of birth, or null when unknown (disables Ascendant, houses, hour pillar). */
    val time: LocalTime?,
    val latitude: Double,
    /** East-positive longitude in degrees. */
    val longitude: Double,
    val zoneId: String,
    val placeName: String = "",
) {
    init {
        require(latitude in -90.0..90.0) { "latitude out of range: $latitude" }
        require(longitude in -180.0..180.0) { "longitude out of range: $longitude" }
        ZoneId.of(zoneId) // validates the id (throws DateTimeException for unknown zones)
    }

    val zone: ZoneId get() = ZoneId.of(zoneId)
    val timeKnown: Boolean get() = time != null

    /** The birth moment; with unknown time, local noon (the least-wrong single instant for the day). */
    val instant: Instant get() = zonedDateTime.toInstant()

    val zonedDateTime: ZonedDateTime get() = ZonedDateTime.of(date, time ?: NOON, zone)

    companion object {
        val NOON: LocalTime = LocalTime.NOON
    }
}
