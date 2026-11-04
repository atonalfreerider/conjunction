package com.metavirtuoso.conjunction.nearby

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.location.Location
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.metavirtuoso.conjunction.ConjunctionApp
import com.metavirtuoso.conjunction.R
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What the Nearby service is doing, for the Nearby screen. */
data class NearbyRuntime(
    val running: Boolean = false,
    val paused: Boolean = false,
    val lastReportAt: Long? = null,
    val lastStatus: String? = null,
    val nearbyCount: Int = 0,
    val error: String? = null,
)

/**
 * Foreground service (type "location") that runs only while the person has Nearby switched on.
 * It is started from the app's UI (so "while in use" location applies; the app never asks for
 * background location) and shows a persistent notification with Pause and Turn off actions.
 *
 * Location: FusedLocationProvider, balanced power, every 60 s or 25 m of movement. Each fix is
 * sent with updatePresence; the server keeps only the latest point for 10 minutes. While paused
 * the service stops location updates and resumes by itself when the pause ends.
 * Not sticky: if the system kills it, it stays off until the app is opened again.
 */
class NearbyService : LifecycleService() {

    private val fused by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private var updatesActive = false
    private var pauseJob: Job? = null
    private val container get() = (application as ConjunctionApp).container

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let(::report)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> {
                lifecycleScope.launch { container.nearby.disable() }
                return START_NOT_STICKY
            }
            ACTION_PAUSE_HOUR -> {
                lifecycleScope.launch { container.nearby.pause(System.currentTimeMillis() + 3_600_000L) }
            }
        }
        if (!hasLocationPermission()) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(this, Notifications.ID_NEARBY_SERVICE, buildNotification(null),
                // Service types exist from API 29; ServiceCompat ignores the argument below that.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0,
            )
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException / SecurityException when not started from the foreground.
            _state.value = _state.value.copy(running = false, error = "Open the app to resume Nearby.")
            stopSelf()
            return START_NOT_STICKY
        }
        _state.value = _state.value.copy(running = true, error = null)
        lifecycleScope.launch { applyPauseState() }
        return START_NOT_STICKY
    }

    private suspend fun applyPauseState() {
        val profile = container.profileStore.current()
        if (!profile.nearbyEnabled) {
            stopSelf()
            return
        }
        val now = System.currentTimeMillis()
        if (profile.isPaused(now)) {
            stopUpdates()
            _state.value = _state.value.copy(paused = true)
            updateNotification(profile.nearbyPausedUntil)
            pauseJob?.cancel()
            pauseJob = lifecycleScope.launch {
                delay(profile.nearbyPausedUntil - now + 1_000)
                container.nearby.resume()
                applyPauseState()
            }
        } else {
            _state.value = _state.value.copy(paused = false)
            updateNotification(null)
            startUpdates()
        }
    }

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun startUpdates() {
        if (updatesActive) return
        val request = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, INTERVAL_MS)
            .setMinUpdateIntervalMillis(INTERVAL_MS / 2)
            .setMinUpdateDistanceMeters(MIN_DISTANCE_M)
            .setWaitForAccurateLocation(false)
            .build()
        try {
            fused.requestLocationUpdates(request, callback, Looper.getMainLooper())
            updatesActive = true
            fused.lastLocation.addOnSuccessListener { it?.let(::report) }
        } catch (e: SecurityException) {
            _state.value = _state.value.copy(error = getString(R.string.perm_location_denied))
            stopSelf()
        }
    }

    private fun stopUpdates() {
        if (!updatesActive) return
        fused.removeLocationUpdates(callback)
        updatesActive = false
    }

    private var lastSentAt = 0L

    private fun report(location: Location) {
        val now = System.currentTimeMillis()
        if (now - lastSentAt < MIN_REPORT_GAP_MS) return
        lastSentAt = now
        lifecycleScope.launch {
            try {
                val result = container.nearbyApi.updatePresence(location.latitude, location.longitude, if (location.hasAccuracy()) location.accuracy else null)
                _state.value = _state.value.copy(lastReportAt = now, lastStatus = result.status, nearbyCount = result.nearbyCount, error = null)
                if (result.status == "paused") applyPauseState()
            } catch (e: NearbyException) {
                _state.value = _state.value.copy(error = e.message)
                if (e.code == "failed-precondition") container.nearby.disable()
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = "Can't reach Nearby right now. Will retry.")
            }
        }
    }

    private fun buildNotification(pausedUntil: Long?) = NotificationCompat.Builder(this, Notifications.CHANNEL_NEARBY_SERVICE)
        .setSmallIcon(R.drawable.ic_stat_conjunction)
        .setColor(ContextCompat.getColor(this, R.color.gold))
        .setContentTitle(getString(R.string.nearby_fgs_notification_title))
        .setContentText(
            if (pausedUntil != null) getString(R.string.nearby_paused_until, DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(pausedUntil)))
            else getString(R.string.nearby_fgs_notification_body),
        )
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .setContentIntent(Notifications.openAppIntent(this, null, 41))
        .apply {
            if (pausedUntil == null) addAction(0, getString(R.string.nearby_pause_1h), serviceIntent(this@NearbyService, ACTION_PAUSE_HOUR, 42))
        }
        .addAction(0, getString(R.string.nearby_turn_off), serviceIntent(this, ACTION_STOP, 43))
        .build()

    private fun updateNotification(pausedUntil: Long?) {
        if (Notifications.canPost(this)) {
            try {
                androidx.core.app.NotificationManagerCompat.from(this).notify(Notifications.ID_NEARBY_SERVICE, buildNotification(pausedUntil))
            } catch (_: SecurityException) {
            }
        }
    }

    override fun onDestroy() {
        stopUpdates()
        pauseJob?.cancel()
        _state.value = _state.value.copy(running = false, paused = false)
        super.onDestroy()
    }

    companion object {
        const val INTERVAL_MS = 60_000L
        const val MIN_DISTANCE_M = 25f
        /** Never report more often than this, whatever the provider delivers. */
        private const val MIN_REPORT_GAP_MS = 20_000L
        private const val ACTION_STOP = "com.metavirtuoso.conjunction.nearby.STOP"
        private const val ACTION_PAUSE_HOUR = "com.metavirtuoso.conjunction.nearby.PAUSE_1H"

        private val _state = MutableStateFlow(NearbyRuntime())
        val state: StateFlow<NearbyRuntime> = _state.asStateFlow()

        private fun serviceIntent(context: Context, action: String, code: Int): PendingIntent =
            PendingIntent.getService(context, code, Intent(context, NearbyService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        /** Must be called while the app is in the foreground (while-in-use location rule). */
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, NearbyService::class.java))
        }

        /** Re-reads the pause state (after Pause / Resume from the app). */
        fun refresh(context: Context) {
            if (_state.value.running) context.startService(Intent(context, NearbyService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, NearbyService::class.java))
        }
    }
}
