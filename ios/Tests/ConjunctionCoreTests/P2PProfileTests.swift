import Foundation
import XCTest
@testable import ConjunctionCore

/// SPEC 5d.6: Instagram handle + chat `profile`, against golden_instagram.json and p2p_wire.json.
final class P2PProfileTests: XCTestCase {
    private let root = Wire.root

    func testGoldenInstagram() throws {
        let g = try Golden.load("golden_instagram.json")
        for c in g.list("normalize") {
            XCTAssertEqual(InstagramHandle.normalize(c.str("input")), c.optStr("handle"), c.str("note"))
        }
        for c in g.list("isValid") { XCTAssertEqual(InstagramHandle.isValid(c.str("handle")), c.bool("valid"), c.str("handle")) }
        for c in root.list("normalize") { XCTAssertEqual(InstagramHandle.normalize(c.str("input")), c.optStr("handle")) }
        XCTAssertEqual(InstagramHandle.profileURL("aurora.stars"), "https://www.instagram.com/aurora.stars/")
    }

    func testPlaintextsAreCanonical() throws {
        for p in root.list("plaintexts") {
            let msg = ChatPayload.profile(id: p.str("id"), ts: Int64(p.int("ts")), rev: p.int("rev"), ig: p.optStr("ig"))
            XCTAssertEqual(String(decoding: P2PMessages.encodeChatPayload(msg), as: UTF8.self), p.str("json"))
            XCTAssertEqual(try P2PMessages.decodeChatPayload(Array(p.str("json").utf8)), msg)
            XCTAssertLessThanOrEqual(p.str("json").utf8.count, 160)
        }
    }

    func testReceiverValidationAndMalformed() throws {
        for c in root.list("profileValidation") {
            guard case .profile(_, _, _, let ig) = try P2PMessages.decodeChatPayload(Array(c.str("plaintext").utf8)) else {
                return XCTFail("not a profile")
            }
            XCTAssertEqual(ig, c.optStr("stored"), c.optStr("ig") ?? "null")
        }
        for c in root.list("profileMalformed") {
            expectP2P(.malformed, c.str("name")) { _ = try P2PMessages.decodeChatPayload(Array(c.str("plaintext").utf8)) }
        }
    }

    func testShareStateLifecycle() throws {
        var st = InstagramShareState.initial(handle: "aurora", shareWithThisFriend: true)
        XCTAssertEqual(st, InstagramShareState(value: "aurora", rev: 0, ackedRev: -1))
        XCTAssertTrue(st.needsSend)
        st = st.sent(String(repeating: "a", count: 32))
        XCTAssertEqual(st.onAck(String(repeating: "b", count: 32)), st)
        st = st.onAck(String(repeating: "a", count: 32))
        XCTAssertFalse(st.needsSend)
        XCTAssertEqual(st.withEffective("aurora"), st)
        st = st.withEffective(InstagramShareState.effective(handle: "aurora", shareWithThisFriend: false))
        XCTAssertEqual(st.rev, 1)
        XCTAssertNil(st.value)
        XCTAssertTrue(st.needsSend)
        st = st.sent(String(repeating: "c", count: 32)).withEffective("new.handle").onAck(String(repeating: "c", count: 32))
        XCTAssertEqual(st.rev, 2)
        XCTAssertEqual(st.ackedRev, 1)
        XCTAssertTrue(st.needsSend)
        XCTAssertNil(InstagramShareState.effective(handle: "Not Valid", shareWithThisFriend: true))
        XCTAssertTrue(InstagramShareState.acceptReceived(storedPeerIgRev: -1, rev: 0))
        XCTAssertFalse(InstagramShareState.acceptReceived(storedPeerIgRev: 3, rev: 3))
        let json = try JSONEncoder().encode(st)
        XCTAssertEqual(try JSONDecoder().decode(InstagramShareState.self, from: json), st)
    }

    func testProfileOnlyOnSecureLinksAndAcked() throws {
        let a = P2PLink(role: .central, local: profileOf(Wire.hello("hello"), "Aurora"), identity: SoftwareP256Key.generate(),
                        policy: TestPolicy(), nowMs: 0)
        let b = P2PLink(role: .peripheral, local: profileOf(Wire.hello("hello_unknown_time"), "Orion"), identity: SoftwareP256Key.generate(),
                        policy: TestPolicy(), nowMs: 0)
        a.setAttMtu(517)
        b.setAttMtu(517)
        a.start()
        pump(a, b)
        XCTAssertEqual(a.state, .matched)
        XCTAssertThrowsError(try a.sendProfile(rev: 0, ig: "aurora"))
        try b.swipe(.right)
        try a.swipe(.right)
        pump(a, b)
        XCTAssertEqual(a.state, .secure)
        _ = a.takeEvents()
        _ = b.takeEvents()
        XCTAssertThrowsError(try a.sendProfile(rev: 0, ig: "Aurora"))
        let id = try a.sendProfile(rev: 0, ig: "aurora.stars")
        pump(a, b)
        let evB = b.takeEvents()
        XCTAssertEqual(evB.count, 1)
        guard case .profileReceived(let gotId, _, let rev, let ig) = evB.first else { return XCTFail("no profile") }
        XCTAssertEqual([gotId, String(rev), ig ?? "nil"], [id, "0", "aurora.stars"])
        XCTAssertEqual(a.takeEvents(), [.delivered(id: id)])
        try b.sendProfile(rev: 0, ig: nil)
        pump(a, b)
        guard case .profileReceived(_, _, _, let none) = a.takeEvents().first else { return XCTFail("no profile") }
        XCTAssertNil(none)
    }

    func testProfileTranscriptAsCentral() throws { try replay(.central) }

    func testProfileTranscriptAsPeripheral() throws { try replay(.peripheral) }

    /// Friend reconnect: hello (friend tag) -> keyx -> kc -> profile -> ack -> bye, byte-identical.
    private func replay(_ role: LinkRole) throws {
        let t = root.obj("profileTranscript")
        let me = role == .central ? "central" : "peripheral"
        let cfg = t.obj(me)
        let hello = Wire.hello(cfg.str("profile"))
        let photoHex = cfg.str("photoHex")
        let photo = photoHex.isEmpty ? nil : unhex(photoHex)
        let identity = Wire.key(cfg.str("identity"))
        let friendKey = Wire.key(cfg.str("friend")).publicKey
        let friend = FriendRecord(id: "friend", identityKey: friendKey,
                                  friendSecret: try P2PCrypto.friendSecret(identity: identity, peerIk: friendKey))
        let link = P2PLink(role: role, local: profileOf(hello, cfg.str("firstName"), bio: cfg.optStr("bio"), photo: photo),
                           identity: identity, policy: TestPolicy(friends: [friend], day: date(t.str("today"))), nowMs: 0,
                           ephemeral: Wire.key(cfg.str("ephemeral")))
        link.setAttMtu(t.int("attMtu"))
        link.start()
        var pending = link.takeFrames()
        var checked = 0
        let now = Int64(t.int("nowMs"))
        for step in t.list("steps") {
            if let action = step.optStr("action") {
                if step.str("side") != me { continue }
                switch action {
                case "profile":
                    try link.sendProfile(rev: step.int("rev"), ig: step.optStr("ig"), id: step.str("id"), tsMs: Int64(step.int("ts")))
                case "close": link.close(reason: step.str("reason"))
                default: XCTFail("unknown action \(action)")
                }
                pending += link.takeFrames()
                continue
            }
            let frames = (step.array("frames") as! [String]).map(unhex)
            let type = step.str("type")
            if step.str("from") == me {
                guard pending.count >= frames.count else { return XCTFail("\(me): missing frames for \(type)") }
                let got = Array(pending.prefix(frames.count))
                pending.removeFirst(frames.count)
                if type == "hello" {
                    guard case .hello(var a) = try P2PMessages.decode(got.joinedBody()).message,
                          case .hello(var e) = try P2PMessages.decode(frames.joinedBody()).message else {
                        return XCTFail("\(me): hello did not decode")
                    }
                    XCTAssertTrue(a.ft.contains(P2PBytes.b64(P2PCrypto.friendTag(friendSecret: friend.friendSecret, eidHex: a.eid))))
                    a.ft = []
                    e.ft = []
                    XCTAssertEqual(a, e, "\(me) hello")
                } else {
                    XCTAssertEqual(got.map(hexOf), frames.map(hexOf), "\(me) \(type)")
                }
                checked += 1
            } else {
                for f in frames { link.onFrame(f, nowMs: now) }
                pending += link.takeFrames()
            }
        }
        let ev = link.takeEvents()
        XCTAssertTrue(pending.isEmpty, "\(me): unexpected extra frames")
        XCTAssertEqual(checked, role == .central ? 5 : 4)
        XCTAssertTrue(ev.contains { if case .friendNearby = $0 { return true } else { return false } })
        if role == .peripheral {
            let igs = ev.compactMap { e -> String? in if case .profileReceived(_, _, _, let ig) = e { return ig } else { return nil } }
            XCTAssertEqual(igs, ["aurora.stars"])
        } else {
            XCTAssertTrue(ev.contains(.delivered(id: String(repeating: "9", count: 32))))
        }
        XCTAssertEqual(ev.protocolErrors, [])
        XCTAssertEqual(ev.chats.count, 0)
        XCTAssertTrue(link.isClosed)
        // The verified link's hello is what the app stores as peerCompat (SPEC 5d.4 backfill).
        XCTAssertEqual(link.peerHello?.compat, Wire.hello(role == .central ? "hello_unknown_time" : "hello").compat)
    }
}
