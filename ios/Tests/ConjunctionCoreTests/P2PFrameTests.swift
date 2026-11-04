import Foundation
import XCTest
@testable import ConjunctionCore

final class P2PFrameTests: XCTestCase {

    func testOnlyTheFirstFrameOfMessageZeroStartsALink() throws {
        let hello = try FrameCodec.encode(msgId: 0, body: [UInt8](repeating: 1, count: 600), frameLen: 182)
        XCTAssertTrue(FrameCodec.isLinkStart(hello[0]))
        XCTAssertTrue(hello.dropFirst().allSatisfy { !FrameCodec.isLinkStart($0) }, "later frames of message 0 do not")
        // Leftovers of an ended link (round 1 OTA: frame 2/16 of message 2 after a dedup bye).
        let photo = try FrameCodec.encode(msgId: 2, body: [UInt8](repeating: 2, count: 3000), frameLen: 182)
        XCTAssertTrue(photo.allSatisfy { !FrameCodec.isLinkStart($0) })
        XCTAssertTrue(try FrameCodec.encode(msgId: 1, body: [0, 2, 0x7b, 0x7d], frameLen: 182).allSatisfy { !FrameCodec.isLinkStart($0) })
        XCTAssertFalse(FrameCodec.isLinkStart(Array(hello[0].prefix(7))), "header only is not a frame")
    }

    func testRandomBodiesRoundTripAtEveryMtu() throws {
        var rnd = SeededRNG(42)
        for _ in 0..<500 {
            let mtu = [23, 27, 185, 247, 517, Int.random(in: 23..<700, using: &rnd)].randomElement(using: &rnd)!
            let frameLen = P2PConstants.maxFrameLen(attMtu: mtu)
            let body = rnd.bytes(Int.random(in: 1..<6000, using: &rnd))
            let id = Int.random(in: 0..<65536, using: &rnd)
            let frames = try FrameCodec.encode(msgId: id, body: body, frameLen: frameLen)
            XCTAssertTrue(frames.allSatisfy { $0.count <= frameLen && $0.count >= 8 })
            XCTAssertTrue(frames.dropLast().allSatisfy { $0.count == frameLen })
            var r = Reassembler()
            let out = try frames.map { try r.accept($0, nowMs: 0) }
            XCTAssertEqual(out.last!, body)
            XCTAssertTrue(out.dropLast().allSatisfy { $0 == nil })
            XCTAssertEqual(r.pending, 0)
        }
    }

    func testMaxBodyAtSmallestMtu() throws {
        var rnd = SeededRNG(1)
        let body = rnd.bytes(P2PConstants.maxBodyLen)
        let frames = try FrameCodec.encode(msgId: 9, body: body, frameLen: 20)
        XCTAssertEqual(frames.count, (body.count + 12) / 13)
        var r = Reassembler()
        var got: [UInt8]?
        for f in frames { got = try r.accept(f, nowMs: 0) ?? got }
        XCTAssertEqual(got, body)
        XCTAssertThrowsError(try FrameCodec.encode(msgId: 9, body: body + [0], frameLen: 20))
    }

    func testInterleavingUpToFourMessages() throws {
        var rnd = SeededRNG(5)
        let bodies = (0..<4).map { rnd.bytes(300 + $0 * 50) }
        var streams = try bodies.enumerated().map { try FrameCodec.encode(msgId: 100 + $0.offset, body: $0.element, frameLen: 20) }
        var r = Reassembler()
        var done: [Int: [UInt8]] = [:]
        while streams.contains(where: { !$0.isEmpty }) {
            let i = streams.indices.filter { !streams[$0].isEmpty }.randomElement(using: &rnd)!
            if let b = try r.accept(streams[i].removeFirst(), nowMs: 0) { done[i] = b }
        }
        for (i, b) in bodies.enumerated() { XCTAssertEqual(done[i], b) }
        // A fifth concurrent message is refused.
        var r2 = Reassembler()
        for i in 0..<4 { _ = try r2.accept(FrameCodec.encode(msgId: i, body: [UInt8](repeating: 0, count: 100), frameLen: 20)[0], nowMs: 0) }
        expectP2P(.rate) { _ = try r2.accept(FrameCodec.encode(msgId: 4, body: [UInt8](repeating: 0, count: 100), frameLen: 20)[0], nowMs: 0) }
    }

    func testViolations() throws {
        let frames = try FrameCodec.encode(msgId: 5, body: (0..<200).map { UInt8($0) }, frameLen: 20)
        var r = Reassembler()
        _ = try r.accept(frames[0], nowMs: 0)
        expectP2P(.malformed, "gap") { _ = try r.accept(frames[2], nowMs: 0) }
        expectP2P(.malformed, "partial was dropped") { _ = try r.accept(frames[1], nowMs: 0) }
        var v2 = frames[0]
        v2[0] = 2
        expectP2P(.frameVersion) { var x = Reassembler(); _ = try x.accept(v2, nowMs: 0) }
        expectP2P(.malformed) { var x = Reassembler(); _ = try x.accept([UInt8](repeating: 0, count: 7), nowMs: 0) }
        var long = [UInt8](repeating: 0, count: 513)
        long[0] = 1
        long[6] = 1
        expectP2P(.malformed) { var x = Reassembler(); _ = try x.accept(long, nowMs: 0) }
        // Count mismatch mid-message.
        var r3 = Reassembler()
        _ = try r3.accept(frames[0], nowMs: 0)
        var bad = frames[1]
        bad[6] &+= 1
        expectP2P(.malformed) { _ = try r3.accept(bad, nowMs: 0) }
        // Restart with index 0 replaces the unfinished message.
        var r4 = Reassembler()
        _ = try r4.accept(frames[0], nowMs: 0)
        let single = try FrameCodec.encode(msgId: 5, body: [1, 2, 3], frameLen: 20)
        XCTAssertEqual(single.count, 1)
        XCTAssertEqual(try r4.accept(single[0], nowMs: 0), [1, 2, 3])
        XCTAssertEqual(r4.pending, 0)
    }

    func testPartialTimesOut() throws {
        let frames = try FrameCodec.encode(msgId: 1, body: [UInt8](repeating: 0, count: 50), frameLen: 20)
        var r = Reassembler()
        XCTAssertNil(try r.accept(frames[0], nowMs: 0))
        XCTAssertEqual(r.expire(nowMs: P2PConstants.reassemblyTimeoutMs), 0)
        XCTAssertEqual(r.expire(nowMs: P2PConstants.reassemblyTimeoutMs + 1), 1)
        expectP2P(.malformed) { _ = try r.accept(frames[1], nowMs: 20_000) }
    }

    func testRandomGarbageOnlyThrowsProtocolErrors() {
        var rnd = SeededRNG(99)
        for _ in 0..<20_000 {
            var r = Reassembler()
            for _ in 0..<Int.random(in: 1..<6, using: &rnd) {
                var junk = rnd.bytes(Int.random(in: 0..<60, using: &rnd))
                if !junk.isEmpty && Bool.random(using: &rnd) { junk[0] = 1 }
                do {
                    if let body = try r.accept(junk, nowMs: 0) {
                        do { _ = try P2PMessages.decode(body) } catch is P2PError {}
                    }
                } catch is P2PError {
                } catch {
                    XCTFail("unexpected error \(error)")
                }
            }
        }
    }

    func testBodyPacking() throws {
        let json = Array(#"{"t":"x"}"#.utf8)
        let body = try FrameCodec.packBody(json, [9, 9])
        XCTAssertEqual(body[0], 0)
        XCTAssertEqual(Int(body[1]), json.count)
        let (j, b) = try FrameCodec.unpackBody(body)
        XCTAssertEqual(j, json)
        XCTAssertEqual(b, [9, 9])
        expectP2P(.malformed) { _ = try FrameCodec.unpackBody([0, 1, 0]) }
        expectP2P(.malformed) { _ = try FrameCodec.unpackBody([0, 9, 0x7B, 0x7D]) }
    }
}
