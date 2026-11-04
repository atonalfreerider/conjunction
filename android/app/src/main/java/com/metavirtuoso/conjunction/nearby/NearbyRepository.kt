package com.metavirtuoso.conjunction.nearby

import android.content.Context
import com.metavirtuoso.conjunction.data.Charts
import com.metavirtuoso.conjunction.data.ProfileStore
import com.metavirtuoso.conjunction.legal.ConsentLedger
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Nearby on/off, pause, and keeping the server's copy of the profile in step with the phone.
 * Only the derived compatibility summary, the dating fields (with their consent), display name,
 * bio and consents are uploaded; birth time and place never are.
 */
class NearbyRepository(
    private val context: Context,
    private val api: NearbyApi,
    private val profiles: ProfileStore,
    private val ledger: ConsentLedger,
) {
    private val _events = MutableSharedFlow<PushEvent>(extraBufferCapacity = 8)
    /** Events to show in-app (full-screen popup) while the app is in the foreground. */
    val events: SharedFlow<PushEvent> = _events.asSharedFlow()

    fun emit(event: PushEvent) {
        _events.tryEmit(event)
    }

    /** Uploads consents not yet on the server. */
    suspend fun syncConsents() {
        val all = ledger.all()
        val pending = ledger.pendingUpload()
        if (pending.isEmpty()) return
        api.recordConsents(pending)
        ledger.markUploaded(all.size)
    }

    /** Pushes the current profile + chart summary to the server. */
    suspend fun syncProfile(charts: Charts?) {
        val p = profiles.current()
        val dating = p.dating.takeIf { p.orientationConsent }
        api.updateProfile(
            displayName = p.displayName.trim().takeIf { it.isNotEmpty() },
            bio = p.bio.trim(),
            gender = dating?.gender?.wire,
            seeking = dating?.seeking?.wire,
            relationshipStatus = dating?.relationshipStatus?.wire,
            lookingFor = dating?.lookingFor?.wire,
            compat = charts?.compatProfile(includeBlood = p.bloodForMatching),
        )
    }

    /**
     * Turns Nearby on: sign in, upload consents and profile, register for pushes, tell the server,
     * then start the location service. The caller must already hold location permission and be in
     * the foreground.
     */
    suspend fun enable(charts: Charts?) {
        val p = profiles.current()
        check(p.nearbyEligible && p.nearbyConsent) { "Finish your Nearby profile first." }
        requireNotNull(charts) { "Add your birth details first." }
        api.ensureSignedIn()
        syncConsents()
        syncProfile(charts)
        runCatching { api.enablePush() }
        api.setNearby(enabled = true, pausedUntil = null)
        profiles.update { it.copy(nearbyEnabled = true, nearbyPausedUntil = 0L) }
        NearbyService.start(context)
    }

    /** Turns Nearby off everywhere; the server deletes the latest position immediately. */
    suspend fun disable() {
        profiles.update { it.copy(nearbyEnabled = false, nearbyPausedUntil = 0L) }
        NearbyService.stop(context)
        if (api.isConfigured && api.uid != null) runCatching { api.setNearby(enabled = false) }
    }

    suspend fun pause(untilMillis: Long) {
        profiles.update { it.copy(nearbyPausedUntil = untilMillis) }
        if (api.isConfigured) runCatching { api.setNearby(enabled = true, pausedUntil = Instant.ofEpochMilli(untilMillis)) }
        NearbyService.refresh(context)
    }

    suspend fun resume() {
        profiles.update { it.copy(nearbyPausedUntil = 0L) }
        if (api.isConfigured) runCatching { api.setNearby(enabled = true, pausedUntil = null) }
        NearbyService.refresh(context)
    }

    /** When the app comes to the foreground: restart the service if Nearby is on but not running. */
    suspend fun resumeServiceIfNeeded(hasPermission: Boolean) {
        val p = profiles.current()
        if (p.nearbyEnabled && api.isConfigured && hasPermission && !NearbyService.state.value.running) {
            runCatching { NearbyService.start(context) }
        }
    }

    companion object {
        /** "Pause until tomorrow" = 06:00 local tomorrow. */
        fun tomorrowMorning(zone: ZoneId = ZoneId.systemDefault()): Long =
            LocalDate.now(zone).plusDays(1).atTime(6, 0).atZone(zone).toInstant().toEpochMilli()
    }
}
