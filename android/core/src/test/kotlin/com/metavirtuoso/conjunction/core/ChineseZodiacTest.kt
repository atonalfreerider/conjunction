package com.metavirtuoso.conjunction.core

import com.metavirtuoso.conjunction.core.chart.BirthData
import com.metavirtuoso.conjunction.core.chinese.ChineseAnimal
import com.metavirtuoso.conjunction.core.chinese.ChineseOptions
import com.metavirtuoso.conjunction.core.chinese.ChineseZodiac
import com.metavirtuoso.conjunction.core.chinese.FiveElement
import com.metavirtuoso.conjunction.core.chinese.HeavenlyStem
import com.metavirtuoso.conjunction.core.chinese.LunarNewYear
import com.metavirtuoso.conjunction.core.chinese.Pillar
import com.metavirtuoso.conjunction.core.chinese.SolarTimeBasis
import com.metavirtuoso.conjunction.core.chinese.YinYang
import com.metavirtuoso.conjunction.core.chinese.ZiHourConvention
import com.metavirtuoso.conjunction.core.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

class ChineseZodiacTest {
    private val chinaTime = ZoneOffset.ofHours(8)

    @Test
    fun goldenCasesFromIndependentOracle() {
        val cases = Json.arr(TestData.json("golden_chinese.json")["cases"]).map(Json::obj)
        assertTrue(cases.size >= 40)
        for (c in cases) {
            val id = c["id"] as String
            val birth = BirthData(
                date = LocalDate.parse(c["date"] as String),
                time = (c["time"] as String?)?.let(LocalTime::parse),
                latitude = 0.0,
                longitude = Json.num(c["longitude"]),
                zoneId = c["zoneId"] as String,
            )
            val e = Json.obj(c["expected"])
            val chart = ChineseZodiac.compute(birth)
            assertEquals("$id year", e["yearPillar"], chart.pillars.year.hanzi)
            assertEquals("$id month", e["monthPillar"], chart.pillars.month.hanzi)
            assertEquals("$id day", e["dayPillar"], chart.pillars.day.hanzi)
            assertEquals("$id hour", e["hourPillar"], chart.pillars.hour?.hanzi)
            assertEquals("$id animal", e["yearAnimal"], chart.yearAnimal.key)
            assertEquals("$id lunar animal", e["lunarYearAnimal"], chart.lunarYearAnimal.key)
            assertEquals("$id solar year", (e["solarYear"] as Number).toInt(), chart.solarYear)
            val lichunErr = kotlin.math.abs(ChineseZodiac.lichun(chart.solarYear).epochSecond - Instant.parse(e["lichunUtc"] as String).epochSecond)
            if (chart.solarYear == LocalDate.parse(c["date"] as String).year) {
                assertTrue("$id Lichun within 2 min of Swiss Ephemeris (was ${lichunErr}s)", lichunErr <= 120)
            }
        }
    }

    @Test
    fun lichunDateMatchesHongKongObservatoryEveryYear() {
        val terms = Json.arr(TestData.json("chinese_calendar.json")["solarTerms"]).map(Json::obj)
        val lichun = terms.filter { (it["longitude"] as Number).toInt() == 315 }
        assertEquals(200, lichun.size)
        for (t in lichun) {
            val date = LocalDate.parse(t["date"] as String)
            assertEquals("Lichun ${date.year}", date, ChineseZodiac.lichun(date.year).atOffset(chinaTime).toLocalDate())
        }
    }

    @Test
    fun allSolarTermsMatchHongKongObservatory() {
        val terms = Json.arr(TestData.json("chinese_calendar.json")["solarTerms"]).map(Json::obj)
        assertEquals(4800, terms.size)
        val mismatches = mutableListOf<String>()
        for (t in terms) {
            val date = LocalDate.parse(t["date"] as String)
            val lon = Json.num(t["longitude"])
            val instant = ChineseZodiac.solarTermInstant(date.year, lon)
            val local = instant.atOffset(chinaTime)
            if (local.toLocalDate() != date) {
                // HKO's tables disagree with modern ephemerides by up to ~12 min for 1912-1928
                // (terms within minutes of midnight, in both directions). Accept a date difference
                // only when the term falls within 15 minutes of midnight China time.
                val minutesFromMidnight = minOf(local.hour * 60 + local.minute, 1440 - (local.hour * 60 + local.minute))
                if (minutesFromMidnight > 15) mismatches += "${t["name"]} $date -> $local"
            }
        }
        assertTrue("solar term dates differ from HKO: $mismatches", mismatches.isEmpty())
    }

    @Test
    fun dayPillarMatchesWikipediaAlgorithmEveryDay1900To2100() {
        var d = LocalDate.of(1900, 1, 1)
        val end = LocalDate.of(2100, 12, 31)
        while (!d.isAfter(end)) {
            assertEquals(d.toString(), wikipediaDayIndex(d), ChineseZodiac.dayPillar(d).cycleIndex)
            d = d.plusDays(1)
        }
        assertEquals("甲子", ChineseZodiac.dayPillar(LocalDate.of(1949, 10, 1)).hanzi)
        assertEquals("甲辰", ChineseZodiac.dayPillar(LocalDate.of(1900, 1, 31)).hanzi)
        assertEquals("戊午", ChineseZodiac.dayPillar(LocalDate.of(2000, 1, 1)).hanzi)
    }

    /** Wikipedia "Sexagenary cycle" mental-calculation algorithm, 0-based (0 = 甲子). */
    private fun wikipediaDayIndex(d: LocalDate): Int {
        val r = (d.year % 400) % 80
        val y = (r * 5 + r / 4) % 60
        val c = d.year / 400 - d.year / 100 + 10
        val mTable = intArrayOf(0, 31, -1, 30, 0, 31, 1, 32, 3, 33, 4, 34)
        var m = mTable[d.monthValue - 1]
        if (d.isLeapYear && d.monthValue <= 2) m -= 1
        val sb = Math.floorMod(y + c + m + d.dayOfMonth, 60)
        return Math.floorMod(sb - 1, 60)
    }

    @Test
    fun lunarNewYearTableCoversRangeAndFlipsAnimal() {
        assertEquals(1900, LunarNewYear.firstYear)
        assertEquals(2100, LunarNewYear.lastYear)
        val newYears = Json.obj(TestData.json("chinese_calendar.json")["newYear"])
        for ((y, date) in newYears) {
            val ny = LocalDate.parse(date as String)
            assertEquals(ny, LunarNewYear.dates[y.toInt()])
            assertEquals(y.toInt(), ChineseZodiac.lunarYearOf(ny))
            assertEquals(y.toInt() - 1, ChineseZodiac.lunarYearOf(ny.minusDays(1)))
        }
        // 2024 = Dragon (甲辰), 2026 = Horse (丙午).
        assertEquals("甲辰", ChineseZodiac.yearPillar(2024).hanzi)
        assertEquals(ChineseAnimal.HORSE, ChineseZodiac.yearPillar(2026).branch.animal)
        assertEquals(FiveElement.FIRE, ChineseZodiac.yearPillar(2026).stem.element)
    }

    @Test
    fun pillarArithmetic() {
        for (i in 0 until 60) assertEquals(i, Pillar.ofCycleIndex(i).cycleIndex)
        assertEquals(YinYang.YANG, HeavenlyStem.JIA.yinYang)
        assertEquals(FiveElement.WATER, HeavenlyStem.GUI.element)
        // Five rats: 甲 day starts 甲子, 乙 day starts 丙子, 戊 day 23:00 is 壬子 from the next 己 day? (next day stem decides).
        assertEquals("甲子", ChineseZodiac.hourPillar(HeavenlyStem.JIA, 0).hanzi)
        assertEquals("丙子", ChineseZodiac.hourPillar(HeavenlyStem.YI, 0).hanzi)
        assertEquals("癸亥", ChineseZodiac.hourPillar(HeavenlyStem.WU, 22).hanzi)
    }

    @Test
    fun bruceLeePublishedChart() {
        // Widely published BaZi of Bruce Lee (27 Nov 1940, ~07:12, San Francisco): 庚辰 丁亥 甲戌 戊辰.
        val birth = BirthData(LocalDate.of(1940, 11, 27), LocalTime.of(7, 12), 37.7749, -122.4194, "America/Los_Angeles")
        val p = ChineseZodiac.compute(birth).pillars
        assertEquals(listOf("庚辰", "丁亥", "甲戌", "戊辰"), listOf(p.year.hanzi, p.month.hanzi, p.day.hanzi, p.hour!!.hanzi))
    }

    @Test
    fun conventionsAndOptions() {
        val late = BirthData(LocalDate.of(2023, 1, 21), LocalTime.of(23, 45), 31.23, 121.47, "Asia/Shanghai")
        val byDefault = ChineseZodiac.compute(late)
        val midnight = ChineseZodiac.compute(late, ChineseOptions(ziHour = ZiHourConvention.DAY_STARTS_AT_MIDNIGHT))
        assertEquals(ChineseZodiac.dayPillar(LocalDate.of(2023, 1, 22)), byDefault.pillars.day)
        assertEquals(ChineseZodiac.dayPillar(LocalDate.of(2023, 1, 21)), midnight.pillars.day)
        assertEquals(byDefault.pillars.hour, midnight.pillars.hour) // both take the next day's Zi stem

        // True solar time in western China moves the clock back ~2 h from Beijing time.
        val kashgar = BirthData(LocalDate.of(1990, 6, 1), LocalTime.of(9, 30), 39.47, 75.99, "Asia/Shanghai")
        val civil = ChineseZodiac.compute(kashgar).pillars.hour!!
        val solar = ChineseZodiac.compute(kashgar, ChineseOptions(timeBasis = SolarTimeBasis.TRUE_SOLAR)).pillars.hour!!
        assertEquals("巳", civil.branch.hanzi)   // 09:30 civil
        assertEquals("卯", solar.branch.hanzi)   // ~06:58 true solar
        val eot = ChineseZodiac.equationOfTimeMinutes(Instant.parse("1990-11-03T12:00:00Z"))
        assertEquals(16.4, eot, 0.3) // early November maximum

        val unknown = ChineseZodiac.compute(BirthData(LocalDate.of(1950, 2, 4), null, 31.23, 121.47, "Asia/Shanghai"))
        assertNull(unknown.pillars.hour)
        assertTrue(unknown.boundaryUncertain)
    }
}
