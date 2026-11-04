package com.metavirtuoso.conjunction.core

import com.metavirtuoso.conjunction.core.blood.BloodType
import com.metavirtuoso.conjunction.core.chart.BirthData
import com.metavirtuoso.conjunction.core.chart.NatalChart
import com.metavirtuoso.conjunction.core.chart.Transits
import com.metavirtuoso.conjunction.core.chinese.ChineseZodiac
import com.metavirtuoso.conjunction.core.compat.CompatProfile
import com.metavirtuoso.conjunction.core.compat.Compatibility
import com.metavirtuoso.conjunction.core.fallback.FallbackReadings
import com.metavirtuoso.conjunction.core.match.MatchKind
import com.metavirtuoso.conjunction.core.palm.FingerRatios
import com.metavirtuoso.conjunction.core.palm.Hand
import com.metavirtuoso.conjunction.core.palm.HandShape
import com.metavirtuoso.conjunction.core.palm.LineFeature
import com.metavirtuoso.conjunction.core.palm.Mount
import com.metavirtuoso.conjunction.core.palm.PalmFeatures
import com.metavirtuoso.conjunction.core.palm.PalmLine
import com.metavirtuoso.conjunction.core.prompt.HoroscopePrompt
import com.metavirtuoso.conjunction.core.prompt.NatalSection
import com.metavirtuoso.conjunction.core.prompt.PalmReadingPrompt
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/** Inputs and expected strings for golden_prompts.json (prompt wording + fallback text parity). */
object PromptFixtures {
    data class ChartCase(val id: String, val birth: BirthData, val blood: BloodType?, val transitInstant: Instant, val date: LocalDate)

    val charts = listOf(
        ChartCase("ava-1990", BirthData(LocalDate.of(1990, 8, 12), LocalTime.of(14, 20), 40.71, -74.01, "America/New_York"),
            BloodType.O, Instant.parse("2026-11-03T17:00:00Z"), LocalDate.of(2026, 11, 3)),
        ChartCase("chen-1992", BirthData(LocalDate.of(1992, 2, 4), LocalTime.of(23, 40), 31.23, 121.47, "Asia/Shanghai"),
            BloodType.AB, Instant.parse("2027-02-14T04:00:00Z"), LocalDate.of(2027, 2, 14)),
        ChartCase("dara-1995-unknown-time", BirthData(LocalDate.of(1995, 11, 3), null, -33.87, 151.21, "Australia/Sydney"),
            null, Instant.parse("2026-06-21T02:00:00Z"), LocalDate.of(2026, 6, 21)),
    )

    val palms = mapOf(
        "palm-water-right" to PalmFeatures(
            hand = Hand.RIGHT, handShape = HandShape.WATER, palmRatio = 0.74,
            lines = mapOf(
                PalmLine.HEART to LineFeature(true, 0.92, 0.6, 0.7),
                PalmLine.HEAD to LineFeature(true, 0.70, 0.2, 0.5),
                PalmLine.LIFE to LineFeature(true, 0.85, 0.7, 0.8, breaks = 1),
                PalmLine.FATE to LineFeature.ABSENT,
            ),
            fingers = FingerRatios(0.95, 0.93), mounts = mapOf(Mount.VENUS to 0.8, Mount.LUNA to 0.65), quality = 0.9,
        ),
        "palm-earth-left-faint" to PalmFeatures(
            hand = Hand.LEFT, handShape = HandShape.EARTH, palmRatio = 0.86,
            lines = mapOf(
                PalmLine.HEART to LineFeature(true, 0.50, 0.1, 0.3),
                PalmLine.HEAD to LineFeature(true, 0.90, 0.5, 0.2, forked = true),
                PalmLine.LIFE to LineFeature(true, 0.60, 0.3, 0.5),
                PalmLine.FATE to LineFeature(true, 0.40, 0.1, 0.5),
            ),
            fingers = FingerRatios(1.05, 0.82), quality = 0.4,
        ),
    )

    fun build(): Map<String, Any?> {
        val chartOut = charts.map { c ->
            val chart = NatalChart.compute(c.birth)
            val transits = Transits.compute(chart, c.transitInstant)
            val chinese = ChineseZodiac.compute(c.birth)
            linkedMapOf(
                "id" to c.id,
                "birth" to linkedMapOf("date" to c.birth.date.toString(), "time" to c.birth.time?.toString(),
                    "latitude" to c.birth.latitude, "longitude" to c.birth.longitude, "zoneId" to c.birth.zoneId),
                "bloodType" to c.blood?.name,
                "transitInstant" to c.transitInstant.toString(),
                "date" to c.date.toString(),
                "prompts" to linkedMapOf(
                    "daily" to HoroscopePrompt.daily(chart, transits, c.date),
                    "natal" to NatalSection.entries.associate { it.name to HoroscopePrompt.natal(chart, it) },
                    "eastern" to HoroscopePrompt.eastern(chinese, c.blood),
                ),
                "fallback" to linkedMapOf(
                    "daily" to FallbackReadings.daily(chart, transits, c.date),
                    "natal" to NatalSection.entries.associate { it.name to FallbackReadings.natal(chart, it) },
                    "eastern" to FallbackReadings.eastern(chinese, c.blood),
                ),
            )
        }
        val profiles = charts.associate { c ->
            c.id to CompatProfile.from(NatalChart.compute(c.birth), ChineseZodiac.compute(c.birth), c.blood)
        }
        val pairOut = listOf("ava-1990" to "chen-1992", "chen-1992" to "dara-1995-unknown-time").flatMap { (x, y) ->
            val a = profiles.getValue(x)
            val b = profiles.getValue(y)
            val score = Compatibility.score(a, b)
            MatchKind.entries.map { kind ->
                linkedMapOf(
                    "a" to x, "b" to y, "kind" to kind.wire,
                    "aProfile" to CompatWire.profileToMap(a), "bProfile" to CompatWire.profileToMap(b),
                    "compatibilityPrompt" to HoroscopePrompt.compatibility(a, b, score),
                    "matchBlurbPrompt" to HoroscopePrompt.matchBlurb(a, b, score, kind),
                    "fallback" to FallbackReadings.compatibility(a, b, score, kind),
                )
            }
        }
        val palmOut = palms.map { (id, f) ->
            linkedMapOf("id" to id, "features" to palmToMap(f), "prompt" to PalmReadingPrompt.build(f), "fallback" to FallbackReadings.palm(f))
        }
        return linkedMapOf(
            "description" to "Prompt wording and fallback reading text that Android and iOS must reproduce exactly. " +
                "GENERATED by ./gradlew :core:writeGoldens from the Kotlin engines.",
            "charts" to chartOut,
            "pairs" to pairOut,
            "palms" to palmOut,
        )
    }

    fun palmToMap(f: PalmFeatures): Map<String, Any?> = linkedMapOf(
        "hand" to f.hand.name, "handShape" to f.handShape.name, "palmRatio" to f.palmRatio,
        "lines" to PalmLine.entries.associate { l ->
            val x = f.line(l)
            l.name to linkedMapOf("present" to x.present, "length" to x.length, "curvature" to x.curvature,
                "depth" to x.depth, "breaks" to x.breaks, "forked" to x.forked)
        },
        "fingers" to linkedMapOf("indexToRing" to f.fingers.indexToRing, "fingerToPalm" to f.fingers.fingerToPalm),
        "mounts" to f.mounts.entries.associate { it.key.name to it.value },
        "quality" to f.quality,
    )
}
