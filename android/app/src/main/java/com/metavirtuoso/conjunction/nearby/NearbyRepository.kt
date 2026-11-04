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

    /** Settings › Notifications switches (read on the engine thread). */
    @Volatile private var notifyMatches = true
    @Volatile private var notifyMessages = true
    private var lastInstagram: Pair<String?, Boolean>? = null

    private val screener = ContentScreener(model) { foreground }

    /** Nearby needs Android 12+ (no location permission for BLE scans) and Bluetooth LE hardware. */
    val supported: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val hasBluetoothLe: Boolean get() = context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)

    /** Created on first use (its thread and Bluetooth objects are not needed until Nearby is used). */
    val node: P2pNode? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) P2pNode(context, store, KeystoreIdentity.DEFAULT_ALIAS, this, screener) else null
    }

    /** The last turn-on found the Bluetooth ("Nearby devices") permission denied (status card). */
    val permissionDenied = MutableStateFlow(false)

    /** Compatibility views already opened in this run (the score counts up on first open only). */
    val compatOpened: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())

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
                notifyMatches = p.notifyMatches
                notifyMessages = p.notifyMessages
                local.set(if (on) runCatching { buildLocal(p) }.getOrNull() else null)
                // A new handle or share default: open friend links get the new `profile` now.
                val ig = p.instagram to p.instagramShareDefault
                if (lastInstagram != null && lastInstagram != ig && on) engine { refreshProfiles() }
                lastInstagram = ig
            }
        }
    }

    // ------------------------------------------------------------------ NearbyHost (engine thread)

    override fun localProfile(): LocalPeerProfile? = local.get()

    override fun announce(event: NearbyEvent, peerName: String?, peerSun: String?) {
        val fg = foreground
        when {
            event.kind == NearbyEventKind.MESSAGE -> if (!(fg && openChatFriendId == event.friendId) && notifyMessages) Notifications.show(context, event, peerName, peerSun)
            fg -> _events.tryEmit(event)
            notifyMatches -> Notifications.show(context, event, peerName, peerSun)
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
        check(p.birth != null && p.datingFieldsComplete) { "Finish your dating & friends profile first." }
        check(p.nearbyConsentGiven) { "Turn on Nearby from the Nearby screen first." }
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
        if (supported && p.nearbyEnabled && p.nearbyConsentGiven && NearbyPermissions.granted(context) && !serviceState.value.running) {
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

    /** Number of unanswered match cards (the Nearby tab badge). */
    fun pendingCount(): Flow<Int> = inbox().map { it.size }.distinctUntilChanged()

    /** The Compatibility view's subject: a pending / waiting card or a friend, while the record exists. */
    fun compatSubject(key: String): Flow<CompatSubject?> = store.state.map { s -> compatSubjectOf(s, key) }.distinctUntilChanged()

    /** Blocked former friends (Settings › Privacy & legal › Blocked people). */
    fun blockedFriends(): Flow<List<StoredFriend>> = store.state.map { s -> s.friends.filter { it.blocked } }.distinctUntilChanged()

    /** Strangers hidden for good (blocked from a match card) or for 7 days (snoozed). */
    fun hiddenStrangers(reason: String): Flow<List<HiddenPeer>> = store.state.map { s ->
        val now = System.currentTimeMillis()
        s.hidden.filter { it.reason == reason && it.until > now }
    }.distinctUntilChanged()

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
            peerIg = f.peerIg, total = (f.score ?: s.encounters.firstOrNull { it.friendId == f.id }?.storedScore())?.total,
            igShareOn = f.igShareOn ?: (lastInstagram?.second ?: true),
        )
    }

    private fun compatSubjectOf(s: P2pState, key: String): CompatSubject? {
        val now = System.currentTimeMillis()
        s.friend(key)?.takeIf { !it.blocked }?.let { f ->
            val enc = s.encounters.firstOrNull { it.friendId == f.id }
            val score = f.score ?: enc?.storedScore() ?: return null
            return CompatSubject(
                key = f.id, friendId = f.id, fingerprint = enc?.fingerprint, name = f.firstName, kind = f.kind,
                photoPath = f.photoPath, photoScreening = f.photoScreening, sun = f.sun, animal = f.chineseAnimal,
                peerCompat = f.peerCompat, score = score, blurb = f.blurb ?: enc?.blurb, peerIg = f.peerIg,
            )
        }
        val e = s.encounter(key)?.takeIf { it.friendId == null && it.expiresAt > now && it.mySwipe != SwipeDirection.LEFT.wire } ?: return null
        return CompatSubject(
            key = e.fingerprint, friendId = null, fingerprint = e.fingerprint, name = e.firstName ?: "Someone nearby", kind = e.kind,
            photoPath = e.photoPath, photoScreening = e.photoScreening, sun = e.sun, animal = e.chineseAnimal,
            peerCompat = e.peerCompat, score = e.storedScore(), blurb = e.blurb, peerIg = null,
        )
    }

    /** Caches the Compatibility view's reading on the record (generated once per pair of profiles). */
    suspend fun saveBlurb(key: String, blurb: StoredBlurb) {
        store.load()
        store.update { s ->
            s.copy(
                friends = s.friends.map { if (it.id == key) it.copy(blurb = blurb) else it },
                encounters = s.encounters.map { if (it.fingerprint == key) it.copy(blurb = blurb) else it },
            )
        }
    }

    /** "Share my Instagram with <name>" for one friend; an open secure link sends the change at once. */
    suspend fun setInstagramShare(friendId: String, on: Boolean) {
        store.load()
        store.update { s -> s.copy(friends = s.friends.map { if (it.id == friendId) it.copy(igShareOn = on) else it }) }
        engine { refreshProfiles() }
    }

    /** Settings › Blocked people: forget a blocked former friend (they could match again later). */
    suspend fun unblockFriend(friendId: String) {
        store.load()
        store.update { s -> s.copy(friends = s.friends.filterNot { it.id == friendId && it.blocked }) }
    }

    /** A stranger blocked from a match card may match again. */
    suspend fun unblockStranger(fingerprint: String) {
        store.load()
        store.update { s -> s.copy(hidden = s.hidden.filterNot { it.fingerprint == fingerprint && it.reason == "block" }) }
    }

    /** Settings › Nearby › Snoozed people: show their card again when next nearby. */
    suspend fun unsnooze(fingerprint: String) {
        store.load()
        store.update { s -> s.copy(hidden = s.hidden.filterNot { it.fingerprint == fingerprint && it.reason == "snooze" }) }
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
        store.update { it.withMessagesSeen(friendId) }
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
        store.update { it.withFriendBlocked(friendId) }
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
        val dating = p.dating?.takeIf { p.nearbyConsentGiven } ?: return null
        val charts = Charts.compute(birth, p.bloodType)
        val compat = WireCompat.from(charts.compatProfile(includeBlood = p.bloodForMatching))
        val photo = p.localPhotoPath?.let { PhotoCodec.cardPhoto(File(it)) }
        return LocalPeerProfile(
            match = dating.toMatchProfile(),
            compat = compat,
            firstName = cardName(p.firstName, charts.natal.sunSign),
            bio = p.bio.lineSequence().firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.let { trimUtf8(it, P2pConstants.MAX_BIO_BYTES) },
            photo = photo?.jpeg, photoW = photo?.width ?: 0, photoH = photo?.height ?: 0,
            instagram = p.instagram?.let { com.metavirtuoso.conjunction.core.profile.InstagramHandle.normalize(it) },
            shareInstagramByDefault = p.instagramShareDefault,
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
