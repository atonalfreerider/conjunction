package com.metavirtuoso.conjunction.core

import com.metavirtuoso.conjunction.core.blood.BloodType
import com.metavirtuoso.conjunction.core.chart.BirthData
import com.metavirtuoso.conjunction.core.chinese.ChineseAnimal
import com.metavirtuoso.conjunction.core.chinese.HeavenlyStem
import com.metavirtuoso.conjunction.core.compat.ChineseRelation
import com.metavirtuoso.conjunction.core.compat.Compatibility
import com.metavirtuoso.conjunction.core.compat.StemRelation
import com.metavirtuoso.conjunction.core.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import kotlin.random.Random

class CompatibilityTest {
    @Test
    fun goldenVectors() {
        val golden = TestData.json("golden_compat.json")
        val cases = Json.arr(golden["cases"]).map(Json::obj)
        assertTrue("need >= 30 golden pairs", cases.size >= 30)
        for (c in cases) {
            val a = CompatWire.profileFromMap(Json.obj(c["a"]))
            val b = CompatWire.profileFromMap(Json.obj(c["b"]))
            val expected = Json.obj(c["expected"])
            val actual = CompatWire.resultToMap(Compatibility.score(a, b))
            for ((k, v) in expected) {
                val got = actual[k]
                val norm = { x: Any? -> if (x is Number) x.toLong() else x }
                assertEquals("${c["id"]}.$k", norm(v), norm(got))
            }
        }
        assertTrue(cases.any { (Json.obj(it["expected"])["isExactTwin"]) == true })
        assertTrue(cases.any { (Json.obj(it["expected"])["isBirthdayTwin"]) == true && Json.obj(it["expected"])["isExactTwin"] == false })
    }

    @Test
    fun synastryGolden() {
        val cases = Json.arr(TestData.json("golden_compat.json")["cases"]).map(Json::obj)
        var lines = 0
        for (c in cases) {
            val a = CompatWire.profileFromMap(Json.obj(c["a"]))
            val b = CompatWire.profileFromMap(Json.obj(c["b"]))
            val expected = Json.arr(c["synastry"]).map(Json::obj).map { m -> m.mapValues { (_, v) -> if (v is Number) v.toLong() else v } }
            val got = CompatWire.synastryToList(Compatibility.synastry(a, b)).map { m -> m.mapValues { (_, v) -> if (v is Number) v.toLong() else v } }
            assertEquals("${c["id"]}", expected, got)
            lines += got.size
        }
        assertTrue("golden synastry should have lines", lines > 50)
        // Every line is in orb and sorted by |score x weight|.
        val profiles = CompatFixtures.allProfiles().values.toList()
        for (a in profiles) for (b in profiles) {
            val s = Compatibility.synastry(a, b)
            assertTrue(s.all { it.orbCentideg in 0..it.aspect.orb })
            assertEquals(s.map { Math.abs(it.score * it.weight) }.sortedDescending(), s.map { Math.abs(it.score * it.weight) })
            if (a.ascendant == null || b.ascendant == null) assertTrue(s.none { it.pointA == Compatibility.Point.ASC && it.pointB == Compatibility.Point.ASC })
        }
    }

    @Test
    fun symmetricAndBounded() {
        val profiles = CompatFixtures.allProfiles().values.toList()
        for (a in profiles) for (b in profiles) {
            val ab = Compatibility.score(a, b)
            val ba = Compatibility.score(b, a)
            assertEquals(ab, ba)
            assertTrue(ab.total in 0..100 && ab.western in 0..100 && ab.chinese in 0..100)
            ab.blood?.let { assertTrue(it in 0..100) }
            assertTrue(ab.highlights.size <= 4)
        }
    }

    @Test
    fun relationsTables() {
        assertEquals(ChineseRelation.TRINE, Compatibility.chineseRelation(ChineseAnimal.RAT, ChineseAnimal.DRAGON))
        assertEquals(ChineseRelation.TRINE, Compatibility.chineseRelation(ChineseAnimal.MONKEY, ChineseAnimal.RAT))
        assertEquals(ChineseRelation.HARMONY, Compatibility.chineseRelation(ChineseAnimal.RAT, ChineseAnimal.OX))
        assertEquals(ChineseRelation.HARMONY, Compatibility.chineseRelation(ChineseAnimal.HORSE, ChineseAnimal.GOAT))
        assertEquals(ChineseRelation.HARMONY, Compatibility.chineseRelation(ChineseAnimal.TIGER, ChineseAnimal.PIG))
        assertEquals(ChineseRelation.CLASH, Compatibility.chineseRelation(ChineseAnimal.RAT, ChineseAnimal.HORSE))
        assertEquals(ChineseRelation.HARM, Compatibility.chineseRelation(ChineseAnimal.RAT, ChineseAnimal.GOAT))
        assertEquals(ChineseRelation.HARM, Compatibility.chineseRelation(ChineseAnimal.ROOSTER, ChineseAnimal.DOG))
        assertEquals(ChineseRelation.SAME_SELF_PUNISHMENT, Compatibility.chineseRelation(ChineseAnimal.DRAGON, ChineseAnimal.DRAGON))
        assertEquals(ChineseRelation.SAME, Compatibility.chineseRelation(ChineseAnimal.OX, ChineseAnimal.OX))
        assertEquals(ChineseRelation.NEUTRAL, Compatibility.chineseRelation(ChineseAnimal.RAT, ChineseAnimal.TIGER))
        assertEquals(StemRelation.COMBINATION, Compatibility.stemRelation(HeavenlyStem.JIA, HeavenlyStem.JI))
        assertEquals(StemRelation.SAME_ELEMENT, Compatibility.stemRelation(HeavenlyStem.JIA, HeavenlyStem.YI))
        assertEquals(StemRelation.GENERATING, Compatibility.stemRelation(HeavenlyStem.JIA, HeavenlyStem.BING))
        assertEquals(StemRelation.CONTROLLING, Compatibility.stemRelation(HeavenlyStem.JIA, HeavenlyStem.WU))
        assertEquals(85, Compatibility.bloodScore(BloodType.A, BloodType.O))
        assertEquals(null, Compatibility.bloodScore(BloodType.A, null))
        // Rounding helpers.
        assertEquals(0, Compatibility.centidegrees(359.995))
        assertEquals(3000, Compatibility.centidegrees(29.995))
        assertEquals(3, Compatibility.roundDiv(5, 2))
        assertEquals(-2, Compatibility.roundDiv(-5, 2))
        // Negative aspect parts truncate toward zero: square 1.23 deg off -> -400 * 577 / 700 = -329.7 -> -329.
        assertEquals(-329, Compatibility.pairScore(0, 9123).aspectPart)
    }

    @Test
    fun twins() {
        val all = CompatFixtures.allProfiles()
        val ava = all.getValue("ava-1990")
        assertTrue(Compatibility.isExactTwin(ava, all.getValue("sam-1990")))
        assertTrue(Compatibility.isBirthdayTwin(ava, all.getValue("kai-1990")))
        assertFalse(Compatibility.isExactTwin(ava, all.getValue("kai-1990")))
        assertFalse(Compatibility.isExactTwin(ava, all.getValue("lena-1990"))) // unknown hour never "exact"
        assertTrue(Compatibility.sharesBirthday(ava, all.getValue("omar-1975")))
        assertEquals("Exact twins: same day, same hour", Compatibility.score(ava, all.getValue("sam-1990")).highlights.first())
    }

    /** Calibration guard: across many real charts the score should use the scale sensibly. */
    @Test
    fun distributionIsSensible() {
        val rnd = Random(20261103)
        val zones = listOf("America/New_York" to (40.7 to -74.0), "Europe/London" to (51.5 to -0.1), "Asia/Tokyo" to (35.7 to 139.7))
        val profiles = (0 until 160).map {
            val date = LocalDate.of(1960, 1, 1).plusDays(rnd.nextLong(0, 45L * 365))
            val (zone, ll) = zones[rnd.nextInt(zones.size)]
            val time = if (rnd.nextInt(5) == 0) null else LocalTime.of(rnd.nextInt(24), rnd.nextInt(60))
            val blood = if (rnd.nextInt(4) == 0) null else BloodType.entries[rnd.nextInt(4)]
            CompatFixtures.profile(CompatFixtures.Person("r$it", BirthData(date, time, ll.first, ll.second, zone), blood))
        }
        val totals = mutableListOf<Int>()
        val westerns = mutableListOf<Int>()
        for (i in profiles.indices) for (j in i + 1 until profiles.size) {
            val r = Compatibility.score(profiles[i], profiles[j])
            totals += r.total
            westerns += r.western
        }
        totals.sort(); westerns.sort()
        fun pct(l: List<Int>, p: Double) = l[((l.size - 1) * p).toInt()]
        val mean = totals.average()
        println("compat totals: mean=${"%.1f".format(mean)} p5=${pct(totals, 0.05)} p50=${pct(totals, 0.5)} p95=${pct(totals, 0.95)} min=${totals.first()} max=${totals.last()}")
        println("western: mean=${"%.1f".format(westerns.average())} p5=${pct(westerns, 0.05)} p95=${pct(westerns, 0.95)} min=${westerns.first()} max=${westerns.last()}")
        assertTrue("mean $mean", mean in 55.0..72.0)
        assertTrue(pct(totals, 0.05) >= 35)
        assertTrue(pct(totals, 0.95) <= 92)
        assertTrue(pct(totals, 0.95) - pct(totals, 0.05) >= 20)
    }
}
