package com.metavirtuoso.conjunction.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.metavirtuoso.conjunction.core.blood.BloodType
import com.metavirtuoso.conjunction.core.blood.RhFactor
import com.metavirtuoso.conjunction.core.match.Gender
import com.metavirtuoso.conjunction.core.match.LookingFor
import com.metavirtuoso.conjunction.core.match.RelationshipStatus
import com.metavirtuoso.conjunction.core.match.Seeking
import java.io.IOException
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Sensitive, on-device profile (birth data, blood type, consents). Excluded from backups. */
private val Context.profileDataStore by preferencesDataStore(name = "profile")

/**
 * Typed access to the owner's [Profile]. All writes go through [update] so the whole profile is
 * read-modify-written atomically.
 */
class ProfileStore(private val store: DataStore<Preferences>) {
    constructor(context: Context) : this(context.applicationContext.profileDataStore)

    val profile: Flow<Profile> = store.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map(::decode)

    suspend fun current(): Profile = profile.first()

    suspend fun update(transform: (Profile) -> Profile) {
        store.edit { prefs -> encode(prefs, transform(decode(prefs))) }
    }

    /** Erases everything (Delete account / reset). */
    suspend fun clear() {
        store.edit { it.clear() }
    }

    private object K {
        val ageGateDob = stringPreferencesKey("age_gate_dob")
        val ageBlocked = booleanPreferencesKey("age_blocked")
        val legalAccepted = booleanPreferencesKey("legal_accepted")
        val birthDate = stringPreferencesKey("birth_date")
        val birthTime = stringPreferencesKey("birth_time")
        val birthLat = doublePreferencesKey("birth_lat")
        val birthLon = doublePreferencesKey("birth_lon")
        val birthZone = stringPreferencesKey("birth_zone")
        val birthPlace = stringPreferencesKey("birth_place")
        val blood = stringPreferencesKey("blood_group")
        val rh = stringPreferencesKey("blood_rh")
        val bloodForMatching = booleanPreferencesKey("blood_for_matching")
        val gender = stringPreferencesKey("gender")
        val seeking = stringPreferencesKey("seeking")
        val status = stringPreferencesKey("relationship_status")
        val lookingFor = stringPreferencesKey("looking_for")
        val orientationConsent = booleanPreferencesKey("orientation_consent")
        val honesty = booleanPreferencesKey("honesty_confirmed")
        val displayName = stringPreferencesKey("display_name")
        val bio = stringPreferencesKey("bio")
        val photoPath = stringPreferencesKey("photo_path")
        /** "nearby_consent" was the server build's location consent; Bluetooth Nearby needs a new one. */
        val nearbyConsent = booleanPreferencesKey("nearby_bluetooth_consent")
        val nearbyEnabled = booleanPreferencesKey("nearby_enabled")
        val pausedUntil = longPreferencesKey("nearby_paused_until")
        val notificationsPrompted = booleanPreferencesKey("notifications_prompted")
        val onboardingComplete = booleanPreferencesKey("onboarding_complete")
        val safetyTipsSeen = booleanPreferencesKey("safety_tips_seen")
        val interests = stringPreferencesKey("interests")
        val instagram = stringPreferencesKey("instagram")
        val instagramShareDefault = booleanPreferencesKey("instagram_share_default")
        val notifyMatches = booleanPreferencesKey("notify_matches")
        val notifyMessages = booleanPreferencesKey("notify_messages")
        val dailyTime = stringPreferencesKey("daily_reading_time")
        val landOnChart = booleanPreferencesKey("land_on_chart")
        val onboardingStep = stringPreferencesKey("onboarding_step")
    }

    companion object {
        fun decode(p: Preferences): Profile {
            val birth = run {
                val date = p[K.birthDate]?.let(LocalDate::parse) ?: return@run null
                val zone = p[K.birthZone] ?: return@run null
                BirthInput(
                    date = date,
                    time = p[K.birthTime]?.let(LocalTime::parse),
                    latitude = p[K.birthLat] ?: return@run null,
                    longitude = p[K.birthLon] ?: return@run null,
                    zoneId = zone,
                    placeName = p[K.birthPlace].orEmpty(),
                )
            }
            // Each dating field on its own (UX 2.0 onboarding may set only some of them).
            fun <T> wire(v: String?, parse: (String) -> T): T? = v?.let { runCatching { parse(it) }.getOrNull() }
            return Profile(
                ageGateBirthDate = p[K.ageGateDob]?.let(LocalDate::parse),
                ageBlocked = p[K.ageBlocked] ?: false,
                legalAccepted = p[K.legalAccepted] ?: false,
                birth = birth,
                bloodType = p[K.blood]?.let(BloodType::parse),
                rh = p[K.rh]?.let { sign -> RhFactor.entries.firstOrNull { it.name == sign } },
                bloodForMatching = p[K.bloodForMatching] ?: false,
                gender = wire(p[K.gender], Gender::fromWire),
                seeking = wire(p[K.seeking], Seeking::fromWire),
                relationshipStatus = wire(p[K.status], RelationshipStatus::fromWire),
                lookingFor = wire(p[K.lookingFor], LookingFor::fromWire),
                orientationConsent = p[K.orientationConsent] ?: false,
                honestyConfirmed = p[K.honesty] ?: false,
                displayName = p[K.displayName].orEmpty(),
                bio = p[K.bio].orEmpty(),
                localPhotoPath = p[K.photoPath],
                nearbyConsent = p[K.nearbyConsent] ?: false,
                nearbyEnabled = p[K.nearbyEnabled] ?: false,
                nearbyPausedUntil = p[K.pausedUntil] ?: 0L,
                notificationsPrompted = p[K.notificationsPrompted] ?: false,
                onboardingComplete = p[K.onboardingComplete] ?: false,
                safetyTipsSeen = p[K.safetyTipsSeen] ?: false,
                interests = p[K.interests].orEmpty(),
                instagram = p[K.instagram],
                instagramShareDefault = p[K.instagramShareDefault] ?: true,
                notifyMatches = p[K.notifyMatches] ?: true,
                notifyMessages = p[K.notifyMessages] ?: true,
                dailyReadingTime = p[K.dailyTime]?.let { runCatching { LocalTime.parse(it) }.getOrNull() } ?: LocalTime.of(8, 0),
                landOnChart = p[K.landOnChart] ?: false,
                onboardingStep = p[K.onboardingStep],
            )
        }

        fun encode(p: MutablePreferences, v: Profile) {
            fun <T> put(key: Preferences.Key<T>, value: T?) {
                if (value == null) p.remove(key) else p[key] = value
            }
            put(K.ageGateDob, v.ageGateBirthDate?.toString())
            put(K.ageBlocked, v.ageBlocked)
            put(K.legalAccepted, v.legalAccepted)
            put(K.birthDate, v.birth?.date?.toString())
            put(K.birthTime, v.birth?.time?.toString())
            put(K.birthLat, v.birth?.latitude)
            put(K.birthLon, v.birth?.longitude)
            put(K.birthZone, v.birth?.zoneId)
            put(K.birthPlace, v.birth?.placeName)
            put(K.blood, v.bloodType?.name)
            put(K.rh, v.rh?.name)
            put(K.bloodForMatching, v.bloodForMatching)
            put(K.gender, v.gender?.wire)
            put(K.seeking, v.seeking?.wire)
            put(K.status, v.relationshipStatus?.wire)
            put(K.lookingFor, v.lookingFor?.wire)
            put(K.orientationConsent, v.orientationConsent)
            put(K.honesty, v.honestyConfirmed)
            put(K.displayName, v.displayName)
            put(K.bio, v.bio)
            put(K.photoPath, v.localPhotoPath)
            put(K.nearbyConsent, v.nearbyConsent)
            put(K.nearbyEnabled, v.nearbyEnabled)
            put(K.pausedUntil, v.nearbyPausedUntil)
            put(K.notificationsPrompted, v.notificationsPrompted)
            put(K.onboardingComplete, v.onboardingComplete)
            put(K.safetyTipsSeen, v.safetyTipsSeen)
            put(K.interests, v.interests)
            put(K.instagram, v.instagram)
            put(K.instagramShareDefault, v.instagramShareDefault)
            put(K.notifyMatches, v.notifyMatches)
            put(K.notifyMessages, v.notifyMessages)
            put(K.dailyTime, v.dailyReadingTime.toString())
            put(K.landOnChart, v.landOnChart)
            put(K.onboardingStep, v.onboardingStep)
        }
    }
}
