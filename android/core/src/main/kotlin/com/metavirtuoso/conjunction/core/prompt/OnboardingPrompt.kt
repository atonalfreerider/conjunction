package com.metavirtuoso.conjunction.core.prompt

import java.time.LocalDate

/**
 * On-device model prompts for streamlined onboarding (SPEC 5d.5), identical wording on Android and
 * iOS (pinned by testdata/golden_prompts.json `onboarding`). The answers are validated by
 * `onboarding.OnboardingExtraction`; the deterministic parsers run first and the model only fills
 * chips that are still empty. The model never sees Instagram data and fetches nothing.
 */
object OnboardingPrompt {
    /** Longest user text passed to the model, in code points. */
    const val MAX_INPUT_CODE_POINTS = 500

    fun birth(text: String, today: LocalDate): String = listOf(
        "Extract birth details from the text between <<< and >>>. Answer with one JSON object and nothing else, exactly these keys:",
        "{\"date\":\"YYYY-MM-DD\" or null,\"time\":\"HH:MM\" in 24-hour time or null,\"timeUnknown\":true or false,\"place\":string or null}",
        "Fill a field only if the text states it; never guess. \"timeUnknown\" is true only if the text says the birth time is not known. " +
            "\"place\" is the birth town or city with its region or country, as written. Today is $today; a birth date is never in the future.",
        "<<<${sanitize(text)}>>>",
    ).joinToString("\n")

    fun aboutYou(text: String): String = listOf(
        "Extract profile details from the text between <<< and >>>. Answer with one JSON object and nothing else, exactly these keys:",
        "{\"firstName\":string or null,\"gender\":\"male\" or \"female\" or null,\"seeking\":\"male\" or \"female\" or \"both\" or null," +
            "\"relationship\":\"single\" or \"in_a_relationship\" or null,\"lookingFor\":\"dating\" or \"friends\" or \"both\" or null,\"interests\":string or null}",
        "Fill a field only if the text states it. Never guess gender or orientation from a name or from interests. " +
            "\"seeking\" is who they want to meet; \"lookingFor\" is dating, friends or both; \"interests\" is a short comma-separated list of hobbies in their own words.",
        "<<<${sanitize(text)}>>>",
    ).joinToString("\n")

    /**
     * User text for a prompt: line breaks and other control characters become spaces, the
     * delimiters are dropped, the result is trimmed and capped at [MAX_INPUT_CODE_POINTS] code points.
     */
    fun sanitize(text: String): String {
        val flat = buildString(text.length) { for (c in text) append(if (c < ' ' || c == '\u007F' || c == '\u2028' || c == '\u2029') ' ' else c) }
        val t = flat.replace("<<<", "").replace(">>>", "").trim()
        val n = t.codePointCount(0, t.length)
        return if (n <= MAX_INPUT_CODE_POINTS) t else t.substring(0, t.offsetByCodePoints(0, MAX_INPUT_CODE_POINTS))
    }
}
