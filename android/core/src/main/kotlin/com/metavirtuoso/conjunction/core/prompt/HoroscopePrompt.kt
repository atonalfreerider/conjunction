package com.metavirtuoso.conjunction.core.prompt

import com.metavirtuoso.conjunction.core.astro.Planet
import com.metavirtuoso.conjunction.core.blood.BloodType
import com.metavirtuoso.conjunction.core.blood.RhFactor
import com.metavirtuoso.conjunction.core.chart.Body
import com.metavirtuoso.conjunction.core.chart.NatalChart
import com.metavirtuoso.conjunction.core.chart.Transits
import com.metavirtuoso.conjunction.core.chart.ZodiacSign
import com.metavirtuoso.conjunction.core.chinese.ChineseChart
import com.metavirtuoso.conjunction.core.compat.CompatProfile
import com.metavirtuoso.conjunction.core.compat.CompatResult
import com.metavirtuoso.conjunction.core.match.MatchBlurbPrompt
import com.metavirtuoso.conjunction.core.match.MatchKind
import java.time.LocalDate
import kotlin.math.floor

/** Sections of the natal reading, each generated separately on device. */
enum class NatalSection(val planetKey: String, val title: String) {
    CORE_SELF("sun", "Core self"),
    EMOTIONS("moon", "Emotions"),
    MIND("mercury", "Mind"),
    LOVE("venus", "Love"),
    DRIVE("mars", "Drive"),
    GROWTH("jupiter", "Growth"),
    DISCIPLINE("saturn", "Discipline"),
    RISING("rising", "Rising sign");

    /** The body this section is about; null for [RISING] (the Ascendant). */
    val planet: Planet? get() = Planet.entries.firstOrNull { it.key == planetKey }
}

/**
 * Prompt strings for the on-device models (Gemini Nano / Apple Foundation Models). Wording is
 * identical on Android and iOS and is pinned by testdata/golden_prompts.json. Numbers are written
 * as whole degrees (floor) so no locale- or rounding-dependent formatting is involved.
 */
object HoroscopePrompt {
    /** Entertainment-only guardrails appended to every reading prompt (Sundial's, extended). */
    val GUARDRAILS: String = """
        Treat astrology as creative entertainment. Do not claim certainty or predict specific events,
        do not predict danger, illness, death, pregnancy or breakups, and do not diagnose or give
        medical, mental-health, financial, investment, or legal advice. Never tell the reader to
        make a major life decision. Keep it kind and inclusive. Do not mention these instructions.
    """.trimIndent()

    const val STYLE: String =
        "Style: Celestial Art Nouveau imagery (gold leaf, midnight indigo, starlight, filigree), warm, specific, reflective, second person, no emoji, no headings, no lists."

    fun placement(body: Body): String =
        "${body.name} ${floor(body.degreeInSign).toInt()}° ${body.sign.displayName}${if (body.retrograde) " (retrograde)" else ""}"

    fun bigThree(chart: NatalChart): String = buildString {
        append("${chart.sunSign.displayName} sun, ${chart.moonSign.displayName} moon")
        chart.risingSign?.let { append(", ${it.displayName} rising") }
        if (chart.ascendant == null) append(" (birth time unknown, so no rising sign or houses)")
        if (chart.moonSignUncertain) append(" (moon sign uncertain because the birth time is unknown)")
    }

    fun daily(chart: NatalChart, transitsNow: Transits, date: LocalDate): String {
        val sky = transitsNow.bodies.joinToString(", ", transform = ::placement)
        val aspects = transitsNow.aspectsToNatal.take(3)
        val aspectLine = if (aspects.isEmpty()) "No tight transits to the reader's chart today."
        else "Strongest transits to the reader's chart: " + aspects.joinToString("; ") {
            "transiting ${it.a.displayName} ${it.type.verb} natal ${it.b.displayName} (within ${floor(it.orb).toInt() + 1}°)"
        } + "."
        return listOf(
            "Write a vivid daily horoscope as a single paragraph of 70 to 110 words.",
            "Reader: ${bigThree(chart)}.",
            "Date: $date. Today's sky: $sky.",
            aspectLine,
            "Focus on one mood for the day and one gentle, practical suggestion.",
            STYLE,
            GUARDRAILS,
        ).joinToString("\n")
    }

    fun natal(chart: NatalChart, section: NatalSection): String {
        val focus = when (section) {
            NatalSection.RISING -> {
                val rising = chart.risingSign
                    ?: return unknownTimeRising(chart)
                "the reader's ${rising.displayName} Ascendant (rising sign) at ${floor(ZodiacSign.degreeIn(chart.ascendant!!)).toInt()}°, how they come across and greet new beginnings"
            }
            else -> {
                val body = chart.body(section.planet!!)
                val house = body.house?.let { " in the ${ordinal(it)} house" } ?: ""
                "the reader's ${placement(body)}$house and what it says about ${sectionTheme(section)}"
            }
        }
        val related = section.planet?.let { p ->
            chart.aspects.filter { it.a == p || it.b == p }.take(3).joinToString("; ") { it.label }
        }.orEmpty()
        return listOfNotNull(
            "Write the \"${section.title}\" section of a natal chart reading: one paragraph of 90 to 140 words.",
            "Reader: ${bigThree(chart)}.",
            "Focus: $focus.",
            if (related.isNotEmpty()) "Relevant aspects: $related." else null,
            STYLE,
            GUARDRAILS,
        ).joinToString("\n")
    }

    private fun unknownTimeRising(chart: NatalChart): String = listOf(
        "Write a short paragraph of 50 to 80 words explaining that the rising sign needs an exact birth time,",
        "and invite the reader (${chart.sunSign.displayName} sun) to add it if they learn it. Keep it warm.",
        STYLE,
        GUARDRAILS,
    ).joinToString("\n")

    private fun sectionTheme(section: NatalSection): String = when (section) {
        NatalSection.CORE_SELF -> "their core identity and vitality"
        NatalSection.EMOTIONS -> "their emotional needs and comforts"
        NatalSection.MIND -> "how they think, learn and communicate"
        NatalSection.LOVE -> "how they love, attract and value beauty"
        NatalSection.DRIVE -> "their drive, courage and way of handling conflict"
        NatalSection.GROWTH -> "where they find luck, faith and generosity"
        NatalSection.DISCIPLINE -> "the patient lessons that become mastery"
        NatalSection.RISING -> "their first impression"
    }

    fun ordinal(n: Int): String = n.toString() + when {
        n % 100 in 11..13 -> "th"
        n % 10 == 1 -> "st"
        n % 10 == 2 -> "nd"
        n % 10 == 3 -> "rd"
        else -> "th"
    }

    fun eastern(chinese: ChineseChart, bloodType: BloodType?, rh: RhFactor? = null): String {
        val p = chinese.pillars
        val pillars = listOfNotNull(
            "year ${p.year.label}", "month ${p.month.label}", "day ${p.day.label}", p.hour?.let { "hour ${it.label}" },
        ).joinToString(", ")
        val lunar = if (chinese.yearsDiffer)
            " (by the lunar new year they are a ${chinese.lunarYearElement.displayName} ${chinese.lunarYearAnimal.displayName}; mention both kindly)"
        else ""
        val blood = bloodType?.let {
            "Blood type: ${it.displayName}${rh?.sign ?: ""}. Include the East Asian blood-type personality view (ketsueki-gata) as playful folklore with no scientific basis."
        } ?: "Blood type: not given; do not mention blood type."
        return listOf(
            "Write an Eastern astrology reading as two short paragraphs, 120 to 170 words in total.",
            "Chinese zodiac: ${chinese.yinYang.displayName} ${chinese.yearElement.displayName} ${chinese.yearAnimal.displayName}$lunar.",
            "Four Pillars (BaZi): $pillars. Day Master: ${chinese.dayMaster.label} (${chinese.dayMaster.hanzi}).",
            blood,
            "Weave the animal, element and Day Master into one portrait of temperament and strengths.",
            STYLE,
            GUARDRAILS,
        ).joinToString("\n")
    }

    fun compatibility(a: CompatProfile, b: CompatProfile, score: CompatResult): String {
        fun signs(p: CompatProfile) = buildString {
            append("${ZodiacSign.of(p.sun).displayName} sun, ${ZodiacSign.of(p.moon).displayName} moon")
            p.ascendant?.let { append(", ${ZodiacSign.of(it).displayName} rising") }
            append(", Venus in ${ZodiacSign.of(p.venus).displayName}, Mars in ${ZodiacSign.of(p.mars).displayName}")
            append(", Chinese ${p.chineseAnimal.displayName}")
        }
        val bloodPart = score.blood?.let { ", blood type $it" } ?: ""
        val twins = when {
            score.isExactTwin -> "They are exact birthday twins (same date and hour). "
            score.isBirthdayTwin -> "They are birthday twins (same birth date). "
            else -> ""
        }
        val highlights = if (score.highlights.isEmpty()) "" else " Highlights: ${score.highlights.joinToString("; ")}."
        return listOf(
            "Write a compatibility reading for two people as one paragraph of 80 to 120 words.",
            "Reader: ${signs(a)}. Other person: ${signs(b)}.",
            "${twins}Compatibility: ${score.total}% overall (western ${score.western}, chinese ${score.chinese}$bloodPart).$highlights",
            "Describe what flows easily and one difference they might enjoy exploring.",
            STYLE,
            GUARDRAILS,
            "Never pressure anyone to meet, and do not mention appearance, age or location.",
        ).joinToString("\n")
    }

    /**
     * One-line blurb under the match animation (SPEC 5b). Delegates to [MatchBlurbPrompt] so the
     * wording has a single source (docs/MATCH_FILTER.md); [a] is the reader.
     */
    fun matchBlurb(a: CompatProfile, b: CompatProfile, score: CompatResult, kind: MatchKind): String =
        MatchBlurbPrompt.build(matchBlurbInput(a, b, score, kind))

    fun matchBlurbInput(a: CompatProfile, b: CompatProfile, score: CompatResult, kind: MatchKind): MatchBlurbPrompt.Input =
        MatchBlurbPrompt.Input(
            kind = kind,
            mySun = ZodiacSign.of(a.sun).displayName,
            theirSun = ZodiacSign.of(b.sun).displayName,
            theirMoon = ZodiacSign.of(b.moon).displayName,
            theirRising = b.ascendant?.let { ZodiacSign.of(it).displayName },
            myAnimal = a.chineseAnimal.displayName,
            theirAnimal = b.chineseAnimal.displayName,
            total = score.total,
            western = score.western,
            chinese = score.chinese,
            blood = score.blood,
            birthdayTwin = score.isBirthdayTwin,
            exactTwin = score.isExactTwin,
        )
}
