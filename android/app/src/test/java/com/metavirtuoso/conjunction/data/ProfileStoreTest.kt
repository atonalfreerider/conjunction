package com.metavirtuoso.conjunction.data

import androidx.datastore.preferences.core.mutablePreferencesOf
import com.metavirtuoso.conjunction.core.blood.BloodType
import com.metavirtuoso.conjunction.core.blood.RhFactor
import com.metavirtuoso.conjunction.core.match.Gender
import com.metavirtuoso.conjunction.core.match.LookingFor
import com.metavirtuoso.conjunction.core.match.RelationshipStatus
import com.metavirtuoso.conjunction.core.match.Seeking
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProfileStoreTest {
    @Test
    fun encodeDecodeRoundTrip() {
        val profile = Profile(
            ageGateBirthDate = LocalDate.of(1990, 8, 15),
            legalAccepted = true,
            birth = BirthInput(LocalDate.of(1990, 8, 15), LocalTime.of(14, 30), 41.8781, -87.6298, "America/Chicago", "Chicago, Illinois, United States"),
            bloodType = BloodType.AB,
            rh = RhFactor.NEGATIVE,
            bloodForMatching = true,
            gender = Gender.FEMALE, seeking = Seeking.BOTH, relationshipStatus = RelationshipStatus.SINGLE, lookingFor = LookingFor.BOTH,
            orientationConsent = true,
            honestyConfirmed = true,
            displayName = "Ada Lovelace",
            bio = "Stargazer",
            nearbyConsent = true,
            nearbyEnabled = true,
            nearbyPausedUntil = 123L,
            onboardingComplete = true,
            interests = "jazz, hiking",
            instagram = "ada.stars",
            instagramShareDefault = false,
            notifyMatches = false,
            dailyReadingTime = LocalTime.of(7, 15),
            onboardingStep = "CARD",
        )
        val prefs = mutablePreferencesOf()
        ProfileStore.encode(prefs, profile)
        assertEquals(profile, ProfileStore.decode(prefs))
        assertEquals("Ada", profile.firstName)
        assertEquals(DatingProfile(Gender.FEMALE, Seeking.BOTH, RelationshipStatus.SINGLE, LookingFor.BOTH), profile.dating)
        assertEquals(true, profile.nearbyEligible)
    }

    /** SPEC 5d.1.4: a 2.0 profile (no new keys, honesty set) decodes with UX 2.0 defaults and stays eligible. */
    @Test
    fun legacyProfileMigrates() {
        val prefs = mutablePreferencesOf()
        val legacy = Profile(
            birth = BirthInput(LocalDate.of(1990, 8, 15), null, 1.0, 2.0, "UTC", "x"),
            gender = Gender.MALE, seeking = Seeking.FEMALE, relationshipStatus = RelationshipStatus.SINGLE, lookingFor = LookingFor.DATING,
            orientationConsent = true, honestyConfirmed = true, nearbyConsent = true, nearbyEnabled = true, onboardingComplete = true,
        )
        ProfileStore.encode(prefs, legacy)
        listOf("interests", "instagram", "instagram_share_default", "notify_matches", "notify_messages", "daily_reading_time", "land_on_chart", "onboarding_step")
            .forEach { prefs.remove(androidx.datastore.preferences.core.stringPreferencesKey(it)); prefs.remove(androidx.datastore.preferences.core.booleanPreferencesKey(it)) }
        val decoded = ProfileStore.decode(prefs)
        assertEquals(true, decoded.instagramShareDefault)
        assertEquals(LocalTime.of(8, 0), decoded.dailyReadingTime)
        assertNull(decoded.instagram)
        assertEquals(true, decoded.nearbyEligible)
        assertEquals(true, decoded.nearbyEnabled)
        // A partial dating profile is kept field by field.
        val partial = mutablePreferencesOf()
        ProfileStore.encode(partial, Profile(gender = Gender.FEMALE))
        assertEquals(Gender.FEMALE, ProfileStore.decode(partial).gender)
        assertNull(ProfileStore.decode(partial).dating)
    }

    @Test
    fun unknownTimeAndClearedFieldsSurvive() {
        val prefs = mutablePreferencesOf()
        val withTime = Profile(birth = BirthInput(LocalDate.of(2000, 1, 1), LocalTime.NOON, 0.0, 0.0, "UTC", "x"), bloodType = BloodType.O)
        ProfileStore.encode(prefs, withTime)
        ProfileStore.encode(prefs, withTime.copy(birth = withTime.birth!!.copy(time = null), bloodType = null))
        val decoded = ProfileStore.decode(prefs)
        assertNull(decoded.birth!!.time)
        assertNull(decoded.bloodType)
        assertNull(decoded.dating)
    }

    @Test
    fun chartsFingerprintChangesWithBirthData() {
        val birth = BirthInput(LocalDate.of(1990, 8, 15), LocalTime.of(14, 30), 41.8781, -87.6298, "America/Chicago", "Chicago")
        val a = Charts.compute(birth, null)
        val b = Charts.compute(birth.copy(time = LocalTime.of(14, 31)), null)
        val c = Charts.compute(birth, BloodType.A)
        assert(a.fingerprint != b.fingerprint)
        assert(a.fingerprint != c.fingerprint)
        assertEquals(a.fingerprint, Charts.compute(birth, null).fingerprint)
        assertNull(a.compatProfile(includeBlood = true).bloodType)
        assertEquals(BloodType.A, c.compatProfile(includeBlood = true).bloodType)
        assertNull(c.compatProfile(includeBlood = false).bloodType)
    }
}
