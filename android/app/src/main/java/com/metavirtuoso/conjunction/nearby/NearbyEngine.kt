package com.metavirtuoso.conjunction.nearby

import com.metavirtuoso.conjunction.core.compat.CompatResult
import com.metavirtuoso.conjunction.core.compat.Compatibility
import com.metavirtuoso.conjunction.core.match.MatchProfile
import com.metavirtuoso.conjunction.core.p2p.ChatPayload
import com.metavirtuoso.conjunction.core.p2p.EphemeralIdSource
import com.metavirtuoso.conjunction.core.p2p.FriendRecord
import com.metavirtuoso.conjunction.core.p2p.LinkDedup
import com.metavirtuoso.conjunction.core.p2p.LinkEvent
import com.metavirtuoso.conjunction.core.p2p.LinkPolicy
import com.metavirtuoso.conjunction.core.p2p.LinkRole
import com.metavirtuoso.conjunction.core.p2p.LinkState
import com.metavirtuoso.conjunction.core.p2p.LocalP2pProfile
import com.metavirtuoso.conjunction.core.p2p.P256Agreement
import com.metavirtuoso.conjunction.core.p2p.P2pCrypto
import com.metavirtuoso.conjunction.core.p2p.P2pLink
import com.metavirtuoso.conjunction.core.p2p.P2pMatching
import com.metavirtuoso.conjunction.core.p2p.SwipeDirection
import com.metavirtuoso.conjunction.core.p2p.WireCompat
import com.metavirtuoso.conjunction.core.profile.InstagramHandle
import com.metavirtuoso.conjunction.core.profile.InstagramShareState
import java.security.SecureRandom
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What this phone sends about its owner (rebuilt for every new link, so profile edits apply at once). */
class LocalPeerProfile(
    val match: MatchProfile,
    val compat: WireCompat,
    val firstName: String,
    val bio: String?,
    /** JPEG, longest side <= 640, <= 61440 bytes; null = no photo. */
    val photo: ByteArray?,
    val photoW: Int,
    val photoH: Int,
    /** Normalised Instagram handle, sent only to friends inside the encrypted chat (SPEC 5d.6). */
    val instagram: String? = null,
    /** Settings › Nearby "Share my Instagram with new matches": the per-friend switch's default. */
    val shareInstagramByDefault: Boolean = true,
)

/** One Bluetooth connection as the engine sees it; implemented by the BLE transport (and by tests). */
interface LinkPort {
    /** For logs only. */
    val label: String

    /** Queue frames for the peer in order (central: INBOX writes; peripheral: OUTBOX notifications). */
    fun send(frames: List<ByteArray>)

    /** The link is finished: disconnect once queued frames are written. [outcome] sets the reconnect cool-down. */
    fun close(outcome: LinkOutcome)

    /**
     * Drop every queued frame not yet handed to the radio (a write or notification already in
     * flight completes). Called when a link is closed as a duplicate (protocol 7.2): the peer
     * closes it too, and on the LE connection the pair shares with the kept link, frames written
     * after its `bye` would reach a peripheral that has no link for them any more.
     */
    fun discardPending() {}
}

enum class LinkOutcome { NO_MATCH, DONE, DEDUP, ERROR, TIMEOUT, SHUTDOWN }

/** Integration points the engine needs from the app (or a test). Called on the engine thread. */
interface NearbyHost {
    /** Null while Nearby cannot run (no birth data / dating profile): links are refused. */
    fun localProfile(): LocalPeerProfile?

    /** A match, twin, mutual match or message to announce (popup in the foreground, local notification otherwise). */
    fun announce(event: NearbyEvent, peerName: String?, peerSun: String?)

    fun log(message: String) {}
}

/** Live engine status for the Nearby screen. */
data class EngineStatus(
    val openLinks: Int = 0,
    val friendsInRange: Set<String> = emptySet(),
    val encountersThisSession: Int = 0,
)

/**
 * The Nearby protocol engine: one [P2pLink] (core state machine) per Bluetooth link, plus
 * everything SPEC 5c asks of the app around it — link de-duplication (protocol 7.2), the
 * Matches inbox, swipes made out of range, friend records, store-and-forward chat with delivery
 * and read receipts, on-device screening, hide / block lists.
 *
 * **Threading:** not thread-safe. Every method must run on the one engine thread (the BLE
 * transport posts all callbacks there; the app uses [NearbyRepository], which posts too).
 */
class NearbyEngine(
    private val store: P2pStore,
    private val identity: () -> P256Agreement,
    private val host: NearbyHost,
    private val screener: ContentScreener,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val today: () -> LocalDate = LocalDate::now,
    private val random: SecureRandom = SecureRandom(),
    /** Decodes a received JPEG for the photo screener; null when it is not a decodable image. */
    private val decodePhoto: (ByteArray) -> android.graphics.Bitmap? = { PhotoCodec.decode(it, 640) },
) {
    private class Rec(
        val id: Int,
        val port: LinkPort,
        val link: P2pLink,
        val myEid: String,
        var lastActivity: Long,
    ) {
        var peerEid: String? = null
        var fingerprint: String? = null
        var friendId: String? = null
        var suppressed = false
        var portClosed = false
        val inflight = LinkedHashSet<String>()
        /** Rev of the `profile` already written on this link (not re-sent on the same link). */
        var profileSentRev = -1
        var chatTokens = CHAT_BURST.toDouble()
        var chatTokensAt = lastActivity
    }

    private val links = LinkedHashMap<Int, Rec>()
    private var nextId = 1
    private val eids = EphemeralIdSource(random)
    private var lastHousekeeping = 0L
    private var running = false
    private val _status = MutableStateFlow(EngineStatus())
    val status: StateFlow<EngineStatus> = _status.asStateFlow()

    val openLinkCount: Int get() = links.size

    /** Nearby switched on: new ephemeral id (protocol 7.1). */
    fun start() {
        // Links left from an earlier run whose disconnect was never reported: their ports are
        // closed, so drop them (unacked chats go back to the queue) instead of counting them
        // against MAX_LINKS forever.
        for (rec in links.values.toList()) requeue(rec)
        links.clear()
        closeReason.clear()
        running = true
        eids.rotate(clock())
        housekeeping(clock(), force = true)
        publishStatus()
    }

    /** Nearby switched off / paused / Bluetooth off: say goodbye on every link. */
    fun stop(outcome: LinkOutcome = LinkOutcome.SHUTDOWN) {
        running = false
        for (rec in links.values.toList()) {
            if (!rec.link.isClosed) rec.link.close("shutdown")
            flush(rec)
            closePort(rec, outcome)
        }
        publishStatus()
    }

    // ------------------------------------------------------------------ transport side

    /**
     * A connection is ready for the protocol (central: MTU exchanged, OUTBOX notifications on;
     * peripheral: first frame or subscription from a central). Returns the link id, or null when
     * the link is refused (Nearby not running, profile incomplete, too many links): disconnect then.
     */
    fun openLink(role: LinkRole, port: LinkPort, attMtu: Int): Int? {
        if (!running) return null
        if (links.size >= MAX_LINKS) return null
        val me = host.localProfile() ?: return null
        val now = clock()
        val eid = eids.current(now)
        val local = try {
            LocalP2pProfile(eid, me.match, me.compat, me.firstName, me.bio, me.photo, me.photoW, me.photoH)
        } catch (e: IllegalArgumentException) {
            host.log("local profile rejected: ${e.message}")
            return null
        }
        val link = P2pLink(role, local, identity(), policy, now, random = random)
        link.setAttMtu(attMtu)
        val rec = Rec(nextId++, port, link, eid, now)
        links[rec.id] = rec
        host.log("link ${rec.id} open as $role (${port.label}, ATT_MTU $attMtu)")
        link.start()
        flush(rec)
        publishStatus()
        return rec.id
    }

    fun onFrame(linkId: Int, frame: ByteArray) {
        val rec = links[linkId] ?: return
        rec.lastActivity = clock()
        rec.link.onFrame(frame, rec.lastActivity)
        flush(rec)
    }

    fun onMtu(linkId: Int, attMtu: Int) {
        links[linkId]?.link?.setAttMtu(attMtu)
    }

    /** The Bluetooth connection is gone (either side disconnected). */
    fun onDisconnected(linkId: Int) {
        val rec = links.remove(linkId) ?: return
        closeReason.remove(linkId)
        host.log("link $linkId disconnected (state ${rec.link.state})")
        requeue(rec)
        publishStatus()
    }

    /** About once a second: protocol timeouts, chat pacing, idle secure links, inbox expiry. */
    fun tick() {
        val now = clock()
        for (rec in links.values.toList()) {
            rec.link.tick(now)
            if (rec.link.state == LinkState.SECURE) {
                deliverQueued(rec)
                if (now - rec.lastActivity > SECURE_IDLE_MS && !hasPendingFor(rec.friendId)) rec.link.close("done")
            }
            flush(rec)
        }
        housekeeping(now)
    }

    // ------------------------------------------------------------------ app side

    /** The person swiped a match card. Sent now if the peer is in range, else at the next encounter. */
    fun swipe(fingerprint: String, dir: SwipeDirection) {
        val now = clock()
        store.update { s ->
            val enc = s.encounter(fingerprint) ?: return@update s
            s.copy(
                encounters = s.encounters.map { if (it.fingerprint == fingerprint) it.copy(mySwipe = dir.wire) else it },
                hidden = if (dir == SwipeDirection.LEFT) s.hidden.filterNot { it.fingerprint == enc.fingerprint } +
                    HiddenPeer(fingerprint, now + LEFT_SWIPE_HIDE_MS, "left") else s.hidden,
            )
        }
        for (rec in links.values.toList()) {
            if (rec.fingerprint == fingerprint && rec.link.state == LinkState.MATCHED) {
                runCatching { rec.link.swipe(dir) }.onFailure { host.log("swipe on link ${rec.id}: ${it.message}") }
                flush(rec)
            }
        }
    }

    /** New queued messages or read receipts for [friendId]: send them if a secure link is open. */
    fun deliver(friendId: String) {
        for (rec in links.values.toList()) {
            if (rec.friendId == friendId && rec.link.state == LinkState.SECURE) {
                deliverQueued(rec)
                flush(rec)
            }
        }
    }

    /**
     * The owner's Instagram handle or a per-friend share switch changed (SPEC 5d.6): send the new
     * `profile` now on every open secure friend link whose effective value changed.
     */
    fun refreshProfiles() {
        for (rec in links.values.toList()) {
            if (rec.friendId != null && rec.link.state == LinkState.SECURE) {
                sendProfileIfNeeded(rec)
                flush(rec)
            }
        }
    }

    /** Block a friend (same store change as the app's Block): their links close now and later ones are refused. */
    fun blockFriend(friendId: String) {
        store.update { it.withFriendBlocked(friendId) }
        host.log("blocked friend ${friendId.take(8)}")
        disconnectPeer(friendId, null)
    }

    /** The person read the chat with [friendId]: read receipts go out now if a secure link is open. */
    fun markSeen(friendId: String) {
        store.update { it.withMessagesSeen(friendId) }
        deliver(friendId)
    }

    /** Close every link to a person (block, unmatch, report, snooze). */
    fun disconnectPeer(friendId: String?, fingerprint: String?) {
        for (rec in links.values.toList()) {
            if ((friendId != null && rec.friendId == friendId) || (fingerprint != null && rec.fingerprint == fingerprint)) {
                rec.link.close("done")
                flush(rec)
            }
        }
    }

    /** Re-run screening that had to wait for the foreground (on-device model). */
    fun rescreenPending() {
        val s = store.state.value
        s.messages.filter { !it.mine && it.screening == Screening.PENDING }.forEach { screenMessage(it.id, it.text) }
        s.encounters.filter { it.photoPath != null && it.photoScreening == Screening.PENDING }.forEach { screenPhotoFile(it.photoPath!!) }
        s.friends.filter { it.photoPath != null && it.photoScreening == Screening.PENDING }.forEach { screenPhotoFile(it.photoPath!!) }
    }

    // ------------------------------------------------------------------ internals

    private val policy = object : LinkPolicy {
        override fun friends(): List<FriendRecord> = store.state.value.friends
            .sortedByDescending { it.lastSeen }
            .map { FriendRecord(it.id, b64d(it.identityKey), b64d(it.friendSecret), it.blocked) }

        override fun isHidden(fingerprint: String): Boolean = store.state.value.isHidden(fingerprint, clock())

        override fun presetSwipe(fingerprint: String): SwipeDirection? {
            val e = store.state.value.encounter(fingerprint) ?: return null
            if (e.friendId != null || e.expiresAt < clock()) return null
            return SwipeDirection.entries.firstOrNull { it.wire == e.mySwipe }
        }

        override fun today(): LocalDate = this@NearbyEngine.today()
    }

    private fun flush(rec: Rec) {
        var guard = 0
        while (guard++ < 16) {
            val frames = rec.link.takeFrames()
            if (frames.isNotEmpty() && !rec.portClosed) rec.port.send(frames)
            val events = rec.link.takeEvents()
            if (frames.isEmpty() && events.isEmpty()) break
            for (e in events) handle(rec, e)
        }
        if (rec.link.isClosed && !rec.portClosed) closePort(rec, outcomeOf(rec))
    }

    private val closeReason = HashMap<Int, String>()

    private fun outcomeOf(rec: Rec): LinkOutcome = when (closeReason[rec.id]) {
        "no_match" -> LinkOutcome.NO_MATCH
        "dedup" -> LinkOutcome.DEDUP
        "timeout" -> LinkOutcome.TIMEOUT
        "error" -> LinkOutcome.ERROR
        "shutdown" -> LinkOutcome.SHUTDOWN
        else -> LinkOutcome.DONE
    }

    private fun closePort(rec: Rec, outcome: LinkOutcome) {
        if (rec.portClosed) return
        rec.portClosed = true
        requeue(rec)
        rec.port.close(outcome)
    }

    private fun handle(rec: Rec, e: LinkEvent) {
        val now = clock()
        when (e) {
            is LinkEvent.HelloReceived -> {
                rec.peerEid = e.hello.eid
                rec.fingerprint = e.fingerprint
                dedup(rec)
            }
            LinkEvent.NoMatch -> host.log("link ${rec.id}: no match")
            is LinkEvent.Match -> if (!rec.suppressed) onMatch(rec, e, now)
            is LinkEvent.CardReceived -> if (!rec.suppressed) updateEncounter(rec.fingerprint) {
                it.copy(firstName = e.card.firstName, bio = e.card.bio?.lineSequence()?.firstOrNull())
            }
            is LinkEvent.PhotoReceived -> if (!rec.suppressed) onPhoto(rec, e)
            is LinkEvent.PeerSwiped -> updateEncounter(rec.fingerprint) { it.copy(peerSwipe = e.dir.wire) }
            is LinkEvent.FriendEstablished -> onFriendEstablished(rec, e, now)
            is LinkEvent.FriendNearby -> {
                rec.friendId = e.friend.id
                // SPEC 5d.4 backfill: the friend's hello (kc already verified) gives their chart
                // positions; keep them (replace and re-score when they changed).
                val peer = rec.link.peerHello?.compat
                store.update { s ->
                    s.copy(friends = s.friends.map { f ->
                        if (f.id != e.friend.id) f
                        else if (peer != null && peer != f.peerCompat) f.copy(lastSeen = now, peerCompat = peer, score = rescore(peer) ?: f.score)
                        else f.copy(lastSeen = now)
                    })
                }
                host.log("link ${rec.id}: friend ${e.friend.id.take(8)} nearby, secure")
                sendProfileIfNeeded(rec)
                deliverQueued(rec)
                publishStatus()
            }
            is LinkEvent.ChatReceived -> onChat(rec, e.payload)
            is LinkEvent.ProfileReceived -> onProfile(rec, e.profile)
            is LinkEvent.Delivered -> {
                rec.inflight.remove(e.id)
                store.update { s ->
                    s.copy(
                        messages = s.messages.map { if (it.id == e.id && it.mine && it.status != ChatStatus.READ) it.copy(status = ChatStatus.DELIVERED) else it },
                        friends = s.friends.map { f -> if (f.id == rec.friendId && f.igState?.inFlightId == e.id) f.copy(igState = f.igState.onAck(e.id)) else f },
                    )
                }
            }
            is LinkEvent.ProtocolError -> host.log("link ${rec.id}: protocol error ${e.code} (${if (e.byPeer) "peer" else "us"}): ${e.detail}")
            is LinkEvent.Closed -> {
                closeReason[rec.id] = e.reason
                host.log("link ${rec.id}: closed '${e.reason}' by ${if (e.byPeer) "peer" else "us"}")
            }
        }
    }

    /** Protocol 7.2: of two links to the same phone keep the one whose central has the smaller eid. */
    private fun dedup(rec: Rec) {
        val peerEid = rec.peerEid ?: return
        val other = links.values.firstOrNull { it !== rec && it.peerEid == peerEid && !it.link.isClosed } ?: return
        val keepThis = LinkDedup.keep(rec.myEid, peerEid, rec.link.role == LinkRole.CENTRAL)
        val keepOther = LinkDedup.keep(other.myEid, peerEid, other.link.role == LinkRole.CENTRAL)
        val loser = if (keepThis && !keepOther) other else rec
        host.log("dedup: links ${rec.id}/${other.id} reach the same phone; closing ${loser.id}")
        loser.suppressed = true
        // Nothing more goes out on the duplicate but its bye (protocol 7.2): card / photo frames
        // queued in the transport would otherwise keep flowing for seconds after the peer closed it.
        if (!loser.portClosed) loser.port.discardPending()
        loser.link.close("dedup")
        if (loser !== rec) flush(loser)
    }

    private fun onMatch(rec: Rec, e: LinkEvent.Match, now: Long) {
        val m = e.match
        val peer = e.peer
        var announce = false
        store.update { s ->
            val old = s.encounter(e.fingerprint)?.takeIf { it.expiresAt > now && it.friendId == null }
            val enc = StoredEncounter(
                fingerprint = e.fingerprint, kind = m.kind.wire, total = m.score.total, western = m.score.western,
                chinese = m.score.chinese, blood = m.score.blood, highlights = m.score.highlights,
                birthdayTwin = m.isBirthdayTwin, exactTwin = m.isExactTwin, age = m.peerAge,
                sun = m.peerSun.name.lowercase(), moon = m.peerMoon.name.lowercase(), rising = m.peerRising?.name?.lowercase(),
                chineseAnimal = peer.compat.animal.key, relationship = peer.match.relationshipStatus.wire,
                lookingFor = peer.match.lookingFor.wire,
                firstName = old?.firstName, bio = old?.bio, photoPath = old?.photoPath, photoScreening = old?.photoScreening ?: Screening.PENDING,
                mySwipe = old?.mySwipe, peerSwipe = null, friendId = null,
                createdAt = old?.createdAt ?: now, expiresAt = old?.expiresAt ?: (now + INBOX_TTL_MS),
                notifiedAt = old?.notifiedAt ?: 0L,
                peerCompat = peer.compat, chineseRelation = m.score.chineseRelation.name, stemRelation = m.score.stemRelation.name,
                blurb = old?.blurb?.takeIf { old.peerCompat == peer.compat },
            )
            announce = now - enc.notifiedAt > RENOTIFY_MS
            val updated = if (announce) enc.copy(notifiedAt = now) else enc
            s.copy(encounters = s.encounters.filterNot { it.fingerprint == e.fingerprint } + updated)
        }
        _status.value = _status.value.copy(encountersThisSession = _status.value.encountersThisSession + 1)
        host.log("link ${rec.id}: match ${m.kind.wire} ${m.score.total}% twin=${m.isBirthdayTwin}/${m.isExactTwin}")
        if (announce) {
            val kind = when {
                m.isExactTwin -> NearbyEventKind.EXACT_TWIN
                m.isBirthdayTwin -> NearbyEventKind.BIRTHDAY_TWIN
                else -> NearbyEventKind.MATCH
            }
            host.announce(NearbyEvent(kind, e.fingerprint, null, m.kind.wire, m.score.total), null, m.peerSun.displayName)
        }
    }

    private fun onPhoto(rec: Rec, e: LinkEvent.PhotoReceived) {
        val file = store.newPhotoFile(e.sha256)
        try {
            if (!file.exists()) file.writeBytes(e.jpeg)
        } catch (x: java.io.IOException) {
            host.log("could not store photo: ${x.message}")
            return
        }
        val existing = store.state.value.encounter(rec.fingerprint ?: return)
        val alreadyScreened = existing?.photoPath == file.absolutePath && existing.photoScreening != Screening.PENDING
        updateEncounter(rec.fingerprint) { it.copy(photoPath = file.absolutePath, photoScreening = if (alreadyScreened) it.photoScreening else Screening.PENDING) }
        if (!alreadyScreened) screenPhotoFile(file.absolutePath)
    }

    private fun onFriendEstablished(rec: Rec, e: LinkEvent.FriendEstablished, now: Long) {
        val id = hexOf(P2pCrypto.sha256(e.peerIdentityKey)).take(32)
        rec.friendId = id
        val enc = rec.fingerprint?.let { store.state.value.encounter(it) }
        val c = e.peer.compat
        val name = e.card?.firstName ?: enc?.firstName ?: "Your match"
        val me = host.localProfile()
        val shareOn = me?.shareInstagramByDefault ?: true
        val score = enc?.takeIf { it.peerCompat == null || it.peerCompat == c }?.storedScore() ?: rescore(c)
        store.update { s ->
            val friend = StoredFriend(
                id = id, identityKey = b64(e.peerIdentityKey), friendSecret = b64(e.friendSecret), safetyCode = e.safetyCode,
                kind = rec.link.match?.kind?.wire ?: enc?.kind ?: "friends", firstName = name,
                bio = e.card?.bio?.lineSequence()?.firstOrNull() ?: enc?.bio,
                sun = P2pMatching.sign(c.sun).name.lowercase(), moon = P2pMatching.sign(c.moon).name.lowercase(),
                rising = c.asc?.let { P2pMatching.sign(it).name.lowercase() }, chineseAnimal = c.animal.key,
                photoPath = enc?.photoPath, photoScreening = if (enc?.photoPath != null) enc.photoScreening else Screening.SAFE,
                since = now, lastSeen = now,
                peerCompat = c, score = score, blurb = enc?.blurb,
                igShareOn = shareOn, igState = InstagramShareState.initial(me?.instagram, shareOn),
            )
            s.copy(
                friends = s.friends.filterNot { it.id == id } + friend,
                encounters = s.encounters.map { if (it.fingerprint == rec.fingerprint) it.copy(friendId = id) else it },
            )
        }
        host.log("link ${rec.id}: new friend ${id.take(8)} (safety code ${e.safetyCode})")
        host.announce(NearbyEvent(NearbyEventKind.CONJUNCTION, rec.fingerprint, id, enc?.kind, enc?.total), name, enc?.sun)
        // The `profile` is the first chat on a new secure link (SPEC 5d.6), then queued messages.
        sendProfileIfNeeded(rec)
        deliverQueued(rec)
        publishStatus()
    }

    private fun onChat(rec: Rec, payload: ChatPayload) {
        val fid = rec.friendId ?: return
        val s = store.state.value
        val friend = s.friend(fid) ?: return
        if (friend.blocked) return
        when (payload) {
            is ChatPayload.Text -> {
                if (s.messages.any { it.id == payload.id }) return // re-sent queued message: already stored (and acked again)
                store.update { st ->
                    val line = ChatLine(payload.id, fid, mine = false, text = payload.text, ts = payload.ts, status = ChatStatus.RECEIVED, screening = Screening.PENDING)
                    st.copy(messages = trim(st.messages + line, fid))
                }
                host.log("link ${rec.id}: chat ${payload.id.take(8)} received (${payload.text.length} chars)")
                screenMessage(payload.id, payload.text)
                host.announce(NearbyEvent(NearbyEventKind.MESSAGE, null, fid), friend.firstName, friend.sun)
            }
            is ChatPayload.Read -> store.update { st ->
                val refs = payload.refs.toSet()
                st.copy(messages = st.messages.map { if (it.mine && it.friendId == fid && it.id in refs) it.copy(status = ChatStatus.READ) else it })
            }
            is ChatPayload.Profile, is ChatPayload.Unknown -> Unit
        }
    }

    /** Receiver side of `profile` (SPEC 5d.6): keep the newest rev only. The link already sent the ack. */
    private fun onProfile(rec: Rec, p: ChatPayload.Profile) {
        val fid = rec.friendId ?: return
        store.update { s ->
            s.copy(friends = s.friends.map { f ->
                if (f.id == fid && !f.blocked && InstagramShareState.acceptReceived(f.peerIgRev, p.rev)) {
                    f.copy(peerIg = InstagramHandle.received(p.ig), peerIgRev = p.rev)
                } else f
            })
        }
        host.log("link ${rec.id}: profile rev ${p.rev} (${if (p.ig == null) "no handle" else "handle"})")
    }

    /**
     * Sender side of `profile` (SPEC 5d.6): applies the current effective value (the handle when
     * set and this friend's switch is on) and sends it when the friend has not acknowledged the
     * current rev. One send per rev per link; a new link re-sends with a fresh id.
     */
    private fun sendProfileIfNeeded(rec: Rec) {
        val fid = rec.friendId ?: return
        if (rec.link.state != LinkState.SECURE) return
        val f = store.state.value.friend(fid) ?: return
        if (f.blocked) return
        val me = host.localProfile()
        val on = f.igShareOn ?: me?.shareInstagramByDefault ?: true
        var st = (f.igState ?: InstagramShareState.initial(me?.instagram, on)).withEffective(InstagramShareState.effective(me?.instagram, on))
        if (st.needsSend && rec.profileSentRev != st.rev) {
            val id = try {
                rec.link.sendProfile(st.rev, st.value)
            } catch (e: IllegalStateException) {
                null
            }
            if (id != null) {
                st = st.sent(id)
                rec.profileSentRev = st.rev
                rec.chatTokens -= 1
                rec.lastActivity = clock()
            }
        }
        if (st != f.igState) store.update { s -> s.copy(friends = s.friends.map { if (it.id == fid) it.copy(igState = st) else it }) }
    }

    /** Compatibility of the owner with [peer] (the friend backfill and friends made without a card). */
    private fun rescore(peer: WireCompat): StoredScore? {
        val me = host.localProfile()?.compat ?: return null
        return runCatching { Compatibility.score(me.toCompatProfile(), peer.toCompatProfile()) }.getOrNull()?.let(::storedScoreOf)
    }

    private fun trim(messages: List<ChatLine>, friendId: String): List<ChatLine> {
        val mine = messages.count { it.friendId == friendId }
        if (mine <= P2pStore.MAX_MESSAGES_PER_FRIEND) return messages
        var drop = mine - P2pStore.MAX_MESSAGES_PER_FRIEND
        return messages.filter { m -> if (drop > 0 && m.friendId == friendId && m.status != ChatStatus.QUEUED) { drop--; false } else true }
    }

    private fun hasPendingFor(friendId: String?): Boolean {
        friendId ?: return false
        return store.state.value.messages.any { it.friendId == friendId && ((it.mine && it.status == ChatStatus.QUEUED) || (!it.mine && it.seen && !it.readReceiptSent)) }
    }

    /** Store-and-forward: queued chats in order, paced below the peer's inbound chat bucket (10 burst, 1/s). */
    private fun deliverQueued(rec: Rec) {
        val fid = rec.friendId ?: return
        if (rec.link.state != LinkState.SECURE) return
        val now = clock()
        rec.chatTokens = minOf(CHAT_BURST.toDouble(), rec.chatTokens + (now - rec.chatTokensAt).coerceAtLeast(0) * CHAT_RATE_PER_S / 1000.0)
        rec.chatTokensAt = now
        val inflightElsewhere = links.values.filter { it !== rec }.flatMap { it.inflight }.toSet()
        val s = store.state.value
        val queued = s.messages.filter {
            it.mine && it.friendId == fid && it.id !in rec.inflight && it.id !in inflightElsewhere &&
                (it.status == ChatStatus.QUEUED || it.status == ChatStatus.SENT)
        }.sortedBy { it.ts }
        val sent = ArrayList<String>()
        for (m in queued) {
            if (rec.chatTokens < 1) break
            try {
                rec.link.sendChat(m.text, m.id, m.ts)
            } catch (e: IllegalStateException) {
                break
            }
            rec.chatTokens -= 1
            rec.inflight += m.id
            sent += m.id
        }
        val readRefs = s.messages.filter { !it.mine && it.friendId == fid && it.seen && !it.readReceiptSent }.map { it.id }
        val receipted = ArrayList<String>()
        for (chunk in readRefs.chunked(64)) {
            if (rec.chatTokens < 1) break
            try {
                rec.link.sendRead(chunk)
            } catch (e: IllegalStateException) {
                break
            }
            rec.chatTokens -= 1
            receipted += chunk
        }
        if (sent.isEmpty() && receipted.isEmpty()) return
        rec.lastActivity = now
        val sentSet = sent.toSet()
        val rSet = receipted.toSet()
        store.update { st ->
            st.copy(messages = st.messages.map {
                when {
                    it.id in sentSet && it.mine && it.status == ChatStatus.QUEUED -> it.copy(status = ChatStatus.SENT)
                    it.id in rSet && !it.mine -> it.copy(readReceiptSent = true)
                    else -> it
                }
            })
        }
        host.log("link ${rec.id}: sent ${sent.size} chat(s), ${receipted.size} read receipt(s)")
    }

    /** Messages written to a link that ended before their ack go back to the queue (same id; the receiver de-duplicates). */
    private fun requeue(rec: Rec) {
        if (rec.inflight.isEmpty()) return
        val ids = rec.inflight.toSet()
        rec.inflight.clear()
        store.update { s ->
            s.copy(messages = s.messages.map { if (it.mine && it.id in ids && it.status == ChatStatus.SENT) it.copy(status = ChatStatus.QUEUED) else it })
        }
    }

    private fun updateEncounter(fingerprint: String?, transform: (StoredEncounter) -> StoredEncounter) {
        fingerprint ?: return
        store.update { s -> s.copy(encounters = s.encounters.map { if (it.fingerprint == fingerprint) transform(it) else it }) }
    }

    private fun screenMessage(id: String, text: String) {
        scope.launch {
            val verdict = screener.screenText(text)
            store.update { s -> s.copy(messages = s.messages.map { if (it.id == id && !it.mine) it.copy(screening = verdict) else it }) }
        }
    }

    private fun screenPhotoFile(path: String) {
        scope.launch {
            val bytes = runCatching { java.io.File(path).readBytes() }.getOrNull()
            val bitmap = bytes?.let(decodePhoto)
            val verdict = if (bitmap == null) Screening.FLAGGED else screener.screenPhoto(bitmap)
            store.update { s ->
                s.copy(
                    encounters = s.encounters.map { if (it.photoPath == path) it.copy(photoScreening = verdict) else it },
                    friends = s.friends.map { if (it.photoPath == path) it.copy(photoScreening = verdict) else it },
                )
            }
        }
    }

    /** Every minute: drop expired inbox cards (and their photos) and expired hide entries. */
    private fun housekeeping(now: Long, force: Boolean = false) {
        if (!force && now - lastHousekeeping < 60_000) return
        lastHousekeeping = now
        if (!store.isLoaded) return
        val s = store.state.value
        val expired = s.encounters.filter { it.friendId == null && it.expiresAt < now }
        val hiddenExpired = s.hidden.filter { it.until < now }
        if (expired.isEmpty() && hiddenExpired.isEmpty()) return
        val keepPhotos = (s.friends.mapNotNull { it.photoPath } + (s.encounters - expired.toSet()).mapNotNull { it.photoPath }).toSet()
        expired.mapNotNull { it.photoPath }.filter { it !in keepPhotos }.forEach { runCatching { java.io.File(it).delete() } }
        store.update { st -> st.copy(encounters = st.encounters - expired.toSet(), hidden = st.hidden - hiddenExpired.toSet()) }
    }

    private fun publishStatus() {
        _status.value = _status.value.copy(
            openLinks = links.size,
            friendsInRange = links.values.filter { it.link.state == LinkState.SECURE && it.friendId != null }.mapNotNull { it.friendId }.toSet(),
        )
    }

    companion object {
        const val MAX_LINKS = 4
        const val INBOX_TTL_MS = 24 * 3_600_000L
        const val RENOTIFY_MS = 24 * 3_600_000L
        const val LEFT_SWIPE_HIDE_MS = 30 * 24 * 3_600_000L
        const val SNOOZE_MS = 7 * 24 * 3_600_000L
        /** A secure friend link with nothing to send closes after this long without traffic. */
        const val SECURE_IDLE_MS = 5 * 60_000L
        /** Outgoing chat pacing (the peer accepts a burst of 10, then 1 per second). */
        const val CHAT_BURST = 8
        const val CHAT_RATE_PER_S = 0.8

        fun b64(b: ByteArray): String = java.util.Base64.getEncoder().encodeToString(b)
        fun b64d(s: String): ByteArray = java.util.Base64.getDecoder().decode(s)

        fun hexOf(b: ByteArray): String = buildString(b.size * 2) { for (x in b) append("%02x".format(x.toInt() and 0xFF)) }

        fun newMessageId(random: SecureRandom = SecureRandom()): String = hexOf(ByteArray(16).also(random::nextBytes))

        fun storedScoreOf(r: CompatResult) = StoredScore(
            r.total, r.western, r.chinese, r.blood, r.highlights, r.isBirthdayTwin, r.isExactTwin, r.chineseRelation.name, r.stemRelation.name,
        )
    }
}
