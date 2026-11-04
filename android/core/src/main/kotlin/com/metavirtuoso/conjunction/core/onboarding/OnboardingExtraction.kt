package com.metavirtuoso.conjunction.core.onboarding

import com.metavirtuoso.conjunction.core.match.Gender
import com.metavirtuoso.conjunction.core.match.LookingFor
import com.metavirtuoso.conjunction.core.match.RelationshipStatus
import com.metavirtuoso.conjunction.core.match.Seeking
import com.metavirtuoso.conjunction.core.p2p.P2pException
import com.metavirtuoso.conjunction.core.p2p.WireJson
import java.time.DateTimeException
import java.time.LocalDate
import java.time.LocalTime

/** The on-device model's answer to `OnboardingPrompt.birth`, after validation. */
data class BirthExtraction(val date: LocalDate?, val time: LocalTime?, val timeUnknown: Boolean, val place: String?)

/**
 * Validation of the on-device model's JSON for onboarding (SPEC 5d.5, golden
 * `testdata/golden_onboarding_ai.json`). Twin of Swift `OnboardingExtraction`.
 *
 * Model output is untrusted: the JSON object is taken from the first `{` to the last `}` (so code
 * fences or a sentence around it do not matter) and parsed strictly (no floats, no duplicate keys);
 * anything unparsable -> null (keep the deterministic result silently). Each field is validated on
 * its own: a value that fails its rule becomes null and the others are kept. iOS guided generation
 * (`@Generable`) passes the fields to [validateBirth] / [validateAboutYou] directly.
 */
object OnboardingExtraction {
    /** Model timeouts (SPEC 5d.5): birth and about-you extraction, bio. */
    const val EXTRACTION_TIMEOUT_MS = 4_000L
    const val BIO_TIMEOUT_MS = 6_000L

    const val MAX_PLACE_BYTES = 120
    private val TIME = Regex("^([01][0-9]|2[0-3]):([0-5][0-9])$")
    private val DATE = Regex("^([0-9]{4})-([0-9]{2})-([0-9]{2})$")
    private val NAME_CHARS = Regex("^[\\p{L}\\p{M}][\\p{L}\\p{M} '’-]*$")

    /** The first `{` .. last `}` of [raw] parsed as a strict JSON object, or null. */
    fun jsonObject(raw: String?): Map<*, *>? {
        if (raw == null) return null
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end < start) return null
        return try { WireJson.parse(raw.substring(start, end + 1)) as? Map<*, *> } catch (e: P2pException) { null }
    }

    fun parseBirth(raw: String?, today: LocalDate): BirthExtraction? {
        val o = jsonObject(raw) ?: return null
        return validateBirth(o["date"] as? String, o["time"] as? String, o["timeUnknown"] == true, o["place"] as? String, today)
    }

    fun validateBirth(date: String?, time: String?, timeUnknown: Boolean, place: String?, today: LocalDate): BirthExtraction {
        val d = date?.let { DATE.matchEntire(it.trim()) }?.let { m ->
            try { LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) } catch (e: DateTimeException) { null }
        }?.takeIf { BirthTextParser.isAcceptableDate(it, today) }
        val t = time?.let { TIME.matchEntire(it.trim()) }?.let { LocalTime.of(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }
        return BirthExtraction(d, t, timeUnknown && t == null, cleanPlace(place))
    }

    fun parseAboutYou(raw: String?): AboutYou? {
        val o = jsonObject(raw) ?: return null
        return validateAboutYou(
            o["firstName"] as? String, o["gender"] as? String, o["seeking"] as? String,
            o["relationship"] as? String, o["lookingFor"] as? String, o["interests"] as? String,
        )
    }

    /** Enum values must be exactly the wire strings; names and interests must pass their rules. */
    fun validateAboutYou(
        firstName: String?, gender: String?, seeking: String?, relationship: String?, lookingFor: String?, interests: String?,
    ): AboutYou = AboutYou(
        firstName = cleanFirstName(firstName),
        gender = Gender.entries.firstOrNull { it.wire == gender },
        seeking = Seeking.entries.firstOrNull { it.wire == seeking },
        relationship = RelationshipStatus.entries.firstOrNull { it.wire == relationship },
        lookingFor = LookingFor.entries.firstOrNull { it.wire == lookingFor },
        interests = interests?.trim()?.takeIf { it.isNotEmpty() && utf8(it) <= AboutYouParser.MAX_INTERESTS_BYTES },
    )

    /** 1..64 UTF-8 bytes of letters (with marks), spaces, apostrophes and hyphens, trimmed; else null. */
    fun cleanFirstName(s: String?): String? {
        val t = s?.trim() ?: return null
        return t.takeIf { utf8(it) in 1..AboutYouParser.MAX_FIRST_NAME_BYTES && NAME_CHARS.matches(it) }
    }

    /** Trimmed, cut at a code-point boundary to <= 200 UTF-8 bytes (the parser's own interests). */
    fun cleanInterests(s: String?): String? {
        var t = s?.trim() ?: return null
        while (utf8(t) > AboutYouParser.MAX_INTERESTS_BYTES) {
            t = t.substring(0, t.offsetByCodePoints(t.length, -1)).trimEnd()
        }
        return t.ifEmpty { null }
    }

    /** A place query: trimmed, 1..120 UTF-8 bytes, no control characters; else null. */
    fun cleanPlace(s: String?): String? {
        val t = s?.trim() ?: return null
        return t.takeIf { utf8(it) in 1..MAX_PLACE_BYTES && it.none { c -> c < ' ' || c == '\u007F' } }
    }

    /**
     * Merges a model answer into the parser result: the model only fills what is still empty
     * (never a parsed or user-edited value); a parsed time or "time unknown" is kept.
     */
    fun fillEmpty(parsed: BirthTextResult, model: BirthExtraction?): BirthTextResult {
        if (model == null) return parsed
        val hasTime = parsed.time != null || parsed.timeUnknown
        return parsed.copy(
            date = parsed.date ?: model.date,
            time = if (hasTime) parsed.time else model.time,
            timeUnknown = if (hasTime) parsed.timeUnknown else model.time == null && model.timeUnknown,
            placeQuery = parsed.placeQuery ?: model.place,
        )
    }

    private fun utf8(s: String) = s.toByteArray(Charsets.UTF_8).size
}
