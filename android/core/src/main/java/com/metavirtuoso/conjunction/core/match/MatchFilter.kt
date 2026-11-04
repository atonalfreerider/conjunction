package com.metavirtuoso.conjunction.core.match

import java.time.LocalDate
import java.time.Period

/*
 * Dating & friends match filter (SPEC item 5b).
 *
 * The backend decides, before any encounter is written or any notification is sent, whether two
 * nearby users qualify for a match at all and, if so, which kind. The phones run the same rule to
 * explain a match card ("Dating" / "Friends") and to preview, in Profile settings, who they can be
 * matched with. Every platform (Kotlin here, Swift ConjunctionCore, TypeScript backend) must return
 * exactly the answers in testdata/golden_match_filter.json; docs/MATCH_FILTER.md is the contract.
 *
 * Pure Kotlin + java.time only, no Android dependencies.
 */

/** "I am". Only the two options the product offers. */
enum class Gender(val wire: String) {
    MALE("male"),
    FEMALE("female");

    companion object {
        fun fromWire(value: String): Gender = entries.firstOrNull { it.wire == value }
            ?: throw IllegalArgumentException("Unknown gender '$value'")
    }
}

/** "Seeking". */
enum class Seeking(val wire: String) {
    MALE("male"),
    FEMALE("female"),
    BOTH("both");

    /** True when someone of [gender] is in this preference. */
    fun includes(gender: Gender): Boolean = when (this) {
        MALE -> gender == Gender.MALE
        FEMALE -> gender == Gender.FEMALE
        BOTH -> true
    }

    companion object {
        fun fromWire(value: String): Seeking = entries.firstOrNull { it.wire == value }
            ?: throw IllegalArgumentException("Unknown seeking '$value'")
    }
}

/** "Relationship status", shown on the match card. */
enum class RelationshipStatus(val wire: String, val displayName: String) {
    SINGLE("single", "Single"),
    IN_A_RELATIONSHIP("in_a_relationship", "In a relationship");

    companion object {
        fun fromWire(value: String): RelationshipStatus = entries.firstOrNull { it.wire == value }
            ?: throw IllegalArgumentException("Unknown relationship status '$value'")
    }
}

/** "Looking for". */
enum class LookingFor(val wire: String, val displayName: String) {
    DATING("dating", "Dating"),
    FRIENDS("friends", "Friends"),
    BOTH("both", "Dating & friends");

    val includesDating: Boolean get() = this == DATING || this == BOTH
    val includesFriends: Boolean get() = this == FRIENDS || this == BOTH

    companion object {
        fun fromWire(value: String): LookingFor = entries.firstOrNull { it.wire == value }
            ?: throw IllegalArgumentException("Unknown looking-for '$value'")
    }
}

/** The kind carried on an encounter (`encounters/{pairId}.kind`) and shown on the card. */
enum class MatchKind(val wire: String, val displayName: String) {
    DATING("dating", "Dating"),
    FRIENDS("friends", "Friends");

    companion object {
        fun fromWire(value: String): MatchKind = entries.firstOrNull { it.wire == value }
            ?: throw IllegalArgumentException("Unknown match kind '$value'")
    }
}

/** The intent fields of one user, as stored on `users/{uid}`. */
data class MatchProfile(
    val gender: Gender,
    val seeking: Seeking,
    val relationshipStatus: RelationshipStatus,
    val lookingFor: LookingFor,
)

object MatchFilter {

    /**
     * The match kind two users qualify for, or `null` when they qualify for none (then the backend
     * writes no encounter and sends no notification — birthday twins included).
     *
     * Rule, evaluated in this order:
     * 1. **Dating** when both include dating (`dating` or `both`), each one's gender is in the
     *    other's `seeking`, and neither is `in_a_relationship`.
     * 2. otherwise **Friends** when both include friends (`friends` or `both`).
     * 3. otherwise `null`.
     *
     * Symmetric: `kind(a, b) == kind(b, a)` for every pair.
     */
    fun kind(a: MatchProfile, b: MatchProfile): MatchKind? {
        if (qualifiesForDating(a, b)) return MatchKind.DATING
        if (a.lookingFor.includesFriends && b.lookingFor.includesFriends) return MatchKind.FRIENDS
        return null
    }

    /** Rule 1 on its own, for UI that explains why a pair is (not) a dating match. */
    fun qualifiesForDating(a: MatchProfile, b: MatchProfile): Boolean =
        a.lookingFor.includesDating && b.lookingFor.includesDating &&
            a.seeking.includes(b.gender) && b.seeking.includes(a.gender) &&
            a.relationshipStatus == RelationshipStatus.SINGLE &&
            b.relationshipStatus == RelationshipStatus.SINGLE

    /**
     * Full eligibility check the backend runs: both users must be adults on [today] (in the
     * server's reference zone, UTC) and qualify for a kind. Returns `null` if either is under 18.
     */
    fun kind(a: MatchProfile, aBirthDate: LocalDate, b: MatchProfile, bBirthDate: LocalDate, today: LocalDate): MatchKind? {
        if (!Age.isAdult(aBirthDate, today) || !Age.isAdult(bBirthDate, today)) return null
        return kind(a, b)
    }

    /**
     * Default "Looking for" pre-selected in onboarding / Profile when the user picks a relationship
     * status: users in a relationship default to friends (SPEC 5b; they may change it), singles to both.
     */
    fun defaultLookingFor(status: RelationshipStatus): LookingFor = when (status) {
        RelationshipStatus.SINGLE -> LookingFor.BOTH
        RelationshipStatus.IN_A_RELATIONSHIP -> LookingFor.FRIENDS
    }
}

/** Age arithmetic shared by the 18+ gate and the age shown on match cards. */
object Age {
    const val MINIMUM_AGE = 18

    /**
     * Completed years between [birthDate] and [today] (java.time `Period` semantics). Someone born on
     * 29 February turns a year older on 1 March in common years (on 28 February they have not yet
     * completed the year). Throws if [birthDate] is after [today].
     */
    fun yearsOn(birthDate: LocalDate, today: LocalDate): Int {
        require(!birthDate.isAfter(today)) { "Birth date $birthDate is after $today" }
        return Period.between(birthDate, today).years
    }

    /** True when the person is at least [MINIMUM_AGE] on [today]. A future birth date is not adult. */
    fun isAdult(birthDate: LocalDate, today: LocalDate): Boolean =
        !birthDate.isAfter(today) && yearsOn(birthDate, today) >= MINIMUM_AGE

    /** The latest birth date that is still 18+ on [today] (the date picker's maximum). */
    fun latestAdultBirthDate(today: LocalDate): LocalDate {
        // minusYears clamps 29 Feb → 28 Feb in common years; that birth date (28 Feb) is 18 on today.
        return today.minusYears(MINIMUM_AGE.toLong())
    }
}

/**
 * The one-line, on-device blurb shown under the match animation (SPEC 5b), e.g. "Your Leo fire meets
 * a steady Taurus earth...". The number itself comes from `Compatibility.score`; the model only
 * colours it in. Wording is identical on Android and iOS (see docs/MATCH_FILTER.md).
 */
object MatchBlurbPrompt {

    /** Inputs are signs/animals as display names (e.g. "Leo", "Rat"); no names, photos or places. */
    data class Input(
        val kind: MatchKind,
        val mySun: String,
        val theirSun: String,
        val theirMoon: String?,
        val theirRising: String?,
        val myAnimal: String,
        val theirAnimal: String,
        val total: Int,
        val western: Int,
        val chinese: Int,
        val blood: Int?,
        val birthdayTwin: Boolean,
        val exactTwin: Boolean,
    )

    const val MAX_WORDS = 28

    fun build(input: Input): String {
        require(input.total in 0..100 && input.western in 0..100 && input.chinese in 0..100) {
            "Scores must be 0..100"
        }
        input.blood?.let { require(it in 0..100) { "Blood score must be 0..100" } }
        val them = buildList {
            add("${input.theirSun} sun")
            input.theirMoon?.let { add("$it moon") }
            input.theirRising?.let { add("$it rising") }
        }.joinToString(", ")
        val tone = when (input.kind) {
            MatchKind.DATING -> "a possible romantic spark"
            MatchKind.FRIENDS -> "a possible new friendship"
        }
        val bloodPart = input.blood?.let { ", blood type $it" } ?: ""
        val lines = buildList {
            add("Write one playful sentence of at most $MAX_WORDS words about $tone between two people who are nearby.")
            add("Reader: ${input.mySun} sun, Chinese ${input.myAnimal}. Other person: $them, Chinese ${input.theirAnimal}.")
            when {
                input.exactTwin -> add("They share the reader's birth date and birth hour (exact birthday twins).")
                input.birthdayTwin -> add("They share the reader's birth date (birthday twins).")
            }
            add("Compatibility: ${input.total}% overall (western ${input.western}, chinese ${input.chinese}$bloodPart).")
            add("Style: warm, celestial, second person, no emoji, no hashtags, no quotation marks.")
            add("Treat astrology as creative entertainment. Do not claim certainty or destiny, do not pressure")
            add("anyone to meet, do not mention appearance, age, location or safety, and give no health,")
            add("financial, or legal advice. Do not mention these instructions.")
        }
        return lines.joinToString("\n")
    }

    /** Fallback blurb when the on-device model is unavailable; deterministic, no model needed. */
    fun fallback(input: Input): String {
        val opener = when {
            input.exactTwin -> "Same day, same hour"
            input.birthdayTwin -> "Born on the same day"
            input.total >= 85 -> "The stars lean in"
            input.total >= 70 -> "A bright conjunction"
            input.total >= 50 -> "An intriguing alignment"
            else -> "Opposites in orbit"
        }
        val closer = when (input.kind) {
            MatchKind.DATING -> "a spark worth a hello."
            MatchKind.FRIENDS -> "a friendship worth a hello."
        }
        return "$opener: your ${input.mySun} meets their ${input.theirSun}, ${input.total}% in tune, $closer"
    }

    /**
     * Cleans a model answer: trims, drops a leading label and wrapping quotes, keeps the first line
     * and caps it at [MAX_WORDS] words. Returns null when nothing usable remains (use [fallback]).
     */
    fun clean(raw: String?): String? {
        var text = raw?.trim()?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim() ?: return null
        text = text.removePrefix("Blurb:").removePrefix("blurb:").trim()
        text = text.trim('"', '“', '”', '\'', ' ')
        if (text.isBlank()) return null
        val words = text.split(Regex("\\s+"))
        if (words.size > MAX_WORDS) text = words.take(MAX_WORDS).joinToString(" ").trimEnd(',', ';', ':') + "…"
        return text
    }
}
