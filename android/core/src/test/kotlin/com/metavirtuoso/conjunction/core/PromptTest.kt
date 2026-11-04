package com.metavirtuoso.conjunction.core

import com.metavirtuoso.conjunction.core.json.Json
import com.metavirtuoso.conjunction.core.prompt.HoroscopePrompt
import com.metavirtuoso.conjunction.core.prompt.ReadingCleaner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptTest {
    @Test
    fun promptsAndFallbacksMatchGolden() {
        val expected = TestData.json("golden_prompts.json")
        val actual = Json.obj(Json.parse(Json.write(PromptFixtures.build())))
        assertEquals(Json.write(expected), Json.write(actual))
    }

    @Test
    fun everyPromptCarriesGuardrails() {
        val g = TestData.json("golden_prompts.json")
        val prompts = mutableListOf<String>()
        for (c in Json.arr(g["charts"]).map(Json::obj)) {
            val p = Json.obj(c["prompts"])
            prompts += p["daily"] as String
            prompts += p["eastern"] as String
            prompts += Json.obj(p["natal"]).values.map { it as String }
        }
        for (pair in Json.arr(g["pairs"]).map(Json::obj)) prompts += pair["compatibilityPrompt"] as String
        for (palm in Json.arr(g["palms"]).map(Json::obj)) prompts += palm["prompt"] as String
        for (p in prompts) {
            assertTrue(p, p.contains(HoroscopePrompt.GUARDRAILS))
            assertFalse("no birthplace/coordinates in prompts", p.contains("40.71") || p.contains("America/"))
        }
    }

    @Test
    fun fallbackTextHasNoUnfilledPlaceholders() {
        val text = Json.write(TestData.json("golden_prompts.json"))
        assertFalse(Regex("\\{[a-zA-Z]+}").containsMatchIn(text))
    }

    @Test
    fun cleaner() {
        assertEquals("Today glows.", ReadingCleaner.clean("Horoscope: **Today** glows."))
        assertEquals("A quiet tide.", ReadingCleaner.clean("## Reading\n\"A quiet tide.\""))
        assertEquals("Line one.\n\nLine two.", ReadingCleaner.clean("Line one.\n\n\n\nLine two."))
        assertNull(ReadingCleaner.clean("   "))
        assertNull(ReadingCleaner.clean(null))
    }
}
