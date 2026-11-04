package com.metavirtuoso.conjunction.core.chinese

import com.metavirtuoso.conjunction.core.astro.Angles
import com.metavirtuoso.conjunction.core.astro.Angles.DEG
import com.metavirtuoso.conjunction.core.astro.Angles.RAD
import com.metavirtuoso.conjunction.core.astro.Ephemeris
import com.metavirtuoso.conjunction.core.astro.Planet
import com.metavirtuoso.conjunction.core.astro.TimeScales
import com.metavirtuoso.conjunction.core.chart.BirthData
import java.io.BufferedReader
import java.io.InputStreamReader
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/** Wu Xing. [key] matches art/elements/<key>.png. */
enum class FiveElement(val displayName: String, val hanzi: String) {
    WOOD("Wood", "木"), FIRE("Fire", "火"), EARTH("Earth", "土"), METAL("Metal", "金"), WATER("Water", "水");

    val key: String get() = name.lowercase()

    /** The element this one nourishes in the generating (sheng) cycle. */
    val generates: FiveElement get() = entries[(ordinal + 1) % 5]

    /** The element this one restrains in the controlling (ke) cycle. */
    val controls: FiveElement get() = entries[(ordinal + 2) % 5]
}

enum class YinYang(val displayName: String) { YANG("Yang"), YIN("Yin") }

/** Twelve animals. [key] matches art/chinese/<key>.png. */
enum class ChineseAnimal(val displayName: String) {
    RAT("Rat"), OX("Ox"), TIGER("Tiger"), RABBIT("Rabbit"), DRAGON("Dragon"), SNAKE("Snake"),
    HORSE("Horse"), GOAT("Goat"), MONKEY("Monkey"), ROOSTER("Rooster"), DOG("Dog"), PIG("Pig");

    val key: String get() = name.lowercase()

    companion object {
        fun fromKey(key: String): ChineseAnimal = entries.first { it.key == key.lowercase() }
    }
}

/** Ten Heavenly Stems, index 0 = 甲. */
enum class HeavenlyStem(val hanzi: String, val pinyin: String) {
    JIA("甲", "Jiǎ"), YI("乙", "Yǐ"), BING("丙", "Bǐng"), DING("丁", "Dīng"), WU("戊", "Wù"),
    JI("己", "Jǐ"), GENG("庚", "Gēng"), XIN("辛", "Xīn"), REN("壬", "Rén"), GUI("癸", "Guǐ");

    val element: FiveElement get() = FiveElement.entries[ordinal / 2]
    val yinYang: YinYang get() = if (ordinal % 2 == 0) YinYang.YANG else YinYang.YIN

    /** "Yang Wood" etc. */
    val label: String get() = "${yinYang.displayName} ${element.displayName}"
}

/** Twelve Earthly Branches, index 0 = 子 (Rat). */
enum class EarthlyBranch(val hanzi: String, val pinyin: String) {
    ZI("子", "Zǐ"), CHOU("丑", "Chǒu"), YIN("寅", "Yín"), MAO("卯", "Mǎo"), CHEN("辰", "Chén"), SI("巳", "Sì"),
    WU("午", "Wǔ"), WEI("未", "Wèi"), SHEN("申", "Shēn"), YOU("酉", "Yǒu"), XU("戌", "Xū"), HAI("亥", "Hài");

    val animal: ChineseAnimal get() = ChineseAnimal.entries[ordinal]

    /** Native element of the branch (water, earth, wood, wood, earth, fire, fire, earth, metal, metal, earth, water). */
    val element: FiveElement get() = BRANCH_ELEMENTS[ordinal]

    private companion object {
        val BRANCH_ELEMENTS = listOf(
            FiveElement.WATER, FiveElement.EARTH, FiveElement.WOOD, FiveElement.WOOD, FiveElement.EARTH, FiveElement.FIRE,
            FiveElement.FIRE, FiveElement.EARTH, FiveElement.METAL, FiveElement.METAL, FiveElement.EARTH, FiveElement.WATER,
        )
    }
}

/** One of the sixty stem-branch pairs. */
data class Pillar(val stem: HeavenlyStem, val branch: EarthlyBranch) {
    init {
        require(stem.ordinal % 2 == branch.ordinal % 2) { "Invalid stem-branch pair $stem/$branch" }
    }

    /** Position in the sexagenary cycle, 0 = 甲子 ... 59 = 癸亥. */
    val cycleIndex: Int get() = (6 * stem.ordinal - 5 * branch.ordinal).mod(60)

    val hanzi: String get() = stem.hanzi + branch.hanzi

    /** "甲子 Jiǎ-Zǐ (Yang Wood Rat)". */
    val label: String get() = "$hanzi ${stem.pinyin}-${branch.pinyin} (${stem.label} ${branch.animal.displayName})"

    companion object {
        fun ofCycleIndex(index: Int): Pillar {
            val i = index.mod(60)
            return Pillar(HeavenlyStem.entries[i % 10], EarthlyBranch.entries[i % 12])
        }
    }
}

data class FourPillars(val year: Pillar, val month: Pillar, val day: Pillar, val hour: Pillar?)

/** Which clock the hour (and day boundary) of the Four Pillars use. */
enum class SolarTimeBasis {
    /** Default: the local civil clock time as entered (what birth certificates record). */
    CIVIL,

    /** True (apparent) local solar time: civil UTC + longitude/15 h + equation of time. */
    TRUE_SOLAR,
}

/** How the 子 (Zi) hour 23:00-01:00 is split across days. */
enum class ZiHourConvention {
    /** Default (traditional): the new day - and its day pillar - begins at 23:00. */
    DAY_STARTS_AT_23,

    /** "Late Zi" school: the day pillar changes at midnight; 23:00-24:00 keeps the old day but takes the next day's Zi-hour stem. */
    DAY_STARTS_AT_MIDNIGHT,
}

data class ChineseOptions(
    val timeBasis: SolarTimeBasis = SolarTimeBasis.CIVIL,
    val ziHour: ZiHourConvention = ZiHourConvention.DAY_STARTS_AT_23,
)

data class ChineseChart(
    /** Animal of the solar (Lichun-bounded) year - the BaZi year. */
    val yearAnimal: ChineseAnimal,
    /** Element of the year stem. */
    val yearElement: FiveElement,
    val yinYang: YinYang,
    /** Animal of the lunar year (bounded by Chinese New Year) - the popular "what year are you". */
    val lunarYearAnimal: ChineseAnimal,
    val lunarYearElement: FiveElement,
    val pillars: FourPillars,
    /** The solar year (Gregorian year in which this BaZi year began at Lichun). */
    val solarYear: Int,
    /** The lunar year number (Gregorian year in which this lunar year began). */
    val lunarYear: Int,
    /** True when the birth time is unknown and Lichun or a month-term falls on the birth date. */
    val boundaryUncertain: Boolean,
) {
    /** The "Day Master": the day stem, the self in BaZi. */
    val dayMaster: HeavenlyStem get() = pillars.day.stem
    val yearsDiffer: Boolean get() = yearAnimal != lunarYearAnimal
}

/**
 * Chinese zodiac and Four Pillars (BaZi).
 *
 * - Year pillar: sexagenary year beginning at Lichun, the instant the apparent Sun reaches 315 deg
 *   (computed with our ephemeris, so it is exact to seconds; compared as an instant, which makes it
 *   independent of time zones). Stem = (Y - 4) mod 10, branch = (Y - 4) mod 12; 1984 = 甲子.
 * - Lunar-year animal: from the bundled Chinese New Year table 1900-2100 (Hong Kong Observatory),
 *   compared with the local civil birth date; outside the table it falls back to the solar year.
 * - Month pillar: the twelve "jie" terms (Sun at 315, 345, 15, ... deg) start the months 寅, 卯 ...;
 *   stem by the "five tigers" rule from the year stem.
 * - Day pillar: continuous sexagenary day count, index = (JDN - 11) mod 60 (anchor: 1949-10-01 = 甲子,
 *   1900-01-31 = 甲辰), on the local date (see [ZiHourConvention]).
 * - Hour pillar: branch from the two-hour 時辰 (23-01 子, 01-03 丑, ...), stem by the "five rats" rule
 *   from the day stem. Not available when the birth time is unknown.
 */
object ChineseZodiac {
    private val lichunCache = ConcurrentHashMap<Int, Instant>()

    fun compute(birth: BirthData, options: ChineseOptions = ChineseOptions()): ChineseChart {
        val instant = birth.instant
        val localDate = birth.date

        // Year (solar, Lichun-bounded).
        var solarYear = localDate.year
        if (instant < lichun(solarYear)) solarYear -= 1
        val yearPillar = yearPillar(solarYear)

        // Month from the Sun's apparent longitude.
        val sunLon = Ephemeris.position(Planet.SUN, instant).longitude
        val monthPillar = monthPillar(yearPillar.stem, sunLon)

        // Day and hour.
        val dayPillar: Pillar
        val hourPillar: Pillar?
        if (birth.time == null) {
            dayPillar = dayPillar(localDate)
            hourPillar = null
        } else {
            val clock = when (options.timeBasis) {
                SolarTimeBasis.CIVIL -> birth.zonedDateTime.toLocalDateTime()
                SolarTimeBasis.TRUE_SOLAR -> trueSolarTime(instant, birth.longitude)
            }
            val lateZi = clock.hour == 23
            val dayDate = if (lateZi && options.ziHour == ZiHourConvention.DAY_STARTS_AT_23) clock.toLocalDate().plusDays(1) else clock.toLocalDate()
            dayPillar = dayPillar(dayDate)
            // In the late-Zi convention the 23:00 hour still takes the NEXT day's Zi stem.
            val stemDay = if (lateZi) dayPillar(clock.toLocalDate().plusDays(1)) else dayPillar
            hourPillar = hourPillar(stemDay.stem, clock.hour)
        }

        // Lunar year from the new-year table.
        val lunarYear = lunarYearOf(localDate)
        val lunarPillar = yearPillar(lunarYear)

        val uncertain = birth.time == null && run {
            val start = localDate.atStartOfDay(birth.zone).toInstant()
            val end = localDate.plusDays(1).atStartOfDay(birth.zone).toInstant()
            val a = Ephemeris.position(Planet.SUN, start).longitude
            val b = Ephemeris.position(Planet.SUN, end).longitude
            floor(Angles.norm360(a - 315.0) / 30.0) != floor(Angles.norm360(b - 315.0) / 30.0)
        }

        return ChineseChart(
            yearAnimal = yearPillar.branch.animal,
            yearElement = yearPillar.stem.element,
            yinYang = yearPillar.stem.yinYang,
            lunarYearAnimal = lunarPillar.branch.animal,
            lunarYearElement = lunarPillar.stem.element,
            pillars = FourPillars(yearPillar, monthPillar, dayPillar, hourPillar),
            solarYear = solarYear,
            lunarYear = lunarYear,
            boundaryUncertain = uncertain,
        )
    }

    fun yearPillar(year: Int): Pillar = Pillar.ofCycleIndex(year - 4)

    /** Month pillar for a solar-year stem and the Sun's apparent longitude. */
    fun monthPillar(yearStem: HeavenlyStem, sunLongitude: Double): Pillar {
        val k = floor(Angles.norm360(sunLongitude - 315.0) / 30.0).toInt().coerceIn(0, 11)
        val branch = EarthlyBranch.entries[(2 + k) % 12]
        val stem = HeavenlyStem.entries[((yearStem.ordinal % 5) * 2 + 2 + k) % 10]
        return Pillar(stem, branch)
    }

    /** Day pillar of a civil date: index = (JDN - 11) mod 60 = (epochDay + 17) mod 60. */
    fun dayPillar(date: LocalDate): Pillar = Pillar.ofCycleIndex(Math.floorMod(date.toEpochDay() + 17L, 60L).toInt())

    /** Hour pillar from the day stem and the clock hour 0..23. */
    fun hourPillar(dayStem: HeavenlyStem, hour: Int): Pillar {
        require(hour in 0..23) { "hour out of range: $hour" }
        val branchIndex = ((hour + 1) / 2) % 12
        val stem = HeavenlyStem.entries[((dayStem.ordinal % 5) * 2 + branchIndex) % 10]
        return Pillar(stem, EarthlyBranch.entries[branchIndex])
    }

    /** The Gregorian year whose lunar new year most recently began on or before [date]. */
    fun lunarYearOf(date: LocalDate): Int {
        val table = LunarNewYear.dates
        val y = date.year
        val thisYear = table[y] ?: return if (lichunDateFallback(date)) y else y - 1
        return if (date < thisYear) y - 1 else y
    }

    private fun lichunDateFallback(date: LocalDate): Boolean = date >= LocalDate.of(date.year, 2, 4)

    /** Instant the apparent Sun reaches 315 deg (Lichun) in a Gregorian year. */
    fun lichun(year: Int): Instant = lichunCache.getOrPut(year) { solarTermInstant(year, 315.0) }

    /**
     * Instant the apparent Sun reaches [longitude] (deg) during Gregorian [year]. For 285 and 300
     * (Xiaohan, Dahan) that is in January; others follow in order through the year.
     */
    fun solarTermInstant(year: Int, longitude: Double): Instant {
        // The Sun is at ~280 deg on Jan 1; days after Jan 1 ~ (lon - 280) mod 360 / 0.9856.
        val guessDays = Angles.norm360(longitude - 280.0) / 0.98564736
        var t = LocalDate.of(year, 1, 1).atStartOfDay().toInstant(ZoneOffset.UTC)
            .plusSeconds((guessDays * 86_400.0).toLong())
        repeat(8) {
            val p = Ephemeris.position(Planet.SUN, t)
            val diff = Angles.norm180(longitude - p.longitude)
            if (kotlin.math.abs(diff) < 1e-7) return t
            t = t.plusMillis((diff / p.speed * 86_400_000.0).toLong())
        }
        return t
    }

    /** True local solar time (date and time) at an east-positive longitude. */
    fun trueSolarTime(instant: Instant, longitude: Double): LocalDateTime {
        val offsetSeconds = longitude * 240.0 + equationOfTimeMinutes(instant) * 60.0
        return LocalDateTime.ofInstant(instant.plus(Duration.ofMillis((offsetSeconds * 1000.0).toLong())), ZoneOffset.UTC)
    }

    /** Equation of time (apparent - mean solar time), minutes (Meeus 28.3). */
    fun equationOfTimeMinutes(instant: Instant): Double {
        val jde = TimeScales.julianDayTt(instant)
        val tau = (jde - TimeScales.J2000) / 365_250.0
        val l0 = Angles.norm360(280.4664567 + 360_007.6982779 * tau + 0.03032028 * tau * tau +
            tau * tau * tau / 49_931.0 - tau * tau * tau * tau / 15_300.0 - Math.pow(tau, 5.0) / 2_000_000.0)
        val nut = Ephemeris.nutation(jde)
        val lambda = Ephemeris.position(Planet.SUN, instant).longitude * DEG
        val eps = nut.trueObliquity * DEG
        val alpha = Angles.norm360(atan2(cos(eps) * sin(lambda), cos(lambda)) * RAD)
        val e = Angles.norm180(l0 - 0.0057183 - alpha + nut.deltaPsi * cos(eps))
        return e * 4.0
    }
}

/** Chinese New Year dates 1900-2100 (China time), bundled as chinese/chinese_new_year.txt. */
object LunarNewYear {
    val dates: Map<Int, LocalDate> by lazy {
        val stream = LunarNewYear::class.java.getResourceAsStream("chinese_new_year.txt")
            ?: error("Missing bundled resource chinese_new_year.txt")
        BufferedReader(InputStreamReader(stream, Charsets.US_ASCII)).useLines { lines ->
            lines.map(String::trim).filter { it.isNotEmpty() && !it.startsWith("#") }
                .associate { line -> line.split(' ').let { it[0].toInt() to LocalDate.parse(it[1]) } }
        }
    }

    val firstYear: Int get() = dates.keys.min()
    val lastYear: Int get() = dates.keys.max()
}
