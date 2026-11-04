package com.metavirtuoso.conjunction.tz

import java.io.InputStream
import java.time.ZoneId
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Fallback lat/lon -> IANA zone lookup from the tz database's own `zone1970.tab` (bundled as
 * `tz/zone1970.tab`, public domain, ~17 KB): each zone's reference city with its coordinates and
 * the countries it covers.
 *
 * Used when [TimeZoneGrid] is unavailable. It picks the nearest reference city among the zones of
 * the geocoded country (any zone when the country is unknown). That is exact for single-zone
 * countries and usually right elsewhere, but can pick a neighbouring zone near a zone border; the
 * place step shows the zone and offset so the user can see what was chosen.
 */
class ZoneTable private constructor(private val entries: List<Entry>) {
    private class Entry(val countries: Set<String>, val latitude: Double, val longitude: Double, val zone: String)

    fun zoneFor(latitude: Double, longitude: Double, countryCode: String? = null): ZoneId {
        val country = countryCode?.uppercase()
        val candidates = entries.filter { country != null && country in it.countries }.ifEmpty { entries }
        val best = candidates.minBy { distanceKm(latitude, longitude, it.latitude, it.longitude) }
        return ZoneId.of(best.zone)
    }

    /** Whether [zone] is one of [countryCode]'s zones (to sanity-check another lookup). */
    fun zoneBelongsTo(zone: String, countryCode: String): Boolean =
        entries.any { it.zone == zone && countryCode.uppercase() in it.countries }

    companion object {
        const val ASSET_PATH = "tz/zone1970.tab"

        fun read(input: InputStream): ZoneTable = ZoneTable(
            input.bufferedReader().readLines()
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .mapNotNull { line ->
                    val cols = line.split('\t')
                    if (cols.size < 3) return@mapNotNull null
                    val (lat, lon) = parseIso6709(cols[1]) ?: return@mapNotNull null
                    if (runCatching { ZoneId.of(cols[2]) }.isFailure) return@mapNotNull null
                    Entry(cols[0].split(',').toSet(), lat, lon, cols[2])
                },
        )

        /** "+4230+00131" (±DDMM±DDDMM) or "+404251-0740023" (±DDMMSS±DDDMMSS) -> degrees. */
        internal fun parseIso6709(s: String): Pair<Double, Double>? {
            val split = s.indexOfAny(charArrayOf('+', '-'), startIndex = 1)
            if (split < 0) return null
            return part(s.substring(0, split), 2) to part(s.substring(split), 3)
        }

        private fun part(s: String, degreeDigits: Int): Double {
            val sign = if (s[0] == '-') -1.0 else 1.0
            val digits = s.substring(1)
            val deg = digits.substring(0, degreeDigits).toDouble()
            val min = digits.substring(degreeDigits, degreeDigits + 2).toDouble()
            val sec = if (digits.length > degreeDigits + 2) digits.substring(degreeDigits + 2).toDouble() else 0.0
            return sign * (deg + min / 60 + sec / 3600)
        }

        private fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
            val dp = p2 - p1; val dl = Math.toRadians(lon2 - lon1)
            val a = sin(dp / 2).pow(2) + cos(p1) * cos(p2) * sin(dl / 2).pow(2)
            return 2 * 6371.0 * asin(sqrt(a))
        }
    }
}
