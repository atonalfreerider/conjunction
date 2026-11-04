package com.metavirtuoso.conjunction.ai

import android.graphics.Bitmap
import com.metavirtuoso.conjunction.core.chart.Transits
import com.metavirtuoso.conjunction.core.compat.CompatProfile
import com.metavirtuoso.conjunction.core.compat.CompatResult
import com.metavirtuoso.conjunction.core.fallback.FallbackReadings
import com.metavirtuoso.conjunction.core.match.MatchBlurbPrompt
import com.metavirtuoso.conjunction.core.match.MatchKind
import com.metavirtuoso.conjunction.core.palm.PalmFeatures
import com.metavirtuoso.conjunction.core.prompt.HoroscopePrompt
import com.metavirtuoso.conjunction.core.prompt.NatalSection
import com.metavirtuoso.conjunction.core.prompt.PalmReadingPrompt
import com.metavirtuoso.conjunction.core.prompt.ReadingCleaner
import com.metavirtuoso.conjunction.data.CachedReading
import com.metavirtuoso.conjunction.data.Charts
import com.metavirtuoso.conjunction.data.ReadingCache
import com.metavirtuoso.conjunction.data.ReadingSource
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A reading ready to show, and who wrote it. */
data class Reading(val text: String, val source: ReadingSource, val note: String? = null)

/**
 * Builds every reading the app shows: prompt from core's [HoroscopePrompt] / [PalmReadingPrompt]
 * (wording shared with iOS), generated on the phone by [TextModel], tidied by [ReadingCleaner].
 * When the on-device model is unavailable or fails, the deterministic [FallbackReadings] text is
 * used instead — never a cloud model. Daily, natal and Eastern readings are cached per day /
 * birth-data fingerprint in [ReadingCache].
 */
class ReadingService(private val model: TextModel, private val cache: ReadingCache) {

    private suspend fun generateOrFallback(
        prompt: String,
        fallback: () -> String,
        onStatus: (String) -> Unit,
        image: Bitmap? = null,
    ): Pair<Reading, Boolean> {
        val fallbackNote = "Written from Conjunction's bundled verses: this phone's on-device AI isn't available right now."
        return try {
            val generated = model.generate(prompt, image, onStatus)
            val cleaned = ReadingCleaner.clean(generated.text)
            if (cleaned != null) Reading(cleaned, ReadingSource.ON_DEVICE_AI) to generated.usedImage
            else Reading(fallback(), ReadingSource.FALLBACK, fallbackNote) to false
        } catch (e: CancellationException) {
            throw e
        } catch (e: ModelUnavailableException) {
            Reading(fallback(), ReadingSource.FALLBACK, fallbackNote) to false
        } catch (e: RuntimeException) {
            // AICore can throw platform exceptions on unsupported devices; readings must still appear.
            Reading(fallback(), ReadingSource.FALLBACK, fallbackNote) to false
        }
    }

    private suspend fun cached(key: String, refresh: Boolean, produce: suspend () -> Reading): Reading {
        if (!refresh) cache.get(key)?.let { return Reading(it.text, it.source) }
        val reading = produce()
        cache.put(key, CachedReading(reading.text, reading.source, Instant.now()))
        return reading
    }

    suspend fun daily(charts: Charts, date: LocalDate, zone: ZoneId, refresh: Boolean = false, onStatus: (String) -> Unit = {}): Reading =
        cached(ReadingCache.dailyKey(date, charts.fingerprint), refresh) {
            val transits = withContext(Dispatchers.Default) { Transits.compute(charts.natal, Transits.noonOf(date, zone)) }
            generateOrFallback(
                HoroscopePrompt.daily(charts.natal, transits, date),
                { FallbackReadings.daily(charts.natal, transits, date) },
                onStatus,
            ).first
        }.also { cache.prune(date) }

    suspend fun natal(charts: Charts, section: NatalSection, refresh: Boolean = false, onStatus: (String) -> Unit = {}): Reading =
        cached(ReadingCache.natalKey(section.name, charts.fingerprint), refresh) {
            generateOrFallback(
                HoroscopePrompt.natal(charts.natal, section),
                { FallbackReadings.natal(charts.natal, section) },
                onStatus,
            ).first
        }

    suspend fun eastern(charts: Charts, rh: com.metavirtuoso.conjunction.core.blood.RhFactor?, refresh: Boolean = false, onStatus: (String) -> Unit = {}): Reading =
        cached(ReadingCache.easternKey(charts.fingerprint + (rh?.name ?: "")), refresh) {
            generateOrFallback(
                HoroscopePrompt.eastern(charts.chinese, charts.bloodType, rh),
                { FallbackReadings.eastern(charts.chinese, charts.bloodType) },
                onStatus,
            ).first
        }

    /**
     * Palm readings are not cached (the photo and features are discarded after the reading). The
     * photo is offered to Gemini Nano alongside the measurements; devices whose Nano does not take
     * images get the measurements only (see [GeminiNanoModel]).
     */
    suspend fun palm(features: PalmFeatures, photo: Bitmap?, onStatus: (String) -> Unit = {}): Reading {
        val (reading, usedImage) = generateOrFallback(
            PalmReadingPrompt.build(features),
            { FallbackReadings.palm(features) },
            onStatus,
            image = photo,
        )
        return if (reading.source == ReadingSource.ON_DEVICE_AI && usedImage) reading.copy(note = "Gemini Nano read your measurements and looked at the photo, on this phone.") else reading
    }

    suspend fun compatibility(me: CompatProfile, them: CompatProfile, score: CompatResult, kind: MatchKind, onStatus: (String) -> Unit = {}): Reading =
        generateOrFallback(
            HoroscopePrompt.compatibility(me, them, score),
            { FallbackReadings.compatibility(me, them, score, kind) },
            onStatus,
        ).first

    /** One-line blurb for the match animation; falls back to the deterministic line. */
    suspend fun matchBlurb(input: MatchBlurbPrompt.Input): String = try {
        MatchBlurbPrompt.clean(model.generate(MatchBlurbPrompt.build(input)).text) ?: MatchBlurbPrompt.fallback(input)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        MatchBlurbPrompt.fallback(input)
    }
}
