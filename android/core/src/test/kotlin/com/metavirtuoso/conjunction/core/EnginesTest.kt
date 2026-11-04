package com.metavirtuoso.conjunction.core

import com.metavirtuoso.conjunction.core.astro.Angles
import com.metavirtuoso.conjunction.core.astro.HouseSystem
import com.metavirtuoso.conjunction.core.astro.Planet
import com.metavirtuoso.conjunction.core.blood.BloodType
import com.metavirtuoso.conjunction.core.blood.BloodTypeReading
import com.metavirtuoso.conjunction.core.blood.RhFactor
import com.metavirtuoso.conjunction.core.chart.BirthData
import com.metavirtuoso.conjunction.core.chart.NatalChart
import com.metavirtuoso.conjunction.core.chart.Transits
import com.metavirtuoso.conjunction.core.chart.ZodiacSign
import com.metavirtuoso.conjunction.core.chart.formatLongitude
import com.metavirtuoso.conjunction.core.palm.Hand
import com.metavirtuoso.conjunction.core.palm.HandShape
import com.metavirtuoso.conjunction.core.palm.LineLength
import com.metavirtuoso.conjunction.core.palm.PalmAnalyzer
import com.metavirtuoso.conjunction.core.palm.PalmLine
import com.metavirtuoso.conjunction.core.palm.Point2
import com.metavirtuoso.conjunction.core.prompt.HoroscopePrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.cos
import kotlin.math.sin

class EnginesTest {
    private val ava = BirthData(LocalDate.of(1990, 8, 12), LocalTime.of(14, 20), 40.71, -74.01, "America/New_York", "New York")

    @Test
    fun chartStructure() {
        val chart = NatalChart.compute(ava)
        assertEquals(10, chart.bodies.size)
        assertEquals(ZodiacSign.LEO, chart.sunSign)
        assertEquals(12, chart.houses!!.size)
        assertTrue(chart.bodies.all { it.house in 1..12 })
        // Whole-sign: the Sun's house is its sign's distance from the rising sign.
        val expected = Math.floorMod(chart.sunSign.ordinal - chart.risingSign!!.ordinal, 12) + 1
        assertEquals(expected, chart.sun.house)
        // Aspects are within orb and sorted tightest first.
        assertTrue(chart.aspects.zipWithNext().all { (x, y) -> x.orb <= y.orb })
        for (a in chart.aspects) {
            val sep = Angles.separation(chart.body(a.a).longitude, chart.body(a.b).longitude)
            assertEquals(a.orb, kotlin.math.abs(sep - a.type.angle), 1e-9)
        }
        val placidus = NatalChart.compute(ava, HouseSystem.PLACIDUS)
        assertEquals(chart.ascendant!!, placidus.houses!![0], 1e-9)
        assertEquals(chart.midheaven!!, placidus.houses!![9], 1e-9)
    }

    @Test
    fun unknownTime() {
        val chart = NatalChart.compute(ava.copy(time = null))
        assertNull(chart.ascendant)
        assertNull(chart.risingSign)
        assertTrue(chart.bodies.all { it.house == null })
        assertTrue(HoroscopePrompt.bigThree(chart).contains("birth time unknown"))
        // 1995-11-03 Sydney: Moon crosses a sign boundary that day? Check the flag is computed consistently.
        val day = NatalChart.compute(BirthData(LocalDate.of(2026, 11, 3), null, 0.0, 0.0, "UTC"))
        val start = NatalChart.compute(BirthData(LocalDate.of(2026, 11, 3), LocalTime.MIDNIGHT, 0.0, 0.0, "UTC")).moonSign
        val end = NatalChart.compute(BirthData(LocalDate.of(2026, 11, 3), LocalTime.of(23, 59, 59), 0.0, 0.0, "UTC")).moonSign
        assertEquals(start != end, day.moonSignUncertain)
    }

    @Test
    fun transits() {
        val chart = NatalChart.compute(ava)
        val t = Transits.compute(chart, Instant.parse("2026-11-03T17:00:00Z"))
        assertEquals(10, t.bodies.size)
        assertTrue(t.aspectsToNatal.all { it.orb <= 5.0 })
        assertTrue(t.bodies.first { it.planet == Planet.MERCURY }.retrograde) // Mercury retrograde early Nov 2026
    }

    @Test
    fun formatting() {
        assertEquals("14°07′ Leo", formatLongitude(134.12))
        assertEquals("29°59′ Pisces", formatLongitude(359.999))
        assertEquals(ZodiacSign.ARIES, ZodiacSign.of(360.0))
        assertEquals("1st", HoroscopePrompt.ordinal(1)); assertEquals("12th", HoroscopePrompt.ordinal(12)); assertEquals("3rd", HoroscopePrompt.ordinal(3))
    }

    @Test
    fun bloodTypes() {
        assertEquals(BloodType.AB, BloodType.parse("ab+"))
        assertEquals(BloodType.O, BloodType.parse(" O- "))
        assertNull(BloodType.parse("C"))
        assertEquals(RhFactor.NEGATIVE, RhFactor.parse("A-"))
        assertNull(RhFactor.parse("A"))
        for (t in BloodType.entries) {
            val r = BloodTypeReading.traits(t)
            assertTrue(r.title.startsWith("Type ${t.displayName}"))
            assertEquals(3, r.keywords.size)
        }
        assertEquals("Type AB−", BloodTypeReading.label(BloodType.AB, RhFactor.NEGATIVE))
    }

    @Test
    fun palmGeometry() {
        // Synthetic hand: palm 100 px long, 90 px wide, long fingers (middle 95 px).
        fun hand(palmWidth: Double, middle: Double): List<Point2> {
            val pts = MutableList(21) { Point2(0.0, 0.0) }
            pts[0] = Point2(50.0, 200.0)
            val knuckles = mapOf(5 to 50.0 - palmWidth / 2, 9 to 50.0, 13 to 50.0 + palmWidth / 4, 17 to 50.0 + palmWidth / 2)
            for ((mcp, x) in knuckles) {
                val len = when (mcp) { 9 -> middle; 5 -> middle * 0.88; 13 -> middle * 0.92; else -> middle * 0.7 }
                pts[mcp] = Point2(x, 100.0)
                for (k in 1..3) pts[mcp + k] = Point2(x, 100.0 - len * k / 3.0)
            }
            for (k in 1..4) pts[k] = Point2(50.0 - palmWidth / 2 - 10.0 * k, 180.0 - 15.0 * k)
            return pts
        }
        val arc = (0..20).map { i -> val a = Math.PI * i / 40.0; Point2(100.0 * cos(a), 100.0 * sin(a)) }
        val f = PalmAnalyzer.analyze(Hand.RIGHT, hand(90.0, 95.0), mapOf(PalmLine.HEART to arc), mapOf(PalmLine.HEART to 0.9))
        assertEquals(HandShape.AIR, f.handShape)
        assertEquals(0.9, f.palmRatio, 1e-9)
        assertTrue(f.fingers.indexToRing < 1.0)
        assertTrue(f.line(PalmLine.HEART).present)
        assertEquals(LineLength.LONG, f.line(PalmLine.HEART).lengthClass)
        assertTrue(f.line(PalmLine.HEART).curvature > 0.0)
        assertFalse(f.line(PalmLine.FATE).present)
        assertEquals(HandShape.FIRE, PalmAnalyzer.analyze(Hand.LEFT, hand(70.0, 80.0), emptyMap()).handShape)
        assertEquals(HandShape.EARTH, PalmAnalyzer.classifyHandShape(0.9, 0.8))
        assertEquals(HandShape.WATER, PalmAnalyzer.classifyHandShape(0.7, 0.95))
        assertEquals(0.0, PalmAnalyzer.curvature(listOf(Point2(0.0, 0.0), Point2(10.0, 0.0))), 1e-12)
    }
}
