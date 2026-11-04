package com.metavirtuoso.conjunction.core.astro

import com.metavirtuoso.conjunction.core.astro.Angles.ARCSEC
import com.metavirtuoso.conjunction.core.astro.Angles.DEG
import com.metavirtuoso.conjunction.core.astro.Angles.RAD
import java.time.Instant
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** The ten bodies of a natal chart, in conventional order. */
enum class Planet(val displayName: String, val glyph: String) {
    SUN("Sun", "☉"), MOON("Moon", "☽"), MERCURY("Mercury", "☿"), VENUS("Venus", "♀"), MARS("Mars", "♂"),
    JUPITER("Jupiter", "♃"), SATURN("Saturn", "♄"), URANUS("Uranus", "♅"), NEPTUNE("Neptune", "♆"),
    PLUTO("Pluto", "♇");

    /** Lower-case key used in JSON (golden files, uploaded summaries). */
    val key: String get() = name.lowercase()
}

/** Apparent geocentric ecliptic coordinates of date. */
data class EclipticPosition(
    /** Apparent longitude, true equinox of date, degrees [0, 360). */
    val longitude: Double,
    /** Apparent latitude, degrees. */
    val latitude: Double,
    /** Geocentric distance: AU for planets and the Sun, km for the Moon. */
    val distance: Double,
    /** Daily motion in longitude, degrees per day (negative = retrograde). */
    val speed: Double,
)

/**
 * Apparent geocentric positions, accurate to a few arcseconds against Swiss Ephemeris over
 * 1900-2100 (golden-tested to 0.05 deg):
 *  - Sun and Mercury-Neptune: VSOP87D (ecliptic of date) truncated at ~2e-7 rad, Earth and planet
 *    both taken at t - light-time (which applies light-time AND annual aberration, Meeus ch. 33),
 *    FK5 frame correction (Meeus 32.3) and nutation in longitude.
 *  - Moon: Meeus ch. 47 (ELP-2000/82 main terms) + nutation.
 *  - Pluto: Meeus ch. 37 (J2000 heliocentric), precessed to the ecliptic of date (Meeus 21.5).
 * Nutation: IAU 1980 leading terms (Meeus ch. 22 low-precision form, < 0.5").
 */
object Ephemeris {
    private const val LIGHT_TIME_DAYS_PER_AU = 0.0057755183
    private const val SPEED_STEP_DAYS = 0.05

    /** Mean obliquity, nutation in longitude and true obliquity at a JDE (degrees). */
    data class Nutation(val deltaPsi: Double, val deltaEpsilon: Double, val meanObliquity: Double) {
        val trueObliquity: Double get() = meanObliquity + deltaEpsilon
    }

    fun position(planet: Planet, instant: Instant): EclipticPosition {
        val jde = TimeScales.julianDayTt(instant)
        val now = positionAtJde(planet, jde)
        val before = positionAtJde(planet, jde - SPEED_STEP_DAYS)
        val after = positionAtJde(planet, jde + SPEED_STEP_DAYS)
        val speed = Angles.norm180(after.first - before.first) / (2.0 * SPEED_STEP_DAYS)
        return EclipticPosition(now.first, now.second, now.third, speed)
    }

    fun nutation(jde: Double): Nutation {
        val t = TimeScales.centuriesTt(jde)
        val omega = (125.04452 - 1934.136261 * t + 0.0020708 * t * t + t * t * t / 450_000.0) * DEG
        val l = (280.4665 + 36_000.7698 * t) * DEG
        val lp = (218.3165 + 481_267.8813 * t) * DEG
        val dPsi = (-17.20 * sin(omega) - 1.32 * sin(2 * l) - 0.23 * sin(2 * lp) + 0.21 * sin(2 * omega)) * ARCSEC
        val dEps = (9.20 * cos(omega) + 0.57 * cos(2 * l) + 0.10 * cos(2 * lp) - 0.09 * cos(2 * omega)) * ARCSEC
        val eps0 = 23.0 + 26.0 / 60.0 + 21.448 / 3600.0 +
            (-46.8150 * t - 0.00059 * t * t + 0.001813 * t * t * t) * ARCSEC
        return Nutation(dPsi, dEps, eps0)
    }

    /** Returns (longitude deg, latitude deg, distance) for one JDE. */
    private fun positionAtJde(planet: Planet, jde: Double): Triple<Double, Double, Double> {
        val nut = nutation(jde)
        return when (planet) {
            Planet.MOON -> {
                val (lon, lat, dist) = moonMeanEquinox(jde)
                Triple(Angles.norm360(lon + nut.deltaPsi), lat, dist)
            }
            else -> {
                // Light-time iteration. Earth is taken at the same retarded time as the body, so the
                // result also includes annual aberration (Meeus ch. 33).
                var tau = 0.0
                var geo = Vec3(0.0, 0.0, 0.0)
                repeat(3) {
                    val t = jde - tau
                    val earth = vsopRect("EARTH", t)
                    val body = heliocentricRect(planet, t)
                    geo = body - earth
                    tau = LIGHT_TIME_DAYS_PER_AU * geo.length
                }
                var lon = atan2(geo.y, geo.x) * RAD
                val lat = atan2(geo.z, sqrt(geo.x * geo.x + geo.y * geo.y)) * RAD
                // VSOP87 dynamical ecliptic -> FK5 (Meeus 32.3).
                val tc = TimeScales.centuriesTt(jde)
                val lp = (lon - 1.397 * tc - 0.00031 * tc * tc) * DEG
                lon += (-0.09033 + 0.03916 * (cos(lp) + sin(lp)) * tan(lat * DEG)) * ARCSEC
                val latFk5 = lat + 0.03916 * (cos(lp) - sin(lp)) * ARCSEC
                Triple(Angles.norm360(lon + nut.deltaPsi), latFk5, geo.length)
            }
        }
    }

    internal data class Vec3(val x: Double, val y: Double, val z: Double) {
        operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
        val length: Double get() = sqrt(x * x + y * y + z * z)
    }

    /** Heliocentric rectangular coordinates, ecliptic and mean equinox of date, AU. */
    private fun heliocentricRect(planet: Planet, jde: Double): Vec3 = when (planet) {
        Planet.SUN -> Vec3(0.0, 0.0, 0.0)
        Planet.PLUTO -> plutoOfDate(jde)
        Planet.MOON -> error("Moon is geocentric")
        else -> vsopRect(planet.name, jde)
    }

    /** VSOP87D heliocentric spherical (L, B radians; R AU) for a body at a JDE. */
    internal fun vsopSpherical(body: String, jde: Double): Triple<Double, Double, Double> {
        val tau = (jde - TimeScales.J2000) / 365_250.0
        val data = EphemerisData.vsop[body] ?: error("No VSOP87 series for $body")
        fun sum(variable: Int): Double {
            var total = 0.0
            var tPow = 1.0
            for (series in data.series[variable]) {
                var s = 0.0
                for (k in series.a.indices) s += series.a[k] * cos(series.b[k] + series.c[k] * tau)
                total += s * tPow
                tPow *= tau
            }
            return total
        }
        return Triple(Angles.normRadians(sum(0)), sum(1), sum(2))
    }

    private fun vsopRect(body: String, jde: Double): Vec3 {
        val (l, b, r) = vsopSpherical(body, jde)
        return Vec3(r * cos(b) * cos(l), r * cos(b) * sin(l), r * sin(b))
    }

    /** Meeus ch. 47: geocentric Moon, mean equinox of date (no nutation). Degrees, degrees, km. */
    internal fun moonMeanEquinox(jde: Double): Triple<Double, Double, Double> {
        val t = TimeScales.centuriesTt(jde)
        fun poly(vararg c: Double): Double {
            var r = 0.0
            for (k in c.indices.reversed()) r = r * t + c[k]
            return r
        }
        val lp = Angles.norm360(poly(218.3164477, 481_267.88123421, -0.0015786, 1.0 / 538_841.0, -1.0 / 65_194_000.0))
        val d = Angles.norm360(poly(297.8501921, 445_267.1114034, -0.0018819, 1.0 / 545_868.0, -1.0 / 113_065_000.0))
        val m = Angles.norm360(poly(357.5291092, 35_999.0502909, -0.0001535, 1.0 / 24_490_000.0))
        val mp = Angles.norm360(poly(134.9633964, 477_198.8675055, 0.0087414, 1.0 / 69_699.0, -1.0 / 14_712_000.0))
        val f = Angles.norm360(poly(93.2720950, 483_202.0175233, -0.0036539, -1.0 / 3_526_000.0, 1.0 / 863_310_000.0))
        val a1 = (119.75 + 131.849 * t) * DEG
        val a2 = (53.09 + 479_264.290 * t) * DEG
        val a3 = (313.45 + 481_266.484 * t) * DEG
        val e = 1.0 - 0.002516 * t - 0.0000074 * t * t
        var sl = 0.0
        var sr = 0.0
        for (row in EphemerisData.moon.lr) {
            val arg = (row[0] * d + row[1] * m + row[2] * mp + row[3] * f) * DEG
            val ef = eccentricityFactor(row[1], e)
            sl += row[4] * ef * sin(arg)
            sr += row[5] * ef * cos(arg)
        }
        var sb = 0.0
        for (row in EphemerisData.moon.b) {
            val arg = (row[0] * d + row[1] * m + row[2] * mp + row[3] * f) * DEG
            sb += row[4] * eccentricityFactor(row[1], e) * sin(arg)
        }
        val lpr = lp * DEG
        val fr = f * DEG
        val mpr = mp * DEG
        sl += 3958 * sin(a1) + 1962 * sin(lpr - fr) + 318 * sin(a2)
        sb += -2235 * sin(lpr) + 382 * sin(a3) + 175 * sin(a1 - fr) + 175 * sin(a1 + fr) +
            127 * sin(lpr - mpr) - 115 * sin(lpr + mpr)
        return Triple(Angles.norm360(lp + sl * 1e-6), sb * 1e-6, 385_000.56 + sr * 1e-3)
    }

    private fun eccentricityFactor(mMultiple: Int, e: Double): Double = when (mMultiple) {
        0 -> 1.0
        1, -1 -> e
        else -> e * e
    }

    /** Meeus ch. 37 Pluto: heliocentric ecliptic J2000 (l, b degrees; r AU). Valid 1885-2099. */
    internal fun plutoJ2000(jde: Double): Triple<Double, Double, Double> {
        val t = TimeScales.centuriesTt(jde)
        val j = 34.35 + 3034.9057 * t
        val s = 50.08 + 1222.1138 * t
        val p = 238.96 + 144.9600 * t
        var l = 0.0
        var b = 0.0
        var r = 0.0
        for (row in EphemerisData.pluto) {
            val alpha = (row[0] * j + row[1] * s + row[2] * p) * DEG
            val sa = sin(alpha)
            val ca = cos(alpha)
            l += row[3] * sa + row[4] * ca
            b += row[5] * sa + row[6] * ca
            r += row[7] * sa + row[8] * ca
        }
        return Triple(238.958116 + 144.96 * t + l, -3.908239 + b, 40.7241346 + r)
    }

    /** Pluto heliocentric rectangular, ecliptic and mean equinox of date. */
    private fun plutoOfDate(jde: Double): Vec3 {
        val (l0, b0, r) = plutoJ2000(jde)
        val (l, b) = precessEclipticFromJ2000(l0, b0, jde)
        val lr = l * DEG
        val br = b * DEG
        return Vec3(r * cos(br) * cos(lr), r * cos(br) * sin(lr), r * sin(br))
    }

    /** Rigorous ecliptic precession from J2000.0 to the mean ecliptic of date (Meeus 21.5, 21.6). */
    internal fun precessEclipticFromJ2000(lonDeg: Double, latDeg: Double, jde: Double): Pair<Double, Double> {
        val t = TimeScales.centuriesTt(jde) // T (start epoch J2000) = 0
        val eta = (47.0029 * t - 0.03302 * t * t + 0.000060 * t * t * t) * ARCSEC * DEG
        val bigPi = (174.876384 - 869.8089 * ARCSEC * t + 0.03536 * ARCSEC * t * t) * DEG
        val p = (5029.0966 * t + 1.11113 * t * t - 0.000006 * t * t * t) * ARCSEC * DEG
        val lon = lonDeg * DEG
        val lat = latDeg * DEG
        val a = cos(eta) * cos(lat) * sin(bigPi - lon) - sin(eta) * sin(lat)
        val bb = cos(lat) * cos(bigPi - lon)
        val c = cos(eta) * sin(lat) + sin(eta) * cos(lat) * sin(bigPi - lon)
        val newLon = p + bigPi - atan2(a, bb)
        return Pair(Angles.norm360(newLon * RAD), asin(c.coerceIn(-1.0, 1.0)) * RAD)
    }

    /** Greenwich apparent sidereal time in degrees (Meeus 12.4 + equation of the equinoxes). */
    fun greenwichApparentSiderealDegrees(instant: Instant): Double {
        val jd = TimeScales.julianDayUt(instant)
        val t = (jd - TimeScales.J2000) / 36_525.0
        val gmst = 280.46061837 + 360.98564736629 * (jd - TimeScales.J2000) + 0.000387933 * t * t - t * t * t / 38_710_000.0
        val nut = nutation(TimeScales.julianDayTt(instant))
        return Angles.norm360(gmst + nut.deltaPsi * cos(nut.trueObliquity * DEG))
    }
}
