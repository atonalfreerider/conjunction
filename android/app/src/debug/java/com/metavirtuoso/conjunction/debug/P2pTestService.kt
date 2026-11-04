package com.metavirtuoso.conjunction.debug

import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.metavirtuoso.conjunction.R
import com.metavirtuoso.conjunction.ai.GeminiNanoModel
import com.metavirtuoso.conjunction.core.match.Gender
import com.metavirtuoso.conjunction.core.match.LookingFor
import com.metavirtuoso.conjunction.core.match.MatchProfile
import com.metavirtuoso.conjunction.core.match.RelationshipStatus
import com.metavirtuoso.conjunction.core.match.Seeking
import com.metavirtuoso.conjunction.core.p2p.WireCompat
import com.metavirtuoso.conjunction.data.BirthInput
import com.metavirtuoso.conjunction.data.Charts
import com.metavirtuoso.conjunction.nearby.BleTransport
import com.metavirtuoso.conjunction.nearby.NearbyPermissions
import com.metavirtuoso.conjunction.nearby.ContentScreener
import com.metavirtuoso.conjunction.nearby.KeystoreIdentity
import com.metavirtuoso.conjunction.nearby.LocalPeerProfile
import com.metavirtuoso.conjunction.nearby.NearbyBluetoothService
import com.metavirtuoso.conjunction.nearby.NearbyEngine
import com.metavirtuoso.conjunction.nearby.NearbyEvent
import com.metavirtuoso.conjunction.nearby.NearbyHost
import com.metavirtuoso.conjunction.nearby.Notifications
import com.metavirtuoso.conjunction.nearby.P2pNode
import com.metavirtuoso.conjunction.nearby.P2pStore
import com.metavirtuoso.conjunction.nearby.ChatLine
import com.metavirtuoso.conjunction.nearby.ChatStatus
import com.metavirtuoso.conjunction.core.p2p.SwipeDirection
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * DEBUG BUILDS ONLY (src/debug): runs the real Nearby stack (BleTransport + NearbyEngine + core
 * P2pLink, Keystore identity, local notifications) as a fixed test persona, **"Vega"**, with its
 * own store (`files/datastore/p2p_debug.json`, `files/p2p_debug/`) and its own Keystore alias, so
 * it never reads or changes the owner's profile, friends or chats. Driven from the laptop with adb
 * (the service is protected by android.permission.DUMP, which only the shell and system hold):
 *
 * ```
 * S="-n com.metavirtuoso.conjunction/.debug.P2pTestService"
 * adb shell am start-foreground-service $S -a start [--ei rssi -90]
 * adb shell am start-foreground-service $S -a swipe --es dir right      # newest pending card
 * adb shell am start-foreground-service $S -a chat --es text "hello"    # newest friend (queued if away)
 * adb shell am start-foreground-service $S -a status                    # dump store to logcat
 * adb shell am start-foreground-service $S -a stop
 * adb shell am start-foreground-service $S -a reset                     # wipe the test persona's data + key
 * adb logcat -s ConjunctionP2P ConjunctionP2PTest
 * ```
 * Persona Vega: male, seeking women, single, looking for dating and friends, born 1992-05-21
 * 09:15 in Paris, with a generated 480x640 test photo. Refuses to run while the real Nearby
 * service is on.
 */
@SuppressLint("NewApi") // guarded by the SDK_INT check in onStartCommand; debug builds only
class P2pTestService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var node: P2pNode? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = NotificationCompat.Builder(this, Notifications.CHANNEL_NEARBY_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_conjunction)
            .setContentTitle("Nearby test peer (debug)")
            .setContentText("Bluetooth test persona Vega is running.")
            .setOngoing(true)
            .build()
        ServiceCompat.startForeground(this, ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return stop("needs Android 12+")
        val action = intent?.action ?: "start"
        log("command: $action")
        when (action) {
            "start" -> {
                if (NearbyBluetoothService.state.value.running) return stop("refusing: the real Nearby service is running")
                if (!NearbyPermissions.granted(this)) return stop("missing Bluetooth permissions (pm grant BLUETOOTH_SCAN/ADVERTISE/CONNECT)")
                val n = node ?: createNode().also { node = it }
                intent?.getIntExtra("rssi", Int.MIN_VALUE)?.takeIf { it != Int.MIN_VALUE }?.let { r -> n.post { n.transport.gate.rssiThreshold = r } }
                n.transport.scanMode = android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_LATENCY
                scope.launch { log("radio: ${n.startRadio()}") }
            }
            "swipe" -> withNode { n ->
                val dir = if (intent?.getStringExtra("dir") == "left") SwipeDirection.LEFT else SwipeDirection.RIGHT
                n.post {
                    val enc = n.store.state.value.encounters.filter { it.friendId == null && it.mySwipe == null }.maxByOrNull { it.createdAt }
                    if (enc == null) log("swipe: no pending card") else {
                        log("swipe ${dir.wire} on ${enc.firstName ?: "?"} (${enc.fingerprint.take(8)})")
                        n.engine.swipe(enc.fingerprint, dir)
                    }
                }
            }
            "chat" -> withNode { n ->
                val text = intent?.getStringExtra("text")?.takeIf { it.isNotBlank() } ?: "Hello from Vega (Pixel test peer)"
                n.post {
                    val friend = n.store.state.value.friends.filter { !it.blocked }.maxByOrNull { it.since }
                    if (friend == null) log("chat: no friend yet") else {
                        val line = ChatLine(NearbyEngine.newMessageId(), friend.id, mine = true, text = text, ts = System.currentTimeMillis(), status = ChatStatus.QUEUED)
                        n.store.update { s -> s.copy(messages = s.messages + line) }
                        log("chat queued ${line.id.take(8)} to ${friend.firstName}: $text")
                        n.engine.deliver(friend.id)
                        if (friend.id !in n.engine.status.value.friendsInRange) n.transport.expediteReconnect()
                    }
                }
            }
            "status" -> withNode { n -> n.post { dump(n) } }
            "stop" -> {
                node?.let { n -> scope.launch { n.stopRadio(graceful = true); log("radio stopped") } }
            }
            "reset" -> {
                val n = node ?: createNode().also { node = it }
                scope.launch {
                    n.stopRadio(graceful = false)
                    n.store.clear()
                    KeystoreIdentity.delete(ALIAS)
                    log("test persona data and key deleted")
                }
            }
            else -> log("unknown command $action")
        }
        return START_NOT_STICKY
    }

    private fun withNode(block: (P2pNode) -> Unit) {
        val n = node ?: return log("not started")
        block(n)
    }

    private fun stop(why: String): Int {
        log(why)
        stopSelf()
        return START_NOT_STICKY
    }

    private fun dump(n: P2pNode) {
        val s = n.store.state.value
        log("status: links=${n.engine.openLinkCount} radio=${n.radioStatus.value} engine=${n.engineStatus.value}")
        s.encounters.forEach { e ->
            log("  card ${e.fingerprint.take(8)} ${e.firstName} ${e.kind} ${e.total}% (W${e.western} C${e.chinese} B${e.blood}) twin=${e.birthdayTwin}/${e.exactTwin} age=${e.age} sun=${e.sun} photo=${e.photoPath != null}/${e.photoScreening} mine=${e.mySwipe} peer=${e.peerSwipe} friend=${e.friendId?.take(8)}")
        }
        s.friends.forEach { f -> log("  friend ${f.id.take(8)} ${f.firstName} ${f.kind} safety=${f.safetyCode} blocked=${f.blocked}") }
        s.messages.forEach { m -> log("  msg ${m.id.take(8)} ${if (m.mine) "->" else "<-"} ${m.status} ${m.screening} \"${m.text}\"") }
    }

    private fun createNode(): P2pNode {
        val store = P2pStore.create(filesDir, "p2p_debug")
        return P2pNode(this, store, ALIAS, host, ContentScreener(GeminiNanoModel()) { true })
    }

    private val host = object : NearbyHost {
        private val profile: LocalPeerProfile by lazy { vega() }

        override fun localProfile(): LocalPeerProfile = profile

        override fun announce(event: NearbyEvent, peerName: String?, peerSun: String?) {
            log("announce ${event.kind.wire} name=$peerName sun=$peerSun total=${event.total} -> local notification")
            Notifications.show(this@P2pTestService, event, peerName, peerSun)
        }

        override fun log(message: String) = this@P2pTestService.log(message)
    }

    private fun vega(): LocalPeerProfile {
        val birth = BirthInput(LocalDate.of(1992, 5, 21), LocalTime.of(9, 15), 48.8566, 2.3522, "Europe/Paris", "Paris, France")
        val charts = Charts.compute(birth, null)
        val compat = WireCompat.from(charts.compatProfile(includeBlood = false))
        val photo = testPhoto()
        log("persona Vega: sun=${charts.natal.sunSign} compat=$compat photo=${photo.size} bytes")
        return LocalPeerProfile(
            match = MatchProfile(Gender.MALE, Seeking.FEMALE, RelationshipStatus.SINGLE, LookingFor.BOTH),
            compat = compat, firstName = "Vega", bio = "Pixel test peer (debug build).",
            photo = photo, photoW = 480, photoH = 640,
        )
    }

    /** A generated 480x640 JPEG (gold-on-indigo gradient with a label), well under 60 KB. */
    private fun testPhoto(): ByteArray {
        val bmp = Bitmap.createBitmap(480, 640, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, 480f, 640f, 0xFF0B0A2A.toInt(), 0xFFD4AF37.toInt(), Shader.TileMode.CLAMP) })
        c.drawText("VEGA", 120f, 340f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFEDE6F7.toInt(); textSize = 96f })
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 70, it) }.toByteArray()
    }

    private fun log(message: String) {
        Log.i(TAG, message)
    }

    override fun onDestroy() {
        node?.let { n -> n.post { n.transport.stop(graceful = false) } }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val TAG = "ConjunctionP2PTest"
        const val ALIAS = "conjunction_identity_debug"
        private const val ID = 4900
    }
}
