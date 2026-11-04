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
    /// SPEC 5d.4: the friend's `hello.compat` (nil for friends made before UX 2.0 until the backfill).
    public var peerCompat: WireCompat?
    /// Compatibility numbers (copied from the match card; recomputed when `peerCompat` changes).
    public var score: StoredScore?
    public var blurb: StoredBlurb?
    /// SPEC 5d.6: the friend's Instagram handle (validated) and the rev it came with (-1 = none yet).
    public var peerIg: String?
    public var peerIgRev: Int
    /// Per-friend "Share my Instagram with ..." switch; nil = the Settings default.
    public var igShareOn: Bool?
    /// Sender state of our `profile` toward this friend; nil = not started (rev 0 on first use).
    public var igState: InstagramShareState?

    public init(id: String, identityKey: String, friendSecret: String, safetyCode: String, kind: String, firstName: String,
                bio: String?, sun: String?, moon: String?, rising: String?, chineseAnimal: String?, photoFile: String?,
                photoScreening: Screening, since: Int64, lastSeen: Int64, blocked: Bool = false, peerCompat: WireCompat? = nil,
                score: StoredScore? = nil, blurb: StoredBlurb? = nil, peerIg: String? = nil, peerIgRev: Int = -1,
                igShareOn: Bool? = nil, igState: InstagramShareState? = nil) {
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
        self.peerCompat = peerCompat
        self.score = score
        self.blurb = blurb
        self.peerIg = peerIg
        self.peerIgRev = peerIgRev
        self.igShareOn = igShareOn
        self.igState = igState
    }

    enum CodingKeys: String, CodingKey {
        case id, identityKey = "ik", friendSecret = "fs", safetyCode = "safety", kind, firstName = "name", bio, sun, moon,
             rising, chineseAnimal = "animal", photoFile = "photo", photoScreening = "photoScreen", since, lastSeen, blocked,
             peerCompat, score, blurb, peerIg, peerIgRev, igShareOn = "igShare", igState
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
        // UX 2.0 fields: a part that does not parse is dropped, never the friend.
        peerCompat = (try? c.decodeIfPresent(WireCompat.self, forKey: .peerCompat)) ?? nil
        score = (try? c.decodeIfPresent(StoredScore.self, forKey: .score)) ?? nil
        blurb = (try? c.decodeIfPresent(StoredBlurb.self, forKey: .blurb)) ?? nil
        peerIg = InstagramHandle.received((try? c.decodeIfPresent(String.self, forKey: .peerIg)) ?? nil)
        peerIgRev = (try? c.decodeIfPresent(Int.self, forKey: .peerIgRev)) ?? -1
        igShareOn = (try? c.decodeIfPresent(Bool.self, forKey: .igShareOn)) ?? nil
        igState = (try? c.decodeIfPresent(InstagramShareState.self, forKey: .igState)) ?? nil
    }

    /// Unmatch / block / delete removes what this phone kept of them (SPEC 5d.4, 5d.6).
    public mutating func clearPeerData() {
        peerCompat = nil
        blurb = nil
        peerIg = nil
        peerIgRev = -1
        igState = nil
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
    /// SPEC 5d.4: the peer's `hello.compat`, kept with the match (copied into the friend).
    public var peerCompat: WireCompat?
    /// `ChineseRelation` / `StemRelation` names; nil for cards made before UX 2.0.
    public var chineseRelation: String?
    public var stemRelation: String?
    public var blurb: StoredBlurb?

    public var id: String { fingerprint }

    public init(fingerprint: String, kind: String, total: Int, western: Int, chinese: Int, blood: Int?, highlights: [String],
                birthdayTwin: Bool, exactTwin: Bool, age: Int, sun: String, moon: String, rising: String?,
                chineseAnimal: String, relationship: String, lookingFor: String, firstName: String? = nil,
                bio: String? = nil, photoFile: String? = nil, photoScreening: Screening = .pending, mySwipe: String? = nil,
                peerSwipe: String? = nil, friendId: String? = nil, createdAt: Int64, expiresAt: Int64, notifiedAt: Int64 = 0,
                peerCompat: WireCompat? = nil, chineseRelation: String? = nil, stemRelation: String? = nil,
                blurb: StoredBlurb? = nil) {
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
        self.peerCompat = peerCompat
        self.chineseRelation = chineseRelation
        self.stemRelation = stemRelation
        self.blurb = blurb
    }

    enum CodingKeys: String, CodingKey {
        case fingerprint = "fp", kind, total, western, chinese, blood, highlights, birthdayTwin = "twin", exactTwin, age, sun,
             moon, rising, chineseAnimal = "animal", relationship, lookingFor, firstName = "name", bio, photoFile = "photo",
             photoScreening = "photoScreen", mySwipe, peerSwipe, friendId = "friend", createdAt = "created",
             expiresAt = "expires", notifiedAt = "notified", peerCompat, chineseRelation = "chineseRel",
             stemRelation = "stemRel", blurb
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
        peerCompat = (try? c.decodeIfPresent(WireCompat.self, forKey: .peerCompat)) ?? nil
        chineseRelation = (try? c.decodeIfPresent(String.self, forKey: .chineseRelation)) ?? nil
        stemRelation = (try? c.decodeIfPresent(String.self, forKey: .stemRelation)) ?? nil
        blurb = (try? c.decodeIfPresent(StoredBlurb.self, forKey: .blurb)) ?? nil
    }

    public var isTwin: Bool { birthdayTwin || exactTwin }

    public func storedScore() -> StoredScore {
        StoredScore(total: total, western: western, chinese: chinese, blood: blood, highlights: highlights,
                    birthdayTwin: birthdayTwin, exactTwin: exactTwin, chineseRelation: chineseRelation, stemRelation: stemRelation)
    }
}

/// Compatibility numbers kept with a match or friend (SPEC 5d.4 "falls back to the stored numbers").
public struct StoredScore: Codable, Hashable, Sendable {
    public var total: Int
    public var western: Int
    public var chinese: Int
    public var blood: Int?
    public var highlights: [String]
    public var birthdayTwin: Bool
    public var exactTwin: Bool
    /// `ChineseRelation` / `StemRelation` names, nil for records made before UX 2.0.
    public var chineseRelation: String?
    public var stemRelation: String?

    public init(total: Int, western: Int, chinese: Int, blood: Int?, highlights: [String], birthdayTwin: Bool, exactTwin: Bool,
                chineseRelation: String? = nil, stemRelation: String? = nil) {
        self.total = total
        self.western = western
        self.chinese = chinese
        self.blood = blood
        self.highlights = highlights
        self.birthdayTwin = birthdayTwin
        self.exactTwin = exactTwin
        self.chineseRelation = chineseRelation
        self.stemRelation = stemRelation
    }

    public init(_ r: CompatResult) {
        self.init(total: r.total, western: r.western, chinese: r.chinese, blood: r.blood, highlights: r.highlights,
                  birthdayTwin: r.isBirthdayTwin, exactTwin: r.isExactTwin, chineseRelation: r.chineseRelation.name,
                  stemRelation: r.stemRelation.name)
    }

    enum CodingKeys: String, CodingKey {
        case total, western, chinese, blood, highlights, birthdayTwin = "twin", exactTwin, chineseRelation = "chineseRel",
             stemRelation = "stemRel"
    }
}

/// The Compatibility view's reading, generated once (SPEC 5d.4 item 6). `source` = "model" | "fallback".
public struct StoredBlurb: Codable, Hashable, Sendable {
    public var text: String
    public var source: String
    public var forHash: String

    public init(text: String, source: String, forHash: String) {
        self.text = text
        self.source = source
        self.forHash = forHash
    }

    enum CodingKeys: String, CodingKey { case text, source, forHash = "for" }
}

/// The store form of a peer's `hello.compat` (stem and blood type by enum name).
extension WireCompat: Codable {
    enum CodingKeys: String, CodingKey { case sun, moon, venus, mars, asc, animal, stem, blood, birthDate, birthHour }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        let animalKey = try c.decode(String.self, forKey: .animal)
        let stemName = try c.decode(String.self, forKey: .stem)
        let bloodName = try c.decodeIfPresent(String.self, forKey: .blood)
        guard let animal = ChineseAnimal.fromKey(animalKey),
              let stem = HeavenlyStem.allCases.first(where: { $0.name.lowercased() == stemName.lowercased() }),
              let date = LocalDate.parse(try c.decode(String.self, forKey: .birthDate)) else {
            throw DecodingError.dataCorruptedError(forKey: .animal, in: c, debugDescription: "bad compat")
        }
        var blood: BloodType?
        if let bloodName {
            guard let b = BloodType(rawValue: bloodName.uppercased()) else {
                throw DecodingError.dataCorruptedError(forKey: .blood, in: c, debugDescription: "bad blood type")
            }
            blood = b
        }
        do {
            try self.init(sun: c.decode(Int.self, forKey: .sun), moon: c.decode(Int.self, forKey: .moon),
                          venus: c.decode(Int.self, forKey: .venus), mars: c.decode(Int.self, forKey: .mars),
                          asc: c.decodeIfPresent(Int.self, forKey: .asc), animal: animal, stem: stem, blood: blood,
                          birthDate: date, birthHour: c.decodeIfPresent(Int.self, forKey: .birthHour))
        } catch let e as DecodingError {
            throw e
        } catch {
            throw DecodingError.dataCorruptedError(forKey: .sun, in: c, debugDescription: "\(error)")
        }
    }

    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(sun, forKey: .sun)
        try c.encode(moon, forKey: .moon)
        try c.encode(venus, forKey: .venus)
        try c.encode(mars, forKey: .mars)
        try c.encodeIfPresent(asc, forKey: .asc)
        try c.encode(animal.key, forKey: .animal)
        try c.encode(stem.name, forKey: .stem)
        try c.encodeIfPresent(blood?.name, forKey: .blood)
        try c.encode(birthDate.description, forKey: .birthDate)
        try c.encodeIfPresent(birthHour, forKey: .birthHour)
    }
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

    // ------------------------------------------------------------------ safety changes

    /// Block a friend: kept as a blocked record (recognised and refused), their chat, cards and
    /// everything this phone kept of them (chart, reading, Instagram) deleted.
    public mutating func blockFriend(_ friendId: String) {
        let fps = encounters.filter { $0.friendId == friendId }.map(\.fingerprint)
        for i in friends.indices where friends[i].id == friendId {
            friends[i].blocked = true
            friends[i].clearPeerData()
        }
        messages.removeAll { $0.friendId == friendId }
        encounters.removeAll { $0.friendId == friendId }
        hidden += fps.map { HiddenPeer(fingerprint: $0, until: Int64.max, reason: "block") }
    }

    /// Unmatch: the friendship, chat and kept data deleted here; their card stays hidden until [hiddenUntil].
    public mutating func unmatch(_ friendId: String, hiddenUntil: Int64) {
        let fps = encounters.filter { $0.friendId == friendId }.map(\.fingerprint)
        friends.removeAll { $0.id == friendId }
        messages.removeAll { $0.friendId == friendId }
        encounters.removeAll { $0.friendId == friendId }
        hidden += fps.map { HiddenPeer(fingerprint: $0, until: hiddenUntil, reason: "unmatch") }
    }

    /// Hide a stranger's card (block from a card, snooze): the card and its kept chart go.
    public mutating func hideStranger(_ fingerprint: String, until: Int64, reason: String) {
        encounters.removeAll { $0.fingerprint == fingerprint }
        hidden.removeAll { $0.fingerprint == fingerprint }
        hidden.append(HiddenPeer(fingerprint: fingerprint, until: until, reason: reason))
    }

    /// Settings › Nearby › Snoozed people: the snoozed cards still hidden.
    public func snoozed(now: Int64) -> [HiddenPeer] { hidden.filter { $0.reason == "snooze" && $0.until > now } }

    /// "Share my Instagram with <name>" for one friend.
    public mutating func setInstagramShare(_ friendId: String, on: Bool) {
        for i in friends.indices where friends[i].id == friendId { friends[i].igShareOn = on }
    }

    /// Stores the Compatibility view's reading on the record it is about (encounter or friend).
    public mutating func saveBlurb(_ key: String, _ blurb: StoredBlurb) {
        for i in friends.indices where friends[i].id == key { friends[i].blurb = blurb }
        for i in encounters.indices where encounters[i].fingerprint == key { encounters[i].blurb = blurb }
    }

    // ------------------------------------------------------------------ what the Nearby screen lists

    /// Unanswered match cards from the last 24 h, newest first.
    public func inbox(now: Int64) -> [StoredEncounter] {
        encounters.filter { $0.friendId == nil && $0.expiresAt > now && $0.mySwipe == nil && !isHidden($0.fingerprint, now: now) }
            .sorted { $0.createdAt > $1.createdAt }
    }

    /// Cards I said yes to that are waiting for the other person.
    public func waiting(now: Int64) -> [StoredEncounter] {
        encounters.filter { $0.friendId == nil && $0.expiresAt > now && $0.mySwipe == SwipeDirection.right.wire && !isHidden($0.fingerprint, now: now) }
            .sorted { $0.createdAt > $1.createdAt }
    }

    /// The Nearby badge: unanswered new matches.
    public func pendingCount(now: Int64) -> Int { inbox(now: now).count }

    /// Who the Compatibility view is about: a friend id or an encounter fingerprint (pending or
    /// waiting, <= 24 h). Nil once the record is gone (expired, unmatched, blocked).
    public func compatSubject(_ key: String, now: Int64) -> CompatSubject? {
        if let f = friend(key), !f.blocked {
            let enc = encounters.first { $0.friendId == f.id }
            let score = f.score ?? enc?.storedScore() ?? StoredScore(total: 0, western: 0, chinese: 0, blood: nil, highlights: [],
                                                                    birthdayTwin: false, exactTwin: false)
            return CompatSubject(key: key, friendId: f.id, fingerprint: enc?.fingerprint, name: f.firstName, kind: f.kind,
                                 photoFile: f.photoFile, photoScreening: f.photoScreening, sun: f.sun, animal: f.chineseAnimal,
                                 peerCompat: f.peerCompat ?? enc?.peerCompat, score: score, blurb: f.blurb ?? enc?.blurb,
                                 peerIg: f.peerIg, hasScore: f.score != nil || enc != nil || f.peerCompat != nil)
        }
        if let e = encounter(key), e.expiresAt > now, !isHidden(key, now: now) {
            if let fid = e.friendId { return compatSubject(fid, now: now) }
            return CompatSubject(key: key, friendId: nil, fingerprint: key, name: e.firstName ?? "Someone nearby", kind: e.kind,
                                 photoFile: e.photoFile, photoScreening: e.photoScreening, sun: e.sun, animal: e.chineseAnimal,
                                 peerCompat: e.peerCompat, score: e.storedScore(), blurb: e.blurb, peerIg: nil, hasScore: true)
        }
        return nil
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
            lastMessageTs: msgs.last?.ts ?? 0,
            total: f.score?.total ?? encounters.first { $0.friendId == f.id }?.total)
    }
}

/// Who the Compatibility view is about (SPEC 5d.4): a pending or waiting card (by fingerprint) or
/// a friend (by id), with what this phone kept of them. Their birth date and hour are inside
/// `peerCompat` only for the computation; the UI never shows them.
public struct CompatSubject: Hashable, Sendable {
    /// Encounter fingerprint or friend id.
    public let key: String
    public let friendId: String?
    public let fingerprint: String?
    public let name: String
    public let kind: String
    public let photoFile: String?
    public let photoScreening: Screening
    public let sun: String?
    public let animal: String?
    public let peerCompat: WireCompat?
    public let score: StoredScore
    public let blurb: StoredBlurb?
    public let peerIg: String?
    /// False for a friend made before UX 2.0 without any stored numbers.
    public let hasScore: Bool
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
    /// Compatibility total (stored with the friend, or from their old match card); nil when unknown.
    public let total: Int?

    public init(friend: StoredFriend, inRange: Bool, unread: Int, queued: Int, lastMessage: String?, lastMessageTs: Int64,
                total: Int? = nil) {
        self.friend = friend
        self.inRange = inRange
        self.unread = unread
        self.queued = queued
        self.lastMessage = lastMessage
        self.lastMessageTs = lastMessageTs
        self.total = total
    }

    public var id: String { friend.id }
    /// Their Instagram handle when they shared one (SPEC 5d.6).
    public var peerIg: String? { friend.peerIg }
    /// "Share my Instagram with <name>" for this friend, given the Settings default.
    public func igShareOn(default value: Bool) -> Bool { friend.igShareOn ?? value }
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
