package com.metavirtuoso.conjunction.ai

import android.graphics.Bitmap
import com.metavirtuoso.conjunction.core.chart.NatalChart
import com.metavirtuoso.conjunction.core.chinese.ChineseAnimal
import com.metavirtuoso.conjunction.core.fallback.FallbackReadings
import com.metavirtuoso.conjunction.core.match.LookingFor
import com.metavirtuoso.conjunction.core.onboarding.AboutYou
import com.metavirtuoso.conjunction.core.onboarding.BirthExtraction
import com.metavirtuoso.conjunction.core.onboarding.OnboardingExtraction
import com.metavirtuoso.conjunction.core.prompt.BioPrompt
import com.metavirtuoso.conjunction.core.prompt.OnboardingPrompt
import com.metavirtuoso.conjunction.core.prompt.ReadingCleaner
import com.metavirtuoso.conjunction.nearby.TextHeuristics
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * On-device AI helpers for streamlined onboarding (SPEC 5d.5). Every call:
 * - runs only when the model is available NOW (no download is started during onboarding);
 * - has a hard timeout (4 s extraction, 6 s bio) and returns null on timeout, error, invalid JSON
 *   or a value that fails validation, so the caller silently keeps its deterministic result;
 * - is a plain suspend function: the caller's coroutine scope cancels it (latest input wins,
 *   leaving the screen drops a late answer).
 */
class OnboardingAssistant(private val model: TextModel) {
    suspend fun birth(text: String, today: LocalDate): BirthExtraction? = safely(OnboardingExtraction.EXTRACTION_TIMEOUT_MS) {
        OnboardingExtraction.parseBirth(model.generateIfReady(OnboardingPrompt.birth(text, today))?.text, today)
    }

    suspend fun aboutYou(text: String): AboutYou? = safely(OnboardingExtraction.EXTRACTION_TIMEOUT_MS) {
        OnboardingExtraction.parseAboutYou(model.generateIfReady(OnboardingPrompt.aboutYou(text))?.text)
    }

    /** A model-written bio line, or null (the caller shows [fallbackBio]). */
    suspend fun bio(chart: NatalChart, animal: ChineseAnimal, lookingFor: LookingFor?, interests: String?, photo: Bitmap?, reroll: Boolean): String? =
        safely(OnboardingExtraction.BIO_TIMEOUT_MS) {
            val prompt = BioPrompt.build(chart.sunSign, chart.moonSign, chart.risingSign, animal, lookingFor, interests)
            cleanBio(model.generateIfReady(prompt, photo, if (reroll) 0.9f else null)?.text)
        }

    private suspend fun <T> safely(timeoutMs: Long, block: suspend () -> T?): T? = try {
        withTimeoutOrNull(timeoutMs) { block() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    companion object {
        /** Shown at once; "Another" increments [seed]. */
        fun fallbackBio(chart: NatalChart, interests: String?, seed: Long): String = FallbackReadings.bio(chart, interests?.takeIf { it.isNotBlank() }, seed)

        /**
         * One line, <= 140 characters, through ReadingCleaner and the local contact-info filter;
         * an answer that is only emoji / punctuation is rejected.
         */
        fun cleanBio(raw: String?): String? {
            val cleaned = ReadingCleaner.clean(raw ?: return null) ?: return null
            val line = cleaned.lineSequence().map { it.trim().trim('"', '“', '”') }.firstOrNull { it.isNotEmpty() } ?: return null
            if (line.none { it.isLetter() }) return null
            if (TextHeuristics.flags(line)) return null
            return if (line.codePointCount(0, line.length) <= BioPrompt.MAX_CHARS) line else null
        }
    }
}
