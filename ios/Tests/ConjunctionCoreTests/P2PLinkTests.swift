import Foundation
import XCTest
@testable import ConjunctionCore

final class P2PLinkTests: XCTestCase {
    private var photo: [UInt8] = { var r = SeededRNG(3); return r.bytes(20_000) }()
    private lazy var aurora = profileOf(Wire.hello("hello"), "Aurora", bio: "Stargazer", photo: photo)
    private let orion = profileOf(Wire.hello("hello_unknown_time"), "Orion")

    private struct Pair {
        let a: P2PLink
        let b: P2PLink
        let ida: SoftwareP256Key
        let idb: SoftwareP256Key
    }

    private func pair(_ pa: LocalP2PProfile? = nil, _ pb: LocalP2PProfile? = nil,
                      pola: TestPolicy = TestPolicy(), polb: TestPolicy = TestPolicy(), mtu: Int = 517,
                      ida: SoftwareP256Key = .generate(), idb: SoftwareP256Key = .generate()) -> Pair {
        let a = P2PLink(role: .central, local: pa ?? aurora, identity: ida, policy: pola, nowMs: 0)
        let b = P2PLink(role: .peripheral, local: pb ?? orion, identity: idb, policy: polb, nowMs: 0)
        a.setAttMtu(mtu)
        b.setAttMtu(mtu)
        return Pair(a: a, b: b, ida: ida, idb: idb)
    }

    private func matched(mtu: Int = 517) -> Pair {
        let p = pair(mtu: mtu)
        p.a.start()
        pump(p.a, p.b)
        return p
    }

    private typealias Friendship = (peerIdentityKey: [UInt8], friendSecret: [UInt8], safetyCode: String, card: Card?, photo: [UInt8]?)

    private func friends() throws -> (Pair, Friendship, Friendship) {
        let p = matched()
        _ = p.a.takeEvents()
        _ = p.b.takeEvents()
        try p.a.swipe(.right)
        try p.b.swipe(.right)
        pump(p.a, p.b)
        let fa = p.a.takeEvents().friendEstablished
        let fb = p.b.takeEvents().friendEstablished
        XCTAssertEqual(fa.count, 1)
        XCTAssertEqual(fb.count, 1)
        return (p, fa[0], fb[0])
    }

    func testMatchSwipeKeyxChat() throws {
        let p = matched()
        let ea = p.a.takeEvents(), eb = p.b.takeEvents()
        XCTAssertEqual(ea.map(\.name), ["HelloReceived", "Match", "CardReceived"])
        XCTAssertEqual(eb.map(\.name), ["HelloReceived", "Match", "CardReceived", "PhotoReceived"])
        let ma = ea.matches[0], mb = eb.matches[0]
        XCTAssertEqual(ma.kind, .dating)
        XCTAssertEqual(ma.score.total, mb.score.total)
        XCTAssertEqual(eb.photos, [photo])
        XCTAssertEqual(eb.cards[0].firstName, "Aurora")
        XCTAssertNil(ma.peerRising) // Orion's birth time is unknown
        XCTAssertEqual(mb.peerRising, .aries)

        try p.a.swipe(.right)
        pump(p.a, p.b)
        XCTAssertEqual(p.b.takeEvents(), [.peerSwiped(.right)])
        try p.b.swipe(.right)
        pump(p.a, p.b)
        let fa = p.a.takeEvents().friendEstablished, fb = p.b.takeEvents().friendEstablished
        XCTAssertEqual(fa[0].friendSecret, fb[0].friendSecret)
        XCTAssertEqual(fa[0].safetyCode, fb[0].safetyCode)
        XCTAssertEqual(fa[0].peerIdentityKey, p.idb.publicKey)
        XCTAssertEqual(fb[0].photo, photo)
        XCTAssertEqual(p.a.state, .secure)

        let id = try p.a.sendChat("Hi Orion ✨", tsMs: 5)
        pump(p.a, p.b)
        XCTAssertEqual(p.b.takeEvents().chats, [.text(id: id, ts: 5, text: "Hi Orion ✨")])
        XCTAssertEqual(p.a.takeEvents(), [.delivered(id: id)])
        try p.b.sendRead([id])
        pump(p.a, p.b)
        guard case .read(_, _, let refs) = p.a.takeEvents().chats.first else { return XCTFail("no read receipt") }
        XCTAssertEqual(refs, [id])
        p.a.close()
        pump(p.a, p.b)
        XCTAssertTrue(p.b.isClosed)
        XCTAssertEqual(p.b.takeEvents().last, .closed(reason: "done", byPeer: true))
    }

    func testLeftSwipeClosesQuietly() throws {
        let p = matched()
        _ = p.a.takeEvents()
        _ = p.b.takeEvents()
        try p.b.swipe(.right)
        try p.a.swipe(.left)
        pump(p.a, p.b)
        XCTAssertTrue(p.a.isClosed && p.b.isClosed)
        XCTAssertTrue(p.b.takeEvents().friendEstablished.isEmpty)
        XCTAssertThrowsError(try p.a.swipe(.right))
    }

    func testNoMatchClosesWithoutCards() {
        let friendsOnly = MatchProfile(gender: .female, seeking: .male, relationshipStatus: .inARelationship, lookingFor: .friends)
        let p = pair(withMatch(aurora, friendsOnly))
        p.a.start()
        pump(p.a, p.b)
        let ea = p.a.takeEvents()
        XCTAssertTrue(ea.contains(.noMatch))
        XCTAssertTrue(ea.cards.isEmpty)
        XCTAssertTrue(p.a.isClosed && p.b.isClosed)
    }

    func testUnderageIsNoMatch() {
        let p = pair(pola: TestPolicy(day: LocalDate(2010, 1, 1)))
        p.a.start()
        pump(p.a, p.b)
        XCTAssertTrue(p.a.takeEvents().contains(.noMatch))
    }

    func testSmallMtuSkipsPhoto() {
        let p = matched(mtu: 23)
        let eb = p.b.takeEvents()
        XCTAssertTrue(eb.photos.isEmpty)
        XCTAssertNil(eb.cards[0].photo)
        XCTAssertEqual(p.a.state, .matched)
    }

    func testHiddenStrangerAndPresetSwipe() {
        let fp = P2PCrypto.profileFingerprint(orion.compat)
        let hidden = pair(pola: TestPolicy(hidden: [fp]))
        hidden.a.start()
        pump(hidden.a, hidden.b)
        XCTAssertTrue(hidden.a.isClosed)
        XCTAssertTrue(hidden.a.takeEvents().matches.isEmpty)

        let fpA = P2PCrypto.profileFingerprint(aurora.compat)
        let preset = pair(pola: TestPolicy(preset: [fp: .right]), polb: TestPolicy(preset: [fpA: .right]))
        preset.a.start()
        pump(preset.a, preset.b)
        XCTAssertEqual(preset.a.state, .secure)
        XCTAssertEqual(preset.b.state, .secure)
    }

    func testFriendsRecogniseEachOtherWithNewEphemeralIds() throws {
        let (p, fa, fb) = try friends()
        let fra = [FriendRecord(id: "orion", identityKey: fa.peerIdentityKey, friendSecret: fa.friendSecret)]
        let frb = [FriendRecord(id: "aurora", identityKey: fb.peerIdentityKey, friendSecret: fb.friendSecret)]
        let src = EphemeralIdSource()
        let eidA = src.current(nowMs: 0)
        src.rotate(nowMs: 1)
        let a2 = profileOf(Wire.hello("hello"), "Aurora", eid: eidA)
        let b2 = profileOf(Wire.hello("hello_unknown_time"), "Orion", eid: src.current(nowMs: 2))
        let q = pair(a2, b2, pola: TestPolicy(friends: fra), polb: TestPolicy(friends: frb), ida: p.ida, idb: p.idb)
        q.a.start()
        pump(q.a, q.b)
        XCTAssertEqual(q.a.state, .secure)
        XCTAssertEqual(q.a.takeEvents().friendsNearby.map(\.id), ["orion"])
        XCTAssertEqual(q.b.takeEvents().friendsNearby.map(\.id), ["aurora"])
        try q.b.sendChat("queued while apart", id: "0123456789abcdef0123456789abcdef")
        pump(q.a, q.b)
        guard case .text(let id, _, let text) = q.a.takeEvents().chats.first else { return XCTFail("no chat") }
        XCTAssertEqual(text, "queued while apart")
        XCTAssertEqual(id, "0123456789abcdef0123456789abcdef")
    }

    func testBlockedFriendGetsByeDone() throws {
        let (p, fa, fb) = try friends()
        // Orion still lists Aurora as a friend; Aurora has blocked Orion.
        let fra = [FriendRecord(id: "orion", identityKey: fa.peerIdentityKey, friendSecret: fa.friendSecret, blocked: true)]
        let frb = [FriendRecord(id: "aurora", identityKey: fb.peerIdentityKey, friendSecret: fb.friendSecret)]
        let q = pair(pola: TestPolicy(friends: fra), polb: TestPolicy(friends: frb), ida: p.ida, idb: p.idb)
        q.a.start()
        pump(q.a, q.b)
        XCTAssertTrue(q.a.isClosed && q.b.isClosed)
        XCTAssertTrue(q.b.takeEvents().friendsNearby.isEmpty)
    }

    func testImpostorReplayingFriendTagsFailsKeyConfirmation() throws {
        let (p, fa, fb) = try friends()
        let fra = [FriendRecord(id: "orion", identityKey: fa.peerIdentityKey, friendSecret: fa.friendSecret)]
        let frb = [FriendRecord(id: "aurora", identityKey: fb.peerIdentityKey, friendSecret: fb.friendSecret)]
        // Mallory has Aurora's profile and friend list (replayed tags) but not her identity key.
        let q = pair(pola: TestPolicy(friends: fra), polb: TestPolicy(friends: frb), ida: .generate(), idb: p.idb)
        q.a.start()
        pump(q.a, q.b)
        XCTAssertTrue(q.b.isClosed)
        XCTAssertEqual(q.b.takeEvents().protocolErrors.first, "crypto")
        XCTAssertNotEqual(q.b.state, .secure)
    }

    func testTamperedChatIsRejected() throws {
        let (p, _, _) = try friends()
        try p.a.sendChat("hello")
        guard case .chat(let ctr, let ctB64) = try P2PMessages.decode(p.a.takeFrames().joinedBody()).message else { return XCTFail() }
        var ct = P2PBytes.b64d(ctB64)
        ct[3] ^= 1
        for f in try FrameCodec.encode(msgId: 77, body: P2PMessages.encode(.chat(ctr: ctr, ct: P2PBytes.b64(ct))), frameLen: p.b.frameLen) {
            p.b.onFrame(f, nowMs: 1_000)
        }
        XCTAssertEqual(p.b.takeEvents().protocolErrors, ["crypto"])
        XCTAssertTrue(p.b.isClosed)
    }

    func testReplayedChatCounterIsRejected() throws {
        let (p, _, _) = try friends()
        try p.a.sendChat("one")
        let frames = p.a.takeFrames()
        for f in frames { p.b.onFrame(f, nowMs: 1_000) }
        XCTAssertEqual(p.b.takeEvents().chats.count, 1)
        for var f in frames {
            f[2] = 0x55 // same ctr, new msgId
            p.b.onFrame(f, nowMs: 1_000)
        }
        XCTAssertEqual(p.b.takeEvents().protocolErrors, ["crypto"])
    }

    func testTamperedCardPhotoHashIsRejected() {
        let p = pair()
        p.a.start()
        // Flip a byte in the third frame of Aurora's photo message (inside the JPEG blob).
        var count = 0
        pump(p.a, p.b, tamper: { f, fromA in
            guard fromA, FrameCodec.msgId(of: f) == 2 else { return f }
            count += 1
            guard count == 3 else { return f }
            var g = f
            g[g.count - 1] &+= 1
            return g
        })
        XCTAssertEqual(p.b.takeEvents().protocolErrors, ["malformed"])
    }

    func testMessagesOutOfStateAreRejected() throws {
        let p = matched()
        _ = p.b.takeEvents()
        for f in try FrameCodec.encode(msgId: 50, body: P2PMessages.encode(.chat(ctr: 0, ct: P2PBytes.b64([UInt8](repeating: 0, count: 32)))), frameLen: 512) {
            p.b.onFrame(f, nowMs: 1_000)
        }
        XCTAssertEqual(p.b.takeEvents().protocolErrors, ["state"])

        let q = pair()
        for f in try FrameCodec.encode(msgId: 0, body: P2PMessages.encode(.swipe(.right)), frameLen: 512) { q.b.onFrame(f, nowMs: 0) }
        XCTAssertEqual(q.b.takeEvents().protocolErrors, ["state"])
        // The error message itself goes out on the wire.
        guard case .error(let code, _) = try P2PMessages.decode(q.b.takeFrames().joinedBody()).message else { return XCTFail() }
        XCTAssertEqual(code, "state")
    }

    func testUnknownMessageTypesAreIgnoredButRateLimited() throws {
        let p = matched()
        _ = p.b.takeEvents()
        let unknown = try FrameCodec.encode(msgId: 9, body: FrameCodec.packBody(Array(#"{"t":"future","x":1}"#.utf8)), frameLen: 512)
        XCTAssertEqual(unknown.count, 1)
        for _ in 0..<20 { p.b.onFrame(unknown[0], nowMs: 1_000) }
        XCTAssertEqual(p.b.state, .matched)
        for _ in 0..<400 { p.b.onFrame(unknown[0], nowMs: 1_000) }
        XCTAssertTrue(p.b.isClosed)
        XCTAssertEqual(p.b.takeEvents().protocolErrors, ["rate"])
    }

    func testVersionNegotiation() throws {
        let q = pair()
        let future = Hello(pv: 5, pmin: 3, eid: String(repeating: "ab", count: 16), match: orion.match, compat: orion.compat, ft: [])
        for f in try FrameCodec.encode(msgId: 0, body: P2PMessages.encode(.hello(future)), frameLen: 512) { q.b.onFrame(f, nowMs: 0) }
        XCTAssertEqual(q.b.takeEvents().protocolErrors, ["version"])
        // A peer speaking 1..3 is fine.
        let q2 = pair()
        let newer = Hello(pv: 3, pmin: 1, eid: String(repeating: "ab", count: 16), match: aurora.match, compat: aurora.compat, ft: [])
        for f in try FrameCodec.encode(msgId: 0, body: P2PMessages.encode(.hello(newer)), frameLen: 512) { q2.b.onFrame(f, nowMs: 0) }
        XCTAssertEqual(q2.b.takeEvents().matches.count, 1)
    }

    func testOwnEphemeralIdIsRejected() throws {
        let q = pair()
        let echo = Hello(pv: 1, pmin: 1, eid: orion.eid, match: aurora.match, compat: aurora.compat, ft: [])
        for f in try FrameCodec.encode(msgId: 0, body: P2PMessages.encode(.hello(echo)), frameLen: 512) { q.b.onFrame(f, nowMs: 0) }
        XCTAssertEqual(q.b.takeEvents().protocolErrors, ["state"])
    }

    func testTimeouts() throws {
        let q = pair()
        q.b.tick(nowMs: P2PConstants.helloTimeoutMs + 1)
        XCTAssertTrue(q.b.isClosed)
        let p = matched()
        p.a.tick(nowMs: P2PConstants.matchLinkTimeoutMs + 2_000)
        XCTAssertTrue(p.a.isClosed)
        let k = matched()
        try k.a.swipe(.right)
        pump(k.a, k.b)
        try k.b.swipe(.right)
        _ = k.b.takeFrames() // lost: Aurora never sees Orion's swipe/keyx
        k.b.tick(nowMs: 1_000_000)
        XCTAssertEqual(k.b.state, .keyx)
        k.b.tick(nowMs: 1_000_000 + P2PConstants.keyxTimeoutMs + 1)
        XCTAssertTrue(k.b.isClosed)
        XCTAssertEqual(k.b.takeEvents().last, .closed(reason: "timeout", byPeer: false))
    }

    func testFuzzedFramesNeverCrash() {
        var rnd = SeededRNG(11)
        for _ in 0..<300 {
            let p = pair()
            p.a.start()
            for f in p.a.takeFrames() {
                var g = f
                if Int.random(in: 0..<3, using: &rnd) == 0 {
                    let i = Int.random(in: 0..<g.count, using: &rnd)
                    g[i] ^= UInt8(1) << UInt8.random(in: 0..<8, using: &rnd)
                }
                p.b.onFrame(g, nowMs: 1_000)
            }
            _ = p.b.takeEvents()
        }
    }

    func testDedupRule() {
        let lo = String(repeating: "00", count: 16), hi = String(repeating: "ff", count: 16)
        XCTAssertTrue(LinkDedup.keep(myEid: lo, peerEid: hi, iAmCentral: true))
        XCTAssertFalse(LinkDedup.keep(myEid: lo, peerEid: hi, iAmCentral: false))
        XCTAssertTrue(LinkDedup.keep(myEid: hi, peerEid: lo, iAmCentral: false))
        XCTAssertFalse(LinkDedup.keep(myEid: hi, peerEid: lo, iAmCentral: true))
    }

    func testEphemeralIdRotates() {
        let src = EphemeralIdSource()
        let a = src.current(nowMs: 0)
        XCTAssertTrue(EphemeralIdSource.isValid(a))
        XCTAssertEqual(src.current(nowMs: P2PConstants.ephemeralIdRotationMs - 1), a)
        let b = src.current(nowMs: P2PConstants.ephemeralIdRotationMs)
        XCTAssertTrue(a != b && EphemeralIdSource.isValid(b))
        src.rotate(nowMs: P2PConstants.ephemeralIdRotationMs + 5)
        XCTAssertNotEqual(src.current(nowMs: P2PConstants.ephemeralIdRotationMs + 6), b)
    }

    func testHelloCarriesEightShuffledTagsIncludingFriends() throws {
        let (_, fa, _) = try friends()
        let fr = FriendRecord(id: "orion", identityKey: fa.peerIdentityKey, friendSecret: fa.friendSecret)
        let link = P2PLink(role: .central, local: aurora, identity: SoftwareP256Key.generate(),
                           policy: TestPolicy(friends: Array(repeating: fr, count: 9)), nowMs: 0)
        link.start()
        guard case .hello(let h) = try P2PMessages.decode(link.takeFrames().joinedBody()).message else { return XCTFail() }
        XCTAssertEqual(h.ft.count, 16)
        let real = P2PBytes.b64(P2PCrypto.friendTag(friendSecret: fa.friendSecret, eidHex: aurora.eid))
        XCTAssertEqual(h.ft.filter { $0 == real }.count, 9)
    }

    func testCryptoRejectsBadPoints() throws {
        let k = SoftwareP256Key.generate()
        var offCurve = k.publicKey
        offCurve[64] ^= 1
        XCTAssertThrowsError(try P2PCrypto.validatePoint(offCurve))
        // x = p (out of range) with an arbitrary y.
        let p = unhex("ffffffff00000001000000000000000000000000ffffffffffffffffffffffff")
        XCTAssertThrowsError(try P2PCrypto.validatePoint([0x04] + p + Array(k.publicKey[33...])))
        XCTAssertThrowsError(try P2PCrypto.validatePoint(Array(k.publicKey.prefix(33))))
        XCTAssertThrowsError(try k.agree(offCurve))
        XCTAssertThrowsError(try P2PCrypto.deriveSession(identity: k, ephemeral: SoftwareP256Key.generate(),
                                                          peerIk: k.publicKey, peerEk: SoftwareP256Key.generate().publicKey))
        // Raw scalar round trip (Keychain storage).
        XCTAssertEqual(try SoftwareP256Key(rawRepresentation: k.rawRepresentation).publicKey, k.publicKey)
        XCTAssertThrowsError(try SoftwareP256Key(rawRepresentation: [UInt8](repeating: 0, count: 32)))
    }
}
