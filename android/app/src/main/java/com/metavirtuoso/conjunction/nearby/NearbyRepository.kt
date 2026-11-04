package com.metavirtuoso.conjunction.nearby

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.metavirtuoso.conjunction.ai.TextModel
import com.metavirtuoso.conjunction.core.chart.ZodiacSign
import com.metavirtuoso.conjunction.core.p2p.P2pConstants
import com.metavirtuoso.conjunction.core.p2p.SwipeDirection
import com.metavirtuoso.conjunction.core.p2p.WireCompat
import com.metavirtuoso.conjunction.data.Charts
import com.metavirtuoso.conjunction.data.Profile
import com.metavirtuoso.conjunction.data.ProfileStore
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Nearby for the app: on / off / pause, the Matches inbox, friends, chats and safety actions, all
 * on this phone (SPEC 5c, no server). The Bluetooth work happens in [node] (engine + transport),
 * kept running by [NearbyBluetoothService] while Nearby is on.
 */
// Every Bluetooth path goes through [node], which is null below API 31 (Nearby needs Android 12).
@android.annotation.SuppressLint("NewApi")
class NearbyRepository(
    private val context: Context,
    val profileStore: ProfileStore,
    model: TextModel,
) : NearbyHost {
    val store: P2pStore = P2pStore.create(context.filesDir)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val local = AtomicReference<LocalPeerProfile?>(null)

    /** The app is visible (popups instead of notifications; on-device screening may run). */
    @Volatile var foreground: Boolean = false
        private set

    /** The chat currently on screen, so its messages don't also raise a notification. */
    @Volatile var openChatFriendId: String? = null

    /** Nearby is switched on in the profile (the Bluetooth node is only touched then). */
    @Volatile private var profileStoreSaysNearbyMayRun = false

    private val screener = ContentScreener(model) { foreground }

    /** Nearby needs Android 12+ (no location permission for BLE scans) and Bluetooth LE hardware. */
    val supported: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val hasBluetoothLe: Boolean get() = context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)

    /** Created on first use (its thread and Bluetooth objects are not needed until Nearby is used). */
    val node: P2pNode? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) P2pNode(context, store, KeystoreIdentity.DEFAULT_ALIAS, this, screener) else null
    }

    private val _events = MutableSharedFlow<NearbyEvent>(extraBufferCapacity = 8)
    /** Matches / twins / mutual matches to show full-screen while the app is open. */
    val events: SharedFlow<NearbyEvent> = _events.asSharedFlow()

    val serviceState: StateFlow<ServiceState> get() = NearbyBluetoothService.state
    val radioStatus: StateFlow<RadioStatus> by lazy { node?.radioStatus ?: MutableStateFlow(RadioStatus()) }
    val engineStatus: StateFlow<EngineStatus> by lazy { node?.engineStatus ?: MutableStateFlow(EngineStatus()) }

    init {
        scope.launch { runCatching { store.load() } }
        scope.launch {
            profileStore.profile.map { it.nearbyEnabled to it }.distinctUntilChanged().collect { (on, p) ->
                profileStoreSaysNearbyMayRun = on
                local.set(if (on) runCatching { buildLocal(p) }.getOrNull() else null)
            }
        }
    }

    // ------------------------------------------------------------------ NearbyHost (engine thread)

    override fun localProfile(): LocalPeerProfile? = local.get()

    override fun announce(event: NearbyEvent, peerName: String?, peerSun: String?) {
        val fg = foreground
        when {
            event.kind == NearbyEventKind.MESSAGE -> if (!(fg && openChatFriendId == event.friendId)) Notifications.show(context, event, peerName, peerSun)
            fg -> _events.tryEmit(event)
            else -> Notifications.show(context, event, peerName, peerSun)
        }
    }

    override fun log(message: String) {
        Log.i(P2pNode.TAG, message)
    }

    /** Recomputes what this phone sends (call before the radio starts and after profile edits). */
    suspend fun refreshLocalProfile() {
        val p = profileStore.current()
        local.set(withContext(Dispatchers.Default) { runCatching { buildLocal(p) }.getOrNull() })
    }

    // ------------------------------------------------------------------ lifecycle

    fun onForegroundChanged(visible: Boolean) {
        foreground = visible
        val s = store.state.value
        val pending = store.isLoaded && (s.messages.any { !it.mine && it.screening == Screening.PENDING } ||
            s.encounters.any { it.photoScreening == Screening.PENDING && it.photoPath != null } ||
            s.friends.any { it.photoScreening == Screening.PENDING && it.photoPath != null })
        if (!profileStoreSaysNearbyMayRun && !pending) return
        val n = node ?: return
        n.transport.scanMode = if (visible) android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_LATENCY else android.bluetooth.le.ScanSettings.SCAN_MODE_BALANCED
        // Screening that had to wait for the foreground (AICore serves only the visible app).
        if (visible && pending) n.post { n.engine.rescreenPending() }
    }

    /** Turns Nearby on (consent and permissions already handled by the UI, which is visible). */
    suspend fun enable() {
        val p = profileStore.current()
        check(supported) { "Nearby needs Android 12 or later." }
        check(p.nearbyEligible) { "Finish your dating & friends profile first." }
        check(p.nearbyConsent) { "Turn on Nearby from the Nearby screen first." }
        check(NearbyPermissions.granted(context)) { "Allow Nearby devices first." }
        profileStore.update { it.copy(nearbyEnabled = true, nearbyPausedUntil = 0L) }
        refreshLocalProfile()
        NearbyBluetoothService.start(context)
    }

    suspend fun disable() {
        profileStore.update { it.copy(nearbyEnabled = false, nearbyPausedUntil = 0L) }
        local.set(null)
        NearbyBluetoothService.stop(context)
    }

    suspend fun pause(untilMillis: Long) {
        profileStore.update { it.copy(nearbyPausedUntil = untilMillis) }
        NearbyBluetoothService.refresh(context)
    }

    suspend fun resume() {
        profileStore.update { it.copy(nearbyPausedUntil = 0L) }
        NearbyBluetoothService.refresh(context)
    }

    /** App came to the foreground: restart the service if Nearby is on but it is not running. */
    suspend fun resumeServiceIfNeeded() {
        val p = profileStore.current()
        if (supported && p.nearbyEnabled && p.nearbyConsent && NearbyPermissions.granted(context) && !serviceState.value.running) {
            NearbyBluetoothService.start(context)
        }
    }

    // ------------------------------------------------------------------ reads (UI)

    /** Unanswered match cards from the last 24 h, newest first. */
    fun inbox(): Flow<List<EncounterCard>> = store.state.map { s ->
        val now = System.currentTimeMillis()
        s.encounters
            .filter { it.friendId == null && it.expiresAt > now && it.mySwipe == null && !s.isHidden(it.fingerprint, now) }
            .sortedByDescending { it.createdAt }
            .map { it.toCard() }
    }.distinctUntilChanged()

    /** Cards I said yes to that are waiting for the other person. */
    fun waiting(): Flow<List<EncounterCard>> = store.state.map { s ->
        val now = System.currentTimeMillis()
        s.encounters.filter { it.friendId == null && it.expiresAt > now && it.mySwipe == SwipeDirection.RIGHT.wire }.map { it.toCard() }
    }.distinctUntilChanged()

    fun encounter(fingerprint: String): Flow<EncounterCard?> = store.state.map { it.encounter(fingerprint)?.toCard() }.distinctUntilChanged()

    fun friends(): Flow<List<FriendSummary>> = combine(store.state, engineStatus) { s, st ->
        s.friends.filter { !it.blocked }.map { summarize(s, it, st) }.sortedByDescending { maxOf(it.lastSeen, s.messagesWith(it.id).lastOrNull()?.ts ?: 0L) }
    }.distinctUntilChanged()

    fun friend(id: String): Flow<FriendSummary?> = combine(store.state, engineStatus) { s, st ->
        s.friend(id)?.takeIf { !it.blocked }?.let { summarize(s, it, st) }
    }.distinctUntilChanged()

    fun messages(friendId: String): Flow<List<ChatLine>> = store.state.map { it.messagesWith(friendId) }.distinctUntilChanged()

    private fun summarize(s: P2pState, f: StoredFriend, st: EngineStatus): FriendSummary {
        val msgs = s.messagesWith(f.id)
        return FriendSummary(
            id = f.id, kind = f.kind, firstName = f.firstName, bio = f.bio, sun = f.sun, moon = f.moon, rising = f.rising,
            chineseAnimal = f.chineseAnimal, photoPath = f.photoPath, photoScreening = f.photoScreening, safetyCode = f.safetyCode,
            since = f.since, lastSeen = f.lastSeen, inRange = f.id in st.friendsInRange,
            unread = msgs.count { !it.mine && !it.seen }, queued = msgs.count { it.mine && (it.status == ChatStatus.QUEUED || it.status == ChatStatus.SENT) },
            lastMessage = msgs.lastOrNull { it.mine || it.screening == Screening.SAFE }?.text,
        )
    }

    // ------------------------------------------------------------------ actions

    private fun engine(block: NearbyEngine.() -> Unit) {
        val n = node ?: return
        n.post { n.engine.block() }
    }

    suspend fun swipe(fingerprint: String, accept: Boolean) {
        store.load()
        val n = node
        if (n == null) {
            // No engine on this Android version: nothing to send; just record the answer.
            store.update { s -> s.copy(encounters = s.encounters.map { if (it.fingerprint == fingerprint) it.copy(mySwipe = if (accept) "right" else "left") else it }) }
            return
        }
        n.post { n.engine.swipe(fingerprint, if (accept) SwipeDirection.RIGHT else SwipeDirection.LEFT) }
    }

    /** Queues a message; it goes out at once if the friend is in range, else the next time they are. */
    suspend fun sendChat(friendId: String, text: String): ChatLine {
        store.load()
        val trimmed = trimUtf8(text.trim(), P2pConstants.MAX_CHAT_TEXT_BYTES)
        require(trimmed.isNotEmpty()) { "empty message" }
        val line = ChatLine(NearbyEngine.newMessageId(), friendId, mine = true, text = trimmed, ts = System.currentTimeMillis(), status = ChatStatus.QUEUED)
        store.update { s -> s.copy(messages = s.messages + line) }
        val n = node ?: return line
        n.post {
            n.engine.deliver(friendId)
            if (friendId !in n.engine.status.value.friendsInRange) n.transport.expediteReconnect()
        }
        return line
    }

    /** The person looked at the chat: mark incoming messages seen (read receipts go out when in range). */
    suspend fun markSeen(friendId: String) {
        store.load()
        val s = store.state.value
        if (s.messages.none { it.friendId == friendId && !it.mine && !it.seen }) return
        store.update { st -> st.copy(messages = st.messages.map { if (it.friendId == friendId && !it.mine) it.copy(seen = true) else it }) }
        engine { deliver(friendId) }
    }

    /** "Show anyway" on a hidden message or photo. */
    suspend fun reveal(messageId: String) {
        store.load()
        store.update { s -> s.copy(messages = s.messages.map { if (it.id == messageId) it.copy(screening = Screening.SAFE) else it }) }
    }

    /** Block a friend: kept as a blocked record (so they are recognised and refused), chat deleted here. */
    suspend fun blockFriend(friendId: String) {
        store.load()
        val now = System.currentTimeMillis()
        store.update { s ->
            val fps = s.encounters.filter { it.friendId == friendId }.map { it.fingerprint }
            s.copy(
                friends = s.friends.map { if (it.id == friendId) it.copy(blocked = true) else it },
                messages = s.messages.filterNot { it.friendId == friendId },
                encounters = s.encounters.filterNot { it.friendId == friendId },
                hidden = s.hidden + fps.map { HiddenPeer(it, Long.MAX_VALUE, "block") },
            )
        }
        Log.i(P2pNode.TAG, "blocked friend ${friendId.take(8)} at $now")
        engine { disconnectPeer(friendId, null) }
    }

    /** Unmatch: the friendship and chat are deleted from this phone; their card stays hidden for 30 days. */
    suspend fun unmatch(friendId: String) {
        store.load()
        val now = System.currentTimeMillis()
        store.update { s ->
            val fps = s.encounters.filter { it.friendId == friendId }.map { it.fingerprint }
            s.copy(
                friends = s.friends.filterNot { it.id == friendId },
                messages = s.messages.filterNot { it.friendId == friendId },
                encounters = s.encounters.filterNot { it.friendId == friendId },
                hidden = s.hidden + fps.map { HiddenPeer(it, now + NearbyEngine.LEFT_SWIPE_HIDE_MS, "unmatch") },
            )
        }
        engine { disconnectPeer(friendId, null) }
    }

    /** Block a stranger (match card): never matched again from this phone. */
    suspend fun blockStranger(fingerprint: String) = hideStranger(fingerprint, Long.MAX_VALUE, "block")

    /** Snooze a stranger's card for 7 days. */
    suspend fun snooze(fingerprint: String) = hideStranger(fingerprint, System.currentTimeMillis() + NearbyEngine.SNOOZE_MS, "snooze")

    private suspend fun hideStranger(fingerprint: String, until: Long, reason: String) {
        store.load()
        store.update { s ->
            s.copy(
                encounters = s.encounters.filterNot { it.fingerprint == fingerprint },
                hidden = s.hidden.filterNot { it.fingerprint == fingerprint } + HiddenPeer(fingerprint, until, reason),
            )
        }
        engine { disconnectPeer(null, fingerprint) }
    }

    /** Delete all my data: Nearby off, friends, chats, photos and the identity key. */
    suspend fun deleteAll() {
        disable()
        store.load()
        store.clear()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) KeystoreIdentity.delete()
    }

    // ------------------------------------------------------------------ helpers

    private fun buildLocal(p: Profile): LocalPeerProfile? {
        val birth = p.birth ?: return null
        val dating = p.dating?.takeIf { p.orientationConsent } ?: return null
        val charts = Charts.compute(birth, p.bloodType)
        val compat = WireCompat.from(charts.compatProfile(includeBlood = p.bloodForMatching))
        val photo = p.localPhotoPath?.let { PhotoCodec.cardPhoto(File(it)) }
        return LocalPeerProfile(
            match = dating.toMatchProfile(),
            compat = compat,
            firstName = cardName(p.firstName, charts.natal.sunSign),
            bio = p.bio.lineSequence().firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.let { trimUtf8(it, P2pConstants.MAX_BIO_BYTES) },
            photo = photo?.jpeg, photoW = photo?.width ?: 0, photoH = photo?.height ?: 0,
        )
    }

    companion object {
        /** First name for the card (1..64 UTF-8 bytes); people without one appear as "A Leo". */
        fun cardName(firstName: String, sun: ZodiacSign): String =
            trimUtf8(firstName.trim().ifEmpty { "A ${sun.displayName}" }, P2pConstants.MAX_FIRST_NAME_BYTES)

        /** Truncates to at most [maxBytes] UTF-8 bytes at a code-point boundary. */
        fun trimUtf8(s: String, maxBytes: Int): String {
            if (s.toByteArray(Charsets.UTF_8).size <= maxBytes) return s
            val sb = StringBuilder()
            var bytes = 0
            var i = 0
            while (i < s.length) {
                val cp = s.codePointAt(i)
                val n = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).size
                if (bytes + n > maxBytes) break
                sb.appendCodePoint(cp)
                bytes += n
                i += Character.charCount(cp)
            }
            return sb.toString()
        }

        /** "Pause until tomorrow" = 06:00 local tomorrow. */
        fun tomorrowMorning(zone: ZoneId = ZoneId.systemDefault()): Long =
            LocalDate.now(zone).plusDays(1).atTime(6, 0).atZone(zone).toInstant().toEpochMilli()
    }
}
