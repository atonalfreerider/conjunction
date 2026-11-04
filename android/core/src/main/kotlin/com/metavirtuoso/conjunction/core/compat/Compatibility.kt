package com.metavirtuoso.conjunction.core.compat

import com.metavirtuoso.conjunction.core.astro.Planet
import com.metavirtuoso.conjunction.core.blood.BloodType
import com.metavirtuoso.conjunction.core.chart.Element
import com.metavirtuoso.conjunction.core.chart.Modality
import com.metavirtuoso.conjunction.core.chart.NatalChart
import com.metavirtuoso.conjunction.core.chart.ZodiacSign
import com.metavirtuoso.conjunction.core.chinese.ChineseAnimal
import com.metavirtuoso.conjunction.core.chinese.ChineseChart
import com.metavirtuoso.conjunction.core.chinese.HeavenlyStem
import java.time.LocalDate
import kotlin.math.floor

/**
 * The summary a phone uploads while Nearby is on (never raw birth time or place).
 * Longitudes are degrees [0, 360) rounded to 0.01 deg on creation.
 */
data class CompatProfile(
    val sun: Double,
    val moon: Double,
    val venus: Double,
    val mars: Double,
    /** Null when the birth time is unknown. */
    val ascendant: Double?,
    val chineseAnimal: ChineseAnimal,
    val dayStem: HeavenlyStem,
    val bloodType: BloodType?,
    val birthDate: LocalDate,
    /** Local clock hour of birth 0..23, null when unknown. */
    val birthHour: Int?,
) {
    init {
        for (v in listOfNotNull(sun, moon, venus, mars, ascendant)) require(v in 0.0..360.0) { "longitude out of range: $v" }
        birthHour?.let { require(it in 0..23) { "birthHour out of range: $it" } }
    }

    companion object {
        /** Builds the uploaded summary from on-device charts. */
        fun from(chart: NatalChart, chinese: ChineseChart, bloodType: BloodType?): CompatProfile = CompatProfile(
            sun = round2(chart.body(Planet.SUN).longitude),
            moon = round2(chart.body(Planet.MOON).longitude),
            venus = round2(chart.body(Planet.VENUS).longitude),
            mars = round2(chart.body(Planet.MARS).longitude),
            ascendant = chart.ascendant?.let(::round2),
            chineseAnimal = chinese.yearAnimal,
            dayStem = chinese.dayMaster,
            bloodType = bloodType,
            birthDate = chart.birth.date,
            birthHour = chart.birth.time?.hour,
        )

        /** Rounds to 0.01 deg, wrapping 360.00 to 0.00. */
        fun round2(lon: Double): Double {
            val c = Compatibility.centidegrees(lon)
            return c / 100.0
        }
    }
}

/** Branch relation between two Chinese year animals (docs/COMPATIBILITY.md section 3). */
enum class ChineseRelation(val score: Int) {
    SAME(70), SAME_SELF_PUNISHMENT(60), TRINE(95), HARMONY(90), CLASH(25), HARM(40), NEUTRAL(60)
}

/** Day-master (day stem) relation. */
enum class StemRelation(val score: Int) {
    COMBINATION(95), SAME_STEM(70), SAME_ELEMENT(75), GENERATING(80), CONTROLLING(45)
}

data class CompatResult(
    /** Overall 0..100. */
    val total: Int,
    val western: Int,
    val chinese: Int,
    /** Null when either blood type is unknown. */
    val blood: Int?,
    /** Up to four short, deterministic English highlights (identical on every platform). */
    val highlights: List<String>,
    val isBirthdayTwin: Boolean,
    val isExactTwin: Boolean,
    /** Same month and day, different year (a lighter "birthday buddies" note). */
    val sharesBirthday: Boolean,
    val chineseRelation: ChineseRelation,
    val stemRelation: StemRelation,
)

/**
 * Deterministic compatibility score. Integer arithmetic throughout after quantising longitudes
 * to centidegrees, so the Kotlin, Swift and TypeScript implementations agree exactly. The full
 * algorithm (tables, weights, rounding) is specified in docs/COMPATIBILITY.md; keep them in sync.
 */
object Compatibility {
    /** Chart points used in synastry, in canonical order (used for labels and tie-breaks). */
    enum class Point(val label: String) { SUN("Sun"), MOON("Moon"), VENUS("Venus"), MARS("Mars"), ASC("Ascendant") }

    /** (point of A, point of B, weight). Symmetric: every cross pair appears in both directions. */
    val PAIRS: List<Triple<Point, Point, Int>> = listOf(
        Triple(Point.SUN, Point.MOON, 5), Triple(Point.MOON, Point.SUN, 5),
        Triple(Point.VENUS, Point.MARS, 4), Triple(Point.MARS, Point.VENUS, 4),
        Triple(Point.MOON, Point.MOON, 4),
        Triple(Point.SUN, Point.SUN, 3),
        Triple(Point.VENUS, Point.VENUS, 2),
        Triple(Point.SUN, Point.VENUS, 2), Triple(Point.VENUS, Point.SUN, 2),
        Triple(Point.MOON, Point.VENUS, 2), Triple(Point.VENUS, Point.MOON, 2),
        Triple(Point.MARS, Point.MARS, 1),
        Triple(Point.ASC, Point.SUN, 2), Triple(Point.SUN, Point.ASC, 2),
        Triple(Point.ASC, Point.MOON, 2), Triple(Point.MOON, Point.ASC, 2),
        Triple(Point.ASC, Point.ASC, 1),
    )

    /** Aspect: exact angle and orb in centidegrees, value in permille. Order = tie-break order. */
    data class SynastryAspect(val name: String, val angle: Int, val orb: Int, val value: Int)

    val ASPECTS = listOf(
        SynastryAspect("conjunction", 0, 800, 600),
        SynastryAspect("sextile", 6000, 500, 400),
        SynastryAspect("square", 9000, 700, -400),
        SynastryAspect("trine", 12000, 700, 500),
        SynastryAspect("opposition", 18000, 800, 200),
    )

    /** ELEMENT[a][b] in permille, order FIRE, EARTH, AIR, WATER. */
    val ELEMENT: Array<IntArray> = arrayOf(
        intArrayOf(300, -50, 250, -150),
        intArrayOf(-50, 300, -150, 250),
        intArrayOf(250, -150, 300, -50),
        intArrayOf(-150, 250, -50, 300),
    )

    /** MODALITY: same modality -50, different +100 (permille). */
    fun modality(a: Modality, b: Modality): Int = if (a == b) -50 else 100

    /** BLOOD[a][b], order A, B, AB, O. */
    val BLOOD: Array<IntArray> = arrayOf(
        intArrayOf(80, 50, 70, 85),
        intArrayOf(50, 80, 85, 75),
        intArrayOf(70, 85, 75, 60),
        intArrayOf(85, 75, 60, 80),
    )

    const val WEIGHT_WESTERN = 55
    const val WEIGHT_CHINESE = 30
    const val WEIGHT_BLOOD = 15

    /** Mapping from the permille synastry average to 0..100: western = roundDiv(raw + OFFSET, DIVISOR), clamped. */
    const val WESTERN_OFFSET = 145
    const val WESTERN_DIVISOR = 5

    /** floor(lon * 100 + 0.5) mod 36000 - the only floating-point step. */
    fun centidegrees(lon: Double): Int = Math.floorMod(floor(lon * 100.0 + 0.5).toLong(), 36_000L).toInt()

    /** Round half up for integer n / d (d > 0): floorDiv(2n + d, 2d). */
    fun roundDiv(n: Int, d: Int): Int = Math.floorDiv(2 * n + d, 2 * d)

    fun isBirthdayTwin(a: CompatProfile, b: CompatProfile): Boolean = a.birthDate == b.birthDate

    fun isExactTwin(a: CompatProfile, b: CompatProfile): Boolean =
        isBirthdayTwin(a, b) && a.birthHour != null && a.birthHour == b.birthHour

    fun sharesBirthday(a: CompatProfile, b: CompatProfile): Boolean =
        !isBirthdayTwin(a, b) && a.birthDate.monthValue == b.birthDate.monthValue && a.birthDate.dayOfMonth == b.birthDate.dayOfMonth

    private fun lon(p: CompatProfile, point: Point): Double? = when (point) {
        Point.SUN -> p.sun
        Point.MOON -> p.moon
        Point.VENUS -> p.venus
        Point.MARS -> p.mars
        Point.ASC -> p.ascendant
    }

    /** One synastry pair: sign part + aspect part (permille) and the in-orb aspect, if any. */
    data class PairScore(val score: Int, val aspect: SynastryAspect?, val aspectPart: Int)

    fun pairScore(lonA: Int, lonB: Int): PairScore {
        val signA = ZodiacSign.entries[lonA / 3000]
        val signB = ZodiacSign.entries[lonB / 3000]
        val signPart = ELEMENT[signA.element.ordinal][signB.element.ordinal] + modality(signA.modality, signB.modality)
        val diff = Math.abs(lonA - lonB)
        val sep = if (diff > 18_000) 36_000 - diff else diff
        var best: SynastryAspect? = null
        var bestDev = Int.MAX_VALUE
        for (asp in ASPECTS) {
            val dev = Math.abs(sep - asp.angle)
            if (dev <= asp.orb && dev < bestDev) { best = asp; bestDev = dev }
        }
        // Integer division truncates toward zero in Kotlin, Swift and JS (Math.trunc).
        val aspectPart = best?.let { it.value * (it.orb - bestDev) / it.orb } ?: 0
        val score = (signPart + aspectPart).coerceIn(-1000, 1000)
        return PairScore(score, best, aspectPart)
    }

    /**
     * One cross-aspect of the synastry bi-wheel (SPEC 5d.4): [pointA] of the first profile ("me",
     * inner ring), [pointB] of the second ("them", outer ring), the in-orb [aspect], its deviation
     * from exact in centidegrees ([orbCentideg], 0..orb), the PAIRS [weight] and the [pairScore] score
     * (permille, -1000..1000). Gold lines: conjunction/sextile/trine; rose-copper: square/opposition.
     */
    data class SynastryLine(
        val pointA: Point,
        val pointB: Point,
        val aspect: SynastryAspect,
        val orbCentideg: Int,
        val weight: Int,
        val score: Int,
    ) {
        val isHarmonious: Boolean get() = aspect.name == "conjunction" || aspect.name == "sextile" || aspect.name == "trine"
    }

    /**
     * Every [PAIRS] pair with both longitudes present and an in-orb aspect, ordered by |score x weight|
     * descending, then PAIRS order (golden `synastry` in testdata/golden_compat.json).
     */
    fun synastry(a: CompatProfile, b: CompatProfile): List<SynastryLine> {
        val lines = ArrayList<Pair<Int, SynastryLine>>()
        for ((i, pair) in PAIRS.withIndex()) {
            val (pa, pb, w) = pair
            val la = centidegrees(lon(a, pa) ?: continue)
            val lb = centidegrees(lon(b, pb) ?: continue)
            val ps = pairScore(la, lb)
            val asp = ps.aspect ?: continue
            val diff = Math.abs(la - lb)
            val sep = if (diff > 18_000) 36_000 - diff else diff
            lines += i to SynastryLine(pa, pb, asp, Math.abs(sep - asp.angle), w, ps.score)
        }
        return lines.sortedWith(compareByDescending<Pair<Int, SynastryLine>> { Math.abs(it.second.score * it.second.weight) }.thenBy { it.first })
            .map { it.second }
    }

    fun chineseRelation(a: ChineseAnimal, b: ChineseAnimal): ChineseRelation {
        val x = a.ordinal
        val y = b.ordinal
        val d = Math.abs(x - y)
        return when {
            x == y -> if (x in setOf(4, 6, 9, 11)) ChineseRelation.SAME_SELF_PUNISHMENT else ChineseRelation.SAME
            d % 4 == 0 -> ChineseRelation.TRINE
            (x + y) % 12 == 1 -> ChineseRelation.HARMONY
            d == 6 -> ChineseRelation.CLASH
            (x + y) % 12 == 7 -> ChineseRelation.HARM
            else -> ChineseRelation.NEUTRAL
        }
    }

    fun stemRelation(a: HeavenlyStem, b: HeavenlyStem): StemRelation {
        val ea = a.element
        val eb = b.element
        return when {
            Math.abs(a.ordinal - b.ordinal) == 5 -> StemRelation.COMBINATION
            a == b -> StemRelation.SAME_STEM
            ea == eb -> StemRelation.SAME_ELEMENT
            ea.generates == eb || eb.generates == ea -> StemRelation.GENERATING
            else -> StemRelation.CONTROLLING
        }
    }

    fun bloodScore(a: BloodType?, b: BloodType?): Int? =
        if (a == null || b == null) null else BLOOD[a.ordinal][b.ordinal]

    fun score(a: CompatProfile, b: CompatProfile): CompatResult {
        // 1. Western synastry.
        var weighted = 0
        var weights = 0
        data class Contribution(val label: String, val value: Int)
        val contributions = mutableListOf<Contribution>()
        for ((pa, pb, w) in PAIRS) {
            val la = lon(a, pa) ?: continue
            val lb = lon(b, pb) ?: continue
            val ps = pairScore(centidegrees(la), centidegrees(lb))
            weighted += w * ps.score
            weights += w
            if (ps.aspect != null && ps.aspectPart > 0) {
                val (first, second) = if (pa.ordinal <= pb.ordinal) pa to pb else pb to pa
                val label = "${first.label}–${second.label} ${ps.aspect.name}"
                contributions += Contribution(label, w * ps.aspectPart)
            }
        }
        val raw = Math.floorDiv(weighted, weights)
        val western = roundDiv(raw + WESTERN_OFFSET, WESTERN_DIVISOR).coerceIn(0, 100)

        // 2. Chinese: year-branch relation (3/5) and day-master relation (2/5).
        val cRel = chineseRelation(a.chineseAnimal, b.chineseAnimal)
        val sRel = stemRelation(a.dayStem, b.dayStem)
        val chinese = roundDiv(3 * cRel.score + 2 * sRel.score, 5)

        // 3. Blood type.
        val blood = bloodScore(a.bloodType, b.bloodType)

        // 4. Blend.
        val total = if (blood != null)
            roundDiv(WEIGHT_WESTERN * western + WEIGHT_CHINESE * chinese + WEIGHT_BLOOD * blood, 100)
        else
            roundDiv(WEIGHT_WESTERN * western + WEIGHT_CHINESE * chinese, WEIGHT_WESTERN + WEIGHT_CHINESE)

        // 5. Highlights.
        val twin = isBirthdayTwin(a, b)
        val exact = isExactTwin(a, b)
        val shares = sharesBirthday(a, b)
        val highlights = mutableListOf<String>()
        when {
            exact -> highlights += "Exact twins: same day, same hour"
            twin -> highlights += "Birthday twins"
            shares -> highlights += "Same birthday"
        }
        contributions
            .sortedWith(compareByDescending<Contribution> { it.value }.thenBy { it.label })
            .map { it.label }
            .distinct()
            .take(2)
            .forEach { highlights += it }
        chineseHighlight(cRel, a.chineseAnimal, b.chineseAnimal)?.let { highlights += it }
        if (sRel == StemRelation.COMBINATION) highlights += "Day Masters combine (${minStem(a.dayStem, b.dayStem)})"
        if (blood != null && blood >= 80) {
            val (x, y) = listOf(a.bloodType!!, b.bloodType!!).sortedBy { it.ordinal }
            highlights += "Blood types ${x.displayName} + ${y.displayName}"
        }
        return CompatResult(
            total = total.coerceIn(0, 100),
            western = western,
            chinese = chinese,
            blood = blood,
            highlights = highlights.take(4),
            isBirthdayTwin = twin,
            isExactTwin = exact,
            sharesBirthday = shares,
            chineseRelation = cRel,
            stemRelation = sRel,
        )
    }

    private fun minStem(a: HeavenlyStem, b: HeavenlyStem): String {
        val (x, y) = if (a.ordinal <= b.ordinal) a to b else b to a
        return "${x.hanzi}${y.hanzi}"
    }

    private fun chineseHighlight(rel: ChineseRelation, a: ChineseAnimal, b: ChineseAnimal): String? {
        val (x, y) = if (a.ordinal <= b.ordinal) a to b else b to a
        return when (rel) {
            ChineseRelation.TRINE -> "${x.displayName} & ${y.displayName}: San He trine"
            ChineseRelation.HARMONY -> "${x.displayName} & ${y.displayName}: Liu He secret friends"
            ChineseRelation.CLASH -> "${x.displayName} & ${y.displayName}: opposites attract"
            ChineseRelation.SAME, ChineseRelation.SAME_SELF_PUNISHMENT -> "Both ${x.displayName}s"
            ChineseRelation.HARM, ChineseRelation.NEUTRAL -> null
        }
    }

    init {
        // ELEMENT is indexed by Element.ordinal; guard the order the tables assume.
        check(Element.entries.map { it.name } == listOf("FIRE", "EARTH", "AIR", "WATER"))
        check(BloodType.entries.map { it.name } == listOf("A", "B", "AB", "O"))
    }
}
