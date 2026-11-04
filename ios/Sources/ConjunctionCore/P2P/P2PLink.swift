import Foundation

/// Our side of a link: what goes into hello and card.
public struct LocalP2PProfile: Sendable {
    public let eid: String
    public let match: MatchProfile
    public let compat: WireCompat
    public let firstName: String
    public let bio: String?
    /// Approved profile photo: JPEG, longest side <= 640, <= 61440 bytes.
    public let photo: [UInt8]?
    public let photoW: Int
    public let photoH: Int

    public init(eid: String, match: MatchProfile, compat: WireCompat, firstName: String, bio: String? = nil,
                photo: [UInt8]? = nil, photoW: Int = 0, photoH: Int = 0) throws {
        guard EphemeralIdSource.isValid(eid) else { throw P2PUsageError("bad eid") }
        guard (1...P2PConstants.maxFirstNameBytes).contains(firstName.utf8.count) else { throw P2PUsageError("firstName length") }
        if let b = bio, b.utf8.count > P2PConstants.maxBioBytes { throw P2PUsageError("bio length") }
        if let p = photo {
            guard (1...P2PConstants.maxBlobLen).contains(p.count) else { throw P2PUsageError("photo size") }
            guard (1...P2PConstants.maxPhotoSide).contains(photoW), (1...P2PConstants.maxPhotoSide).contains(photoH) else {
                throw P2PUsageError("photo dimensions")
            }
        }
        self.eid = eid
        self.match = match
        self.compat = compat
        self.firstName = firstName
        self.bio = bio
        self.photo = photo
        self.photoW = photoW
        self.photoH = photoH
    }
}

/// A stored friend: their identity public key and our shared friend secret (section 8.5).
public struct FriendRecord: Hashable, Sendable {
    public let id: String
    public let identityKey: [UInt8]
    public let friendSecret: [UInt8]
    public let blocked: Bool

    public init(id: String, identityKey: [UInt8], friendSecret: [UInt8], blocked: Bool = false) {
        self.id = id
        self.identityKey = identityKey
        self.friendSecret = friendSecret
        self.blocked = blocked
    }
}

/// What the link asks the app. Implementations must be cheap and non-blocking.
public protocol LinkPolicy {
    /// Friends, most recently seen first (the first 56 non-blocked ones get tags in our hello).
    func friends() -> [FriendRecord]
    /// True for strangers hidden after a left swipe (30 days) or blocked, by profile fingerprint.
    func isHidden(_ fingerprint: String) -> Bool
    /// A swipe the user already made for this fingerprint while out of range (24-h inbox).
    func presetSwipe(_ fingerprint: String) -> SwipeDirection?
    /// The phone's local date (age gate).
    func today() -> LocalDate
}

public extension LinkPolicy {
    func friends() -> [FriendRecord] { [] }
    func isHidden(_ fingerprint: String) -> Bool { false }
    func presetSwipe(_ fingerprint: String) -> SwipeDirection? { nil }
    func today() -> LocalDate { LocalDate.todayInCurrentTimeZone() }
}

extension LocalDate {
    /// The device's current local date.
    public static func todayInCurrentTimeZone() -> LocalDate {
        let c = Calendar(identifier: .gregorian).dateComponents(in: TimeZone.current, from: Date())
        return LocalDate(c.year!, c.month!, c.day!)
    }
}

public enum LinkRole: Sendable { case central, peripheral }

public enum LinkState: Sendable { case awaitHello, matched, keyx, secure, closed }

public enum LinkEvent: Equatable, Sendable {
    case helloReceived(hello: Hello, fingerprint: String)
    case noMatch
    case match(P2PMatch, fingerprint: String, peer: Hello)
    case cardReceived(Card)
    /// Unscreened JPEG; the app must screen it on device (SensitiveContentAnalysis when enabled,
    /// otherwise blurred until the user taps "show") before showing it.
    case photoReceived(jpeg: [UInt8], sha256: String)
    case peerSwiped(SwipeDirection)
    /// Mutual right swipe completed and keys confirmed: store the friend record.
    case friendEstablished(peerIdentityKey: [UInt8], friendSecret: [UInt8], safetyCode: String,
                           card: Card?, photo: [UInt8]?, peer: Hello)
    /// A known friend is nearby and the link is secure: send queued chats now.
    case friendNearby(FriendRecord)
    /// Text or read receipt (never `.profile`: that arrives as `profileReceived`).
    case chatReceived(ChatPayload)
    /// SPEC 5d.6: the friend's Instagram handle (`ig` already validated; nil = not shared). Store it as
    /// peerIg/peerIgRev only when `InstagramShareState.acceptReceived` (rev > stored peerIgRev). The
    /// link has already sent the `ack`.
    case profileReceived(id: String, ts: Int64, rev: Int, ig: String?)
    case delivered(id: String)
    case protocolError(code: String, detail: String?, byPeer: Bool)
    case closed(reason: String, byPeer: Bool)
}

/// Transport-free per-link protocol state machine (docs/P2P_PROTOCOL.md section 6), the Swift
/// twin of Kotlin `P2pLink` and Python `Link`.
///
/// The BLE layer calls `setAttMtu`, `start`, `onFrame` and `tick`, and writes `takeFrames()` to
/// the peer in order (central: INBOX writes with response; peripheral: OUTBOX notifications). The
/// app calls `swipe`, `sendChat`, `sendRead`, `close` and consumes `takeEvents()`. Not
/// thread-safe: drive each link from one serial queue (the CoreBluetooth queue).
public final class P2PLink {
    public let role: LinkRole
    private let local: LocalP2PProfile
    private let identity: any P256Agreement
    private let policy: any LinkPolicy
    private let ephemeral: any P256Agreement
    private let randomBytes: (Int) -> [UInt8]

    public private(set) var state: LinkState = .awaitHello
    public private(set) var frameLen: Int = P2PConstants.minFrameLen
    public private(set) var peerHello: Hello?
    public private(set) var fingerprint: String?
    public private(set) var match: P2PMatch?
    public private(set) var friend: FriendRecord?

    private var rx = Reassembler()
    private var nextMsgId = 0
    private var frames: [[UInt8]] = []
    private var events: [LinkEvent] = []
    private let startedMs: Int64
    private var lastRxMs: Int64
    private var stateSinceMs: Int64
    /// First tick seen in KEYX (entering KEYX via a local swipe has no timestamp).
    private var keyxSeenMs: Int64?
    private var bFrames: Bucket
    private var bMsgs: Bucket
    private var bChats: Bucket

    private var sentHello = false
    private var myHello: Hello?
    private var friendMode = false
    private var mySwipe: SwipeDirection?
    private var peerSwipe: SwipeDirection?
    private var peerCard: Card?
    private var peerPhoto: [UInt8]?
    private var sentKeyx = false
    private var peerIk: [UInt8]?
    private var session: P2PSession?
    private var sendCtr: Int64 = 0
    private var recvCtr: Int64 = 0

    /// [ephemeral] defaults to a fresh key per link; [randomBytes] (decoy tags, shuffling,
    /// message ids) to the system CSPRNG. Tests pass fixed ones.
    public init(role: LinkRole, local: LocalP2PProfile, identity: any P256Agreement, policy: any LinkPolicy,
                nowMs: Int64, ephemeral: (any P256Agreement)? = nil,
                randomBytes: @escaping (Int) -> [UInt8] = P2PRandom.bytes) {
        self.role = role
        self.local = local
        self.identity = identity
        self.policy = policy
        self.ephemeral = ephemeral ?? SoftwareP256Key.generate()
        self.randomBytes = randomBytes
        startedMs = nowMs
        lastRxMs = nowMs
        stateSinceMs = nowMs
        bFrames = Bucket(cap: P2PConstants.rateFramesCap, perSecond: P2PConstants.rateFramesPerSecond, nowMs: nowMs)
        bMsgs = Bucket(cap: P2PConstants.rateMessagesCap, perSecond: P2PConstants.rateMessagesPerSecond, nowMs: nowMs)
        bChats = Bucket(cap: P2PConstants.rateChatsCap, perSecond: P2PConstants.rateChatsPerSecond, nowMs: nowMs)
    }

    public var isClosed: Bool { state == .closed }

    // ------------------------------------------------------------------ transport API

    /// iOS central: `peripheral.maximumWriteValueLength(for: .withoutResponse) + 3`;
    /// iOS peripheral: `central.maximumUpdateValueLength + 3` (section 2.4).
    public func setAttMtu(_ attMtu: Int) {
        frameLen = P2PConstants.maxFrameLen(attMtu: attMtu)
    }

    /// Link ready (central: MTU known and OUTBOX notifications enabled). The central sends hello.
    public func start() {
        if role == .central && !sentHello && state == .awaitHello { guarded { try sendHello() } }
    }

    public func takeFrames() -> [[UInt8]] {
        defer { frames.removeAll() }
        return frames
    }

    public func takeEvents() -> [LinkEvent] {
        defer { events.removeAll() }
        return events
    }

    public func onFrame(_ frame: [UInt8], nowMs: Int64) {
        if state == .closed { return }
        lastRxMs = nowMs
        if !bFrames.take(nowMs) { return fail(.rate, "too many frames") }
        guarded {
            guard let body = try rx.accept(frame, nowMs: nowMs) else { return }
            if !bMsgs.take(nowMs) { return fail(.rate, "too many messages") }
            let decoded = try P2PMessages.decode(body)
            try handle(decoded.message, blob: decoded.blob, nowMs: nowMs)
        }
    }

    public func tick(nowMs: Int64) {
        if state == .closed { return }
        rx.expire(nowMs: nowMs)
        switch state {
        case .awaitHello where nowMs - startedMs > P2PConstants.helloTimeoutMs:
            close(reason: "timeout")
        case .keyx:
            if keyxSeenMs == nil { keyxSeenMs = nowMs }
            if nowMs - max(keyxSeenMs!, lastRxMs) > P2PConstants.keyxTimeoutMs { close(reason: "timeout") }
        case .matched where nowMs - stateSinceMs > P2PConstants.matchLinkTimeoutMs:
            close(reason: "timeout")
        default:
            break
        }
    }

    // ------------------------------------------------------------------ app API

    public func swipe(_ dir: SwipeDirection) throws {
        guard state == .matched, mySwipe == nil else { throw P2PUsageError("cannot swipe in state \(state)") }
        mySwipe = dir
        try send(.swipe(dir))
        try resolveSwipes()
    }

    /// Sends a text chat; returns its id. [id] stays the same when re-sending a queued message.
    @discardableResult
    public func sendChat(_ text: String, id: String? = nil, tsMs: Int64? = nil) throws -> String {
        guard state == .secure else { throw P2PUsageError("chat requires a secure link") }
        guard (1...P2PConstants.maxChatTextBytes).contains(text.utf8.count) else { throw P2PUsageError("chat text length") }
        let mid = id ?? newMessageId()
        guard P2PMessages.isHex(mid, 32) else { throw P2PUsageError("bad message id") }
        try sendSecure(.text(id: mid, ts: tsMs ?? P2PLink.nowMs(), text: text))
        return mid
    }

    @discardableResult
    public func sendRead(_ refs: [String], id: String? = nil, tsMs: Int64? = nil) throws -> String {
        guard state == .secure else { throw P2PUsageError("read receipts require a secure link") }
        guard (1...P2PConstants.maxReadRefs).contains(refs.count), refs.allSatisfy({ P2PMessages.isHex($0, 32) }) else {
            throw P2PUsageError("read receipt refs")
        }
        let mid = id ?? newMessageId()
        try sendSecure(.read(id: mid, ts: tsMs ?? P2PLink.nowMs(), refs: refs))
        return mid
    }

    /// Sends a chat `profile` (SPEC 5d.6): `ig` = the normalised handle shared with this friend, or nil.
    /// Only on a SECURE link (after a mutual match or a verified friend reconnect). Returns the message
    /// id: keep it with `InstagramShareState.sent`; the friend's `ack` arrives as `.delivered(id:)`.
    @discardableResult
    public func sendProfile(rev: Int, ig: String?, id: String? = nil, tsMs: Int64? = nil) throws -> String {
        guard state == .secure else { throw P2PUsageError("profile requires a secure link") }
        guard (0...P2PConstants.maxProfileRev).contains(rev) else { throw P2PUsageError("profile rev") }
        if let ig, !InstagramHandle.isValid(ig) { throw P2PUsageError("profile ig must be a normalised handle") }
        let mid = id ?? newMessageId()
        guard P2PMessages.isHex(mid, 32) else { throw P2PUsageError("bad message id") }
        try sendSecure(.profile(id: mid, ts: tsMs ?? P2PLink.nowMs(), rev: rev, ig: ig))
        return mid
    }

    public func close(reason: String = "done") {
        if state == .closed { return }
        try? send(.bye(reason: reason))
        closed(reason, byPeer: false)
    }

    public func newMessageId() -> String { P2PBytes.hex(randomBytes(16)) }

    public static func nowMs() -> Int64 { Int64((Date().timeIntervalSince1970 * 1000).rounded(.down)) }

    // ------------------------------------------------------------------ internals

    /// Runs [body]; protocol and crypto failures become `error` + close, anything else `error internal`.
    private func guarded(_ body: () throws -> Void) {
        do {
            try body()
        } catch let e as P2PError {
            fail(e.code, e.detail)
        } catch let e as P2PCryptoError {
            fail(.crypto, e.message)
        } catch {
            fail(.internal, "internal error")
        }
    }

    private func send(_ msg: P2PMessage, blob: [UInt8] = []) throws {
        frames += try FrameCodec.encode(msgId: nextMsgId, body: try P2PMessages.encode(msg, blob: blob), frameLen: frameLen)
        nextMsgId = (nextMsgId + 1) & 0xFFFF
    }

    private func sendSecure(_ payload: ChatPayload) throws {
        guard let s = session else { throw P2PUsageError("no session") }
        let ct = try P2PCrypto.seal(key: s.sendKey, ctr: sendCtr, plaintext: P2PMessages.encodeChatPayload(payload))
        try send(.chat(ctr: sendCtr, ct: P2PBytes.b64(ct)))
        sendCtr += 1
    }

    private func closed(_ reason: String, byPeer: Bool) {
        state = .closed
        events.append(.closed(reason: reason, byPeer: byPeer))
    }

    private func fail(_ code: P2PErrorCode, _ detail: String) {
        if state == .closed { return }
        let d = P2PLink.truncateUTF8(detail, 200)
        try? send(.error(code: code.wire, detail: d.isEmpty ? nil : d))
        events.append(.protocolError(code: code.wire, detail: detail, byPeer: false))
        closed("error", byPeer: false)
    }

    /// At most [maxBytes] UTF-8 bytes, cut at a character boundary.
    static func truncateUTF8(_ s: String, _ maxBytes: Int) -> String {
        if s.utf8.count <= maxBytes { return s }
        var out = ""
        var n = 0
        for ch in s {
            let c = String(ch).utf8.count
            if n + c > maxBytes { break }
            out.append(ch)
            n += c
        }
        return out
    }

    private func stateError(_ detail: String) -> P2PError { P2PError(.state, detail) }

    private struct ByteRNG: RandomNumberGenerator {
        let source: (Int) -> [UInt8]
        mutating func next() -> UInt64 { source(8).reduce(0) { $0 << 8 | UInt64($1) } }
    }

    private func friendTags() -> [String] {
        var real = policy.friends().filter { !$0.blocked }.prefix(P2PConstants.friendTagsRealMax)
            .map { P2PCrypto.friendTag(friendSecret: $0.friendSecret, eidHex: local.eid) }
        let block = P2PConstants.friendTagsBlock
        let n = max(block, (real.count + block - 1) / block * block)
        while real.count < n { real.append(randomBytes(P2PConstants.friendTagLen)) }
        var rng = ByteRNG(source: randomBytes)
        real.shuffle(using: &rng)
        return real.map(P2PBytes.b64)
    }

    private func sendHello() throws {
        let h = Hello(pv: P2PConstants.protocolVersion, pmin: P2PConstants.protocolMinVersion, eid: local.eid,
                      match: local.match, compat: local.compat, ft: friendTags())
        myHello = h
        try send(.hello(h))
        sentHello = true
    }

    private func handle(_ msg: P2PMessage, blob: [UInt8], nowMs: Int64) throws {
        switch msg {
        case .unknown:
            return
        case .bye(let reason):
            return closed(reason, byPeer: true)
        case .error(let code, let detail):
            events.append(.protocolError(code: code, detail: detail, byPeer: true))
            return closed("error", byPeer: true)
        case .hello(let h):
            if state != .awaitHello || peerHello != nil { throw stateError("duplicate hello") }
            return try onHello(h, nowMs: nowMs)
        default:
            if peerHello == nil { throw stateError("\(msg.type) before hello") }
        }
        switch msg {
        case .card(let card):
            if state != .matched || peerCard != nil { throw stateError("unexpected card") }
            if card.kind != match?.kind { return close(reason: "no_match") }
            peerCard = card
            events.append(.cardReceived(card))
        case .photo(let sha):
            guard [.matched, .keyx, .secure].contains(state), let meta = peerCard?.photo, peerPhoto == nil else {
                throw stateError("unexpected photo")
            }
            if blob.count != meta.size || P2PBytes.hex(P2PCrypto.sha256(blob)) != meta.sha256 || sha != meta.sha256 {
                throw P2PError.malformed("photo does not match card hash/size")
            }
            peerPhoto = blob
            events.append(.photoReceived(jpeg: blob, sha256: sha))
        case .swipe(let dir):
            if state != .matched || peerSwipe != nil { throw stateError("unexpected swipe") }
            peerSwipe = dir
            events.append(.peerSwiped(dir))
            try resolveSwipes()
        case .keyx(let ikB64, let ekB64):
            if state != .keyx || !sentKeyx || session != nil { throw stateError("unexpected keyx") }
            let ik = P2PBytes.b64d(ikB64)
            if friendMode, ik != friend?.identityKey {
                throw P2PCryptoError("identity key does not match recognised friend")
            }
            peerIk = ik
            let s = try P2PCrypto.deriveSession(identity: identity, ephemeral: ephemeral, peerIk: ik, peerEk: P2PBytes.b64d(ekB64))
            session = s
            try send(.kc(mac: P2PBytes.b64(s.myKc)))
        case .kc(let macB64):
            guard state == .keyx, let s = session else { throw stateError("unexpected kc") }
            if !P2PCrypto.constantTimeEquals(P2PBytes.b64d(macB64), s.peerKc) { throw P2PCryptoError("key confirmation failed") }
            state = .secure
            if friendMode, let f = friend {
                events.append(.friendNearby(f))
            } else if let ik = peerIk, let peer = peerHello {
                events.append(.friendEstablished(
                    peerIdentityKey: ik,
                    friendSecret: try P2PCrypto.friendSecret(identity: identity, peerIk: ik),
                    safetyCode: P2PCrypto.safetyCode(identity.publicKey, ik),
                    card: peerCard, photo: peerPhoto, peer: peer))
            }
        case .chat(let ctr, let ctB64):
            guard state == .secure, let s = session else { throw stateError("chat before secure link") }
            if !bChats.take(nowMs) { throw P2PError(.rate, "too many chat messages") }
            if ctr != recvCtr { throw P2PCryptoError("chat counter \(ctr) != expected \(recvCtr)") }
            let pt = try P2PCrypto.open(key: s.recvKey, ctr: ctr, ciphertext: P2PBytes.b64d(ctB64))
            recvCtr += 1
            let payload = try P2PMessages.decodeChatPayload(pt)
            if case .unknown = payload { return }
            if case .profile(let id, let ts, let rev, let ig) = payload {
                events.append(.profileReceived(id: id, ts: ts, rev: rev, ig: ig))
                try send(.ack(id: id))
                return
            }
            events.append(.chatReceived(payload))
            if case .text(let id, _, _) = payload { try send(.ack(id: id)) }
        case .ack(let id):
            if state != .secure { throw stateError("ack before secure link") }
            events.append(.delivered(id: id))
        case .hello, .bye, .error, .unknown:
            break // handled above
        }
    }

    private func onHello(_ peer: Hello, nowMs: Int64) throws {
        peerHello = peer
        if role == .peripheral && !sentHello { try sendHello() }
        if peer.eid == local.eid { throw stateError("peer uses our ephemeral id") }
        if min(peer.pv, P2PConstants.protocolVersion) < max(peer.pmin, P2PConstants.protocolMinVersion) {
            throw P2PError(.version, "no common version (peer \(peer.pmin)..\(peer.pv))")
        }
        let fp = P2PCrypto.profileFingerprint(peer.compat)
        fingerprint = fp
        events.append(.helloReceived(hello: peer, fingerprint: fp))

        // 1. Friend recognition.
        let tags = Set(peer.ft.map(P2PBytes.b64d))
        for f in policy.friends() where tags.contains(P2PCrypto.friendTag(friendSecret: f.friendSecret, eidHex: peer.eid)) {
            if f.blocked { return close(reason: "done") }
            friendMode = true
            friend = f
            state = .keyx
            stateSinceMs = nowMs
            try sendKeyx()
            return
        }
        // 2. Hidden / blocked stranger.
        if policy.isHidden(fp) { return close(reason: "done") }
        // 3. Matching.
        guard let mine = myHello, let m = P2PMatching.evaluate(mine: mine, peer: peer, today: policy.today()) else {
            events.append(.noMatch)
            return close(reason: "no_match")
        }
        match = m
        state = .matched
        stateSinceMs = nowMs
        events.append(.match(m, fingerprint: fp, peer: peer))
        try sendCard(m.kind)
        if let preset = policy.presetSwipe(fp) { try swipe(preset) }
    }

    private func sendCard(_ kind: MatchKind) throws {
        let photo = frameLen >= P2PConstants.photoMinFrameLen ? local.photo : nil
        let meta = photo.map { PhotoMeta(sha256: P2PBytes.hex(P2PCrypto.sha256($0)), size: $0.count, w: local.photoW, h: local.photoH) }
        try send(.card(Card(kind: kind, firstName: local.firstName, bio: local.bio, photo: meta)))
        if let photo, let meta { try send(.photo(sha256: meta.sha256), blob: photo) }
    }

    private func sendKeyx() throws {
        try send(.keyx(ik: identity.publicKey, ek: ephemeral.publicKey))
        sentKeyx = true
    }

    private func resolveSwipes() throws {
        guard let mine = mySwipe else { return }
        if mine == .left { return close(reason: "done") }
        if peerSwipe == .right {
            state = .keyx
            try sendKeyx()
        }
        // Peer swiped left: the peer closes the link; left is silent.
    }

    private struct Bucket {
        private let cap: Double
        private let perSecond: Double
        private var tokens: Double
        private var t: Int64

        init(cap: Int, perSecond: Int, nowMs: Int64) {
            self.cap = Double(cap)
            self.perSecond = Double(perSecond)
            tokens = Double(cap)
            t = nowMs
        }

        mutating func take(_ nowMs: Int64) -> Bool {
            tokens = min(cap, tokens + Double(max(0, nowMs - t)) * perSecond / 1000.0)
            t = max(t, nowMs)
            if tokens < 1 { return false }
            tokens -= 1
            return true
        }
    }
}
