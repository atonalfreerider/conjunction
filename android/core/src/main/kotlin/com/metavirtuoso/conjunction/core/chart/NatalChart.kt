package com.metavirtuoso.conjunction.core.chart

import com.metavirtuoso.conjunction.core.astro.Angles
import com.metavirtuoso.conjunction.core.astro.Ephemeris
import com.metavirtuoso.conjunction.core.astro.HouseResult
import com.metavirtuoso.conjunction.core.astro.HouseSystem
import com.metavirtuoso.conjunction.core.astro.Houses
import com.metavirtuoso.conjunction.core.astro.Planet
import java.time.Instant

/** A body in the chart. [house] is null when the birth time is unknown. */
data class Body(
    val planet: Planet,
    val longitude: Double,
    val latitude: Double,
    val speed: Double,
    val house: Int?,
) {
    val name: String get() = planet.displayName
    val sign: ZodiacSign get() = ZodiacSign.of(longitude)
    val degreeInSign: Double get() = ZodiacSign.degreeIn(longitude)
    val retrograde: Boolean get() = speed < 0.0
}

/** Ptolemaic aspects with their exact angles and base orbs (degrees). */
enum class AspectType(val angle: Double, val orb: Double, val displayName: String, val glyph: String, val verb: String) {
    CONJUNCTION(0.0, 8.0, "Conjunction", "☌", "conjunct"),
    SEXTILE(60.0, 5.0, "Sextile", "⚹", "sextile"),
    SQUARE(90.0, 7.0, "Square", "□", "square"),
    TRINE(120.0, 7.0, "Trine", "△", "trine"),
    OPPOSITION(180.0, 8.0, "Opposition", "☍", "opposite");

    companion object {
        /** Extra orb when the Sun or Moon is involved (the luminaries are given wider orbs). */
        const val LUMINARY_BONUS = 2.0
    }
}

data class Aspect(
    val a: Planet,
    val b: Planet,
    val type: AspectType,
    /** Distance from exact, degrees (>= 0). */
    val orb: Double,
    /** True when the faster body is moving toward exactness. */
    val applying: Boolean,
) {
    val label: String get() = "${a.displayName} ${type.verb} ${b.displayName}"
}

data class NatalChart(
    val birth: BirthData,
    val bodies: List<Body>,
    /** Null when the birth time is unknown. */
    val ascendant: Double?,
    val midheaven: Double?,
    val houseSystem: HouseSystem,
    /** Twelve cusp longitudes (cusp of house 1 first); null when the birth time is unknown. */
    val houses: List<Double>?,
    val aspects: List<Aspect>,
    /** True when Placidus was requested but undefined at this latitude (Porphyry used). */
    val housesFellBack: Boolean = false,
    /** Unknown time only: true when the Moon changes sign during the birth date, so its sign is uncertain. */
    val moonSignUncertain: Boolean = false,
) {
    fun body(planet: Planet): Body = bodies.first { it.planet == planet }
    val sun: Body get() = body(Planet.SUN)
    val moon: Body get() = body(Planet.MOON)
    val sunSign: ZodiacSign get() = sun.sign
    val moonSign: ZodiacSign get() = moon.sign
    val risingSign: ZodiacSign? get() = ascendant?.let(ZodiacSign::of)

    companion object {
        /** Computes a chart. Whole-sign houses are the product default. */
        fun compute(birth: BirthData, houseSystem: HouseSystem = HouseSystem.WHOLE_SIGN): NatalChart {
            val instant = birth.instant
            val houseResult: HouseResult? = if (birth.timeKnown)
                Houses.compute(instant, birth.latitude, birth.longitude, houseSystem) else null
            val bodies = Planet.entries.map { planet ->
                val p = Ephemeris.position(planet, instant)
                Body(planet, p.longitude, p.latitude, p.speed, houseResult?.let { houseFor(p.longitude, it) })
            }
            val moonUncertain = !birth.timeKnown && run {
                val dayStart = birth.date.atStartOfDay(birth.zone).toInstant()
                val dayEnd = birth.date.plusDays(1).atStartOfDay(birth.zone).toInstant().minusSeconds(1)
                ZodiacSign.of(Ephemeris.position(Planet.MOON, dayStart).longitude) !=
                    ZodiacSign.of(Ephemeris.position(Planet.MOON, dayEnd).longitude)
            }
            return NatalChart(
                birth = birth,
                bodies = bodies,
                ascendant = houseResult?.ascendant,
                midheaven = houseResult?.midheaven,
                houseSystem = houseSystem,
                houses = houseResult?.cusps,
                aspects = Aspects.between(bodies),
                housesFellBack = houseResult?.fellBackToPorphyry ?: false,
                moonSignUncertain = moonUncertain,
            )
        }

        private fun houseFor(longitude: Double, houses: HouseResult): Int = when (houses.system) {
            HouseSystem.WHOLE_SIGN ->
                Math.floorMod(ZodiacSign.of(longitude).ordinal - ZodiacSign.of(houses.ascendant).ordinal, 12) + 1
            else -> Houses.houseOf(longitude, houses.cusps)
        }
    }
}

/** Aspect finding between the bodies of one chart, or between two sets (transits, synastry). */
object Aspects {
    private const val SPEED_PROBE_DAYS = 0.01

    fun orbFor(type: AspectType, a: Planet, b: Planet): Double {
        val luminary = a == Planet.SUN || a == Planet.MOON || b == Planet.SUN || b == Planet.MOON
        return type.orb + if (luminary) AspectType.LUMINARY_BONUS else 0.0
    }

    /** The tightest Ptolemaic aspect between two longitudes within [orbFor], or null. */
    fun find(a: Planet, lonA: Double, b: Planet, lonB: Double): Pair<AspectType, Double>? {
        val sep = Angles.separation(lonA, lonB)
        return AspectType.entries
            .map { it to kotlin.math.abs(sep - it.angle) }
            .filter { (type, dev) -> dev <= orbFor(type, a, b) }
            .minByOrNull { it.second }
    }

    fun between(bodies: List<Body>): List<Aspect> {
        val out = mutableListOf<Aspect>()
        for (i in bodies.indices) for (j in i + 1 until bodies.size) {
            val x = bodies[i]
            val y = bodies[j]
            val (type, dev) = find(x.planet, x.longitude, y.planet, y.longitude) ?: continue
            val later = kotlin.math.abs(Angles.separation(x.longitude + x.speed * SPEED_PROBE_DAYS, y.longitude + y.speed * SPEED_PROBE_DAYS) - type.angle)
            out += Aspect(x.planet, y.planet, type, dev, later < dev)
        }
        return out.sortedBy { it.orb }
    }

    /** Aspects from moving bodies (e.g. today's sky) to fixed natal bodies. */
    fun crossing(moving: List<Body>, fixed: List<Body>): List<Aspect> {
        val out = mutableListOf<Aspect>()
        for (m in moving) for (f in fixed) {
            val (type, dev) = find(m.planet, m.longitude, f.planet, f.longitude) ?: continue
            val later = kotlin.math.abs(Angles.separation(m.longitude + m.speed * SPEED_PROBE_DAYS, f.longitude) - type.angle)
            out += Aspect(m.planet, f.planet, type, dev, later < dev)
        }
        return out.sortedBy { it.orb }
    }
}

/** Today's sky relative to a natal chart. */
data class Transits(val instant: Instant, val bodies: List<Body>, val aspectsToNatal: List<Aspect>) {
    companion object {
        /** Transit-to-natal aspects use tighter orbs: half the natal orb. */
        fun compute(natal: NatalChart, instant: Instant): Transits {
            val sky = Planet.entries.map { planet ->
                val p = Ephemeris.position(planet, instant)
                val house = natal.houses?.let { cusps ->
                    if (natal.houseSystem == HouseSystem.WHOLE_SIGN && natal.ascendant != null)
                        Math.floorMod(ZodiacSign.of(p.longitude).ordinal - ZodiacSign.of(natal.ascendant).ordinal, 12) + 1
                    else Houses.houseOf(p.longitude, cusps)
                }
                Body(planet, p.longitude, p.latitude, p.speed, house)
            }
            val aspects = Aspects.crossing(sky, natal.bodies).filter { it.orb <= Aspects.orbFor(it.type, it.a, it.b) / 2.0 }
            return Transits(instant, sky, aspects)
        }

        /** Convenience for "the sky at noon today" in a zone. */
        fun noonOf(date: java.time.LocalDate, zone: java.time.ZoneId): Instant =
            date.atTime(12, 0).atZone(zone).toInstant()
    }
}
