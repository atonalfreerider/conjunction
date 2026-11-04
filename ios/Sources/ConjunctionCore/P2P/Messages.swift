import Foundation

// Message schemas (docs/P2P_PROTOCOL.md section 5.3). `wire` returns the members in wire order;
// `P2PMessages.decode` validates a received body. Byte strings travel as base64 and are kept as
// strings here (as in Kotlin) so equality is simple; use the *Bytes accessors.

/// `hello.compat`: the CompatProfile with longitudes as integer centidegrees.
public struct WireCompat: Hashable, Sendable {
    public let sun: Int
    public let moon: Int
    public let venus: Int
    public let mars: Int
    public let asc: Int?
    public let animal: ChineseAnimal
    public let stem: HeavenlyStem
    public let blood: BloodType?
    public let birthDate: LocalDate
    public let birthHour: Int?

    public init(sun: Int, moon: Int, venus: Int, mars: Int, asc: Int?, animal: ChineseAnimal, stem: HeavenlyStem,
                blood: BloodType?, birthDate: LocalDate, birthHour: Int?) throws {
        for v in [sun, moon, venus, mars] + (asc.map { [$0] } ?? []) where !(0...35_999).contains(v) {
            throw P2PUsageError("centidegrees out of range: \(v)")
        }
        if let h = birthHour, !(0...23).contains(h) { throw P2PUsageError("birthHour out of range") }
        self.sun = sun
        self.moon = moon
        self.venus = venus
        self.mars = mars
        self.asc = asc
        self.animal = animal
        self.stem = stem
        self.blood = blood
        self.birthDate = birthDate
        self.birthHour = birthHour
    }

    /// The lowercase enum name the wire uses for a day stem (jia ... gui).
    static func stemWire(_ s: HeavenlyStem) -> String { s.name.lowercased() }

    public var wire: WireObject {
        ["sun": .int(sun), "moon": .int(moon), "venus": .int(venus), "mars": .int(mars), "asc": .optional(asc),
         "animal": .string(animal.key), "stem": .string(WireCompat.stemWire(stem)), "blood": .optional(blood?.key),
         "birthDate": .string(birthDate.description), "birthHour": .optional(birthHour)]
    }

    /// Exact: Compatibility.centidegrees(c / 100.0) == c for every c in 0..35999.
    public func toCompatProfile() -> CompatProfile {
        // Every field was range-checked in init, so CompatProfile's own checks cannot fail.
        try! CompatProfile(sun: Double(sun) / 100.0, moon: Double(moon) / 100.0, venus: Double(venus) / 100.0,
                           mars: Double(mars) / 100.0, ascendant: asc.map { Double($0) / 100.0 },
                           chineseAnimal: animal, dayStem: stem, bloodType: blood,
                           birthDate: birthDate, birthHour: birthHour)
    }

    public static func from(_ p: CompatProfile) -> WireCompat {
        // centidegrees() is always in 0..35999 and CompatProfile already checked the hour.
        try! WireCompat(sun: Compatibility.centidegrees(p.sun), moon: Compatibility.centidegrees(p.moon),
                        venus: Compatibility.centidegrees(p.venus), mars: Compatibility.centidegrees(p.mars),
                        asc: p.ascendant.map(Compatibility.centidegrees), animal: p.chineseAnimal, stem: p.dayStem,
                        blood: p.bloodType, birthDate: p.birthDate, birthHour: p.birthHour)
    }
}

extension MatchProfile {
    var p2pWire: WireObject {
        ["gender": .string(gender.wire), "seeking": .string(seeking.wire),
         "relationship": .string(relationshipStatus.wire), "lookingFor": .string(lookingFor.wire)]
    }
}

public struct PhotoMeta: Hashable, Sendable {
    public let sha256: String
    public let size: Int
    public let w: Int
    public let h: Int

    public init(sha256: String, size: Int, w: Int, h: Int) {
        self.sha256 = sha256
        self.size = size
        self.w = w
        self.h = h
    }

    var wire: WireObject { ["sha256": .string(sha256), "size": .int(size), "w": .int(w), "h": .int(h)] }
}

public enum SwipeDirection: String, CaseIterable, Sendable {
    case right, left
    public var wire: String { rawValue }
}

public struct Hello: Hashable, Sendable {
    public var pv: Int
    public var pmin: Int
    public var eid: String
    public var match: MatchProfile
    public var compat: WireCompat
    /// Base64 friend tags / decoys.
    public var ft: [String]

    public init(pv: Int, pmin: Int, eid: String, match: MatchProfile, compat: WireCompat, ft: [String]) {
        self.pv = pv
        self.pmin = pmin
        self.eid = eid
        self.match = match
        self.compat = compat
        self.ft = ft
    }
}

public struct Card: Hashable, Sendable {
    public var kind: MatchKind
    public var firstName: String
    public var bio: String?
    public var photo: PhotoMeta?

    public init(kind: MatchKind, firstName: String, bio: String?, photo: PhotoMeta?) {
        self.kind = kind
        self.firstName = firstName
        self.bio = bio
        self.photo = photo
    }
}

public enum P2PMessage: Hashable, Sendable {
    case hello(Hello)
    case card(Card)
    /// The JPEG travels as the body's blob.
    case photo(sha256: String)
    case swipe(SwipeDirection)
    /// Base64 X9.63 identity and link-ephemeral public keys.
    case keyx(ik: String, ek: String)
    /// Base64 16-byte key-confirmation MAC.
    case kc(mac: String)
    /// Counter and base64 ciphertext || tag.
    case chat(ctr: Int64, ct: String)
    case ack(id: String)
    case bye(reason: String)
    case error(code: String, detail: String?)
    /// A message type this version does not know; ignored by the link.
    case unknown(type: String)

    public static func keyx(ik: [UInt8], ek: [UInt8]) -> P2PMessage { .keyx(ik: P2PBytes.b64(ik), ek: P2PBytes.b64(ek)) }

    public var type: String {
        switch self {
        case .hello: return "hello"
        case .card: return "card"
        case .photo: return "photo"
        case .swipe: return "swipe"
        case .keyx: return "keyx"
        case .kc: return "kc"
        case .chat: return "chat"
        case .ack: return "ack"
        case .bye: return "bye"
        case .error: return "error"
        case .unknown(let t): return t
        }
    }

    public var wire: WireObject {
        let t = WireValue.string(type)
        switch self {
        case .hello(let h):
            return ["t": t, "pv": .int(h.pv), "pmin": .int(h.pmin), "eid": .string(h.eid),
                    "match": .object(h.match.p2pWire), "compat": .object(h.compat.wire),
                    "ft": .array(h.ft.map { .string($0) })]
        case .card(let c):
            return ["t": t, "kind": .string(c.kind.wire), "firstName": .string(c.firstName), "bio": .optional(c.bio),
                    "photo": c.photo.map { .object($0.wire) } ?? .null]
        case .photo(let sha):
            return ["t": t, "sha256": .string(sha), "mime": .string("image/jpeg")]
        case .swipe(let d):
            return ["t": t, "dir": .string(d.wire)]
        case .keyx(let ik, let ek):
            return ["t": t, "ik": .string(ik), "ek": .string(ek)]
        case .kc(let mac):
            return ["t": t, "mac": .string(mac)]
        case .chat(let ctr, let ct):
            return ["t": t, "ctr": .int(ctr), "ct": .string(ct)]
        case .ack(let id):
            return ["t": t, "id": .string(id)]
        case .bye(let reason):
            return ["t": t, "reason": .string(reason)]
        case .error(let code, let detail):
            return ["t": t, "code": .string(code), "detail": .optional(detail)]
        case .unknown:
            return ["t": t]
        }
    }
}

/// Decrypted chat plaintext (section 5.3, `chat`).
public enum ChatPayload: Hashable, Sendable {
    case text(id: String, ts: Int64, text: String)
    case read(id: String, ts: Int64, refs: [String])
    /// SPEC 5d.6: the sender's Instagram handle for this friend (`ig` nil = not shared / removed).
    /// Not a chat message: never shown, never stored in the history, never listed in `read.refs`.
    case profile(id: String, ts: Int64, rev: Int, ig: String?)
    /// A kind this version does not know; ignored after decryption.
    case unknown(id: String, ts: Int64, kind: String)

    public var id: String {
        switch self {
        case .text(let id, _, _), .read(let id, _, _), .unknown(let id, _, _), .profile(let id, _, _, _): return id
        }
    }

    public var ts: Int64 {
        switch self {
        case .text(_, let ts, _), .read(_, let ts, _), .unknown(_, let ts, _), .profile(_, let ts, _, _): return ts
        }
    }

    public var wire: WireObject {
        switch self {
        case .text(let id, let ts, let text):
            return ["id": .string(id), "ts": .int(ts), "kind": .string("text"), "text": .string(text)]
        case .read(let id, let ts, let refs):
            return ["id": .string(id), "ts": .int(ts), "kind": .string("read"), "refs": .array(refs.map { .string($0) })]
        case .profile(let id, let ts, let rev, let ig):
            return ["id": .string(id), "ts": .int(ts), "kind": .string("profile"), "rev": .int(Int64(rev)),
                    "ig": ig.map { .string($0) } ?? .null]
        case .unknown(let id, let ts, let kind):
            return ["id": .string(id), "ts": .int(ts), "kind": .string(kind)]
        }
    }
}

/// A decoded body: message + blob (non-empty only for `photo`).
public struct DecodedMessage: Hashable, Sendable {
    public let message: P2PMessage
    public let blob: [UInt8]
}

public enum P2PMessages {

    public static func encode(_ message: P2PMessage, blob: [UInt8] = []) throws -> [UInt8] {
        try FrameCodec.packBody(WireJSON.write(.object(message.wire)), blob)
    }

    public static func canonicalJSON(_ message: P2PMessage) -> String { WireJSON.writeString(.object(message.wire)) }

    public static func decode(_ body: [UInt8]) throws -> DecodedMessage {
        let (json, blob) = try FrameCodec.unpackBody(body)
        guard case .object(let o) = try WireJSON.parse(json) else { throw P2PError.malformed("message is not an object") }
        guard case .string(let t)? = o["t"] else { throw P2PError.malformed("missing or non-string t") }
        if !blob.isEmpty && t != "photo" { throw P2PError.malformed("\(t) must not carry a blob") }
        let msg: P2PMessage
        switch t {
        case "hello":
            let pv = try int(o, "pv", 1, 255)
            let pmin = try int(o, "pmin", 1, Int64(pv))
            let eid = try hex(o, "eid", 32)
            let m = try obj(o, "match")
            let match = MatchProfile(
                gender: try wireEnum(m, "match.gender", "gender", Gender.self),
                seeking: try wireEnum(m, "match.seeking", "seeking", Seeking.self),
                relationshipStatus: try wireEnum(m, "match.relationship", "relationship", RelationshipStatus.self),
                lookingFor: try wireEnum(m, "match.lookingFor", "lookingFor", LookingFor.self))
            let c = try obj(o, "compat")
            let compat = try WireCompat(
                sun: try int(c, "sun", 0, 35_999), moon: try int(c, "moon", 0, 35_999),
                venus: try int(c, "venus", 0, 35_999), mars: try int(c, "mars", 0, 35_999),
                asc: isNull(c, "asc") ? nil : try int(c, "asc", 0, 35_999),
                animal: try oneOf(c, "compat.animal", "animal", ChineseAnimal.allCases) { $0.key },
                stem: try oneOf(c, "compat.stem", "stem", HeavenlyStem.allCases, WireCompat.stemWire),
                blood: isNull(c, "blood") ? nil : try oneOf(c, "compat.blood", "blood", BloodType.allCases) { $0.key },
                birthDate: try date(c, "birthDate"),
                birthHour: isNull(c, "birthHour") ? nil : try int(c, "birthHour", 0, 23))
            guard case .array(let ftRaw)? = o["ft"] else { throw P2PError.malformed("ft: expected a list") }
            if ftRaw.count > P2PConstants.friendTagsMax { throw P2PError.malformed("ft: more than 64 tags") }
            let ft = try ftRaw.map { tag -> String in
                guard case .string(let s) = tag else { throw P2PError.malformed("ft[]: not a string") }
                _ = try P2PBytes.b64Strict(s, length: P2PConstants.friendTagLen, what: "ft[]")
                return s
            }
            msg = .hello(Hello(pv: pv, pmin: pmin, eid: eid, match: match, compat: compat, ft: ft))
        case "card":
            let kind = try wireEnum(o, "kind", "kind", MatchKind.self)
            let name = try str(o, "firstName", P2PConstants.maxFirstNameBytes, 1)
            let bio = isNull(o, "bio") ? nil : try str(o, "bio", P2PConstants.maxBioBytes, 0)
            var photo: PhotoMeta?
            if !isNull(o, "photo") {
                let p = try obj(o, "photo")
                photo = PhotoMeta(sha256: try hex(p, "sha256", 64),
                                  size: try int(p, "size", 1, Int64(P2PConstants.maxBlobLen)),
                                  w: try int(p, "w", 1, Int64(P2PConstants.maxPhotoSide)),
                                  h: try int(p, "h", 1, Int64(P2PConstants.maxPhotoSide)))
            }
            msg = .card(Card(kind: kind, firstName: name, bio: bio, photo: photo))
        case "photo":
            let sha = try hex(o, "sha256", 64)
            guard case .string("image/jpeg")? = o["mime"] else { throw P2PError.malformed("mime must be image/jpeg") }
            if blob.isEmpty { throw P2PError.malformed("photo without blob") }
            msg = .photo(sha256: sha)
        case "swipe":
            msg = .swipe(try oneOf(o, "dir", "dir", SwipeDirection.allCases) { $0.wire })
        case "keyx":
            guard case .string(let ik)? = o["ik"] else { throw P2PError.malformed("ik missing") }
            guard case .string(let ek)? = o["ek"] else { throw P2PError.malformed("ek missing") }
            for (n, v) in [("ik", ik), ("ek", ek)] where try P2PBytes.b64Strict(v, length: 65, what: n)[0] != 0x04 {
                throw P2PError.malformed("\(n): not an uncompressed point")
            }
            msg = .keyx(ik: ik, ek: ek)
        case "kc":
            guard case .string(let mac)? = o["mac"] else { throw P2PError.malformed("mac missing") }
            _ = try P2PBytes.b64Strict(mac, length: 16, what: "mac")
            msg = .kc(mac: mac)
        case "chat":
            let ctr = try long(o, "ctr", 0, 0xFFFF_FFFF)
            guard case .string(let ct)? = o["ct"] else { throw P2PError.malformed("ct missing") }
            let n = try P2PBytes.b64Strict(ct, length: nil, what: "ct").count
            if n < 17 || n > 2 * P2PConstants.maxChatTextBytes + 256 { throw P2PError.malformed("ct length") }
            msg = .chat(ctr: ctr, ct: ct)
        case "ack":
            msg = .ack(id: try hex(o, "id", 32))
        case "bye":
            msg = .bye(reason: try str(o, "reason", 32, 1))
        case "error":
            msg = .error(code: try str(o, "code", 32, 1), detail: isNull(o, "detail") ? nil : try str(o, "detail", 200, 0))
        default:
            msg = .unknown(type: t)
        }
        return DecodedMessage(message: msg, blob: blob)
    }

    public static func encodeChatPayload(_ p: ChatPayload) -> [UInt8] { WireJSON.write(.object(p.wire)) }

    public static func decodeChatPayload(_ plaintext: [UInt8]) throws -> ChatPayload {
        guard case .object(let o) = try WireJSON.parse(plaintext) else { throw P2PError.malformed("chat plaintext is not an object") }
        let id = try hex(o, "id", 32)
        let ts = try long(o, "ts", 0, 1 << 53)
        switch o["kind"] {
        case .string("text")?:
            return .text(id: id, ts: ts, text: try str(o, "text", P2PConstants.maxChatTextBytes, 1))
        case .string("read")?:
            guard case .array(let refs)? = o["refs"] else { throw P2PError.malformed("refs: expected a list") }
            guard (1...P2PConstants.maxReadRefs).contains(refs.count) else { throw P2PError.malformed("refs: 1..64 entries") }
            return .read(id: id, ts: ts, refs: try refs.map { r in
                guard case .string(let s) = r, isHex(s, 32) else { throw P2PError.malformed("refs[]: bad id") }
                return s
            })
        case .string("profile")?:
            let rev = try int(o, "rev", 0, Int64(P2PConstants.maxProfileRev))
            let ig: String?
            switch o["ig"] {
            case nil, .null?: ig = nil
            case .string(let v)?: ig = v
            default: throw P2PError.malformed("ig: expected a string or null")
            }
            // Lenient: a string failing the handle rule is treated as null (SPEC 5d.6).
            return .profile(id: id, ts: ts, rev: rev, ig: InstagramHandle.received(ig))
        case .string(let kind)?:
            return .unknown(id: id, ts: ts, kind: kind)
        default:
            throw P2PError.malformed("chat kind missing")
        }
    }

    // ----------------------------------------------------------------- validation helpers

    /// Absent and JSON null are treated alike, as in the Kotlin and Python decoders.
    private static func isNull(_ o: WireObject, _ k: String) -> Bool {
        switch o[k] {
        case nil, .null?: return true
        default: return false
        }
    }

    private static func obj(_ o: WireObject, _ k: String) throws -> WireObject {
        guard case .object(let v)? = o[k] else { throw P2PError.malformed("\(k): expected an object") }
        return v
    }

    private static func long(_ o: WireObject, _ k: String, _ lo: Int64, _ hi: Int64) throws -> Int64 {
        guard case .int(let v)? = o[k] else { throw P2PError.malformed("\(k): expected an integer") }
        guard v >= lo && v <= hi else { throw P2PError.malformed("\(k): \(v) not in \(lo)..\(hi)") }
        return v
    }

    private static func int(_ o: WireObject, _ k: String, _ lo: Int64, _ hi: Int64) throws -> Int {
        Int(try long(o, k, lo, hi))
    }

    private static func str(_ o: WireObject, _ k: String, _ maxBytes: Int, _ minBytes: Int) throws -> String {
        guard case .string(let v)? = o[k] else { throw P2PError.malformed("\(k): expected a string") }
        let n = v.utf8.count
        guard n >= minBytes && n <= maxBytes else { throw P2PError.malformed("\(k): \(n) bytes not in \(minBytes)..\(maxBytes)") }
        return v
    }

    static func isHex(_ s: String, _ len: Int) -> Bool {
        s.utf8.count == len && s.utf8.allSatisfy { (0x30...0x39).contains($0) || (0x61...0x66).contains($0) }
    }

    private static func hex(_ o: WireObject, _ k: String, _ len: Int) throws -> String {
        guard case .string(let v)? = o[k] else { throw P2PError.malformed("\(k): expected a hex string") }
        guard isHex(v, len) else { throw P2PError.malformed("\(k): bad hex") }
        return v
    }

    private static func date(_ o: WireObject, _ k: String) throws -> LocalDate {
        guard case .string(let v)? = o[k] else { throw P2PError.malformed("\(k): expected a date") }
        let u = Array(v.utf8)
        let shape = u.count == 10 && u[4] == 0x2D && u[7] == 0x2D &&
            [0, 1, 2, 3, 5, 6, 8, 9].allSatisfy { (0x30...0x39).contains(u[$0]) }
        guard shape else { throw P2PError.malformed("\(k): expected YYYY-MM-DD") }
        guard let d = LocalDate.parse(v) else { throw P2PError.malformed("\(k): invalid date") }
        guard d.year >= 1 else { throw P2PError.malformed("\(k): year 0000") }
        return d
    }

    private static func oneOf<E>(_ o: WireObject, _ what: String, _ k: String, _ all: [E], _ wire: (E) -> String) throws -> E {
        guard case .string(let v)? = o[k] else { throw P2PError.malformed("\(what): expected a string") }
        guard let e = all.first(where: { wire($0) == v }) else { throw P2PError.malformed("\(what): '\(v)' is not allowed") }
        return e
    }

    private static func wireEnum<E: WireEnum>(_ o: WireObject, _ what: String, _ k: String, _ type: E.Type) throws -> E {
        try oneOf(o, what, k, Array(E.allCases)) { $0.wire }
    }
}

/// Hex and strict base64 helpers shared by the P2P code.
public enum P2PBytes {
    private static let digits = Array("0123456789abcdef".utf8)

    public static func hex<B: Sequence>(_ b: B) -> String where B.Element == UInt8 {
        var out: [UInt8] = []
        for x in b {
            out.append(digits[Int(x >> 4)])
            out.append(digits[Int(x & 15)])
        }
        return String(decoding: out, as: UTF8.self)
    }

    /// Lowercase or uppercase hex to bytes; nil on odd length or a non-hex character.
    public static func unhex(_ s: String) -> [UInt8]? {
        let u = Array(s.utf8)
        guard u.count % 2 == 0 else { return nil }
        func nib(_ c: UInt8) -> UInt8? {
            switch c {
            case 0x30...0x39: return c - 0x30
            case 0x61...0x66: return c - 0x61 + 10
            case 0x41...0x46: return c - 0x41 + 10
            default: return nil
            }
        }
        var out: [UInt8] = []
        out.reserveCapacity(u.count / 2)
        var i = 0
        while i < u.count {
            guard let h = nib(u[i]), let l = nib(u[i + 1]) else { return nil }
            out.append(h << 4 | l)
            i += 2
        }
        return out
    }

    /// RFC 4648 section 4 base64 with padding.
    public static func b64(_ b: [UInt8]) -> String { Data(b).base64EncodedString() }

    /// Lenient decode for values that were already validated by `P2PMessages.decode`.
    public static func b64d(_ s: String) -> [UInt8] { Data(base64Encoded: s).map { [UInt8]($0) } ?? [] }

    /// Strict base64: standard alphabet, canonical padding, no whitespace; optional exact length.
    public static func b64Strict(_ s: String, length: Int?, what: String) throws -> [UInt8] {
        guard let d = Data(base64Encoded: s) else { throw P2PError.malformed("\(what): bad base64") }
        let raw = [UInt8](d)
        if b64(raw) != s { throw P2PError.malformed("\(what): non-canonical base64") }
        if let n = length, raw.count != n { throw P2PError.malformed("\(what): expected \(n) bytes, got \(raw.count)") }
        return raw
    }
}
