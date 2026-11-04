import Foundation

/// Frame codec (docs/P2P_PROTOCOL.md section 4).
///
/// Frame = ver(1) | msgId(u16 BE) | index(u16 BE) | count(u16 BE) | payload(1..frameLen-7).
/// Body  = jsonLen(u16 BE) | canonical JSON | blob.
public enum FrameCodec {

    /// json + blob -> body. Lengths outside the protocol limits are a programming error (the link
    /// never builds them), so they throw `P2PUsageError`.
    public static func packBody(_ json: [UInt8], _ blob: [UInt8] = []) throws -> [UInt8] {
        guard (2...P2PConstants.maxJsonLen).contains(json.count) else { throw P2PUsageError("json length \(json.count) out of range") }
        guard blob.count <= P2PConstants.maxBlobLen else { throw P2PUsageError("blob too large") }
        var out: [UInt8] = []
        out.reserveCapacity(2 + json.count + blob.count)
        out.append(UInt8(json.count >> 8))
        out.append(UInt8(json.count & 0xFF))
        out.append(contentsOf: json)
        out.append(contentsOf: blob)
        return out
    }

    /// Splits a body into (json, blob); throws malformed.
    public static func unpackBody(_ body: [UInt8]) throws -> (json: [UInt8], blob: [UInt8]) {
        guard body.count >= 4 else { throw P2PError.malformed("body shorter than 4 bytes") }
        let jlen = u16(body, 0)
        guard jlen >= 2, jlen <= P2PConstants.maxJsonLen, 2 + jlen <= body.count else {
            throw P2PError.malformed("bad json length \(jlen)")
        }
        return (Array(body[2..<(2 + jlen)]), Array(body[(2 + jlen)...]))
    }

    /// Splits [body] into frames no longer than [frameLen] bytes (header included).
    public static func encode(msgId: Int, body: [UInt8], frameLen: Int) throws -> [[UInt8]] {
        guard (0...0xFFFF).contains(msgId) else { throw P2PUsageError("msgId out of range") }
        guard (P2PConstants.minFrameLen...P2PConstants.maxFrameLen).contains(frameLen) else { throw P2PUsageError("frameLen out of range") }
        guard (1...P2PConstants.maxBodyLen).contains(body.count) else { throw P2PUsageError("body length out of range") }
        let chunk = frameLen - P2PConstants.frameHeaderLen
        let count = (body.count + chunk - 1) / chunk
        var frames: [[UInt8]] = []
        frames.reserveCapacity(count)
        for i in 0..<count {
            let from = i * chunk
            let to = min(body.count, from + chunk)
            var f: [UInt8] = [P2PConstants.frameVersion,
                              UInt8(msgId >> 8), UInt8(msgId & 0xFF),
                              UInt8(i >> 8), UInt8(i & 0xFF),
                              UInt8(count >> 8), UInt8(count & 0xFF)]
            f.append(contentsOf: body[from..<to])
            frames.append(f)
        }
        return frames
    }

    /// Message id of a frame (for logging / grouping); -1 if too short.
    public static func msgId(of frame: [UInt8]) -> Int { frame.count >= 3 ? u16(frame, 1) : -1 }

    static func u16(_ b: [UInt8], _ off: Int) -> Int { Int(b[off]) << 8 | Int(b[off + 1]) }
}

/// Per-link, per-direction reassembly (section 4.3). `accept` returns a complete body, or nil
/// while a message is incomplete, and throws `P2PError` on violations.
public struct Reassembler: Sendable {
    private struct Partial: Sendable {
        let count: Int
        var nextIndex = 0
        var data: [UInt8] = []
        var lastMs: Int64 = 0
    }

    private var partials: [Int: Partial] = [:]

    public init() {}

    public var pending: Int { partials.count }

    public mutating func accept(_ frame: [UInt8], nowMs: Int64) throws -> [UInt8]? {
        let header = P2PConstants.frameHeaderLen
        if frame.count < header + 1 { throw P2PError.malformed("frame shorter than header + 1") }
        if frame.count > P2PConstants.maxFrameLen { throw P2PError.malformed("frame longer than 512 bytes") }
        let ver = frame[0]
        if ver != P2PConstants.frameVersion { throw P2PError(.frameVersion, "unknown frame version \(ver)") }
        let msgId = FrameCodec.u16(frame, 1)
        let index = FrameCodec.u16(frame, 3)
        let count = FrameCodec.u16(frame, 5)
        if count == 0 || index >= count {
            partials[msgId] = nil
            throw P2PError.malformed("index \(index) / count \(count)")
        }
        var p: Partial
        if index == 0 {
            partials[msgId] = nil // a new message replaces an unfinished one with the same id
            if count == 1 { return Array(frame[header...]) }
            if partials.count >= P2PConstants.maxConcurrentReassemblies {
                throw P2PError(.rate, "too many concurrent messages")
            }
            p = Partial(count: count)
        } else {
            guard let existing = partials[msgId] else { throw P2PError.malformed("frame \(index) of unknown message \(msgId)") }
            if index != existing.nextIndex || count != existing.count {
                partials[msgId] = nil
                throw P2PError.malformed("out-of-order frame \(index)/\(count) for message \(msgId)")
            }
            p = existing
            partials[msgId] = nil // avoid a copy-on-write of the buffer while appending
        }
        p.data.append(contentsOf: frame[header...])
        p.nextIndex += 1
        p.lastMs = nowMs
        if p.data.count > P2PConstants.maxBodyLen {
            throw P2PError(.tooLarge, "message too large")
        }
        if p.nextIndex == p.count { return p.data }
        partials[msgId] = p
        return nil
    }

    /// Drops partial messages idle for more than 15 s; returns how many were dropped.
    @discardableResult
    public mutating func expire(nowMs: Int64) -> Int {
        let dead = partials.filter { nowMs - $0.value.lastMs > P2PConstants.reassemblyTimeoutMs }.map(\.key)
        for k in dead { partials[k] = nil }
        return dead.count
    }
}
