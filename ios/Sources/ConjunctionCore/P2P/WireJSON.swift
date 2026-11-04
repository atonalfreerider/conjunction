import Foundation

/// A JSON value as the wire allows it (docs/P2P_PROTOCOL.md section 5.1): no floating point.
public indirect enum WireValue: Equatable, Sendable {
    case null
    case bool(Bool)
    case int(Int64)
    case string(String)
    case array([WireValue])
    case object(WireObject)

    public static func int(_ v: Int) -> WireValue { .int(Int64(v)) }

    /// `.null` for nil.
    public static func optional(_ v: String?) -> WireValue { v.map { .string($0) } ?? .null }
    public static func optional(_ v: Int?) -> WireValue { v.map { .int(Int64($0)) } ?? .null }
}

/// A JSON object that keeps its members in insertion order (the canonical wire order). Equality
/// ignores order, like Kotlin's LinkedHashMap.
public struct WireObject: Equatable, Sendable, ExpressibleByDictionaryLiteral {
    public private(set) var keys: [String] = []
    private var storage: [String: WireValue] = [:]

    public init() {}

    /// Members in the literal's order. Duplicate keys are a programming error.
    public init(dictionaryLiteral elements: (String, WireValue)...) {
        for (k, v) in elements {
            precondition(insert(k, v), "duplicate key \(k)")
        }
    }

    /// Adds a member; false (and no change) when [key] is already present.
    @discardableResult
    public mutating func insert(_ key: String, _ value: WireValue) -> Bool {
        guard storage[key] == nil else { return false }
        keys.append(key)
        storage[key] = value
        return true
    }

    /// The member's value; nil when absent (`.null` when present as JSON null).
    public subscript(_ key: String) -> WireValue? { storage[key] }

    public var count: Int { keys.count }

    public var members: [(String, WireValue)] { keys.map { ($0, storage[$0]!) } }

    public static func == (a: WireObject, b: WireObject) -> Bool { a.storage == b.storage }
}

/// Canonical JSON writer and strict reader for the wire (section 5.1).
///
/// Writer: members in insertion order, no whitespace, integers only, strings escaping only `"`,
/// `\` and U+0000..U+001F (\b \f \n \r \t, others \u00xx lowercase); everything else, `/` and
/// non-ASCII included, as raw UTF-8. Byte-identical to Python
/// `json.dumps(o, ensure_ascii=False, separators=(",", ":"))` and Kotlin `core.p2p.WireJson`.
/// (Foundation's JSONEncoder / JSONSerialization neither keep member order nor leave `/` alone.)
///
/// Reader: strict UTF-8, rejects duplicate member names, numbers with a fraction or exponent,
/// integers outside Int64, leading zeros, unpaired surrogates, raw control characters, nesting
/// deeper than 32 and trailing data, always with `P2PError(.malformed, ...)`.
public enum WireJSON {

    public static func write(_ value: WireValue) -> [UInt8] {
        var out: [UInt8] = []
        out.reserveCapacity(256)
        write(value, into: &out)
        return out
    }

    public static func writeString(_ value: WireValue) -> String { String(decoding: write(value), as: UTF8.self) }

    private static let hex = Array("0123456789abcdef".utf8)

    private static func write(_ v: WireValue, into out: inout [UInt8]) {
        switch v {
        case .null: out.append(contentsOf: "null".utf8)
        case .bool(let b): out.append(contentsOf: (b ? "true" : "false").utf8)
        case .int(let i): out.append(contentsOf: String(i).utf8)
        case .string(let s): quote(s, into: &out)
        case .array(let items):
            out.append(UInt8(ascii: "["))
            for (i, x) in items.enumerated() {
                if i > 0 { out.append(UInt8(ascii: ",")) }
                write(x, into: &out)
            }
            out.append(UInt8(ascii: "]"))
        case .object(let o):
            out.append(UInt8(ascii: "{"))
            for (i, (k, x)) in o.members.enumerated() {
                if i > 0 { out.append(UInt8(ascii: ",")) }
                quote(k, into: &out)
                out.append(UInt8(ascii: ":"))
                write(x, into: &out)
            }
            out.append(UInt8(ascii: "}"))
        }
    }

    private static func quote(_ s: String, into out: inout [UInt8]) {
        out.append(UInt8(ascii: "\""))
        // Swift strings are always valid Unicode, so no unpaired surrogate can reach the wire.
        for b in s.utf8 {
            switch b {
            case 0x22: out.append(contentsOf: [0x5C, 0x22])
            case 0x5C: out.append(contentsOf: [0x5C, 0x5C])
            case 0x08: out.append(contentsOf: [0x5C, UInt8(ascii: "b")])
            case 0x0C: out.append(contentsOf: [0x5C, UInt8(ascii: "f")])
            case 0x0A: out.append(contentsOf: [0x5C, UInt8(ascii: "n")])
            case 0x0D: out.append(contentsOf: [0x5C, UInt8(ascii: "r")])
            case 0x09: out.append(contentsOf: [0x5C, UInt8(ascii: "t")])
            case 0x00..<0x20:
                out.append(contentsOf: [0x5C, UInt8(ascii: "u"), UInt8(ascii: "0"), UInt8(ascii: "0"),
                                        hex[Int(b >> 4)], hex[Int(b & 15)]])
            default: out.append(b)
            }
        }
        out.append(UInt8(ascii: "\""))
    }

    // ------------------------------------------------------------------ reader

    public static func parse(_ bytes: [UInt8]) throws -> WireValue {
        guard isValidUTF8(bytes) else { throw P2PError.malformed("invalid UTF-8") }
        var p = Parser(s: bytes)
        let v = try p.value(depth: 0)
        p.ws()
        if p.pos != bytes.count { throw P2PError.malformed("trailing data at \(p.pos)") }
        return v
    }

    public static func parse(_ text: String) throws -> WireValue { try parse(Array(text.utf8)) }

    /// RFC 3629 UTF-8: no overlong forms, no surrogates, nothing above U+10FFFF.
    static func isValidUTF8(_ b: [UInt8]) -> Bool {
        var i = 0
        let n = b.count
        while i < n {
            let c = b[i]
            if c < 0x80 { i += 1; continue }
            let len: Int
            var lo: UInt8 = 0x80, hi: UInt8 = 0xBF
            switch c {
            case 0xC2...0xDF: len = 2
            case 0xE0: len = 3; lo = 0xA0
            case 0xE1...0xEC, 0xEE...0xEF: len = 3
            case 0xED: len = 3; hi = 0x9F
            case 0xF0: len = 4; lo = 0x90
            case 0xF1...0xF3: len = 4
            case 0xF4: len = 4; hi = 0x8F
            default: return false
            }
            if i + len > n { return false }
            if b[i + 1] < lo || b[i + 1] > hi { return false }
            var k = 2
            while k < len {
                if b[i + k] < 0x80 || b[i + k] > 0xBF { return false }
                k += 1
            }
            i += len
        }
        return true
    }

    private struct Parser {
        let s: [UInt8]
        var pos = 0

        mutating func ws() {
            while pos < s.count, s[pos] == 0x20 || s[pos] == 0x09 || s[pos] == 0x0A || s[pos] == 0x0D { pos += 1 }
        }

        func peek() throws -> UInt8 {
            guard pos < s.count else { throw P2PError.malformed("unexpected end of JSON") }
            return s[pos]
        }

        mutating func value(depth: Int) throws -> WireValue {
            if depth > 32 { throw P2PError.malformed("JSON nested too deeply") }
            ws()
            let c = try peek()
            switch c {
            case UInt8(ascii: "{"): return .object(try object(depth: depth))
            case UInt8(ascii: "["): return .array(try array(depth: depth))
            case UInt8(ascii: "\""): return .string(try string())
            case UInt8(ascii: "t"): try literal("true"); return .bool(true)
            case UInt8(ascii: "f"): try literal("false"); return .bool(false)
            case UInt8(ascii: "n"): try literal("null"); return .null
            case UInt8(ascii: "-"), UInt8(ascii: "0")...UInt8(ascii: "9"): return .int(try number())
            default: throw P2PError.malformed("unexpected byte at \(pos)")
            }
        }

        mutating func literal(_ w: String) throws {
            let u = Array(w.utf8)
            guard pos + u.count <= s.count, Array(s[pos..<pos + u.count]) == u else {
                throw P2PError.malformed("bad literal at \(pos)")
            }
            pos += u.count
        }

        mutating func object(depth: Int) throws -> WireObject {
            var o = WireObject()
            pos += 1
            ws()
            if try peek() == UInt8(ascii: "}") { pos += 1; return o }
            while true {
                ws()
                guard try peek() == UInt8(ascii: "\"") else { throw P2PError.malformed("expected member name at \(pos)") }
                let k = try string()
                ws()
                guard try peek() == UInt8(ascii: ":") else { throw P2PError.malformed("expected ':' at \(pos)") }
                pos += 1
                if o[k] != nil { throw P2PError.malformed("duplicate member '\(k)'") }
                o.insert(k, try value(depth: depth + 1))
                ws()
                switch try peek() {
                case UInt8(ascii: ","): pos += 1
                case UInt8(ascii: "}"): pos += 1; return o
                default: throw P2PError.malformed("expected ',' or '}' at \(pos)")
                }
            }
        }

        mutating func array(depth: Int) throws -> [WireValue] {
            var l: [WireValue] = []
            pos += 1
            ws()
            if try peek() == UInt8(ascii: "]") { pos += 1; return l }
            while true {
                l.append(try value(depth: depth + 1))
                ws()
                switch try peek() {
                case UInt8(ascii: ","): pos += 1
                case UInt8(ascii: "]"): pos += 1; return l
                default: throw P2PError.malformed("expected ',' or ']' at \(pos)")
                }
            }
        }

        mutating func hex4() throws -> UInt32 {
            guard pos + 4 <= s.count else { throw P2PError.malformed("short \\u escape") }
            var v: UInt32 = 0
            for b in s[pos..<pos + 4] {
                let d: UInt8
                switch b {
                case UInt8(ascii: "0")...UInt8(ascii: "9"): d = b - UInt8(ascii: "0")
                case UInt8(ascii: "a")...UInt8(ascii: "f"): d = b - UInt8(ascii: "a") + 10
                case UInt8(ascii: "A")...UInt8(ascii: "F"): d = b - UInt8(ascii: "A") + 10
                default: throw P2PError.malformed("bad \\u escape")
                }
                v = v << 4 | UInt32(d)
            }
            pos += 4
            return v
        }

        /// The input is valid UTF-8 (checked up front), so raw bytes are copied as they are.
        mutating func string() throws -> String {
            pos += 1 // opening quote
            var out: [UInt8] = []
            while true {
                let c = try peek()
                pos += 1
                switch c {
                case UInt8(ascii: "\""):
                    return String(decoding: out, as: UTF8.self)
                case UInt8(ascii: "\\"):
                    let e = try peek()
                    pos += 1
                    switch e {
                    case UInt8(ascii: "\""): out.append(0x22)
                    case UInt8(ascii: "\\"): out.append(0x5C)
                    case UInt8(ascii: "/"): out.append(0x2F)
                    case UInt8(ascii: "b"): out.append(0x08)
                    case UInt8(ascii: "f"): out.append(0x0C)
                    case UInt8(ascii: "n"): out.append(0x0A)
                    case UInt8(ascii: "r"): out.append(0x0D)
                    case UInt8(ascii: "t"): out.append(0x09)
                    case UInt8(ascii: "u"):
                        var cp = try hex4()
                        if (0xD800...0xDBFF).contains(cp) {
                            guard pos + 2 <= s.count, s[pos] == 0x5C, s[pos + 1] == UInt8(ascii: "u") else {
                                throw P2PError.malformed("unpaired surrogate")
                            }
                            pos += 2
                            let low = try hex4()
                            guard (0xDC00...0xDFFF).contains(low) else { throw P2PError.malformed("unpaired surrogate") }
                            cp = 0x10000 + ((cp - 0xD800) << 10) + (low - 0xDC00)
                        } else if (0xDC00...0xDFFF).contains(cp) {
                            throw P2PError.malformed("unpaired surrogate")
                        }
                        out.append(contentsOf: Array(String(Character(Unicode.Scalar(cp)!)).utf8))
                    default:
                        throw P2PError.malformed("bad escape")
                    }
                case 0x00..<0x20:
                    throw P2PError.malformed("raw control character in string")
                default:
                    out.append(c)
                }
            }
        }

        mutating func number() throws -> Int64 {
            let start = pos
            if s[pos] == UInt8(ascii: "-") { pos += 1 }
            let digitsStart = pos
            while pos < s.count, (UInt8(ascii: "0")...UInt8(ascii: "9")).contains(s[pos]) { pos += 1 }
            if pos == digitsStart { throw P2PError.malformed("bad number at \(start)") }
            if pos < s.count, s[pos] == UInt8(ascii: ".") || s[pos] == UInt8(ascii: "e") || s[pos] == UInt8(ascii: "E") {
                throw P2PError.malformed("floating-point number at \(start)")
            }
            if pos - digitsStart > 1, s[digitsStart] == UInt8(ascii: "0") {
                throw P2PError.malformed("leading zero at \(start)")
            }
            guard let v = Int64(String(decoding: s[start..<pos], as: UTF8.self)) else {
                throw P2PError.malformed("integer out of range at \(start)")
            }
            return v
        }
    }
}
