import Foundation
import XCTest
@testable import ConjunctionCore

/// Checks the Swift implementation against testdata/p2p_wire.json, the vectors the Kotlin
/// P2pWireVectorsTest and the Python reference also pass.
final class P2PWireVectorsTests: XCTestCase {
    private let root = Wire.root
    private var crypto: [String: Any] { root.obj("crypto") }

    func testUUIDsAndFrameLengths() {
        let u = root.obj("uuids")
        XCTAssertEqual(P2PConstants.serviceUUID.lowercased(), u.str("service"))
        XCTAssertEqual(P2PConstants.inboxUUID.lowercased(), u.str("inbox"))
        XCTAssertEqual(P2PConstants.outboxUUID.lowercased(), u.str("outbox"))
        XCTAssertEqual(P2PConstants.protocolVersion, root.int("protocolVersion"))
        let lens = root.obj("frameLenByMtu")
        XCTAssertEqual(lens.count, 6)
        for (mtu, len) in lens {
            XCTAssertEqual(P2PConstants.maxFrameLen(attMtu: Int(mtu)!), (len as! NSNumber).intValue, "MTU \(mtu)")
        }
    }

    func testMessagesDecodeEncodeAndFrameIdentically() throws {
        let messages = root.list("messages")
        XCTAssertGreaterThanOrEqual(messages.count, 10)
        for m in messages {
            let name = m.str("name")
            let body = unhex(m.str("bodyHex"))
            let d = try P2PMessages.decode(body)
            XCTAssertEqual(P2PMessages.canonicalJSON(d.message), m.str("json"), name)
            XCTAssertEqual(Array(P2PMessages.canonicalJSON(d.message).utf8), Array(m.str("json").utf8), name)
            XCTAssertEqual(d.blob, unhex(m.str("blobHex")), name)
            XCTAssertEqual(try P2PMessages.encode(d.message, blob: d.blob), body, name)
            // The canonical JSON text itself must parse to the same message.
            let again = try P2PMessages.decode(FrameCodec.packBody(Array(m.str("json").utf8), d.blob)).message
            XCTAssertEqual(again, d.message, name)
            let byMtu = m.obj("framesByMtu")
            XCTAssertEqual(Set(byMtu.keys), ["23", "185", "517"], name)
            for (mtu, framesHex) in byMtu {
                let expected = (framesHex as! [String]).map(unhex)
                let got = try FrameCodec.encode(msgId: 0x0102, body: body, frameLen: P2PConstants.maxFrameLen(attMtu: Int(mtu)!))
                XCTAssertEqual(got, expected, "\(name)@\(mtu)")
                var r = Reassembler()
                let out = try expected.map { try r.accept($0, nowMs: 0) }
                XCTAssertEqual(out.last!, body, "\(name)@\(mtu)")
                XCTAssertTrue(out.dropLast().allSatisfy { $0 == nil }, "\(name)@\(mtu)")
            }
        }
    }

    func testInvalidBodiesAreRejected() {
        let cases = root.list("invalidBodies")
        XCTAssertFalse(cases.isEmpty)
        for c in cases {
            expectP2P(P2PErrorCode(rawValue: c.str("code"))!, c.str("name")) {
                _ = try P2PMessages.decode(unhex(c.str("bodyHex")))
            }
        }
    }

    func testInvalidFramesAreRejected() {
        let cases = root.list("invalidFrames")
        XCTAssertFalse(cases.isEmpty)
        for c in cases {
            expectP2P(P2PErrorCode(rawValue: c.str("code"))!, c.str("name")) {
                var r = Reassembler()
                _ = try r.accept(unhex(c.str("hex")), nowMs: 0)
            }
        }
    }

    func testKeysEcdhAndSession() throws {
        let keys = crypto.obj("keys")
        for n in ["identityA", "identityB", "ephemeralA", "ephemeralB"] {
            XCTAssertEqual(hexOf(Wire.key(n).publicKey), keys.obj(n).str("pub"), n)
            XCTAssertNoThrow(try P2PCrypto.validatePoint(Wire.key(n).publicKey))
        }
        let ikA = Wire.key("identityA"), ikB = Wire.key("identityB")
        let ekA = Wire.key("ephemeralA"), ekB = Wire.key("ephemeralB")
        let ecdh = crypto.obj("ecdh")
        XCTAssertEqual(hexOf(try ikA.agree(ikB.publicKey)), ecdh.str("identity"))
        XCTAssertEqual(hexOf(try ikB.agree(ikA.publicKey)), ecdh.str("identity"))
        XCTAssertEqual(hexOf(try ekA.agree(ekB.publicKey)), ecdh.str("ephemeral"))

        let s = crypto.obj("session")
        let order = crypto.obj("order")
        let sa = try P2PCrypto.deriveSession(identity: ikA, ephemeral: ekA, peerIk: ikB.publicKey, peerEk: ekB.publicKey)
        let sb = try P2PCrypto.deriveSession(identity: ikB, ephemeral: ekB, peerIk: ikA.publicKey, peerEk: ekA.publicKey)
        XCTAssertEqual(sa.isL, order.bool("identityAIsL"))
        XCTAssertEqual(hexOf(sa.isL ? ikA.publicKey : ikB.publicKey), order.str("ikL"))
        XCTAssertEqual(hexOf(sa.isL ? ekA.publicKey : ekB.publicKey), order.str("ekL"))
        XCTAssertEqual(hexOf(sa.transcriptSalt), s.str("transcriptSalt"))
        XCTAssertEqual(hexOf(sb.transcriptSalt), s.str("transcriptSalt"))
        XCTAssertEqual(String(decoding: P2PCrypto.infoSession, as: UTF8.self), s.str("info"))
        XCTAssertEqual(hexOf(P2PCrypto.hkdf(ikm: unhex(s.str("ikm")), salt: sa.transcriptSalt, info: P2PCrypto.infoSession, length: 96)),
                       s.str("okm"))
        XCTAssertEqual(hexOf(sa.isL ? sa.sendKey : sa.recvKey), s.str("kLH"))
        XCTAssertEqual(hexOf(sa.isL ? sa.recvKey : sa.sendKey), s.str("kHL"))
        XCTAssertEqual(hexOf(sa.confKey), s.str("kConf"))
        XCTAssertEqual(hexOf(P2PCrypto.kcMac(confKey: sa.confKey, role: P2PCrypto.roleL)), s.str("kcL"))
        XCTAssertEqual(hexOf(P2PCrypto.kcMac(confKey: sa.confKey, role: P2PCrypto.roleH)), s.str("kcH"))
        XCTAssertEqual(sa.sendKey, sb.recvKey)
        XCTAssertEqual(sa.myKc, sb.peerKc)
        XCTAssertEqual(P2PMessages.canonicalJSON(.kc(mac: P2PBytes.b64(sa.myKc))), s.str("kcMessageA"))

        let hk = crypto.list("hkdf")
        XCTAssertFalse(hk.isEmpty)
        for h in hk {
            XCTAssertEqual(hexOf(P2PCrypto.hkdf(ikm: unhex(h.str("ikm")), salt: unhex(h.str("salt")), info: unhex(h.str("info")),
                                                length: h.int("length"))), h.str("okm"))
        }
    }

    func testAesGcmVectorsAndTamper() throws {
        let vectors = crypto.list("aesGcm")
        XCTAssertEqual(vectors.count, 3)
        for v in vectors {
            let key = unhex(v.str("key"))
            let ctr = Int64(v.int("ctr"))
            XCTAssertEqual(hexOf(P2PCrypto.nonce(ctr)), v.str("nonce"))
            XCTAssertEqual(hexOf(P2PCrypto.chatAAD), v.str("aadHex"))
            let ct = try P2PCrypto.seal(key: key, ctr: ctr, plaintext: Array(v.str("plaintext").utf8))
            XCTAssertEqual(hexOf(ct), v.str("ciphertextHex"))
            XCTAssertEqual(String(decoding: try P2PCrypto.open(key: key, ctr: ctr, ciphertext: ct), as: UTF8.self), v.str("plaintext"))
            XCTAssertEqual(P2PMessages.canonicalJSON(.chat(ctr: ctr, ct: P2PBytes.b64(ct))), v.str("envelope"))
            let payload = try P2PMessages.decodeChatPayload(Array(v.str("plaintext").utf8))
            XCTAssertEqual(String(decoding: P2PMessages.encodeChatPayload(payload), as: UTF8.self), v.str("plaintext"))
            for (mtu, fr) in v.obj("framesByMtu") {
                let got = try FrameCodec.encode(msgId: 3, body: P2PMessages.encode(.chat(ctr: ctr, ct: P2PBytes.b64(ct))),
                                                frameLen: P2PConstants.maxFrameLen(attMtu: Int(mtu)!))
                XCTAssertEqual(got.map(hexOf), fr as! [String], "aesGcm@\(mtu)")
            }
        }
        let key = unhex(vectors[0].str("key"))
        XCTAssertThrowsError(try P2PCrypto.open(key: key, ctr: 0, ciphertext: unhex(crypto.str("tamperedCiphertextHex")))) {
            XCTAssertTrue($0 is P2PCryptoError)
        }
        // Wrong counter (nonce) must fail too.
        XCTAssertThrowsError(try P2PCrypto.open(key: key, ctr: 1, ciphertext: unhex(vectors[0].str("ciphertextHex"))))
        XCTAssertThrowsError(try P2PCrypto.open(key: key, ctr: 0, ciphertext: [1, 2, 3]))
    }

    func testFriendTagsSafetyCodeFingerprint() throws {
        let ikA = Wire.key("identityA"), ikB = Wire.key("identityB")
        let f = crypto.obj("friend")
        let fs = try P2PCrypto.friendSecret(identity: ikA, peerIk: ikB.publicKey)
        XCTAssertEqual(hexOf(fs), f.str("friendSecret"))
        XCTAssertEqual(try P2PCrypto.friendSecret(identity: ikB, peerIk: ikA.publicKey), fs)
        XCTAssertEqual(hexOf(P2PCrypto.sha256(P2PCrypto.labelFriend, ikA.publicKey, ikB.publicKey)), f.str("salt"))
        let tags = f.list("tags")
        XCTAssertEqual(tags.count, 4)
        for t in tags {
            let tag = P2PCrypto.friendTag(friendSecret: fs, eidHex: t.str("eid"))
            XCTAssertEqual(hexOf(tag), t.str("tagHex"))
            XCTAssertEqual(P2PBytes.b64(tag), t.str("tagB64"))
        }
        XCTAssertEqual(P2PCrypto.safetyCode(ikA.publicKey, ikB.publicKey), crypto.str("safetyCode"))
        XCTAssertEqual(P2PCrypto.safetyCode(ikB.publicKey, ikA.publicKey), crypto.str("safetyCode"))
        let fps = crypto.list("profileFingerprint")
        XCTAssertEqual(fps.count, 2)
        for fp in fps {
            let json = #"{"t":"hello","pv":1,"pmin":1,"eid":"00000000000000000000000000000000","match":{"gender":"male","seeking":"both","relationship":"single","lookingFor":"both"},"compat":"# + fp.str("compat") + #","ft":[]}"#
            guard case .hello(let h) = try P2PMessages.decode(FrameCodec.packBody(Array(json.utf8))).message else {
                return XCTFail("not a hello")
            }
            XCTAssertEqual(WireJSON.writeString(.object(h.compat.wire)), fp.str("compat"))
            XCTAssertEqual(P2PCrypto.profileFingerprint(h.compat), fp.str("fingerprint"))
        }
    }

    func testMatchingGlue() throws {
        let m = root.obj("match")
        let today = date(m.str("today"))
        let got = try XCTUnwrap(P2PMatching.evaluate(mine: Wire.hello(m.str("a")), peer: Wire.hello(m.str("b")), today: today))
        XCTAssertEqual(got.kind.wire, m.str("kind"))
        XCTAssertEqual(got.score.total, m.int("total"))
        XCTAssertEqual(got.score.western, m.int("western"))
        XCTAssertEqual(got.score.chinese, m.int("chinese"))
        XCTAssertEqual(got.score.blood, m.optInt("blood"))
        XCTAssertEqual(got.isBirthdayTwin, m.bool("isBirthdayTwin"))
        XCTAssertEqual(got.isExactTwin, m.bool("isExactTwin"))
        XCTAssertEqual(got.peerAge, m.int("peerAgeSeenByA"))
        XCTAssertEqual(got.score.highlights, m.array("highlights") as! [String])
        // Symmetric total and kind.
        let back = try XCTUnwrap(P2PMatching.evaluate(mine: Wire.hello(m.str("b")), peer: Wire.hello(m.str("a")), today: today))
        XCTAssertEqual(back.score.total, got.score.total)
        XCTAssertEqual(back.kind, got.kind)
    }

    func testTranscriptReplayAsPeripheral() throws { try replay(.peripheral) }

    func testTranscriptReplayAsCentral() throws { try replay(.central) }

    /// Plays one side of the Python transcript with the Swift state machine: the other side's frames
    /// are fed in; everything Swift sends must equal the transcript (hello `ft` decoys excepted).
    private func replay(_ role: LinkRole) throws {
        let t = root.obj("transcript")
        let me = role == .central ? "central" : "peripheral"
        let cfg = t.obj(me)
        let hello = Wire.hello(cfg.str("profile"))
        let photoHex = cfg.str("photoHex")
        let photo = photoHex.isEmpty ? nil : unhex(photoHex)
        let profile = profileOf(hello, cfg.str("firstName"), bio: cfg.optStr("bio"), photo: photo)
        let now = Int64(t.int("nowMs"))
        let link = P2PLink(role: role, local: profile, identity: Wire.key(cfg.str("identity")),
                           policy: TestPolicy(day: date(t.str("today"))), nowMs: 0, ephemeral: Wire.key(cfg.str("ephemeral")))
        link.setAttMtu(t.int("attMtu"))
        link.start()
        var pending = link.takeFrames()
        var checked = 0
        for step in t.list("steps") {
            if let action = step.optStr("action") {
                if step.str("side") != me { continue }
                switch action {
                case "swipe": try link.swipe(SwipeDirection(rawValue: step.str("dir"))!)
                case "chat": try link.sendChat(step.str("text"), id: step.str("id"), tsMs: Int64(step.int("ts")))
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
                    XCTAssertEqual(a.ft.count, 8)
                    a.ft = []
                    e.ft = []
                    XCTAssertEqual(a, e, "\(me) hello")
                    XCTAssertEqual(got.map(\.count), frames.map(\.count), "\(me) hello frame sizes")
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
        XCTAssertTrue(pending.isEmpty, "\(me): unexpected extra frames \(pending.map { hexOf($0).prefix(40) })")
        XCTAssertEqual(checked, role == .central ? 9 : 7)
        XCTAssertEqual(ev.friendEstablished.count, 1)
        XCTAssertEqual(ev.chats.count, 1)
        XCTAssertTrue(ev.contains { if case .delivered = $0 { return true } else { return false } })
        XCTAssertTrue(link.isClosed)
        XCTAssertEqual(ev.protocolErrors, [])
    }
}
