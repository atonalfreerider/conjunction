import Foundation

/// What this phone sends about its owner (rebuilt for every new link, so profile edits apply at once).
public struct LocalPeerProfile: Sendable, Equatable {
    public var match: MatchProfile
    public var compat: WireCompat
    public var firstName: String
    public var bio: String?
    /// JPEG, longest side <= 640, <= 61440 bytes; nil = no photo.
    public var photo: [UInt8]?
    public var photoW: Int
    public var photoH: Int
    /// Normalised Instagram handle, sent only to friends inside the encrypted chat (SPEC 5d.6).
    public var instagram: String?
    /// Settings › Nearby "Share my Instagram with new matches": the per-friend switch's default.
    public var shareInstagramByDefault: Bool

    public init(match: MatchProfile, compat: WireCompat, firstName: String, bio: String?, photo: [UInt8]?,
                photoW: Int, photoH: Int, instagram: String? = nil, shareInstagramByDefault: Bool = true) {
        self.match = match
        self.compat = compat
        self.firstName = firstName
        self.bio = bio
        self.photo = photo
        self.photoW = photoW
        self.photoH = photoH
        self.instagram = instagram
        self.shareInstagramByDefault = shareInstagramByDefault
    }
}

/// One Bluetooth connection as the engine sees it; implemented by the BLE transport (and by tests).
public protocol LinkPort: AnyObject {
    /// For logs only.
    var label: String { get }
    /// Queue frames for the peer in order (central: INBOX writes; peripheral: OUTBOX notifications).
    func send(_ frames: [[UInt8]])
    /// The link is finished: disconnect once queued frames are written. [outcome] sets the cool-down.
    func close(_ outcome: LinkOutcome)
    /// Drop every queued frame not yet handed to the radio (a write or notification already in
    /// flight completes). Called when a link is closed as a duplicate (protocol 7.2): the peer
    /// closes it too, and on the LE connection the pair shares with the kept link, frames written
    /// after its `bye` would reach a peripheral that has no link for them any more.
    func discardPending()
}

public extension LinkPort {
    func discardPending() {}
}

/// Integration points the engine needs from the app (or a test). Called on the engine's queue.
public protocol NearbyHost: AnyObject {
    /// Nil while Nearby cannot run (no birth data / dating profile): links are refused.
    func localProfile() -> LocalPeerProfile?
    /// A match, twin, mutual match or message to announce (popup in the foreground, local notification otherwise).
    func announce(_ event: NearbyEvent, peerName: String?, peerSun: String?)
    /// Screen an incoming chat text the deterministic rules let through (on-device model). Report the
    /// verdict with `NearbyEngine.applyMessageScreening` on the engine's queue.
    func screenMessage(id: String, text: String)
    /// Screen a received photo file (SensitiveContentAnalysis on iOS). Report with `applyPhotoScreening`.
    func screenPhoto(fileName: String)
    func log(_ message: String)
}

/// Live engine status for the Nearby screen.
public struct EngineStatus: Equatable, Sendable {
    public var openLinks = 0
    public var friendsInRange: Set<String> = []
    public var encountersThisSession = 0
    public init() {}
}

/// The Nearby protocol engine, a port of the Android app's `NearbyEngine`: one `P2PLink` (core state
/// machine) per Bluetooth link plus everything SPEC 5c asks of the app around it: link
/// de-duplication (protocol 7.2), the Matches inbox, swipes made out of range, friend records,
/// store-and-forward chat with delivery and read receipts, screening, hide / block lists.
///
/// Threading: not thread-safe. Every method must run on the one Bluetooth queue (the CoreBluetooth
/// managers deliver there; the app posts its actions there).
public final class NearbyEngine {
    public static let maxLinks = P2PConstants.maxOpenLinks
    public static let inboxTTLMs: Int64 = 24 * 3_600_000
    public static let renotifyMs: Int64 = 24 * 3_600_000
    public static let leftSwipeHideMs: Int64 = 30 * 24 * 3_600_000
    public static let snoozeMs: Int64 = 7 * 24 * 3_600_000
    /// A secure friend link with nothing to send closes after this long without traffic.
    public static let secureIdleMs: Int64 = 5 * 60_000
    /// Outgoing chat pacing (the peer accepts a burst of 10, then 1 per second).
    public static let chatBurst = 8.0
    public static let chatRatePerSecond = 0.8

    private final class Rec {
        let id: Int
        let port: LinkPort
        let link: P2PLink
        let myEid: String
        var lastActivity: Int64
        var peerEid: String?
        var fingerprint: String?
        var friendId: String?
        var suppressed = false
        var portClosed = false
        var closeReason: String?
        var inflight: [String] = []
        /// Rev of the `profile` already written on this link (not re-sent on the same link).
        var profileSentRev = -1
        var chatTokens = NearbyEngine.chatBurst
        var chatTokensAt: Int64

        init(id: Int, port: LinkPort, link: P2PLink, myEid: String, now: Int64) {
            self.id = id
            self.port = port
            self.link = link
            self.myEid = myEid
            lastActivity = now
            chatTokensAt = now
        }
    }

    private final class Policy: LinkPolicy {
        weak var engine: NearbyEngine?

        func friends() -> [FriendRecord] {
            guard let e = engine else { return [] }
            return e.store.state.friends.sorted { $0.lastSeen > $1.lastSeen }.map {
                FriendRecord(id: $0.id, identityKey: P2PBytes.b64d($0.identityKey), friendSecret: P2PBytes.b64d($0.friendSecret),
                             blocked: $0.blocked)
            }
        }

        func isHidden(_ fingerprint: String) -> Bool {
            guard let e = engine else { return true }
            return e.store.state.isHidden(fingerprint, now: e.clock())
        }

        func presetSwipe(_ fingerprint: String) -> SwipeDirection? {
            guard let e = engine, let enc = e.store.state.encounter(fingerprint) else { return nil }
            if enc.friendId != nil || enc.expiresAt < e.clock() { return nil }
            return enc.mySwipe.flatMap(SwipeDirection.init(rawValue:))
        }

        func today() -> LocalDate { engine?.today() ?? LocalDate.todayInCurrentTimeZone() }
    }

    public let store: P2PStore
    private let identity: () -> any P256Agreement
    private weak var host: NearbyHost?
    let clock: () -> Int64
    let today: () -> LocalDate
    private let policy = Policy()
    private var links: [Rec] = []
    private var nextId = 1
    private let eids = EphemeralIdSource()
    private var lastHousekeeping: Int64 = 0
    public private(set) var running = false
    public private(set) var status = EngineStatus() {
        didSet { if status != oldValue { onStatus?(status) } }
    }
    /// Called (on the engine's queue) whenever [status] changes.
    public var onStatus: ((EngineStatus) -> Void)?

    public init(store: P2PStore, identity: @escaping () -> any P256Agreement, host: NearbyHost,
                clock: @escaping () -> Int64 = { P2PLink.nowMs() },
                today: @escaping () -> LocalDate = { LocalDate.todayInCurrentTimeZone() }) {
        self.store = store
        self.identity = identity
        self.host = host
        self.clock = clock
        self.today = today
        policy.engine = self
    }

    public var openLinkCount: Int { links.count }

    /// Nearby switched on: new ephemeral id (protocol 7.1).
    public func start() {
        running = true
        eids.rotate(nowMs: clock())
        housekeeping(clock(), force: true)
        publishStatus()
    }

    /// Nearby switched off / paused / Bluetooth off: say goodbye on every link.
    public func stop(_ outcome: LinkOutcome = .shutdown) {
        running = false
        for rec in links {
            if !rec.link.isClosed { rec.link.close(reason: "shutdown") }
            flush(rec)
            closePort(rec, outcome)
        }
        store.flush()
        publishStatus()
    }

    // ------------------------------------------------------------------ transport side

    /// A connection is ready for the protocol (central: MTU known, OUTBOX notifications on;
    /// peripheral: first INBOX write). Returns the link id, or nil when refused: disconnect then.
    public func openLink(role: LinkRole, port: LinkPort, attMtu: Int) -> Int? {
        guard running, links.count < NearbyEngine.maxLinks, let me = host?.localProfile() else { return nil }
        let now = clock()
        let eid = eids.current(nowMs: now)
        let local: LocalP2PProfile
        do {
            local = try LocalP2PProfile(eid: eid, match: me.match, compat: me.compat, firstName: me.firstName, bio: me.bio,
                                        photo: me.photo, photoW: me.photoW, photoH: me.photoH)
        } catch {
            log("local profile rejected: \(error)")
            return nil
        }
        let link = P2PLink(role: role, local: local, identity: identity(), policy: policy, nowMs: now)
        link.setAttMtu(attMtu)
        let rec = Rec(id: nextId, port: port, link: link, myEid: eid, now: now)
        nextId += 1
        links.append(rec)
        log("link \(rec.id) open as \(role) (\(port.label), ATT_MTU \(attMtu))")
        link.start()
        flush(rec)
        publishStatus()
        return rec.id
    }

    public func onFrame(_ linkId: Int, _ frame: [UInt8]) {
        guard let rec = rec(linkId) else { return }
        rec.lastActivity = clock()
        rec.link.onFrame(frame, nowMs: rec.lastActivity)
        flush(rec)
    }

    public func onMtu(_ linkId: Int, attMtu: Int) { rec(linkId)?.link.setAttMtu(attMtu) }

    /// The Bluetooth connection is gone (either side disconnected).
    public func onDisconnected(_ linkId: Int) {
        guard let i = links.firstIndex(where: { $0.id == linkId }) else { return }
        let rec = links.remove(at: i)
        log("link \(linkId) disconnected (state \(rec.link.state))")
        requeue(rec)
        publishStatus()
    }

    /// About once a second: protocol timeouts, chat pacing, idle secure links, inbox expiry, saving.
    public func tick() {
        let now = clock()
        for rec in links {
            rec.link.tick(nowMs: now)
            if rec.link.state == .secure {
                deliverQueued(rec)
                if now - rec.lastActivity > NearbyEngine.secureIdleMs && !hasPending(for: rec.friendId) {
                    rec.link.close(reason: "done")
                }
            }
            flush(rec)
        }
        housekeeping(now)
        store.flush()
    }

    // ------------------------------------------------------------------ app side

    /// The person swiped a match card. Sent now if the peer is in range, else at the next encounter.
    public func swipe(_ fingerprint: String, _ dir: SwipeDirection) {
        let now = clock()
        store.update { s in
            guard s.encounter(fingerprint) != nil else { return }
            for i in s.encounters.indices where s.encounters[i].fingerprint == fingerprint { s.encounters[i].mySwipe = dir.wire }
            if dir == .left {
                s.hidden.removeAll { $0.fingerprint == fingerprint }
                s.hidden.append(HiddenPeer(fingerprint: fingerprint, until: now + NearbyEngine.leftSwipeHideMs, reason: "left"))
            }
        }
        for rec in links where rec.fingerprint == fingerprint && rec.link.state == .matched {
            do { try rec.link.swipe(dir) } catch { log("swipe on link \(rec.id): \(error)") }
            flush(rec)
        }
    }

    /// New queued messages or read receipts for [friendId]: send them if a secure link is open.
    public func deliver(_ friendId: String) {
        for rec in links where rec.friendId == friendId && rec.link.state == .secure {
            deliverQueued(rec)
            flush(rec)
        }
    }

    /// The owner's Instagram handle or a per-friend share switch changed (SPEC 5d.6): send the new
    /// `profile` now on every open secure friend link whose effective value changed.
    public func refreshProfiles() {
        for rec in links where rec.friendId != nil && rec.link.state == .secure {
            sendProfileIfNeeded(rec)
            flush(rec)
        }
    }

    /// Close every link to a person (block, unmatch, report, snooze).
    public func disconnectPeer(friendId: String?, fingerprint: String?) {
        for rec in links where (friendId != nil && rec.friendId == friendId) || (fingerprint != nil && rec.fingerprint == fingerprint) {
            rec.link.close(reason: "done")
            flush(rec)
        }
    }

    /// Re-run screening that had to wait for the foreground (the on-device model).
    public func rescreenPending() {
        let s = store.state
        for m in s.messages where !m.mine && m.screening == .pending { screenMessage(m.id, m.text) }
        var files = Set<String>()
        for e in s.encounters where e.photoScreening == .pending { if let f = e.photoFile { files.insert(f) } }
        for f in s.friends where f.photoScreening == .pending { if let p = f.photoFile { files.insert(p) } }
        for f in files.sorted() { host?.screenPhoto(fileName: f) }
    }

    public func applyMessageScreening(id: String, verdict: Screening) {
        store.update { s in
            for i in s.messages.indices where s.messages[i].id == id && !s.messages[i].mine {
                // The model may add a flag; it never clears one the rules raised.
                if s.messages[i].screening != .flagged { s.messages[i].screening = verdict }
            }
        }
    }

    public func applyPhotoScreening(fileName: String, verdict: Screening) {
        store.update { s in
            for i in s.encounters.indices where s.encounters[i].photoFile == fileName { s.encounters[i].photoScreening = verdict }
            for i in s.friends.indices where s.friends[i].photoFile == fileName { s.friends[i].photoScreening = verdict }
        }
    }

    // ------------------------------------------------------------------ internals

    private func rec(_ id: Int) -> Rec? { links.first { $0.id == id } }

    private func log(_ message: String) { host?.log(message) }

    private func flush(_ rec: Rec) {
        var guardCount = 0
        while guardCount < 16 {
            guardCount += 1
            let frames = rec.link.takeFrames()
            if !frames.isEmpty && !rec.portClosed { rec.port.send(frames) }
            let events = rec.link.takeEvents()
            if frames.isEmpty && events.isEmpty { break }
            for e in events { handle(rec, e) }
        }
        if rec.link.isClosed && !rec.portClosed { closePort(rec, outcome(rec)) }
    }

    private func outcome(_ rec: Rec) -> LinkOutcome {
        switch rec.closeReason {
        case "no_match": return .noMatch
        case "dedup": return .dedup
        case "timeout": return .timeout
        case "error": return .error
        case "shutdown": return .shutdown
        default: return .done
        }
    }

    private func closePort(_ rec: Rec, _ outcome: LinkOutcome) {
        if rec.portClosed { return }
        rec.portClosed = true
        requeue(rec)
        rec.port.close(outcome)
    }

    private func handle(_ rec: Rec, _ e: LinkEvent) {
        let now = clock()
        switch e {
        case .helloReceived(let hello, let fingerprint):
            rec.peerEid = hello.eid
            rec.fingerprint = fingerprint
            dedup(rec)
        case .noMatch:
            log("link \(rec.id): no match")
        case .match(let m, let fingerprint, let peer):
            if !rec.suppressed { onMatch(rec, m, fingerprint: fingerprint, peer: peer, now: now) }
        case .cardReceived(let card):
            if !rec.suppressed {
                updateEncounter(rec.fingerprint) {
                    $0.firstName = card.firstName
                    $0.bio = NearbyText.firstLine(card.bio)
                }
            }
        case .photoReceived(let jpeg, let sha):
            if !rec.suppressed { onPhoto(rec, jpeg: jpeg, sha256: sha) }
        case .peerSwiped(let dir):
            updateEncounter(rec.fingerprint) { $0.peerSwipe = dir.wire }
        case .friendEstablished(let ik, let fs, let code, let card, _, let peer):
            onFriendEstablished(rec, ik: ik, fs: fs, safetyCode: code, card: card, peer: peer, now: now)
        case .friendNearby(let f):
            rec.friendId = f.id
            // SPEC 5d.4 backfill: the friend's hello (kc already verified) gives their chart
            // positions; keep them (replace and re-score when they changed).
            let peer = rec.link.peerHello?.compat
            let rescored = peer.flatMap { rescore($0) }
            store.update { s in
                for i in s.friends.indices where s.friends[i].id == f.id {
                    s.friends[i].lastSeen = now
                    if let peer, peer != s.friends[i].peerCompat {
                        s.friends[i].peerCompat = peer
                        if let rescored { s.friends[i].score = rescored }
                    }
                }
            }
            log("link \(rec.id): friend \(f.id.prefix(8)) nearby, secure")
            sendProfileIfNeeded(rec)
            deliverQueued(rec)
            publishStatus()
        case .chatReceived(let payload):
            onChat(rec, payload)
        case .profileReceived(_, _, let rev, let ig):
            onProfile(rec, rev: rev, ig: ig)
        case .delivered(let id):
            rec.inflight.removeAll { $0 == id }
            let fid = rec.friendId
            store.update { s in
                for i in s.messages.indices where s.messages[i].id == id && s.messages[i].mine && s.messages[i].status != .read {
                    s.messages[i].status = .delivered
                }
                for i in s.friends.indices where s.friends[i].id == fid && s.friends[i].igState?.inFlightId == id {
                    s.friends[i].igState = s.friends[i].igState?.onAck(id)
                }
            }
        case .protocolError(let code, let detail, let byPeer):
            log("link \(rec.id): protocol error \(code) (\(byPeer ? "peer" : "us")): \(detail ?? "")")
        case .closed(let reason, let byPeer):
            rec.closeReason = reason
            log("link \(rec.id): closed '\(reason)' by \(byPeer ? "peer" : "us")")
        }
    }

    /// Protocol 7.2: of two links to the same phone keep the one whose central has the smaller eid.
    private func dedup(_ rec: Rec) {
        guard let peerEid = rec.peerEid,
              let other = links.first(where: { $0 !== rec && $0.peerEid == peerEid && !$0.link.isClosed }) else { return }
        // Equal own/peer eids are rejected by the link before this point.
        guard rec.myEid != peerEid, other.myEid != peerEid else { return }
        let keepThis = LinkDedup.keep(myEid: rec.myEid, peerEid: peerEid, iAmCentral: rec.link.role == .central)
        let keepOther = LinkDedup.keep(myEid: other.myEid, peerEid: peerEid, iAmCentral: other.link.role == .central)
        let loser = (keepThis && !keepOther) ? other : rec
        log("dedup: links \(rec.id)/\(other.id) reach the same phone; closing \(loser.id)")
        loser.suppressed = true
        // Nothing more goes out on the duplicate but its bye (protocol 7.2): card / photo frames
        // queued in the transport would otherwise keep flowing for seconds after the peer closed it.
        if !loser.portClosed { loser.port.discardPending() }
        loser.link.close(reason: "dedup")
        if loser !== rec { flush(loser) }
    }

    private func onMatch(_ rec: Rec, _ m: P2PMatch, fingerprint: String, peer: Hello, now: Int64) {
        var announce = false
        store.update { s in
            let old = s.encounter(fingerprint).flatMap { $0.expiresAt > now && $0.friendId == nil ? $0 : nil }
            var enc = StoredEncounter(
                fingerprint: fingerprint, kind: m.kind.wire, total: m.score.total, western: m.score.western,
                chinese: m.score.chinese, blood: m.score.blood, highlights: m.score.highlights,
                birthdayTwin: m.isBirthdayTwin, exactTwin: m.isExactTwin, age: m.peerAge,
                sun: m.peerSun.key, moon: m.peerMoon.key, rising: m.peerRising?.key,
                chineseAnimal: peer.compat.animal.key, relationship: peer.match.relationshipStatus.wire,
                lookingFor: peer.match.lookingFor.wire,
                firstName: old?.firstName, bio: old?.bio, photoFile: old?.photoFile,
                photoScreening: old?.photoScreening ?? .pending, mySwipe: old?.mySwipe, peerSwipe: nil, friendId: nil,
                createdAt: old?.createdAt ?? now, expiresAt: old?.expiresAt ?? (now + NearbyEngine.inboxTTLMs),
                notifiedAt: old?.notifiedAt ?? 0,
                peerCompat: peer.compat, chineseRelation: m.score.chineseRelation.name, stemRelation: m.score.stemRelation.name,
                blurb: old?.peerCompat == peer.compat ? old?.blurb : nil)
            announce = now - enc.notifiedAt > NearbyEngine.renotifyMs
            if announce { enc.notifiedAt = now }
            s.encounters.removeAll { $0.fingerprint == fingerprint }
            s.encounters.append(enc)
        }
        status.encountersThisSession += 1
        log("link \(rec.id): match \(m.kind.wire) \(m.score.total)% twin=\(m.isBirthdayTwin)/\(m.isExactTwin)")
        if announce {
            let kind: NearbyEventKind = m.isExactTwin ? .exactTwin : m.isBirthdayTwin ? .birthdayTwin : .match
            host?.announce(NearbyEvent(kind: kind, encounterId: fingerprint, friendId: nil, matchKind: m.kind.wire,
                                       total: m.score.total),
                           peerName: nil, peerSun: m.peerSun.displayName)
        }
    }

    private func onPhoto(_ rec: Rec, jpeg: [UInt8], sha256: String) {
        guard let fp = rec.fingerprint else { return }
        guard let file = store.savePhoto(jpeg, sha256: sha256) else {
            log("could not store photo")
            return
        }
        let existing = store.state.encounter(fp)
        let alreadyScreened = existing?.photoFile == file && existing?.photoScreening != .pending
        updateEncounter(fp) {
            $0.photoFile = file
            if !alreadyScreened { $0.photoScreening = .pending }
        }
        if !alreadyScreened { host?.screenPhoto(fileName: file) }
    }

    private func onFriendEstablished(_ rec: Rec, ik: [UInt8], fs: [UInt8], safetyCode: String, card: Card?, peer: Hello,
                                     now: Int64) {
        let id = String(P2PBytes.hex(P2PCrypto.sha256(ik)).prefix(32))
        rec.friendId = id
        let enc = rec.fingerprint.flatMap { store.state.encounter($0) }
        let c = peer.compat
        let name = card?.firstName ?? enc?.firstName ?? "Your match"
        let me = host?.localProfile()
        let shareOn = me?.shareInstagramByDefault ?? true
        let score: StoredScore? = {
            if let enc, enc.peerCompat == nil || enc.peerCompat == c { return enc.storedScore() }
            return rescore(c)
        }()
        store.update { s in
            let friend = StoredFriend(
                id: id, identityKey: P2PBytes.b64(ik), friendSecret: P2PBytes.b64(fs), safetyCode: safetyCode,
                kind: rec.link.match?.kind.wire ?? enc?.kind ?? "friends", firstName: name,
                bio: NearbyText.firstLine(card?.bio) ?? enc?.bio,
                sun: P2PMatching.sign(c.sun).key, moon: P2PMatching.sign(c.moon).key,
                rising: c.asc.map { P2PMatching.sign($0).key }, chineseAnimal: c.animal.key,
                photoFile: enc?.photoFile, photoScreening: enc?.photoFile != nil ? (enc?.photoScreening ?? .pending) : .safe,
                since: now, lastSeen: now, peerCompat: c, score: score, blurb: enc?.blurb,
                igShareOn: shareOn, igState: InstagramShareState.initial(handle: me?.instagram, shareWithThisFriend: shareOn))
            s.friends.removeAll { $0.id == id }
            s.friends.append(friend)
            for i in s.encounters.indices where s.encounters[i].fingerprint == rec.fingerprint { s.encounters[i].friendId = id }
        }
        log("link \(rec.id): new friend \(id.prefix(8)) (safety code \(safetyCode))")
        host?.announce(NearbyEvent(kind: .conjunction, encounterId: rec.fingerprint, friendId: id, matchKind: enc?.kind,
                                   total: enc?.total),
                       peerName: name, peerSun: enc.map { CardText.sign($0.sun) })
        // The `profile` is the first chat on a new secure link (SPEC 5d.6), then queued messages.
        sendProfileIfNeeded(rec)
        deliverQueued(rec)
        publishStatus()
    }

    /// Receiver side of `profile` (SPEC 5d.6): keep the newest rev only. The link already sent the ack.
    private func onProfile(_ rec: Rec, rev: Int, ig: String?) {
        guard let fid = rec.friendId else { return }
        store.update { s in
            for i in s.friends.indices where s.friends[i].id == fid && !s.friends[i].blocked
                && InstagramShareState.acceptReceived(storedPeerIgRev: s.friends[i].peerIgRev, rev: rev) {
                s.friends[i].peerIg = InstagramHandle.received(ig)
                s.friends[i].peerIgRev = rev
            }
        }
        log("link \(rec.id): profile rev \(rev) (\(ig == nil ? "no handle" : "handle"))")
    }

    /// Sender side of `profile` (SPEC 5d.6): applies the current effective value (the handle when
    /// set and this friend's switch is on) and sends it when the friend has not acknowledged the
    /// current rev. One send per rev per link; a new link re-sends with a fresh id.
    private func sendProfileIfNeeded(_ rec: Rec) {
        guard let fid = rec.friendId, rec.link.state == .secure, let f = store.state.friend(fid), !f.blocked else { return }
        let me = host?.localProfile()
        let on = f.igShareOn ?? me?.shareInstagramByDefault ?? true
        var st = (f.igState ?? InstagramShareState.initial(handle: me?.instagram, shareWithThisFriend: on))
            .withEffective(InstagramShareState.effective(handle: me?.instagram, shareWithThisFriend: on))
        if st.needsSend && rec.profileSentRev != st.rev, let id = try? rec.link.sendProfile(rev: st.rev, ig: st.value) {
            st = st.sent(id)
            rec.profileSentRev = st.rev
            rec.chatTokens -= 1
            rec.lastActivity = clock()
        }
        if st != f.igState {
            let updated = st
            store.update { s in for i in s.friends.indices where s.friends[i].id == fid { s.friends[i].igState = updated } }
        }
    }

    /// Compatibility of the owner with [peer] (the friend backfill and friends made without a card).
    private func rescore(_ peer: WireCompat) -> StoredScore? {
        guard let me = host?.localProfile()?.compat else { return nil }
        return StoredScore(Compatibility.score(me.toCompatProfile(), peer.toCompatProfile()))
    }

    private func onChat(_ rec: Rec, _ payload: ChatPayload) {
        guard let fid = rec.friendId else { return }
        let s = store.state
        guard let friend = s.friend(fid), !friend.blocked else { return }
        switch payload {
        case .text(let id, let ts, let text):
            if s.messages.contains(where: { $0.id == id }) { return } // re-sent queued message: already stored
            let flagged = TextHeuristics.flags(text)
            store.update { st in
                st.messages.append(ChatLine(id: id, friendId: fid, mine: false, text: text, ts: ts, status: .received,
                                            screening: flagged ? .flagged : .pending))
                st.messages = NearbyEngine.trim(st.messages, friendId: fid)
            }
            log("link \(rec.id): chat \(id.prefix(8)) received (\(text.count) chars)")
            if !flagged { host?.screenMessage(id: id, text: text) }
            host?.announce(NearbyEvent(kind: .message, encounterId: nil, friendId: fid), peerName: friend.firstName,
                           peerSun: friend.sun.map(CardText.sign))
        case .read(_, _, let refs):
            let set = Set(refs)
            store.update { st in
                for i in st.messages.indices where st.messages[i].mine && st.messages[i].friendId == fid && set.contains(st.messages[i].id) {
                    st.messages[i].status = .read
                }
            }
        case .profile, .unknown:
            break
        }
    }

    private func screenMessage(_ id: String, _ text: String) {
        if TextHeuristics.flags(text) {
            applyMessageScreening(id: id, verdict: .flagged)
        } else {
            host?.screenMessage(id: id, text: text)
        }
    }

    static func trim(_ messages: [ChatLine], friendId: String) -> [ChatLine] {
        let count = messages.filter { $0.friendId == friendId }.count
        if count <= P2PStore.maxMessagesPerFriend { return messages }
        var drop = count - P2PStore.maxMessagesPerFriend
        return messages.filter { m in
            if drop > 0 && m.friendId == friendId && m.status != .queued {
                drop -= 1
                return false
            }
            return true
        }
    }

    private func hasPending(for friendId: String?) -> Bool {
        guard let friendId else { return false }
        return store.state.messages.contains {
            $0.friendId == friendId && (($0.mine && $0.status == .queued) || (!$0.mine && $0.seen && !$0.readReceiptSent))
        }
    }

    /// Store-and-forward: queued chats in order, paced below the peer's inbound chat bucket.
    private func deliverQueued(_ rec: Rec) {
        guard let fid = rec.friendId, rec.link.state == .secure else { return }
        let now = clock()
        rec.chatTokens = min(NearbyEngine.chatBurst,
                             rec.chatTokens + Double(max(0, now - rec.chatTokensAt)) * NearbyEngine.chatRatePerSecond / 1000.0)
        rec.chatTokensAt = now
        let inflightElsewhere = Set(links.filter { $0 !== rec }.flatMap { $0.inflight })
        let s = store.state
        let queued = s.messages.filter {
            $0.mine && $0.friendId == fid && !rec.inflight.contains($0.id) && !inflightElsewhere.contains($0.id) &&
                ($0.status == .queued || $0.status == .sent)
        }.sorted { $0.ts < $1.ts }
        var sent: [String] = []
        for m in queued {
            if rec.chatTokens < 1 { break }
            do { try rec.link.sendChat(m.text, id: m.id, tsMs: m.ts) } catch { break }
            rec.chatTokens -= 1
            rec.inflight.append(m.id)
            sent.append(m.id)
        }
        let readRefs = s.messages.filter { !$0.mine && $0.friendId == fid && $0.seen && !$0.readReceiptSent }.map(\.id)
        var receipted: [String] = []
        var start = 0
        while start < readRefs.count {
            if rec.chatTokens < 1 { break }
            let chunk = Array(readRefs[start..<min(start + P2PConstants.maxReadRefs, readRefs.count)])
            do { try rec.link.sendRead(chunk) } catch { break }
            rec.chatTokens -= 1
            receipted += chunk
            start += chunk.count
        }
        if sent.isEmpty && receipted.isEmpty { return }
        rec.lastActivity = now
        let sentSet = Set(sent), rSet = Set(receipted)
        store.update { st in
            for i in st.messages.indices {
                let m = st.messages[i]
                if sentSet.contains(m.id) && m.mine && m.status == .queued { st.messages[i].status = .sent }
                if rSet.contains(m.id) && !m.mine { st.messages[i].readReceiptSent = true }
            }
        }
        log("link \(rec.id): sent \(sent.count) chat(s), \(receipted.count) read receipt(s)")
    }

    /// Messages written to a link that ended before their ack go back to the queue (same id; the receiver de-duplicates).
    private func requeue(_ rec: Rec) {
        if rec.inflight.isEmpty { return }
        let ids = Set(rec.inflight)
        rec.inflight.removeAll()
        store.update { s in
            for i in s.messages.indices where s.messages[i].mine && ids.contains(s.messages[i].id) && s.messages[i].status == .sent {
                s.messages[i].status = .queued
            }
        }
    }

    private func updateEncounter(_ fingerprint: String?, _ transform: (inout StoredEncounter) -> Void) {
        guard let fingerprint else { return }
        store.update { s in for i in s.encounters.indices where s.encounters[i].fingerprint == fingerprint { transform(&s.encounters[i]) } }
    }

    /// Every minute: drop expired inbox cards (and their photos) and expired hide entries.
    private func housekeeping(_ now: Int64, force: Bool = false) {
        if !force && now - lastHousekeeping < 60_000 { return }
        lastHousekeeping = now
        let s = store.state
        let expired = Set(s.encounters.filter { $0.friendId == nil && $0.expiresAt < now }.map(\.fingerprint))
        let hiddenExpired = s.hidden.contains { $0.until < now }
        if expired.isEmpty && !hiddenExpired { return }
        let keep = Set(s.friends.compactMap(\.photoFile) + s.encounters.filter { !expired.contains($0.fingerprint) }.compactMap(\.photoFile))
        for e in s.encounters where expired.contains(e.fingerprint) {
            if let p = e.photoFile, !keep.contains(p) { store.deletePhoto(p) }
        }
        store.update { st in
            st.encounters.removeAll { expired.contains($0.fingerprint) }
            st.hidden.removeAll { $0.until < now }
        }
    }

    private func publishStatus() {
        var s = status
        s.openLinks = links.count
        s.friendsInRange = Set(links.filter { $0.link.state == .secure && $0.friendId != nil }.compactMap(\.friendId))
        status = s
    }

    public static func newMessageId() -> String { P2PBytes.hex(P2PRandom.bytes(16)) }
}
