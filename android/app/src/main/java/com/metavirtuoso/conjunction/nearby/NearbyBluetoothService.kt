package com.metavirtuoso.conjunction.nearby

import android.app.ForegroundServiceStartNotAllowedException
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
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
data class ServiceState(
    val running: Boolean = false,
    val paused: Boolean = false,
    val bluetoothOff: Boolean = false,
    val error: String? = null,
)

/**
 * Foreground service of type `connectedDevice` (FOREGROUND_SERVICE_CONNECTED_DEVICE) that keeps
 * the Bluetooth LE advertiser, GATT server and scanner running while the person has Nearby on
 * (SPEC 5c, store/play/PERMISSIONS.md section 2). Started only from the app's UI while it is
 * visible; never from boot; not sticky (if the system stops it, the app shows Nearby as not
 * running and resumes it the next time it is opened).
 *
 * Android 14+ requires a Bluetooth runtime permission before startForeground with this type, so
 * the service checks them first. The ongoing notification has Pause (1 h) / Resume and Turn off.
 * Bluetooth switched off: the radio stops and the notification says so; it restarts when
 * Bluetooth comes back.
 */
class NearbyBluetoothService : LifecycleService() {

    private val repo get() = (application as ConjunctionApp).container.nearby
    private var pauseJob: Job? = null
    private var receiverRegistered = false
    private var radioOn = false
    private var pausedUntil = 0L

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> lifecycleScope.launch {
                    _state.value = _state.value.copy(bluetoothOff = true)
                    stopRadio(graceful = false)
                    updateNotification()
                }
                BluetoothAdapter.STATE_ON -> lifecycleScope.launch {
                    _state.value = _state.value.copy(bluetoothOff = false)
                    applyState()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || !NearbyPermissions.granted(this)) {
            _state.value = ServiceState(error = getString(R.string.perm_bluetooth_denied))
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(this, Notifications.ID_NEARBY_SERVICE, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException (started from the background) or SecurityException.
            val notAllowed = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && e is ForegroundServiceStartNotAllowedException
            val msg = if (notAllowed) "Open the app to resume Nearby." else (e.message ?: "Nearby couldn't start.")
            _state.value = ServiceState(error = msg)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(this, bluetoothReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
            receiverRegistered = true
        }
        _state.value = _state.value.copy(running = true, error = null)
        when (intent?.action) {
            ACTION_STOP -> lifecycleScope.launch { repo.disable() }
            ACTION_PAUSE_HOUR -> lifecycleScope.launch { repo.pause(System.currentTimeMillis() + 3_600_000L) }
            ACTION_RESUME -> lifecycleScope.launch { repo.resume() }
            else -> lifecycleScope.launch { applyState() }
        }
        return START_NOT_STICKY
    }

    /** Re-reads the profile: off → stop; paused → radio off until the pause ends; else radio on. */
    private suspend fun applyState() {
        val p = repo.profileStore.current()
        if (!p.nearbyEnabled || !p.nearbyConsent) {
            stopSelf()
            return
        }
        val now = System.currentTimeMillis()
        pauseJob?.cancel()
        pausedUntil = if (p.isPaused(now)) p.nearbyPausedUntil else 0L
        if (p.isPaused(now)) {
            _state.value = _state.value.copy(paused = true)
            stopRadio(graceful = true)
            pauseJob = lifecycleScope.launch {
                delay(p.nearbyPausedUntil - now + 1_000)
                repo.resume()
            }
        } else {
            _state.value = _state.value.copy(paused = false)
            startRadio()
        }
        updateNotification()
    }

    private suspend fun startRadio() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val node = repo.node ?: return
        repo.refreshLocalProfile()
        when (node.startRadio()) {
            RadioStartResult.STARTED -> {
                radioOn = true
                _state.value = _state.value.copy(bluetoothOff = false, error = null)
            }
            RadioStartResult.BLUETOOTH_OFF -> _state.value = _state.value.copy(bluetoothOff = true)
            RadioStartResult.NO_PERMISSION -> {
                _state.value = _state.value.copy(error = getString(R.string.perm_bluetooth_denied))
                stopSelf()
            }
            RadioStartResult.UNSUPPORTED -> {
                _state.value = _state.value.copy(error = "This phone can't advertise over Bluetooth LE, so Nearby can't run.")
                stopSelf()
            }
        }
    }

    private suspend fun stopRadio(graceful: Boolean) {
        if (!radioOn || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        radioOn = false
        repo.node?.stopRadio(graceful)
    }

    private fun buildNotification(): android.app.Notification {
        val s = _state.value
        val text = when {
            s.bluetoothOff -> getString(R.string.nearby_bluetooth_off)
            s.paused && pausedUntil > 0 -> getString(R.string.nearby_paused_until, DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(pausedUntil)))
            else -> getString(R.string.nearby_fgs_notification_body)
        }
        return NotificationCompat.Builder(this, Notifications.CHANNEL_NEARBY_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_conjunction)
            .setColor(ContextCompat.getColor(this, R.color.gold))
            .setContentTitle(getString(R.string.nearby_fgs_notification_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(Notifications.openAppIntent(this, null, 41))
            .apply {
                if (s.paused) addAction(0, getString(R.string.nearby_resume), serviceIntent(this@NearbyBluetoothService, ACTION_RESUME, 44))
                else addAction(0, getString(R.string.nearby_pause_1h), serviceIntent(this@NearbyBluetoothService, ACTION_PAUSE_HOUR, 42))
            }
            .addAction(0, getString(R.string.nearby_turn_off), serviceIntent(this, ACTION_STOP, 43))
            .build()
    }

    private fun updateNotification() {
        if (!Notifications.canPost(this)) return
        try {
            NotificationManagerCompat.from(this).notify(Notifications.ID_NEARBY_SERVICE, buildNotification())
        } catch (_: SecurityException) {
        }
    }

    override fun onDestroy() {
        pauseJob?.cancel()
        if (receiverRegistered) runCatching { unregisterReceiver(bluetoothReceiver) }
        receiverRegistered = false
        if (radioOn && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            radioOn = false
            // The node outlives the service (it belongs to the repository): byes still go out.
            repo.node?.let { node -> node.post { node.transport.stop(graceful = true) } }
        }
        _state.value = _state.value.copy(running = false, paused = false)
        super.onDestroy()
    }

    companion object {
        private const val ACTION_STOP = "com.metavirtuoso.conjunction.nearby.STOP"
        private const val ACTION_PAUSE_HOUR = "com.metavirtuoso.conjunction.nearby.PAUSE_1H"
        private const val ACTION_RESUME = "com.metavirtuoso.conjunction.nearby.RESUME"

        private val _state = MutableStateFlow(ServiceState())
        val state: StateFlow<ServiceState> = _state.asStateFlow()

        private fun serviceIntent(context: Context, action: String, code: Int): PendingIntent =
            PendingIntent.getService(context, code, Intent(context, NearbyBluetoothService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        /** Must be called while the app is visible (Android 12+ background-start rules). */
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, NearbyBluetoothService::class.java))
            } catch (e: IllegalStateException) {
                _state.value = ServiceState(error = "Open the app to resume Nearby.")
            }
        }

        /** Re-reads the pause / on-off state (after Pause / Resume from the app). */
        fun refresh(context: Context) {
            if (_state.value.running) start(context)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, NearbyBluetoothService::class.java))
        }
    }
}
