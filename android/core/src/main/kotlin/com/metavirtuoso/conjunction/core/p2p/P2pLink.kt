package com.metavirtuoso.conjunction.core.p2p

import com.metavirtuoso.conjunction.core.match.MatchKind
import com.metavirtuoso.conjunction.core.match.MatchProfile
import java.security.SecureRandom
import java.time.LocalDate
import java.util.Random

/** Our side of a link: what goes into hello and card. */
class LocalP2pProfile(
    val eid: String,
    val match: MatchProfile,
    val compat: WireCompat,
    val firstName: String,
    val bio: String? = null,
    /** Approved profile photo: JPEG, longest side <= 640, <= 61440 bytes. */
    val photo: ByteArray? = null,
    val photoW: Int = 0,
    val photoH: Int = 0,
) {
    init {
        require(EphemeralIdSource.isValid(eid)) { "bad eid" }
        require(firstName.toByteArray().size in 1..P2pConstants.MAX_FIRST_NAME_BYTES) { "firstName length" }
        bio?.let { require(it.toByteArray().size <= P2pConstants.MAX_BIO_BYTES) { "bio length" } }
        photo?.let {
            require(it.size in 1..P2pConstants.MAX_BLOB_LEN) { "photo size" }
            require(photoW in 1..P2pConstants.MAX_PHOTO_SIDE && photoH in 1..P2pConstants.MAX_PHOTO_SIDE) { "photo dimensions" }
        }
    }
}

/** A stored friend: their identity public key and our shared friend secret (section 8.5). */
class FriendRecord(val id: String, val identityKey: ByteArray, val friendSecret: ByteArray, val blocked: Boolean = false)

/** What the link asks the app. Implementations must be cheap and non-blocking. */
interface LinkPolicy {
    /** Friends, most recently seen first (the first 56 non-blocked ones get tags in our hello). */
    fun friends(): List<FriendRecord> = emptyList()

    /** True for strangers hidden after a left swipe (30 days) or blocked, by profile fingerprint. */
    fun isHidden(fingerprint: String): Boolean = false

    /** A swipe the user already made for this fingerprint while out of range (24-h inbox). */
    fun presetSwipe(fingerprint: String): SwipeDirection? = null

    /** The phone's local date (age gate). */
    fun today(): LocalDate = LocalDate.now()
}

enum class LinkRole { CENTRAL, PERIPHERAL }

enum class LinkState { AWAIT_HELLO, MATCHED, KEYX, SECURE, CLOSED }

sealed class LinkEvent {
    data class HelloReceived(val hello: P2pMessage.Hello, val fingerprint: String) : LinkEvent()
    object NoMatch : LinkEvent()
    data class Match(val match: P2pMatch, val fingerprint: String, val peer: P2pMessage.Hello) : LinkEvent()
    data class CardReceived(val card: P2pMessage.Card) : LinkEvent()
    /** Unscreened JPEG; the app must screen it on device before showing it. */
    class PhotoReceived(val jpeg: ByteArray, val sha256: String) : LinkEvent()
    data class PeerSwiped(val dir: SwipeDirection) : LinkEvent()
    /** Mutual right swipe completed and keys confirmed: store the friend record. */
    class FriendEstablished(
        val peerIdentityKey: ByteArray,
        val friendSecret: ByteArray,
        val safetyCode: String,
        val card: P2pMessage.Card?,
        val photo: ByteArray?,
        val peer: P2pMessage.Hello,
    ) : LinkEvent()
    /** A known friend is nearby and the link is secure: send queued chats now. */
    data class FriendNearby(val friend: FriendRecord) : LinkEvent()
    data class ChatReceived(val payload: ChatPayload) : LinkEvent()
    data class Delivered(val id: String) : LinkEvent()
    data class ProtocolError(val code: String, val detail: String?, val byPeer: Boolean) : LinkEvent()
    data class Closed(val reason: String, val byPeer: Boolean) : LinkEvent()
}

/**
 * Transport-free per-link protocol state machine (docs/P2P_PROTOCOL.md section 6).
 *
 * The BLE layer calls [setAttMtu], [start], [onFrame] and [tick], and writes [takeFrames] to the
 * peer in order (central: INBOX writes; peripheral: OUTBOX notifications). The app calls [swipe],
 * [sendChat], [sendRead], [close] and consumes [takeEvents]. Not thread-safe: drive each link from
 * one thread. Equivalent to tools/p2p-sim/conjunction_p2p/link.py.
 */
class P2pLink(
    val role: LinkRole,
    private val local: LocalP2pProfile,
    private val identity: P256Agreement,
    private val policy: LinkPolicy,
    nowMs: Long,
    private val ephemeral: P256Agreement = SoftwareP256Key.generate(),
    private val random: Random = SecureRandom(),
) {
    var state: LinkState = LinkState.AWAIT_HELLO
        private set
    var frameLen: Int = P2pConstants.MIN_FRAME_LEN
        private set

    var peerHello: P2pMessage.Hello? = null
        private set
    var fingerprint: String? = null
        private set
    var match: P2pMatch? = null
        private set
    var friend: FriendRecord? = null
        private set

    private val rx = Reassembler()
    private var nextMsgId = 0
    private val frames = ArrayList<ByteArray>()
    private val events = ArrayList<LinkEvent>()
    private val startedMs = nowMs
    private var lastRxMs = nowMs
    private var stateSinceMs = nowMs
    /** First tick seen in KEYX (entering KEYX via a local swipe has no timestamp). */
    private var keyxSeenMs: Long? = null
    private val bFrames = Bucket(P2pConstants.RATE_FRAMES_CAP, P2pConstants.RATE_FRAMES_PER_S, nowMs)
    private val bMsgs = Bucket(P2pConstants.RATE_MESSAGES_CAP, P2pConstants.RATE_MESSAGES_PER_S, nowMs)
    private val bChats = Bucket(P2pConstants.RATE_CHATS_CAP, P2pConstants.RATE_CHATS_PER_S, nowMs)

    private var sentHello = false
    private var myHello: P2pMessage.Hello? = null
    private var friendMode = false
    private var mySwipe: SwipeDirection? = null
    private var peerSwipe: SwipeDirection? = null
    private var peerCard: P2pMessage.Card? = null
    private var peerPhoto: ByteArray? = null
    private var sentKeyx = false
    private var peerIk: ByteArray? = null
    private var session: P2pSession? = null
    private var sendCtr = 0L
    private var recvCtr = 0L

    val isClosed: Boolean get() = state == LinkState.CLOSED

    // ------------------------------------------------------------------ transport API

    fun setAttMtu(attMtu: Int) {
        frameLen = P2pConstants.maxFrameLen(attMtu)
    }

    /** Link ready (central: MTU exchanged and OUTBOX notifications enabled). Central sends hello. */
    fun start() {
        if (role == LinkRole.CENTRAL && !sentHello && state == LinkState.AWAIT_HELLO) sendHello()
    }

    fun takeFrames(): List<ByteArray> = ArrayList(frames).also { frames.clear() }

    fun takeEvents(): List<LinkEvent> = ArrayList(events).also { events.clear() }

    fun onFrame(frame: ByteArray, nowMs: Long) {
        if (state == LinkState.CLOSED) return
        lastRxMs = nowMs
        if (!bFrames.take(nowMs)) return fail(P2pErrorCode.RATE, "too many frames")
        try {
            val body = rx.accept(frame, nowMs) ?: return
            if (!bMsgs.take(nowMs)) return fail(P2pErrorCode.RATE, "too many messages")
            val decoded = P2pMessages.decode(body)
            handle(decoded.message, decoded.blob, nowMs)
        } catch (e: P2pException) {
            fail(e.code, e.detail)
        } catch (e: P2pCryptoException) {
            fail(P2pErrorCode.CRYPTO, e.message ?: "crypto")
        }
    }

    fun tick(nowMs: Long) {
        if (state == LinkState.CLOSED) return
        rx.expire(nowMs)
        when {
            state == LinkState.AWAIT_HELLO && nowMs - startedMs > P2pConstants.HELLO_TIMEOUT_MS -> close("timeout")
            state == LinkState.KEYX -> {
                val since = maxOf(keyxSeenMs ?: nowMs.also { keyxSeenMs = it }, lastRxMs)
                if (nowMs - since > P2pConstants.KEYX_TIMEOUT_MS) close("timeout")
            }
            state == LinkState.MATCHED && nowMs - stateSinceMs > P2pConstants.MATCH_LINK_TIMEOUT_MS -> close("timeout")
        }
    }

    // ------------------------------------------------------------------ app API

    fun swipe(dir: SwipeDirection) {
        check(state == LinkState.MATCHED && mySwipe == null) { "cannot swipe in state $state" }
        mySwipe = dir
        send(P2pMessage.Swipe(dir))
        resolveSwipes()
    }

    /** Sends a text chat; returns its id. [id] stays the same when re-sending a queued message. */
    fun sendChat(text: String, id: String = newMessageId(), tsMs: Long = System.currentTimeMillis()): String {
        check(state == LinkState.SECURE) { "chat requires a secure link" }
        require(text.toByteArray().size in 1..P2pConstants.MAX_CHAT_TEXT_BYTES) { "chat text length" }
        sendSecure(ChatPayload.Text(id, tsMs, text))
        return id
    }

    fun sendRead(refs: List<String>, id: String = newMessageId(), tsMs: Long = System.currentTimeMillis()): String {
        check(state == LinkState.SECURE) { "read receipts require a secure link" }
        require(refs.size in 1..P2pConstants.MAX_READ_REFS)
        sendSecure(ChatPayload.Read(id, tsMs, refs))
        return id
    }

    fun close(reason: String = "done") {
        if (state == LinkState.CLOSED) return
        send(P2pMessage.Bye(reason))
        closed(reason, byPeer = false)
    }

    fun newMessageId(): String = ByteArray(16).also(random::nextBytes).let(::hexOf)

    // ------------------------------------------------------------------ internals

    private fun send(msg: P2pMessage, blob: ByteArray = FrameCodec.EMPTY) {
        frames += FrameCodec.encode(nextMsgId, P2pMessages.encode(msg, blob), frameLen)
        nextMsgId = (nextMsgId + 1) and 0xFFFF
    }

    private fun sendSecure(payload: ChatPayload) {
        val s = session!!
        val ct = P2pCrypto.seal(s.sendKey, sendCtr, P2pMessages.encodeChatPayload(payload))
        send(P2pMessage.Chat(sendCtr, b64(ct)))
        sendCtr++
    }

    private fun closed(reason: String, byPeer: Boolean) {
        state = LinkState.CLOSED
        events += LinkEvent.Closed(reason, byPeer)
    }

    private fun fail(code: P2pErrorCode, detail: String) {
        if (state == LinkState.CLOSED) return
        send(P2pMessage.Error(code.wire, detail.take(200).ifEmpty { null }))
        events += LinkEvent.ProtocolError(code.wire, detail, byPeer = false)
        closed("error", byPeer = false)
    }

    private fun stateError(detail: String): Nothing = throw P2pException(P2pErrorCode.STATE, detail)

    private fun friendTags(): List<String> {
        val real = policy.friends().filter { !it.blocked }.take(P2pConstants.FRIEND_TAGS_REAL_MAX)
            .map { P2pCrypto.friendTag(it.friendSecret, local.eid) }.toMutableList()
        val block = P2pConstants.FRIEND_TAGS_BLOCK
        val n = maxOf(block, (real.size + block - 1) / block * block)
        while (real.size < n) real += ByteArray(P2pConstants.FRIEND_TAG_LEN).also(random::nextBytes)
        real.shuffle(random)
        return real.map(::b64)
    }

    private fun sendHello() {
        val h = P2pMessage.Hello(
            P2pConstants.PROTOCOL_VERSION, P2pConstants.PROTOCOL_MIN_VERSION,
            local.eid, local.match, local.compat, friendTags(),
        )
        myHello = h
        send(h)
        sentHello = true
    }

    private fun handle(msg: P2pMessage, blob: ByteArray, nowMs: Long) {
        when (msg) {
            is P2pMessage.Unknown -> return
            is P2pMessage.Bye -> return closed(msg.reason, byPeer = true)
            is P2pMessage.Error -> {
                events += LinkEvent.ProtocolError(msg.code, msg.detail, byPeer = true)
                return closed("error", byPeer = true)
            }
            is P2pMessage.Hello -> {
                if (state != LinkState.AWAIT_HELLO || peerHello != null) stateError("duplicate hello")
                return onHello(msg, nowMs)
            }
            else -> if (peerHello == null) stateError("${msg.type} before hello")
        }
        when (msg) {
            is P2pMessage.Card -> {
                if (state != LinkState.MATCHED || peerCard != null) stateError("unexpected card")
                if (msg.kind != match?.kind) return close("no_match")
                peerCard = msg
                events += LinkEvent.CardReceived(msg)
            }
            is P2pMessage.Photo -> {
                val meta = peerCard?.photo
                if (state !in setOf(LinkState.MATCHED, LinkState.KEYX, LinkState.SECURE) || meta == null || peerPhoto != null) {
                    stateError("unexpected photo")
                }
                if (blob.size != meta.size || hexOf(P2pCrypto.sha256(blob)) != meta.sha256 || msg.sha256 != meta.sha256) {
                    malformed("photo does not match card hash/size")
                }
                peerPhoto = blob
                events += LinkEvent.PhotoReceived(blob, msg.sha256)
            }
            is P2pMessage.Swipe -> {
                if (state != LinkState.MATCHED || peerSwipe != null) stateError("unexpected swipe")
                peerSwipe = msg.dir
                events += LinkEvent.PeerSwiped(msg.dir)
                resolveSwipes()
            }
            is P2pMessage.KeyX -> {
                if (state != LinkState.KEYX || !sentKeyx || session != null) stateError("unexpected keyx")
                val ik = msg.ikBytes
                if (friendMode && !ik.contentEquals(friend!!.identityKey)) {
                    throw P2pCryptoException("identity key does not match recognised friend")
                }
                peerIk = ik
                val s = P2pCrypto.deriveSession(identity, ephemeral, ik, msg.ekBytes)
                session = s
                send(P2pMessage.Kc(b64(s.myKc)))
            }
            is P2pMessage.Kc -> {
                val s = session
                if (state != LinkState.KEYX || s == null) stateError("unexpected kc")
                if (!P2pCrypto.constantTimeEquals(msg.macBytes, s.peerKc)) throw P2pCryptoException("key confirmation failed")
                state = LinkState.SECURE
                if (friendMode) {
                    events += LinkEvent.FriendNearby(friend!!)
                } else {
                    val ik = peerIk!!
                    events += LinkEvent.FriendEstablished(
                        peerIdentityKey = ik,
                        friendSecret = P2pCrypto.friendSecret(identity, ik),
                        safetyCode = P2pCrypto.safetyCode(identity.publicKey, ik),
                        card = peerCard,
                        photo = peerPhoto,
                        peer = peerHello!!,
                    )
                }
            }
            is P2pMessage.Chat -> {
                if (state != LinkState.SECURE) stateError("chat before secure link")
                if (!bChats.take(nowMs)) throw P2pException(P2pErrorCode.RATE, "too many chat messages")
                if (msg.ctr != recvCtr) throw P2pCryptoException("chat counter ${msg.ctr} != expected $recvCtr")
                val pt = P2pCrypto.open(session!!.recvKey, msg.ctr, msg.ctBytes)
                recvCtr++
                val payload = P2pMessages.decodeChatPayload(pt)
                if (payload is ChatPayload.Unknown) return
                events += LinkEvent.ChatReceived(payload)
                if (payload is ChatPayload.Text) send(P2pMessage.Ack(payload.id))
            }
            is P2pMessage.Ack -> {
                if (state != LinkState.SECURE) stateError("ack before secure link")
                events += LinkEvent.Delivered(msg.id)
            }
        }
    }

    private fun onHello(peer: P2pMessage.Hello, nowMs: Long) {
        peerHello = peer
        if (role == LinkRole.PERIPHERAL && !sentHello) sendHello()
        if (peer.eid == local.eid) stateError("peer uses our ephemeral id")
        if (minOf(peer.pv, P2pConstants.PROTOCOL_VERSION) < maxOf(peer.pmin, P2pConstants.PROTOCOL_MIN_VERSION)) {
            throw P2pException(P2pErrorCode.VERSION, "no common version (peer ${peer.pmin}..${peer.pv})")
        }
        val fp = P2pCrypto.profileFingerprint(peer.compat)
        fingerprint = fp
        events += LinkEvent.HelloReceived(peer, fp)

        // 1. Friend recognition.
        val tags = peer.ft.map { b64d(it).toList() }.toSet()
        for (f in policy.friends()) {
            if (P2pCrypto.friendTag(f.friendSecret, peer.eid).toList() in tags) {
                if (f.blocked) return close("done")
                friendMode = true
                friend = f
                state = LinkState.KEYX
                stateSinceMs = nowMs
                sendKeyx()
                return
            }
        }
        // 2. Hidden / blocked stranger.
        if (policy.isHidden(fp)) return close("done")
        // 3. Matching.
        val m = P2pMatching.evaluate(myHello!!, peer, policy.today())
        if (m == null) {
            events += LinkEvent.NoMatch
            return close("no_match")
        }
        match = m
        state = LinkState.MATCHED
        stateSinceMs = nowMs
        events += LinkEvent.Match(m, fp, peer)
        sendCard(m.kind)
        policy.presetSwipe(fp)?.let { swipe(it) }
    }

    private fun sendCard(kind: MatchKind) {
        val photo = local.photo?.takeIf { frameLen >= P2pConstants.PHOTO_MIN_FRAME_LEN }
        val meta = photo?.let { PhotoMeta(hexOf(P2pCrypto.sha256(it)), it.size, local.photoW, local.photoH) }
        send(P2pMessage.Card(kind, local.firstName, local.bio, meta))
        if (photo != null && meta != null) send(P2pMessage.Photo(meta.sha256), photo)
    }

    private fun sendKeyx() {
        send(P2pMessage.KeyX.of(identity.publicKey, ephemeral.publicKey))
        sentKeyx = true
    }

    private fun resolveSwipes() {
        val mine = mySwipe ?: return
        if (mine == SwipeDirection.LEFT) return close("done")
        if (peerSwipe == SwipeDirection.RIGHT) {
            state = LinkState.KEYX
            sendKeyx()
        }
        // Peer swiped left: the peer closes the link; left is silent.
    }

    private class Bucket(private val cap: Int, private val perSecond: Int, nowMs: Long) {
        private var tokens = cap.toDouble()
        private var t = nowMs

        fun take(nowMs: Long): Boolean {
            tokens = minOf(cap.toDouble(), tokens + (nowMs - t).coerceAtLeast(0) * perSecond / 1000.0)
            t = maxOf(t, nowMs)
            if (tokens < 1) return false
            tokens -= 1
            return true
        }
    }
}
