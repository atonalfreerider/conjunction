import Foundation

// What the app keeps and shows for Nearby (SPEC 5c): the Swift twin of the Android app's
// nearby/NearbyModels.kt and P2pStore.kt data classes. Everything here lives only on this phone; it
// is built from what nearby phones sent over Bluetooth (docs/P2P_PROTOCOL.md). Pure Foundation, so
// the iOS app's Nearby logic is unit-tested on Linux.

/// On-device screening result for something a stranger or friend sent (photo or chat text).
public enum Screening: String, Codable, CaseIterable, Sendable {
    /// Not checked yet (the on-device model runs only while the app is in the foreground).
    case pending
    /// Checked and looks fine.
    case safe
    /// Looks like it breaks the Community Guidelines: hidden until the person chooses to see it.
    case flagged
    /// Photos only: this phone could not check it: shown blurred until the person taps "Show photo".
    case unchecked

    public static func fromWire(_ v: String?) -> Screening { v.flatMap(Screening.init(rawValue:)) ?? .pending }
}

public enum ChatStatus: String, Codable, CaseIterable, Sendable {
    /// Waiting for the next time the two phones are near each other.
    case queued
    /// Written to the Bluetooth link; waiting for the delivery receipt.
    case sent
    case delivered
    case read
    /// Incoming messages.
    case received
}

public struct ChatLine: Codable, Hashable, Sendable, Identifiable {
    public var id: String
    public var friendId: String
    public var mine: Bool
    public var text: String
    /// Sender's clock, ms since the epoch.
    public var ts: Int64
    public var status: ChatStatus
    /// Incoming only.
    public var screening: Screening
    /// Incoming only: I have seen it (a read receipt is due / was sent).
    public var seen: Bool
    public var readReceiptSent: Bool

    public init(id: String, friendId: String, mine: Bool, text: String, ts: Int64, status: ChatStatus,
                screening: Screening = .safe, seen: Bool = false, readReceiptSent: Bool = false) {
        self.id = id
        self.friendId = friendId
        self.mine = mine
        self.text = text
        self.ts = ts
        self.status = status
        self.screening = screening
        self.seen = seen
        self.readReceiptSent = readReceiptSent
    }

    enum CodingKeys: String, CodingKey {
        case id, friendId = "friend", mine, text, ts, status, screening = "screen", seen, readReceiptSent = "rr"
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        friendId = try c.decode(String.self, forKey: .friendId)
        mine = try c.decode(Bool.self, forKey: .mine)
        text = try c.decode(String.self, forKey: .text)
        ts = try c.decodeIfPresent(Int64.self, forKey: .ts) ?? 0
        status = (try? c.decode(ChatStatus.self, forKey: .status)) ?? .queued
        screening = Screening.fromWire(try c.decodeIfPresent(String.self, forKey: .screening))
        seen = try c.decodeIfPresent(Bool.self, forKey: .seen) ?? false
        readReceiptSent = try c.decodeIfPresent(Bool.self, forKey: .readReceiptSent) ?? false
    }
}

/// A friend record (SPEC 5c "friend record (key, name, photo, card) is stored locally").
public struct StoredFriend: Codable, Hashable, Sendable, Identifiable {
    /// hex(SHA-256(identity key))[0..32): stable local id.
    public var id: String
    /// Base64 of the friend's 65-byte identity public key.
    public var identityKey: String
    /// Base64 of the 32-byte friend secret (docs/P2P_PROTOCOL.md 8.5).
    public var friendSecret: String
    public var safetyCode: String
    /// "dating" | "friends".
    public var kind: String
    public var firstName: String
    public var bio: String?
    public var sun: String?
    public var moon: String?
    public var rising: String?
    public var chineseAnimal: String?
    /// File name of the received photo inside the store's photo folder.
    public var photoFile: String?
    public var photoScreening: Screening
    public var since: Int64
    public var lastSeen: Int64
    /// Blocked friends are kept (so a recognised blocked friend gets `bye done`), with no chat.
    public var blocked: Bool

    public init(id: String, identityKey: String, friendSecret: String, safetyCode: String, kind: String, firstName: String,
                bio: String?, sun: String?, moon: String?, rising: String?, chineseAnimal: String?, photoFile: String?,
                photoScreening: Screening, since: Int64, lastSeen: Int64, blocked: Bool = false) {
        self.id = id
        self.identityKey = identityKey
        self.friendSecret = friendSecret
        self.safetyCode = safetyCode
        self.kind = kind
        self.firstName = firstName
        self.bio = bio
        self.sun = sun
        self.moon = moon
        self.rising = rising
        self.chineseAnimal = chineseAnimal
        self.photoFile = photoFile
        self.photoScreening = photoScreening
        self.since = since
        self.lastSeen = lastSeen
        self.blocked = blocked
    }

    enum CodingKeys: String, CodingKey {
        case id, identityKey = "ik", friendSecret = "fs", safetyCode = "safety", kind, firstName = "name", bio, sun, moon,
             rising, chineseAnimal = "animal", photoFile = "photo", photoScreening = "photoScreen", since, lastSeen, blocked
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        identityKey = try c.decode(String.self, forKey: .identityKey)
        friendSecret = try c.decode(String.self, forKey: .friendSecret)
        safetyCode = try c.decodeIfPresent(String.self, forKey: .safetyCode) ?? ""
        kind = try c.decodeIfPresent(String.self, forKey: .kind) ?? "friends"
        firstName = try c.decodeIfPresent(String.self, forKey: .firstName) ?? ""
        bio = try c.decodeIfPresent(String.self, forKey: .bio)
        sun = try c.decodeIfPresent(String.self, forKey: .sun)
        moon = try c.decodeIfPresent(String.self, forKey: .moon)
        rising = try c.decodeIfPresent(String.self, forKey: .rising)
        chineseAnimal = try c.decodeIfPresent(String.self, forKey: .chineseAnimal)
        photoFile = try c.decodeIfPresent(String.self, forKey: .photoFile)
        photoScreening = Screening.fromWire(try c.decodeIfPresent(String.self, forKey: .photoScreening))
        since = try c.decodeIfPresent(Int64.self, forKey: .since) ?? 0
        lastSeen = try c.decodeIfPresent(Int64.self, forKey: .lastSeen) ?? 0
        blocked = try c.decodeIfPresent(Bool.self, forKey: .blocked) ?? false
    }
}

/// A match card in the 24-h Matches inbox, keyed by the peer's profile fingerprint (protocol 7.4).
public struct StoredEncounter: Codable, Hashable, Sendable, Identifiable {
    public var fingerprint: String
    public var kind: String
    public var total: Int
    public var western: Int
    public var chinese: Int
    public var blood: Int?
    public var highlights: [String]
    public var birthdayTwin: Bool
    public var exactTwin: Bool
    public var age: Int
    /// Lower-case sign keys ("leo").
    public var sun: String
    public var moon: String
    public var rising: String?
    public var chineseAnimal: String
    public var relationship: String
    public var lookingFor: String
    public var firstName: String?
    public var bio: String?
    public var photoFile: String?
    public var photoScreening: Screening
    /// My answer: "right" | "left" | nil. A swipe made out of range is sent at the next encounter.
    public var mySwipe: String?
    public var peerSwipe: String?
    /// Set once both said yes and keys were confirmed.
    public var friendId: String?
    public var createdAt: Int64
    public var expiresAt: Int64
    public var notifiedAt: Int64

    public var id: String { fingerprint }

    public init(fingerprint: String, kind: String, total: Int, western: Int, chinese: Int, blood: Int?, highlights: [String],
                birthdayTwin: Bool, exactTwin: Bool, age: Int, sun: String, moon: String, rising: String?,
                chineseAnimal: String, relationship: String, lookingFor: String, firstName: String? = nil,
                bio: String? = nil, photoFile: String? = nil, photoScreening: Screening = .pending, mySwipe: String? = nil,
                peerSwipe: String? = nil, friendId: String? = nil, createdAt: Int64, expiresAt: Int64, notifiedAt: Int64 = 0) {
        self.fingerprint = fingerprint
        self.kind = kind
        self.total = total
        self.western = western
        self.chinese = chinese
        self.blood = blood
        self.highlights = highlights
        self.birthdayTwin = birthdayTwin
        self.exactTwin = exactTwin
        self.age = age
        self.sun = sun
        self.moon = moon
        self.rising = rising
        self.chineseAnimal = chineseAnimal
        self.relationship = relationship
        self.lookingFor = lookingFor
        self.firstName = firstName
        self.bio = bio
        self.photoFile = photoFile
        self.photoScreening = photoScreening
        self.mySwipe = mySwipe
        self.peerSwipe = peerSwipe
        self.friendId = friendId
        self.createdAt = createdAt
        self.expiresAt = expiresAt
        self.notifiedAt = notifiedAt
    }

    enum CodingKeys: String, CodingKey {
        case fingerprint = "fp", kind, total, western, chinese, blood, highlights, birthdayTwin = "twin", exactTwin, age, sun,
             moon, rising, chineseAnimal = "animal", relationship, lookingFor, firstName = "name", bio, photoFile = "photo",
             photoScreening = "photoScreen", mySwipe, peerSwipe, friendId = "friend", createdAt = "created",
             expiresAt = "expires", notifiedAt = "notified"
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        fingerprint = try c.decode(String.self, forKey: .fingerprint)
        kind = try c.decodeIfPresent(String.self, forKey: .kind) ?? "friends"
        total = try c.decodeIfPresent(Int.self, forKey: .total) ?? 0
        western = try c.decodeIfPresent(Int.self, forKey: .western) ?? 0
        chinese = try c.decodeIfPresent(Int.self, forKey: .chinese) ?? 0
        blood = try c.decodeIfPresent(Int.self, forKey: .blood)
        highlights = try c.decodeIfPresent([String].self, forKey: .highlights) ?? []
        birthdayTwin = try c.decodeIfPresent(Bool.self, forKey: .birthdayTwin) ?? false
        exactTwin = try c.decodeIfPresent(Bool.self, forKey: .exactTwin) ?? false
        age = try c.decodeIfPresent(Int.self, forKey: .age) ?? 0
        sun = try c.decodeIfPresent(String.self, forKey: .sun) ?? ""
        moon = try c.decodeIfPresent(String.self, forKey: .moon) ?? ""
        rising = try c.decodeIfPresent(String.self, forKey: .rising)
        chineseAnimal = try c.decodeIfPresent(String.self, forKey: .chineseAnimal) ?? ""
        relationship = try c.decodeIfPresent(String.self, forKey: .relationship) ?? "single"
        lookingFor = try c.decodeIfPresent(String.self, forKey: .lookingFor) ?? "friends"
        firstName = try c.decodeIfPresent(String.self, forKey: .firstName)
        bio = try c.decodeIfPresent(String.self, forKey: .bio)
        photoFile = try c.decodeIfPresent(String.self, forKey: .photoFile)
        photoScreening = Screening.fromWire(try c.decodeIfPresent(String.self, forKey: .photoScreening))
        mySwipe = try c.decodeIfPresent(String.self, forKey: .mySwipe)
        peerSwipe = try c.decodeIfPresent(String.self, forKey: .peerSwipe)
        friendId = try c.decodeIfPresent(String.self, forKey: .friendId)
        createdAt = try c.decodeIfPresent(Int64.self, forKey: .createdAt) ?? 0
        expiresAt = try c.decodeIfPresent(Int64.self, forKey: .expiresAt) ?? 0
        notifiedAt = try c.decodeIfPresent(Int64.self, forKey: .notifiedAt) ?? 0
    }

    public var isTwin: Bool { birthdayTwin || exactTwin }
}

/// A stranger hidden by fingerprint: left swipe (30 days), snooze (7 days), unmatch (30 days) or block (forever).
public struct HiddenPeer: Codable, Hashable, Sendable {
    public var fingerprint: String
    public var until: Int64
    public var reason: String

    public init(fingerprint: String, until: Int64, reason: String) {
        self.fingerprint = fingerprint
        self.until = until
        self.reason = reason
    }

    enum CodingKeys: String, CodingKey { case fingerprint = "fp", until, reason }
}

/// Everything Nearby stores: friends, chats, the Matches inbox and the hide/block list.
public struct P2PState: Codable, Hashable, Sendable {
    public var friends: [StoredFriend]
    public var messages: [ChatLine]
    public var encounters: [StoredEncounter]
    public var hidden: [HiddenPeer]

    public init(friends: [StoredFriend] = [], messages: [ChatLine] = [], encounters: [StoredEncounter] = [],
                hidden: [HiddenPeer] = []) {
        self.friends = friends
        self.messages = messages
        self.encounters = encounters
        self.hidden = hidden
    }

    enum CodingKeys: String, CodingKey { case v, friends, messages, encounters, hidden }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        friends = try c.decodeIfPresent([StoredFriend].self, forKey: .friends) ?? []
        messages = try c.decodeIfPresent([ChatLine].self, forKey: .messages) ?? []
        encounters = try c.decodeIfPresent([StoredEncounter].self, forKey: .encounters) ?? []
        hidden = try c.decodeIfPresent([HiddenPeer].self, forKey: .hidden) ?? []
    }

    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(1, forKey: .v)
        try c.encode(friends, forKey: .friends)
        try c.encode(messages, forKey: .messages)
        try c.encode(encounters, forKey: .encounters)
        try c.encode(hidden, forKey: .hidden)
    }

    public func friend(_ id: String) -> StoredFriend? { friends.first { $0.id == id } }
    public func encounter(_ fp: String) -> StoredEncounter? { encounters.first { $0.fingerprint == fp } }
    public func isHidden(_ fp: String, now: Int64) -> Bool { hidden.contains { $0.fingerprint == fp && $0.until > now } }
    public func messagesWith(_ friendId: String) -> [ChatLine] { messages.filter { $0.friendId == friendId } }

    // ------------------------------------------------------------------ what the Nearby screen lists

    /// Unanswered match cards from the last 24 h, newest first.
    public func inbox(now: Int64) -> [StoredEncounter] {
        encounters.filter { $0.friendId == nil && $0.expiresAt > now && $0.mySwipe == nil && !isHidden($0.fingerprint, now: now) }
            .sorted { $0.createdAt > $1.createdAt }
    }

    /// Cards I said yes to that are waiting for the other person.
    public func waiting(now: Int64) -> [StoredEncounter] {
        encounters.filter { $0.friendId == nil && $0.expiresAt > now && $0.mySwipe == SwipeDirection.right.wire }
    }

    /// Non-blocked friends, most recent activity first.
    public func friendSummaries(inRange: Set<String>) -> [FriendSummary] {
        friends.filter { !$0.blocked }.map { summarize($0, inRange: inRange) }
            .sorted { max($0.lastSeen, $0.lastMessageTs) > max($1.lastSeen, $1.lastMessageTs) }
    }

    public func friendSummary(_ id: String, inRange: Set<String>) -> FriendSummary? {
        friend(id).flatMap { $0.blocked ? nil : summarize($0, inRange: inRange) }
    }

    private func summarize(_ f: StoredFriend, inRange: Set<String>) -> FriendSummary {
        let msgs = messagesWith(f.id)
        return FriendSummary(
            friend: f, inRange: inRange.contains(f.id),
            unread: msgs.filter { !$0.mine && !$0.seen }.count,
            queued: msgs.filter { $0.mine && ($0.status == .queued || $0.status == .sent) }.count,
            lastMessage: msgs.last { $0.mine || $0.screening == .safe }?.text,
            lastMessageTs: msgs.last?.ts ?? 0)
    }
}

/// A friend (mutual match) as the UI shows it.
public struct FriendSummary: Hashable, Sendable, Identifiable {
    public let friend: StoredFriend
    /// A secure link to this friend is open right now (messages go straight through).
    public let inRange: Bool
    public let unread: Int
    public let queued: Int
    public let lastMessage: String?
    public let lastMessageTs: Int64

    public var id: String { friend.id }
    public var firstName: String { friend.firstName }
    public var lastSeen: Int64 { friend.lastSeen }
}

/// Event kinds for local notifications and the in-app full-screen popups.
public enum NearbyEventKind: String, Codable, CaseIterable, Sendable {
    case match
    case birthdayTwin = "birthday_twin"
    case exactTwin = "exact_twin"
    /// Both swiped right ("It's a Conjunction!"): the chat opens.
    case conjunction
    case message

    public var isTwin: Bool { self == .birthdayTwin || self == .exactTwin }
}

/// Something to show: a full-screen popup while the app is open, or the deep link of a tapped local
/// notification. [encounterId] = profile fingerprint of a match; [friendId] = a friend's id.
public struct NearbyEvent: Hashable, Sendable, Identifiable {
    public var kind: NearbyEventKind
    public var encounterId: String?
    public var friendId: String?
    public var matchKind: String?
    public var total: Int?

    public init(kind: NearbyEventKind, encounterId: String?, friendId: String?, matchKind: String? = nil, total: Int? = nil) {
        self.kind = kind
        self.encounterId = encounterId
        self.friendId = friendId
        self.matchKind = matchKind
        self.total = total
    }

    public var id: String { "\(kind.rawValue):\(encounterId ?? "-"):\(friendId ?? "-")" }

    /// For a notification's userInfo (string values only, so it survives any plist round trip).
    public var userInfo: [String: String] {
        var d = ["conjunction.kind": kind.rawValue]
        d["conjunction.encounter"] = encounterId
        d["conjunction.friend"] = friendId
        d["conjunction.matchKind"] = matchKind
        d["conjunction.total"] = total.map(String.init)
        return d
    }

    public static func fromUserInfo(_ info: [AnyHashable: Any]) -> NearbyEvent? {
        func s(_ k: String) -> String? { info[k] as? String }
        guard let kind = s("conjunction.kind").flatMap(NearbyEventKind.init(rawValue:)) else { return nil }
        return NearbyEvent(kind: kind, encounterId: s("conjunction.encounter"), friendId: s("conjunction.friend"),
                           matchKind: s("conjunction.matchKind"), total: s("conjunction.total").flatMap { Int($0) })
    }

    /// Notification identifier: one per match / friend, so repeats replace rather than pile up.
    public var notificationId: String {
        switch kind {
        case .message: return "msg:\(friendId ?? "")"
        case .conjunction: return "friend:\(friendId ?? "")"
        default: return "match:\(encounterId ?? "")"
        }
    }
}

/// Report reasons (legal/IN_APP_CONSENT_COPY.md section 8). [code] goes into the email subject.
public enum ReportReason: String, CaseIterable, Sendable {
    case harassment, sexual, scam, fake = "fake_profile", hate, minor = "child_safety", other

    public var code: String { rawValue }

    public var label: String {
        switch self {
        case .harassment: return L10n.reportReasonHarassment
        case .sexual: return L10n.reportReasonSexual
        case .scam: return L10n.reportReasonScam
        case .fake: return L10n.reportReasonFake
        case .hate: return L10n.reportReasonHate
        case .minor: return L10n.reportReasonMinor
        case .other: return L10n.reportReasonOther
        }
    }
}

/// Display helpers for the lower-case keys stored on cards.
public enum CardText {
    public static func sign(_ key: String?) -> String {
        guard let key else { return "" }
        return ZodiacSign.fromKey(key.lowercased())?.displayName ?? key
    }

    public static func animal(_ key: String?) -> String {
        guard let key else { return "" }
        return ChineseAnimal.fromKey(key.lowercased())?.displayName ?? key
    }

    public static func relationship(_ wire: String?) -> String? {
        guard let wire else { return nil }
        return wire == "single" ? "Single" : "In a relationship"
    }

    public static func lookingFor(_ wire: String?) -> String? {
        switch wire {
        case nil: return nil
        case "dating": return "Looking for dating"
        case "friends": return "Looking for friends"
        default: return "Dating & friends"
        }
    }

    /// "☉ Leo · ☽ Aries · AC Libra" style line (nil parts left out).
    public static func signsLine(sun: String?, moon: String?, rising: String?, separator: String = "  ") -> String {
        [sun.map { "☉ \(sign($0))" }, moon.map { "☽ \(sign($0))" }, rising.map { "AC \(sign($0))" }]
            .compactMap { $0 }.joined(separator: separator)
    }
}
