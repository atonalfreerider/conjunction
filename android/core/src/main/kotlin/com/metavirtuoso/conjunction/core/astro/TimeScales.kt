package com.metavirtuoso.conjunction.core.astro

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Julian dates and Delta T. UT is taken as UTC (the < 1 s difference is irrelevant at our
 * accuracy). Terrestrial Time = UT + Delta T.
 */
object TimeScales {
    const val JD_UNIX_EPOCH = 2_440_587.5
    const val J2000 = 2_451_545.0

    fun julianDayUt(instant: Instant): Double =
        JD_UNIX_EPOCH + instant.epochSecond / 86_400.0 + instant.nano / 86_400.0e9

    fun instantFromJulianDayUt(jd: Double): Instant {
        val seconds = (jd - JD_UNIX_EPOCH) * 86_400.0
        val whole = Math.floor(seconds)
        return Instant.ofEpochSecond(whole.toLong(), ((seconds - whole) * 1e9).toLong())
    }

    /** Julian Ephemeris Day (TT) for an instant. */
    fun julianDayTt(instant: Instant): Double = julianDayUt(instant) + deltaTSeconds(instant) / 86_400.0

    /** Julian centuries of TT since J2000.0. */
    fun centuriesTt(jde: Double): Double = (jde - J2000) / 36_525.0

    /**
     * Delta T (TT - UT) in seconds. 1900-2101: linear interpolation in the yearly Swiss Ephemeris
     * table bundled as `delta_t.txt` (exactly the time scale the golden charts were made with).
     * Outside the table: Espenak & Meeus (NASA, 2006) polynomials.
     */
    fun deltaTSeconds(instant: Instant): Double {
        val utc = instant.atOffset(ZoneOffset.UTC)
        val year = utc.year
        val table = EphemerisData.deltaT
        val first = table.firstYear
        val last = first + table.seconds.size - 1
        if (year in first until last) {
            val start = jdAtJan1(year)
            val end = jdAtJan1(year + 1)
            val f = (julianDayUt(instant) - start) / (end - start)
            val a = table.seconds[year - first]
            val b = table.seconds[year - first + 1]
            return a + (b - a) * f
        }
        val y = year + (utc.dayOfYear - 0.5) / (if (utc.toLocalDate().isLeapYear) 366.0 else 365.0)
        return espenakMeeus(y)
    }

    private fun jdAtJan1(year: Int): Double = JD_UNIX_EPOCH + LocalDate.of(year, 1, 1).toEpochDay()

    /** Espenak & Meeus polynomial expressions for Delta T, decimal year [y]. */
    fun espenakMeeus(y: Double): Double = when {
        y < 1860.0 -> {
            val t = y - 1800.0
            13.72 - 0.332447 * t + 0.0068612 * t * t + 0.0041116 * t * t * t - 0.00037436 * t * t * t * t +
                0.0000121272 * Math.pow(t, 5.0) - 0.0000001699 * Math.pow(t, 6.0) + 0.000000000875 * Math.pow(t, 7.0)
        }
        y < 1900.0 -> {
            val t = y - 1860.0
            7.62 + 0.5737 * t - 0.251754 * t * t + 0.01680668 * t * t * t - 0.0004473624 * t * t * t * t +
                Math.pow(t, 5.0) / 233_174.0
        }
        y < 1920.0 -> {
            val t = y - 1900.0
            -2.79 + 1.494119 * t - 0.0598939 * t * t + 0.0061966 * t * t * t - 0.000197 * t * t * t * t
        }
        y < 1941.0 -> {
            val t = y - 1920.0
            21.20 + 0.84493 * t - 0.076100 * t * t + 0.0020936 * t * t * t
        }
        y < 1961.0 -> {
            val t = y - 1950.0
            29.07 + 0.407 * t - t * t / 233.0 + t * t * t / 2547.0
        }
        y < 1986.0 -> {
            val t = y - 1975.0
            45.45 + 1.067 * t - t * t / 260.0 - t * t * t / 718.0
        }
        y < 2005.0 -> {
            val t = y - 2000.0
            63.86 + 0.3345 * t - 0.060374 * t * t + 0.0017275 * t * t * t + 0.000651814 * t * t * t * t +
                0.00002373599 * Math.pow(t, 5.0)
        }
        y < 2050.0 -> {
            val t = y - 2000.0
            62.92 + 0.32217 * t + 0.005589 * t * t
        }
        y < 2150.0 -> {
            val u = (y - 1820.0) / 100.0
            -20.0 + 32.0 * u * u - 0.5628 * (2150.0 - y)
        }
        else -> {
            val u = (y - 1820.0) / 100.0
            -20.0 + 32.0 * u * u
        }
    }
}
