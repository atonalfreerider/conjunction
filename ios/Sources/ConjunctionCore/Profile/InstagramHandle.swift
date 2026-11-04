import Foundation

/// Instagram username normalisation and validation (SPEC 5d.6, golden `testdata/golden_instagram.json`).
/// Twin of Kotlin `core.profile.InstagramHandle` and Python `conjunction_p2p.instagram`.
///
/// There is no Instagram sign-in or import: the user types or pastes (`PasteButton`) a handle or a
/// profile link (Instagram > Share profile > Copy link).
public enum InstagramHandle {
    public static let maxLength = 30

    /// A first path segment that is an Instagram page, not a profile.
    public static let reservedSegments: Set<String> =
        Set("p reel reels stories explore accounts direct tv about developer legal web oauth".split(separator: " ").map(String.init))

    private static let host = Array("instagram.com/".unicodeScalars)

    /// `^[a-z0-9._]{1,30}$` with at least one letter or digit: the wire rule for `profile.ig`.
    public static func isValid(_ handle: String?) -> Bool {
        guard let handle else { return false }
        let u = Array(handle.utf8)
        guard (1...maxLength).contains(u.count) else { return false }
        var alnum = false
        for c in u {
            switch c {
            case 0x61...0x7A, 0x30...0x39: alnum = true
            case 0x2E, 0x5F: break
            default: return false
            }
        }
        return alnum
    }

    /// User input -> normalised handle, or nil when it is not a valid Instagram username:
    /// 1. text containing `instagram.com/` (any case, any subdomain/scheme): the first path segment
    ///    after the first occurrence (up to `/ ? #` or whitespace); `_u` -> the next segment; a
    ///    reserved segment (post, reel, ...) -> invalid;
    /// 2. otherwise trim whitespace and strip one leading `@`;
    /// 3. ASCII-lowercase, then `isValid`.
    public static func normalize(_ text: String?) -> String? {
        guard let text else { return nil }
        let s = Array(text.unicodeScalars)
        let lower = s.map(asciiLower)
        var candidate: [Unicode.Scalar]
        if let at = find(lower, host) {
            var (seg, end) = segment(s, at + host.count)
            if String(String.UnicodeScalarView(seg.map(asciiLower))) == "_u" {
                seg = end < s.count && s[end] == "/" ? segment(s, end + 1).0 : []
            }
            if reservedSegments.contains(String(String.UnicodeScalarView(seg.map(asciiLower)))) { return nil }
            candidate = seg
        } else {
            candidate = trim(s)
            if candidate.first == "@" { candidate.removeFirst() }
        }
        let out = String(String.UnicodeScalarView(candidate.map(asciiLower)))
        return isValid(out) ? out : nil
    }

    /// Receiver leniency for `profile.ig`: a string failing `isValid` is treated as nil.
    public static func received(_ ig: String?) -> String? { isValid(ig) ? ig : nil }

    /// The link the Instagram chip opens (`openURL`: universal link -> app, else Safari).
    public static func profileURL(_ handle: String) -> String { "https://www.instagram.com/\(handle)/" }

    /// Unicode White_Space, spelled out so Kotlin, Swift and Python trim identically.
    public static func isWhitespace(_ c: Unicode.Scalar) -> Bool {
        switch c.value {
        case 0x09...0x0D, 0x20, 0x85, 0xA0, 0x1680, 0x2000...0x200A, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000: return true
        default: return false
        }
    }

    static func trim(_ s: [Unicode.Scalar]) -> [Unicode.Scalar] {
        var i = 0, j = s.count
        while i < j && isWhitespace(s[i]) { i += 1 }
        while j > i && isWhitespace(s[j - 1]) { j -= 1 }
        return Array(s[i..<j])
    }

    private static func asciiLower(_ c: Unicode.Scalar) -> Unicode.Scalar {
        (0x41...0x5A).contains(c.value) ? Unicode.Scalar(c.value + 32)! : c
    }

    private static func find(_ hay: [Unicode.Scalar], _ needle: [Unicode.Scalar]) -> Int? {
        guard hay.count >= needle.count else { return nil }
        for i in 0...(hay.count - needle.count) where Array(hay[i..<(i + needle.count)]) == needle { return i }
        return nil
    }

    private static func segment(_ s: [Unicode.Scalar], _ start: Int) -> ([Unicode.Scalar], Int) {
        var end = start
        while end < s.count, s[end] != "/", s[end] != "?", s[end] != "#", !isWhitespace(s[end]) { end += 1 }
        return (Array(s[min(start, s.count)..<end]), end)
    }
}

/// Sender state for the chat `profile` toward ONE friend (SPEC 5d.6); persist it with the friend
/// record (Codable). Twin of Kotlin `InstagramShareState` and Python `instagram.ShareState`.
///
/// - `rev`: profile revision for this friend: 0 when the friendship is created, +1 whenever the
///   effective value changes (handle edited or removed, per-friend switch flipped).
/// - `ackedRev`: highest rev the friend acknowledged (-1 = none).
/// - `inFlightId` / `inFlightRev`: the last `profile` sent and not yet acked.
/// - `value`: the effective value at `rev` (`effective`).
///
/// Flow: on entering SECURE (new friend or friend nearby) and whenever `rev` changes on an open
/// SECURE link, if `needsSend`: `id = try link.sendProfile(rev:ig:)` FIRST (before queued chats), then
/// `state = state.sent(id)`; on `.delivered(id)`: `state = state.onAck(id)`.
public struct InstagramShareState: Hashable, Sendable, Codable {
    public var value: String?
    public var rev: Int
    public var ackedRev: Int
    public var inFlightId: String?
    public var inFlightRev: Int

    public init(value: String? = nil, rev: Int = 0, ackedRev: Int = -1, inFlightId: String? = nil, inFlightRev: Int = -1) {
        self.value = value
        self.rev = rev
        self.ackedRev = ackedRev
        self.inFlightId = inFlightId
        self.inFlightRev = inFlightRev
    }

    public var needsSend: Bool { ackedRev < rev }

    /// Applies the current effective value; bumps `rev` only when it changed.
    public func withEffective(_ newValue: String?) -> InstagramShareState {
        if newValue == value { return self }
        var s = self
        s.value = newValue
        if s.rev < P2PConstants.maxProfileRev { s.rev += 1 }
        return s
    }

    public func sent(_ id: String) -> InstagramShareState {
        var s = self
        s.inFlightId = id
        s.inFlightRev = rev
        return s
    }

    /// An ack for any other id is a chat ack and leaves this unchanged.
    public func onAck(_ id: String) -> InstagramShareState {
        guard id == inFlightId else { return self }
        var s = self
        s.ackedRev = max(ackedRev, inFlightRev)
        s.inFlightId = nil
        s.inFlightRev = -1
        return s
    }

    /// A new friendship: rev 0 with the current effective value (so exactly one `profile` per side).
    public static func initial(handle: String?, shareWithThisFriend: Bool) -> InstagramShareState {
        InstagramShareState(value: effective(handle: handle, shareWithThisFriend: shareWithThisFriend))
    }

    /// The handle if one is set AND sharing with this friend is on, else nil.
    public static func effective(handle: String?, shareWithThisFriend: Bool) -> String? {
        guard shareWithThisFriend, let handle, InstagramHandle.isValid(handle) else { return nil }
        return handle
    }

    /// Receiver: store (peerIg, peerIgRev) only when `rev` > the stored peerIgRev (initial -1).
    public static func acceptReceived(storedPeerIgRev: Int, rev: Int) -> Bool { rev > storedPeerIgRev }
}
