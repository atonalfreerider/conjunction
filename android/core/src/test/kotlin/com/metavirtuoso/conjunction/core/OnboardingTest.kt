package com.metavirtuoso.conjunction.core

import com.metavirtuoso.conjunction.core.chart.NatalChart
import com.metavirtuoso.conjunction.core.chinese.ChineseZodiac
import com.metavirtuoso.conjunction.core.json.Json
import com.metavirtuoso.conjunction.core.match.LookingFor
import com.metavirtuoso.conjunction.core.fallback.FallbackReadings
import com.metavirtuoso.conjunction.core.onboarding.AboutYou
import com.metavirtuoso.conjunction.core.onboarding.AboutYouParser
import com.metavirtuoso.conjunction.core.onboarding.BirthExtraction
import com.metavirtuoso.conjunction.core.onboarding.BirthTextParser
import com.metavirtuoso.conjunction.core.onboarding.BirthTextResult
import com.metavirtuoso.conjunction.core.onboarding.OnboardingExtraction
import com.metavirtuoso.conjunction.core.prompt.BioPrompt
import com.metavirtuoso.conjunction.core.prompt.OnboardingPrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/** SPEC 5d.5: BirthTextParser, AboutYouParser, model JSON validation, onboarding/bio prompts. */
class OnboardingTest {
    @Test
    fun birthTextGolden() {
        val g = TestData.json("golden_birth_text.json")
        val today = LocalDate.parse(g["today"] as String)
        for (c in Json.arr(g["cases"]).map(Json::obj)) {
            val r = BirthTextParser.parse(c["text"] as String, c["region"] as String?, today)
            val what = "${c["text"]} (${c["region"]})"
            assertEquals("$what date", c["date"], r.date?.toString())
            assertEquals("$what time", c["time"], r.time?.toString())
            assertEquals("$what timeUnknown", c["timeUnknown"], r.timeUnknown)
            assertEquals("$what place", c["placeQuery"], r.placeQuery)
            assertEquals("$what ambiguous", c["ambiguousDate"], r.ambiguousDate)
        }
        for (c in Json.arr(g["acceptableDate"]).map(Json::obj)) {
            assertEquals(c["date"].toString(), c["acceptable"], BirthTextParser.isAcceptableDate(LocalDate.parse(c["date"] as String), today))
        }
    }

    @Test
    fun aboutYouGolden() {
        for (c in Json.arr(TestData.json("golden_about_you.json")["cases"]).map(Json::obj)) {
            val r = AboutYouParser.parse(c["text"] as String)
            val what = c["text"].toString()
            assertEquals("$what firstName", c["firstName"], r.firstName)
            assertEquals("$what gender", c["gender"], r.gender?.wire)
            assertEquals("$what seeking", c["seeking"], r.seeking?.wire)
            assertEquals("$what relationship", c["relationship"], r.relationship?.wire)
            assertEquals("$what lookingFor", c["lookingFor"], r.lookingFor?.wire)
            assertEquals("$what interests", c["interests"], r.interests)
        }
    }

    @Test
    fun modelJsonGolden() {
        val g = TestData.json("golden_onboarding_ai.json")
        val today = LocalDate.parse(g["today"] as String)
        for (c in Json.arr(g["birth"]).map(Json::obj)) {
            val r = OnboardingExtraction.parseBirth(c["raw"] as String, today) ?: throw AssertionError("rejected ${c["raw"]}")
            assertEquals("${c["raw"]} date", c["date"], r.date?.toString())
            assertEquals("${c["raw"]} time", c["time"], r.time?.toString())
            assertEquals("${c["raw"]} timeUnknown", c["timeUnknown"], r.timeUnknown)
            assertEquals("${c["raw"]} place", c["place"], r.place)
        }
        for (raw in Json.arr(g["birthReject"])) assertNull(raw.toString(), OnboardingExtraction.parseBirth(raw as String, today))
        for (c in Json.arr(g["aboutYou"]).map(Json::obj)) {
            val r = OnboardingExtraction.parseAboutYou(c["raw"] as String) ?: throw AssertionError("rejected ${c["raw"]}")
            assertEquals("${c["raw"]} firstName", c["firstName"], r.firstName)
            assertEquals("${c["raw"]} gender", c["gender"], r.gender?.wire)
            assertEquals("${c["raw"]} seeking", c["seeking"], r.seeking?.wire)
            assertEquals("${c["raw"]} relationship", c["relationship"], r.relationship?.wire)
            assertEquals("${c["raw"]} lookingFor", c["lookingFor"], r.lookingFor?.wire)
            assertEquals("${c["raw"]} interests", c["interests"], r.interests)
        }
        for (raw in Json.arr(g["aboutYouReject"])) assertNull(raw.toString(), OnboardingExtraction.parseAboutYou(raw as String))
    }

    @Test
    fun modelOnlyFillsEmptyChips() {
        val parsed = BirthTextResult(LocalDate.of(1994, 8, 7), null, true, null, false)
        val model = BirthExtraction(LocalDate.of(1990, 1, 1), LocalTime.of(4, 0), false, "Providence, RI")
        val merged = OnboardingExtraction.fillEmpty(parsed, model)
        assertEquals(LocalDate.of(1994, 8, 7), merged.date)
        assertNull(merged.time)
        assertTrue(merged.timeUnknown)
        assertEquals("Providence, RI", merged.placeQuery)
        assertEquals(parsed, OnboardingExtraction.fillEmpty(parsed, null))
        val mine = AboutYou(firstName = "Sam", lookingFor = LookingFor.FRIENDS)
        val filled = mine.fillEmpty(AboutYou(firstName = "Samuel", lookingFor = LookingFor.DATING, interests = "jazz"))
        assertEquals(AboutYou(firstName = "Sam", lookingFor = LookingFor.FRIENDS, interests = "jazz"), filled)
    }

    @Test
    fun promptsAndBioMatchGolden() {
        val g = TestData.json("golden_prompts.json")
        val onboarding = Json.arr(g["onboarding"]).map(Json::obj)
        assertEquals(PromptFixtures.onboardingTexts.size, onboarding.size)
        for (o in onboarding) {
            val text = o["text"] as String
            assertEquals(o["birthPrompt"], OnboardingPrompt.birth(text, LocalDate.parse(o["today"] as String)))
            assertEquals(o["aboutYouPrompt"], OnboardingPrompt.aboutYou(text))
            assertTrue(OnboardingPrompt.sanitize(text).codePointCount(0, OnboardingPrompt.sanitize(text).length) <= OnboardingPrompt.MAX_INPUT_CODE_POINTS)
        }
        val charts = Json.arr(g["charts"]).map(Json::obj)
        for (c in charts) {
            val case = PromptFixtures.charts.first { it.id == c["id"] }
            val chart = NatalChart.compute(case.birth)
            val chinese = ChineseZodiac.compute(case.birth)
            val bio = Json.obj(c["bio"])
            val lf = (bio["lookingFor"] as String?)?.let { w -> LookingFor.entries.first { it.wire == w } }
            assertEquals(bio["prompt"], BioPrompt.build(chart.sunSign, chart.moonSign, chart.risingSign, chinese.yearAnimal, lf, bio["interests"] as String?))
            val fallbacks = Json.arr(bio["fallback"]).map { it as String }
            fallbacks.forEachIndexed { seed, text ->
                assertEquals(text, FallbackReadings.bio(chart, bio["interests"] as String?, seed.toLong()))
                assertTrue(text, text.codePointCount(0, text.length) <= BioPrompt.MAX_CHARS)
            }
            assertTrue("Another gives a different line", fallbacks.toSet().size > 1)
            assertTrue(!FallbackReadings.bio(chart, " , ", 0).contains("Into"))
        }
    }
}
