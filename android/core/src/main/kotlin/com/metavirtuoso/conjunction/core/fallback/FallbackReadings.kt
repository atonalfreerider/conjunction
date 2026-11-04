package com.metavirtuoso.conjunction.core.fallback

import com.metavirtuoso.conjunction.core.blood.BloodType
import com.metavirtuoso.conjunction.core.blood.BloodTypeReading
import com.metavirtuoso.conjunction.core.chart.NatalChart
import com.metavirtuoso.conjunction.core.chart.Transits
import com.metavirtuoso.conjunction.core.chinese.ChineseChart
import com.metavirtuoso.conjunction.core.compat.ChineseRelation
import com.metavirtuoso.conjunction.core.compat.CompatProfile
import com.metavirtuoso.conjunction.core.compat.CompatResult
import com.metavirtuoso.conjunction.core.match.MatchKind
import com.metavirtuoso.conjunction.core.palm.LineFeature
import com.metavirtuoso.conjunction.core.palm.LineLength
import com.metavirtuoso.conjunction.core.palm.PalmFeatures
import com.metavirtuoso.conjunction.core.palm.PalmLine
import com.metavirtuoso.conjunction.core.prompt.NatalSection
import java.time.LocalDate

/**
 * Deterministic readings assembled from the bundled text in content_en.json, used whenever the
 * on-device model is unavailable (unsupported phone, model still downloading, generation failed).
 * Readings never leave the phone and never fall back to a cloud model. The same inputs give the
 * same text on Android and iOS (pinned by testdata/golden_prompts.json).
 */
object FallbackReadings {
    private fun pick(list: List<String>, seed: Long): String = list[Math.floorMod(seed, list.size.toLong()).toInt()]

    private fun fill(template: String, vararg pairs: Pair<String, String>): String =
        pairs.fold(template) { acc, (k, v) -> acc.replace("{$k}", v) }

    fun daily(chart: NatalChart, transitsNow: Transits, date: LocalDate): String {
        val daily = Content.obj(Content.root["daily"])
        val seed = date.toEpochDay() + 7L * chart.sunSign.ordinal + 13L * chart.moonSign.ordinal
        val moonSign = transitsNow.bodies.first { it.planet.key == "moon" }.sign
        val moonDaily = Content.text("signs", moonSign.key, "daily")
        val opening = fill(pick(Content.strings(daily["openings"]), seed),
            "moonSign" to moonSign.displayName, "moonDaily" to moonDaily)
        val sunLine = fill(pick(Content.strings(daily["sunLines"]), seed / 3),
            "sunSign" to chart.sunSign.displayName, "sunGift" to Content.text("signs", chart.sunSign.key, "gift"))
        val transit = transitsNow.aspectsToNatal.firstOrNull()?.let {
            fill(Content.obj(daily["transit"])[it.type.name.lowercase()] as String,
                "transit" to it.a.displayName, "natal" to it.b.displayName)
        } ?: daily["quietSky"] as String
        val closing = pick(Content.strings(daily["closings"]), seed / 9)
        return listOf(opening, sunLine, transit, closing).joinToString(" ")
    }

    fun natal(chart: NatalChart, section: NatalSection): String {
        val planet = Content.entry("planets", section.planetKey)
        if (section == NatalSection.RISING) {
            val rising = chart.risingSign ?: return Content.root["risingUnknown"] as String
            val s = Content.entry("signs", rising.key)
            return listOf(
                fill(planet["lead"] as String, "sign" to rising.displayName),
                planet["focus"] as String,
                "You tend to arrive ${Content.strings(s["keywords"]).joinToString(", ")}, like ${s["image"]}.",
                Content.root["natalClosing"] as String,
            ).joinToString(" ")
        }
        val body = chart.body(section.planet!!)
        val s = Content.entry("signs", body.sign.key)
        val parts = mutableListOf(
            fill(planet["lead"] as String, "sign" to body.sign.displayName),
            planet["focus"] as String,
            "In ${body.sign.displayName} it brings ${s["gift"]}; the growing edge is ${s["shadow"]}.",
        )
        if (section == NatalSection.LOVE) parts += "In love, ${s["love"]}."
        if (section == NatalSection.DRIVE || section == NatalSection.DISCIPLINE) parts += "In your work, ${s["work"]}."
        body.house?.let { parts += Content.strings(Content.root["houses"])[it - 1] }
        if (body.retrograde) parts += "Its retrograde motion at your birth turns this energy inward, toward reflection before action."
        chart.aspects.firstOrNull { it.a == body.planet || it.b == body.planet }?.let { asp ->
            val other = if (asp.a == body.planet) asp.b else asp.a
            parts += "Here ${body.planet.displayName} ${Content.obj(Content.root["aspects"])[asp.type.name.lowercase()]} ${other.displayName}, weaving the two stories together."
        }
        parts += Content.root["natalClosing"] as String
        return parts.joinToString(" ")
    }

    fun eastern(chinese: ChineseChart, bloodType: BloodType?): String {
        val animal = Content.entry("chinese", "animals").let { Content.obj(it[chinese.yearAnimal.key]) }
        val elements = Content.obj(Content.obj(Content.root["chinese"])["elements"])
        val masters = Content.obj(Content.obj(Content.root["chinese"])["dayMasters"])
        val parts = mutableListOf(
            "You are a ${chinese.yinYang.displayName} ${chinese.yearElement.displayName} ${chinese.yearAnimal.displayName}: ${animal["traits"]}, with a gift for ${animal["gift"]}.",
            elements[chinese.yearElement.key] as String,
            "The tradition's gentle warning for your sign is ${animal["shadow"]}.",
        )
        if (chinese.yearsDiffer) {
            parts += fill(Content.obj(Content.root["chinese"])["yearVsLunar"] as String,
                "lunarAnimal" to chinese.lunarYearAnimal.displayName, "yearAnimal" to chinese.yearAnimal.displayName)
        }
        val dm = chinese.dayMaster
        parts += "Your Day Master is ${dm.label} (${dm.hanzi}), pictured as ${masters[dm.name.lowercase()]}."
        if (bloodType != null) {
            val t = BloodTypeReading.traits(bloodType)
            parts += "${Content.root["bloodIntro"]} ${t.title}: ${t.strengths} ${t.love}"
        }
        return parts.joinToString(" ")
    }

    fun palm(features: PalmFeatures): String {
        val palm = Content.obj(Content.root["palm"])
        val lines = Content.obj(palm["lines"])
        val parts = mutableListOf(palm["intro"] as String, Content.obj(palm["shapes"])[features.handShape.key] as String)
        for (line in PalmLine.entries) {
            val f = features.line(line)
            val text = Content.obj(lines[line.key])
            if (f.lengthClass == LineLength.ABSENT) {
                parts += text["absent"] as String
                continue
            }
            parts += text[f.lengthClass.name.lowercase()] as String
            parts += text[if (f.isCurved) "curved" else "straight"] as String
            val depth = Content.obj(palm["depth"])
            when {
                f.depth >= LineFeature.DEEP_FROM -> parts += fill(depth["deep"] as String, "line" to line.displayName.replaceFirstChar(Char::uppercase))
                f.depth < LineFeature.FAINT_BELOW -> parts += fill(depth["faint"] as String, "line" to line.displayName.replaceFirstChar(Char::uppercase))
            }
        }
        val fingers = Content.obj(palm["fingers"])
        val r = features.fingers.indexToRing
        parts += fingers[if (r > 1.02) "indexLonger" else if (r < 0.98) "ringLonger" else "even"] as String
        parts += palm["closing"] as String
        return parts.joinToString(" ")
    }

    /** Compatibility blurb without the model (one or two sentences). */
    fun compatibility(a: CompatProfile, b: CompatProfile, score: CompatResult, kind: MatchKind): String {
        val compat = Content.obj(Content.root["compat"])
        val bands = Content.arrOfObj(compat["bands"])
        val band = bands.first { score.total >= (it["min"] as Number).toInt() }
        val parts = mutableListOf<String>()
        when {
            score.isExactTwin -> parts += compat["exactTwins"] as String
            score.isBirthdayTwin -> parts += compat["twins"] as String
        }
        parts += band[kind.wire] as String
        val rel = Content.obj(compat["chineseRelations"])
        val relKey = when (score.chineseRelation) {
            ChineseRelation.SAME, ChineseRelation.SAME_SELF_PUNISHMENT -> "same"
            ChineseRelation.TRINE -> "trine"
            ChineseRelation.HARMONY -> "harmony"
            ChineseRelation.CLASH -> "clash"
            ChineseRelation.HARM -> "harm"
            ChineseRelation.NEUTRAL -> "neutral"
        }
        parts += fill(rel[relKey] as String,
            "animal" to a.chineseAnimal.displayName, "animalA" to a.chineseAnimal.displayName, "animalB" to b.chineseAnimal.displayName)
        return parts.joinToString(" ")
    }
}
