package com.metavirtuoso.conjunction.core.astro

import com.metavirtuoso.conjunction.core.astro.Angles.DEG
import com.metavirtuoso.conjunction.core.astro.Angles.RAD
import java.time.Instant
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

/** House systems offered by the chart engine. */
enum class HouseSystem {
    /** Default. Each sign is one house, starting with the Ascendant's sign. */
    WHOLE_SIGN,

    /**
     * Placidus (time-based trisection of the diurnal/nocturnal semi-arcs). Undefined inside the
     * polar circles; there the engine returns Porphyry cusps and reports [HouseResult.fellBackToPorphyry].
     */
    PLACIDUS,

    /** Porphyry: each quadrant between the angles trisected in ecliptic longitude. */
    PORPHYRY,
}

data class HouseResult(
    val system: HouseSystem,
    val ascendant: Double,
    val midheaven: Double,
    /** Twelve cusp longitudes, cusps[0] = 1st house. */
    val cusps: List<Double>,
    val fellBackToPorphyry: Boolean = false,
)

/** Ascendant, Midheaven and house cusps (degrees, true ecliptic of date). */
object Houses {
    /** Local apparent sidereal time (RAMC), degrees, for east-positive longitude. */
    fun ramc(instant: Instant, longitude: Double): Double =
        Angles.norm360(Ephemeris.greenwichApparentSiderealDegrees(instant) + longitude)

    fun compute(instant: Instant, latitude: Double, longitude: Double, system: HouseSystem): HouseResult {
        val eps = Ephemeris.nutation(TimeScales.julianDayTt(instant)).trueObliquity
        return computeFromRamc(ramc(instant, longitude), latitude, eps, system)
    }

    fun computeFromRamc(ramcDeg: Double, latitude: Double, obliquity: Double, system: HouseSystem): HouseResult {
        val mc = midheaven(ramcDeg, obliquity)
        var asc = ascendant(ramcDeg, latitude, obliquity)
        // Inside the polar circles the formula can return the descending point; like Swiss
        // Ephemeris, keep the Ascendant in the eastern half (0..180 deg ahead of the MC).
        if (abs(latitude) >= 90.0 - obliquity && Angles.norm360(asc - mc) > 180.0) {
            asc = Angles.norm360(asc + 180.0)
        }
        return when (system) {
            HouseSystem.WHOLE_SIGN -> {
                val first = floor(asc / 30.0) * 30.0
                HouseResult(system, asc, mc, List(12) { Angles.norm360(first + 30.0 * it) })
            }
            HouseSystem.PORPHYRY -> HouseResult(system, asc, mc, porphyry(asc, mc))
            HouseSystem.PLACIDUS -> {
                val cusps = placidus(ramcDeg, latitude, obliquity, asc, mc)
                if (cusps == null) HouseResult(system, asc, mc, porphyry(asc, mc), fellBackToPorphyry = true)
                else HouseResult(system, asc, mc, cusps)
            }
        }
    }

    /** Ecliptic longitude culminating on the meridian. */
    fun midheaven(ramcDeg: Double, obliquity: Double): Double {
        val r = ramcDeg * DEG
        return Angles.norm360(atan2(sin(r), cos(r) * cos(obliquity * DEG)) * RAD)
    }

    /** Ecliptic longitude rising on the eastern horizon. */
    fun ascendant(ramcDeg: Double, latitude: Double, obliquity: Double): Double {
        val r = ramcDeg * DEG
        val e = obliquity * DEG
        val phi = latitude * DEG
        return Angles.norm360(atan2(cos(r), -(sin(e) * tan(phi) + cos(e) * sin(r))) * RAD)
    }

    private fun porphyry(asc: Double, mc: Double): List<Double> {
        val ic = Angles.norm360(mc + 180.0)
        val q1 = Angles.norm360(ic - asc) // houses 1-3 span (Asc -> IC)
        val cusps = DoubleArray(12)
        cusps[0] = asc
        cusps[1] = Angles.norm360(asc + q1 / 3.0)
        cusps[2] = Angles.norm360(asc + 2.0 * q1 / 3.0)
        cusps[3] = ic
        val q4 = Angles.norm360(asc + 180.0 - ic) // houses 4-6 (IC -> Desc)
        cusps[4] = Angles.norm360(ic + q4 / 3.0)
        cusps[5] = Angles.norm360(ic + 2.0 * q4 / 3.0)
        for (k in 6..11) cusps[k] = Angles.norm360(cusps[k - 6] + 180.0)
        return cusps.toList()
    }

    /**
     * Placidus cusps by fixed-point iteration on right ascension: a cusp's hour angle is a fixed
     * fraction of its own semi-arc. Returns null where the semi-arc is undefined (circumpolar).
     */
    private fun placidus(ramcDeg: Double, latitude: Double, obliquity: Double, asc: Double, mc: Double): List<Double>? {
        val e = obliquity * DEG
        val tanPhi = tan(latitude * DEG)
        if (abs(latitude) >= 90.0 - obliquity) return null

        fun cusp(fraction: Double, aboveHorizon: Boolean): Double? {
            // Initial guess: equal division of right ascension.
            var ra = if (aboveHorizon) ramcDeg + 90.0 * fraction else ramcDeg + 180.0 - 90.0 * fraction
            var lon = 0.0
            for (iteration in 0 until 50) {
                lon = atan2(sin(ra * DEG), cos(ra * DEG) * cos(e)) * RAD
                val dec = asin(sin(e) * sin(lon * DEG))
                val x = tanPhi * tan(dec)
                if (abs(x) >= 1.0) return null
                val ad = asin(x) * RAD // ascensional difference
                val next = if (aboveHorizon) ramcDeg + fraction * (90.0 + ad) else ramcDeg + 180.0 - fraction * (90.0 - ad)
                if (abs(Angles.norm180(next - ra)) < 1e-9) { ra = next; break }
                ra = next
            }
            return Angles.norm360(atan2(sin(ra * DEG), cos(ra * DEG) * cos(e)) * RAD)
        }

        val c11 = cusp(1.0 / 3.0, true) ?: return null
        val c12 = cusp(2.0 / 3.0, true) ?: return null
        val c2 = cusp(2.0 / 3.0, false) ?: return null
        val c3 = cusp(1.0 / 3.0, false) ?: return null
        val cusps = DoubleArray(12)
        cusps[0] = asc; cusps[1] = c2; cusps[2] = c3; cusps[3] = Angles.norm360(mc + 180.0)
        cusps[9] = mc; cusps[10] = c11; cusps[11] = c12
        cusps[4] = Angles.norm360(c11 + 180.0); cusps[5] = Angles.norm360(c12 + 180.0)
        cusps[6] = Angles.norm360(asc + 180.0); cusps[7] = Angles.norm360(c2 + 180.0); cusps[8] = Angles.norm360(c3 + 180.0)
        return cusps.toList()
    }

    /** 1-based house of a longitude given 12 cusps (works for unequal houses). */
    fun houseOf(longitude: Double, cusps: List<Double>): Int {
        for (i in 0 until 12) {
            val start = cusps[i]
            val end = cusps[(i + 1) % 12]
            val span = Angles.norm360(end - start)
            if (Angles.norm360(longitude - start) < span) return i + 1
        }
        return 1
    }
}
