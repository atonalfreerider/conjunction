package com.metavirtuoso.conjunction.core.prompt

import com.metavirtuoso.conjunction.core.palm.LineFeature
import com.metavirtuoso.conjunction.core.palm.LineLength
import com.metavirtuoso.conjunction.core.palm.PalmFeatures
import com.metavirtuoso.conjunction.core.palm.PalmLine

/** Prompt for the on-device palm reading, built only from measured [PalmFeatures] (no image needed). */
object PalmReadingPrompt {
    /** Palm-specific guardrails on top of [HoroscopePrompt.GUARDRAILS]: no lifespan, health or fate claims. */
    const val PALM_GUARDRAILS: String =
        "Palmistry is folklore: never connect any line to lifespan, health, illness, accidents, wealth or death, and say nothing is fixed."

    fun describe(line: PalmLine, f: LineFeature): String {
        if (!f.present) return "${line.displayName} line: not clearly visible"
        val length = when (f.lengthClass) {
            LineLength.LONG -> "long"
            LineLength.MEDIUM -> "medium length"
            LineLength.SHORT -> "short"
            LineLength.ABSENT -> "not visible"
        }
        val shape = if (f.isCurved) "curved" else "fairly straight"
        val depth = when {
            f.depth >= LineFeature.DEEP_FROM -> ", deep"
            f.depth < LineFeature.FAINT_BELOW -> ", faint"
            else -> ""
        }
        val extra = buildList {
            if (f.breaks > 0) add("${f.breaks} break${if (f.breaks > 1) "s" else ""}")
            if (f.forked) add("forked end")
        }.joinToString(", ").let { if (it.isEmpty()) "" else ", $it" }
        return "${line.displayName} line: $length, $shape$depth$extra"
    }

    fun fingerNote(features: PalmFeatures): String {
        val r = features.fingers.indexToRing
        return when {
            r > 1.02 -> "index finger longer than ring finger"
            r < 0.98 -> "ring finger longer than index finger"
            else -> "index and ring fingers about even"
        }
    }

    fun build(features: PalmFeatures): String {
        val lines = PalmLine.entries.joinToString("; ") { describe(it, features.line(it)) }
        val mounts = if (features.mounts.isEmpty()) null else "Prominent mounts: " + features.mounts.entries
            .filter { it.value >= 0.6 }.sortedByDescending { it.value }.joinToString(", ") { "Mount of ${it.key.displayName}" }
            .ifEmpty { "none stand out" } + "."
        return listOfNotNull(
            "Write a playful palm reading as one paragraph of 100 to 150 words.",
            "Hand: ${features.hand.name.lowercase()} hand, ${features.handShape.displayName} hand shape (palmistry's four elements).",
            "Lines: $lines.",
            "Fingers: ${fingerNote(features)}.",
            mounts,
            if (features.quality < 0.5) "The photo was unclear, so keep the reading light and general." else null,
            HoroscopePrompt.STYLE,
            HoroscopePrompt.GUARDRAILS,
            PALM_GUARDRAILS,
        ).joinToString("\n")
    }
}
