package com.metavirtuoso.conjunction.data

import com.metavirtuoso.conjunction.core.blood.BloodType
import com.metavirtuoso.conjunction.core.blood.RhFactor
import com.metavirtuoso.conjunction.core.chart.BirthData
import com.metavirtuoso.conjunction.core.match.Gender
import com.metavirtuoso.conjunction.core.match.LookingFor
import com.metavirtuoso.conjunction.core.match.MatchProfile
import com.metavirtuoso.conjunction.core.match.RelationshipStatus
import com.metavirtuoso.conjunction.core.match.Seeking
import java.time.LocalDate
import java.time.LocalTime

/** Birth details as entered on the phone (never uploaded). */
data class BirthInput(
    val date: LocalDate,
    val time: LocalTime?,
    val latitude: Double,
    val longitude: Double,
    val zoneId: String,
    val placeName: String,
) {
    fun toBirthData(): BirthData = BirthData(date, time, latitude, longitude, zoneId, placeName)
}

/** The four dating/friends fields from SPEC 5b, all set. */
data class DatingProfile(
    val gender: Gender,
    val seeking: Seeking,
    val relationshipStatus: RelationshipStatus,
    val lookingFor: LookingFor,
) {
    fun toMatchProfile() = MatchProfile(gender, seeking, relationshipStatus, lookingFor)
}

/**
 * Everything the app remembers about its owner. Lives in the "profile" DataStore (excluded from backup).
 *
 * UX 2.0 (SPEC 5d): the dating fields are kept one by one (onboarding screen 3 may fill only some of
 * them; the Nearby sheet collects the rest), [dating] is the complete set. `honestyConfirmed` and
 * `legalAccepted` are kept for old records but no longer gate anything.
 */
data class Profile(
    val ageGateBirthDate: LocalDate? = null,
    /** Set when an under-18 birth date was entered: the app stays blocked on this install. */
    val ageBlocked: Boolean = false,
    val legalAccepted: Boolean = false,
    val birth: BirthInput? = null,
    val bloodType: BloodType? = null,
    /** Optional Rh factor; null = not given. Shown only in the owner's own reading. */
    val rh: RhFactor? = null,
    /** CONSENT_BLOOD_TYPE_MATCHING (L3 switch): blood type may be sent to nearby phones. */
    val bloodForMatching: Boolean = false,
    val gender: Gender? = null,
    val seeking: Seeking? = null,
    val relationshipStatus: RelationshipStatus? = null,
    val lookingFor: LookingFor? = null,
    /** CONSENT_ORIENTATION_DATA recorded and not withdrawn (half of the L2 Nearby consent). */
    val orientationConsent: Boolean = false,
    val honestyConfirmed: Boolean = false,
    val displayName: String = "",
    val bio: String = "",
    /** Free-text interests from onboarding screen 3 (feeds "Write one for me"); stays on the phone. */
    val interests: String = "",
    /** The owner's profile photo (JPEG in app storage). Sent only to Nearby matches. */
    val localPhotoPath: String? = null,
    /** Normalised Instagram username (InstagramHandle.normalize) or null. Sent only to friends, encrypted. */
    val instagram: String? = null,
    /** Settings › Nearby "Share my Instagram with new matches" (the per-friend switch's default). */
    val instagramShareDefault: Boolean = true,
    /** CONSENT_NEARBY_BLUETOOTH recorded and not withdrawn (the other half of the L2 consent). */
    val nearbyConsent: Boolean = false,
    val nearbyEnabled: Boolean = false,
    val nearbyPausedUntil: Long = 0L,
    /** The notification permission was asked once (never ask again; Today's card stops showing). */
    val notificationsPrompted: Boolean = false,
    val notifyMatches: Boolean = true,
    val notifyMessages: Boolean = true,
    /** Daily reading time (local), default 08:00. */
    val dailyReadingTime: LocalTime = LocalTime.of(8, 0),
    val onboardingComplete: Boolean = false,
    /** UX 2.0 onboarding screen to resume at ("BIRTH", "ABOUT", "CARD"); null = legacy mapping. */
    val onboardingStep: String? = null,
    /** Set by onboarding's Done: the first main screen is Chart (the wheel draws itself) once. */
    val landOnChart: Boolean = false,
    val safetyTipsSeen: Boolean = false,
) {
    val firstName: String get() = displayName.trim().substringBefore(' ')

    val dating: DatingProfile?
        get() = if (gender != null && seeking != null && relationshipStatus != null && lookingFor != null) {
            DatingProfile(gender, seeking, relationshipStatus, lookingFor)
        } else null

    val datingFieldsComplete: Boolean get() = dating != null

    fun withDating(d: DatingProfile?): Profile =
        copy(gender = d?.gender, seeking = d?.seeking, relationshipStatus = d?.relationshipStatus, lookingFor = d?.lookingFor)

    /** L2 given: both Nearby consent records present and not withdrawn (SPEC 5d.1.4). */
    val nearbyConsentGiven: Boolean get() = orientationConsent && nearbyConsent

    /** SPEC 5d.1.4: birth data, the four dating fields and the L2 consent. */
    val nearbyEligible: Boolean get() = birth != null && datingFieldsComplete && nearbyConsentGiven

    fun isPaused(nowMillis: Long): Boolean = nearbyPausedUntil > nowMillis
}
