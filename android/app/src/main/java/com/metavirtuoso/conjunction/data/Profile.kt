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

/** The dating/friends fields from SPEC 5b, plus the separate explicit consents they need. */
data class DatingProfile(
    val gender: Gender,
    val seeking: Seeking,
    val relationshipStatus: RelationshipStatus,
    val lookingFor: LookingFor,
) {
    fun toMatchProfile() = MatchProfile(gender, seeking, relationshipStatus, lookingFor)
}

enum class PhotoState(val wire: String) {
    NONE("none"), PENDING("pending"), APPROVED("approved"), REJECTED("rejected");

    companion object {
        fun fromWire(value: String?): PhotoState = entries.firstOrNull { it.wire == value } ?: NONE
    }
}

/** Everything the app remembers about its owner. Lives in the "profile" DataStore (excluded from backup). */
data class Profile(
    val ageGateBirthDate: LocalDate? = null,
    /** Set when the age gate saw an under-18 date: onboarding stays blocked on this install. */
    val ageBlocked: Boolean = false,
    val legalAccepted: Boolean = false,
    val birth: BirthInput? = null,
    val bloodType: BloodType? = null,
    /** Optional Rh factor; null = not given. Shown only in the owner's own reading. */
    val rh: RhFactor? = null,
    /** CONSENT_BLOOD_TYPE_MATCHING: blood type may be uploaded for Nearby scores. */
    val bloodForMatching: Boolean = false,
    val dating: DatingProfile? = null,
    /** CONSENT_ORIENTATION_DATA: required before any dating field leaves the phone. */
    val orientationConsent: Boolean = false,
    val honestyConfirmed: Boolean = false,
    val displayName: String = "",
    val bio: String = "",
    val photoState: PhotoState = PhotoState.NONE,
    val localPhotoPath: String? = null,
    /** CONSENT_NEARBY_LOCATION recorded. */
    val nearbyConsent: Boolean = false,
    val nearbyEnabled: Boolean = false,
    val nearbyPausedUntil: Long = 0L,
    val notificationsPrompted: Boolean = false,
    val onboardingComplete: Boolean = false,
    val safetyTipsSeen: Boolean = false,
) {
    val firstName: String get() = displayName.trim().substringBefore(' ')

    /** Whether Nearby can be used at all: dating consent + profile, 18+, honesty and location consent. */
    val nearbyEligible: Boolean get() = dating != null && orientationConsent && honestyConfirmed && birth != null

    fun isPaused(nowMillis: Long): Boolean = nearbyPausedUntil > nowMillis
}
