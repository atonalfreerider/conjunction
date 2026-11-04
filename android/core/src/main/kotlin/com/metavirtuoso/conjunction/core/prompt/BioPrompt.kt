package com.metavirtuoso.conjunction.core.prompt

import com.metavirtuoso.conjunction.core.chart.ZodiacSign
import com.metavirtuoso.conjunction.core.chinese.ChineseAnimal
import com.metavirtuoso.conjunction.core.match.LookingFor

/**
 * "Write one for me" (SPEC 5d.5 screen 4): a one-line dating-card bio. Identical wording on Android
 * and iOS (golden_prompts.json `charts[].bio`). On Android the photo may be passed alongside when
 * the model accepts images; the text is the same. The answer passes ReadingCleaner and the local
 * contact-info filter before it is shown; the deterministic fallback is FallbackReadings.bio.
 */
object BioPrompt {
    const val MAX_CHARS = 140

    fun build(
        sun: ZodiacSign,
        moon: ZodiacSign,
        rising: ZodiacSign?,
        chineseAnimal: ChineseAnimal,
        lookingFor: LookingFor?,
        interests: String?,
    ): String {
        val signs = "${sun.displayName} sun, ${moon.displayName} moon" + (rising?.let { ", ${it.displayName} rising" } ?: "")
        val goal = when (lookingFor) {
            LookingFor.DATING -> " They are here to date."
            LookingFor.FRIENDS -> " They are here to make friends."
            LookingFor.BOTH -> " They are open to dating and friendship."
            null -> ""
        }
        val likes = interests?.let(OnboardingPrompt::sanitize)?.takeIf { it.isNotEmpty() }?.let { " Their interests: $it." } ?: ""
        return "Write one short bio line for a dating card, at most $MAX_CHARS characters, in the first person. " +
            "The person is a $signs, born in the Year of the ${chineseAnimal.displayName}.$goal$likes\n" +
            "Make it warm, playful and specific, with a light celestial touch. No hashtags, no emoji-only answer, no names, no age, " +
            "no contact details, no links, no quotation marks. Answer with the bio line only."
    }
}
