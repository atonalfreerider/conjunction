import Foundation

// Dating & friends match filter (SPEC item 5b), ported from android/core .../match/MatchFilter.kt.
//
// The backend decides, before any encounter is written or any notification is sent, whether two
// nearby users qualify for a match at all and, if so, which kind. The phones run the same rule to
// explain a match card ("Dating" / "Friends") and to preview, in Profile settings, who they can be
// matched with. Every platform must return exactly the answers in testdata/golden_match_filter.json;
// docs/MATCH_FILTER.md is the contract.

/// A wire value (lower-case string on `users/{uid}`) that is not one of the allowed options.
public struct WireValueError: Error, Equatable, CustomStringConvertible, Sendable {
    public let field: String
    public let value: String
    public var description: String { "Unknown \(field) '\(value)'" }
}

/// Shared `fromWire` for the profile enums: exact, case-sensitive match on the raw value.
public protocol WireEnum: RawRepresentable, CaseIterable, Sendable where RawValue == String {
    static var wireField: String { get }
}

public extension WireEnum {
    /// The lower-case wire value.
    var wire: String { rawValue }

    /// Parses a wire value; unknown values are an error, never silently mapped.
    static func fromWire(_ value: String) throws -> Self {
        guard let match = allCases.first(where: { $0.rawValue == value }) else {
            throw WireValueError(field: wireField, value: value)
        }
        return match
    }
}

/// "I am". Only the two options the product offers.
public enum Gender: String, WireEnum {
    case male, female
    public static let wireField = "gender"
}

/// "Seeking".
public enum Seeking: String, WireEnum {
    case male, female, both
    public static let wireField = "seeking"

    /// True when someone of [gender] is in this preference.
    public func includes(_ gender: Gender) -> Bool {
        switch self {
        case .male: return gender == .male
        case .female: return gender == .female
        case .both: return true
        }
    }
}

/// "Relationship status", shown on the match card.
public enum RelationshipStatus: String, WireEnum {
    case single
    case inARelationship = "in_a_relationship"
    public static let wireField = "relationship status"

    public var displayName: String {
        switch self {
        case .single: return "Single"
        case .inARelationship: return "In a relationship"
        }
    }
}

/// "Looking for".
public enum LookingFor: String, WireEnum {
    case dating, friends, both
    public static let wireField = "looking-for"

    public var displayName: String {
        switch self {
        case .dating: return "Dating"
        case .friends: return "Friends"
        case .both: return "Dating & friends"
        }
    }

    public var includesDating: Bool { self == .dating || self == .both }
    public var includesFriends: Bool { self == .friends || self == .both }
}

/// The kind carried on an encounter (`encounters/{pairId}.kind`) and shown on the card.
public enum MatchKind: String, WireEnum {
    case dating, friends
    public static let wireField = "match kind"

    public var displayName: String {
        switch self {
        case .dating: return "Dating"
        case .friends: return "Friends"
        }
    }
}

/// The intent fields of one user, as stored on `users/{uid}`.
public struct MatchProfile: Hashable, Sendable {
    public var gender: Gender
    public var seeking: Seeking
    public var relationshipStatus: RelationshipStatus
    public var lookingFor: LookingFor

    public init(gender: Gender, seeking: Seeking, relationshipStatus: RelationshipStatus, lookingFor: LookingFor) {
        self.gender = gender
        self.seeking = seeking
        self.relationshipStatus = relationshipStatus
        self.lookingFor = lookingFor
    }
}

public enum MatchFilter {
    /// The match kind two users qualify for, or nil when they qualify for none (then the backend
    /// writes no encounter and sends no notification — birthday twins included).
    ///
    /// Rule, evaluated in this order:
    /// 1. **Dating** when both include dating, each one's gender is in the other's `seeking`, and
    ///    neither is `in_a_relationship`.
    /// 2. otherwise **Friends** when both include friends.
    /// 3. otherwise nil.
    ///
    /// Symmetric: `kind(a, b) == kind(b, a)` for every pair.
    public static func kind(_ a: MatchProfile, _ b: MatchProfile) -> MatchKind? {
        if qualifiesForDating(a, b) { return .dating }
        if a.lookingFor.includesFriends && b.lookingFor.includesFriends { return .friends }
        return nil
    }

    /// Rule 1 on its own, for UI that explains why a pair is (not) a dating match.
    public static func qualifiesForDating(_ a: MatchProfile, _ b: MatchProfile) -> Bool {
        a.lookingFor.includesDating && b.lookingFor.includesDating &&
            a.seeking.includes(b.gender) && b.seeking.includes(a.gender) &&
            a.relationshipStatus == .single && b.relationshipStatus == .single
    }

    /// Full eligibility check the backend runs: both users must be adults on [today] (UTC on the
    /// server) and qualify for a kind. Returns nil if either is under 18 or born after [today].
    public static func kind(_ a: MatchProfile, aBirthDate: LocalDate, _ b: MatchProfile, bBirthDate: LocalDate,
                            today: LocalDate) -> MatchKind? {
        guard Age.isAdult(aBirthDate, today: today), Age.isAdult(bBirthDate, today: today) else { return nil }
        return kind(a, b)
    }

    /// Default "Looking for" pre-selected when the user picks a relationship status: users in a
    /// relationship default to friends (SPEC 5b; they may change it), singles to both.
    public static func defaultLookingFor(_ status: RelationshipStatus) -> LookingFor {
        switch status {
        case .single: return .both
        case .inARelationship: return .friends
        }
    }
}

/// A birth date after the reference date.
public struct FutureBirthDateError: Error, Equatable, CustomStringConvertible, Sendable {
    public let birthDate: LocalDate
    public let today: LocalDate
    public var description: String { "Birth date \(birthDate) is after \(today)" }
}

/// Age arithmetic shared by the 18+ gate and the age shown on match cards.
public enum Age {
    public static let minimumAge = 18

    /// Completed years between [birthDate] and [today] (java.time `Period` semantics). Someone born
    /// on 29 February turns a year older on 1 March in common years. Throws if [birthDate] is after
    /// [today].
    public static func yearsOn(_ birthDate: LocalDate, today: LocalDate) throws -> Int {
        guard birthDate <= today else { throw FutureBirthDateError(birthDate: birthDate, today: today) }
        var years = today.year - birthDate.year
        if (today.month, today.day) < (birthDate.month, birthDate.day) { years -= 1 }
        return years
    }

    /// True when the person is at least [minimumAge] on [today]. A future birth date is not adult.
    public static func isAdult(_ birthDate: LocalDate, today: LocalDate) -> Bool {
        guard let years = try? yearsOn(birthDate, today: today) else { return false }
        return years >= minimumAge
    }

    /// The latest birth date that is still 18+ on [today] (the date picker's maximum); 29 Feb
    /// clamps to 28 Feb in a common year, as java.time's minusYears does.
    public static func latestAdultBirthDate(_ today: LocalDate) -> LocalDate {
        today.plusYears(-minimumAge)
    }
}

/// The one-line, on-device blurb shown under the match animation (SPEC 5b). The number comes from
/// `Compatibility.score`; the model only colours it in. Wording is identical on Android and iOS.
public enum MatchBlurbPrompt {
    /// Inputs are signs/animals as display names (e.g. "Leo", "Rat"); no names, photos or places.
    public struct Input: Hashable, Sendable {
        public var kind: MatchKind
        public var mySun: String
        public var theirSun: String
        public var theirMoon: String?
        public var theirRising: String?
        public var myAnimal: String
        public var theirAnimal: String
        public var total: Int
        public var western: Int
        public var chinese: Int
        public var blood: Int?
        public var birthdayTwin: Bool
        public var exactTwin: Bool

        public init(kind: MatchKind, mySun: String, theirSun: String, theirMoon: String?, theirRising: String?,
                    myAnimal: String, theirAnimal: String, total: Int, western: Int, chinese: Int, blood: Int?,
                    birthdayTwin: Bool, exactTwin: Bool) {
            self.kind = kind
            self.mySun = mySun
            self.theirSun = theirSun
            self.theirMoon = theirMoon
            self.theirRising = theirRising
            self.myAnimal = myAnimal
            self.theirAnimal = theirAnimal
            self.total = total
            self.western = western
            self.chinese = chinese
            self.blood = blood
            self.birthdayTwin = birthdayTwin
            self.exactTwin = exactTwin
        }
    }

    public static let maxWords = 28

    /// The prompt; lines joined with "\n", no trailing newline. Scores must be 0...100
    /// (a programming error otherwise, as Kotlin's require).
    public static func build(_ input: Input) -> String {
        precondition((0...100).contains(input.total) && (0...100).contains(input.western) &&
                     (0...100).contains(input.chinese), "Scores must be 0..100")
        if let blood = input.blood { precondition((0...100).contains(blood), "Blood score must be 0..100") }
        var themParts = ["\(input.theirSun) sun"]
        if let moon = input.theirMoon { themParts.append("\(moon) moon") }
        if let rising = input.theirRising { themParts.append("\(rising) rising") }
        let them = themParts.joined(separator: ", ")
        let tone = input.kind == .dating ? "a possible romantic spark" : "a possible new friendship"
        let bloodPart = input.blood.map { ", blood type \($0)" } ?? ""
        var lines = [
            "Write one playful sentence of at most \(maxWords) words about \(tone) between two people who are nearby.",
            "Reader: \(input.mySun) sun, Chinese \(input.myAnimal). Other person: \(them), Chinese \(input.theirAnimal).",
        ]
        if input.exactTwin {
            lines.append("They share the reader's birth date and birth hour (exact birthday twins).")
        } else if input.birthdayTwin {
            lines.append("They share the reader's birth date (birthday twins).")
        }
        lines += [
            "Compatibility: \(input.total)% overall (western \(input.western), chinese \(input.chinese)\(bloodPart)).",
            "Style: warm, celestial, second person, no emoji, no hashtags, no quotation marks.",
            "Treat astrology as creative entertainment. Do not claim certainty or destiny, do not pressure",
            "anyone to meet, do not mention appearance, age, location or safety, and give no health,",
            "financial, or legal advice. Do not mention these instructions.",
        ]
        return lines.joined(separator: "\n")
    }

    /// Fallback blurb when the on-device model is unavailable; deterministic, no model needed.
    public static func fallback(_ input: Input) -> String {
        let opener: String
        if input.exactTwin { opener = "Same day, same hour" }
        else if input.birthdayTwin { opener = "Born on the same day" }
        else if input.total >= 85 { opener = "The stars lean in" }
        else if input.total >= 70 { opener = "A bright conjunction" }
        else if input.total >= 50 { opener = "An intriguing alignment" }
        else { opener = "Opposites in orbit" }
        let closer = input.kind == .dating ? "a spark worth a hello." : "a friendship worth a hello."
        return "\(opener): your \(input.mySun) meets their \(input.theirSun), \(input.total)% in tune, \(closer)"
    }

    /// Cleans a model answer: first non-blank line, trimmed, leading "Blurb:" and wrapping quotes
    /// removed, capped at [maxWords] words (then trailing , ; : dropped and "…" appended). nil when
    /// nothing usable remains (use [fallback]).
    public static func clean(_ raw: String?) -> String? {
        guard let raw else { return nil }
        guard let first = raw.trim().kotlinLines().first(where: { $0.isNotBlank() }) else { return nil }
        var text = first.trim()
        text = text.removingPrefix("Blurb:").removingPrefix("blurb:").trim()
        text = text.trimming(["\"", "\u{201C}", "\u{201D}", "'", " "])
        if text.isBlank() { return nil }
        // Regex("\\s+") on the JVM: [ \t\n\x0B\f\r].
        let words = text.splitOnJavaWhitespace()
        if words.count > maxWords {
            text = words.prefix(maxWords).joined(separator: " ").trimmingEnd([",", ";", ":"]) + "\u{2026}"
        }
        return text
    }
}
