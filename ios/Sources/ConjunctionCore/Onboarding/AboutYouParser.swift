import Foundation

/// The "About you" chips (SPEC 5d.5 screen 3); every field optional.
public struct AboutYou: Hashable, Sendable {
    public var firstName: String?
    public var gender: Gender?
    public var seeking: Seeking?
    public var relationship: RelationshipStatus?
    public var lookingFor: LookingFor?
    public var interests: String?

    public init(firstName: String? = nil, gender: Gender? = nil, seeking: Seeking? = nil,
                relationship: RelationshipStatus? = nil, lookingFor: LookingFor? = nil, interests: String? = nil) {
        self.firstName = firstName
        self.gender = gender
        self.seeking = seeking
        self.relationship = relationship
        self.lookingFor = lookingFor
        self.interests = interests
    }

    /// The model (or any second source) only fills chips that are still empty (SPEC 5d.5).
    public func fillEmpty(_ other: AboutYou?) -> AboutYou {
        guard let other else { return self }
        return AboutYou(firstName: firstName ?? other.firstName, gender: gender ?? other.gender,
                        seeking: seeking ?? other.seeking, relationship: relationship ?? other.relationship,
                        lookingFor: lookingFor ?? other.lookingFor, interests: interests ?? other.interests)
    }
}

/// Deterministic "About you" extraction (SPEC 5d.5, golden `testdata/golden_about_you.json`); port of
/// Kotlin `onboarding.AboutYouParser` with the same regex texts. Only explicit statements count: it
/// never guesses gender or orientation from a name.
public enum AboutYouParser {
    public static let maxInterestsBytes = 200
    public static let maxFirstNameBytes = 64

    private static let b = "(?<![\\p{L}0-9])"
    private static let e = "(?![\\p{L}0-9])"

    private static let men = "men|guys|boys|man|guy|boy|males|male|gentlemen|dudes"
    private static let women = "women|girls|woman|girl|ladies|lady|females|female|gals"
    private static let anyone = "both|everyone|anyone|either|all genders|any gender|men (?:and|or) women|women (?:and|or) men|guys (?:and|or) girls|girls (?:and|or) guys"

    private static let seekingRe = TextPattern(
        "\(b)(?:looking +for|looking +to +date|seeking|into|interested +in|want +to +date|wanna +date|attracted +to|dating|date|likes|like|loves|love)" +
        " +(?:(?:a|an|some|other|cute|nice|kind|single|only|mostly) +)*(\(anyone)|\(men)|\(women))\(e)")
    private static let anyTarget = TextPattern("^(?:\(anyone))$", ignoreCase: false)
    private static let menTarget = TextPattern("^(?:\(men))$", ignoreCase: false)
    private static let orientationBoth = TextPattern("\(b)(?:bisexual|pansexual)\(e)")
    /// "my name is / call me X": any case.
    private static let nameStated = TextPattern("\(b)(?:my +name +is|my +name's|call +me|name:) *(\\p{L}[\\p{L}\\p{M}'-]*)")
    /// "I'm X": only a capitalised word ("I'm funny" is not a name).
    private static let nameIm = TextPattern("\(b)(?:i'm|i +am|im) +(\\p{L}[\\p{L}\\p{M}'-]*)")

    /// Words after "I'm" that are not a name.
    public static let notNames: Set<String> = Set(
        ("a an the single looking into in on at from with married engaged taken not just here new so very really also " +
         "seeking searching dating interested open shy happy living based originally currently over about " +
         "bi bisexual pansexual straight gay lesbian queer " + "\(men) \(women)".replacingOccurrences(of: "|", with: " "))
            .split(separator: " ").map(String.init))
    private static let maleRe = TextPattern("\(b)(?:\(men))\(e)")
    private static let femaleRe = TextPattern("\(b)(?:\(women))\(e)")
    private static let notSingleRe = TextPattern("\(b)not +single\(e)")
    private static let singleRe = TextPattern("\(b)single\(e)")
    private static let inRelationshipRe = TextPattern(
        "\(b)(?:married|engaged|taken|partnered|in +a +relationship|my +(?:partner|boyfriend|girlfriend|husband|wife|fianc[eé]e?)" +
        "|have +a +(?:partner|boyfriend|girlfriend|husband|wife))\(e)")
    private static let datingRe = TextPattern("\(b)(?:date|dates|dating|romance|romantic|relationship|love +of +my +life|soulmate|partner)\(e)")
    private static let friendsRe = TextPattern("\(b)(?:friends|friend|friendship|friendships|platonic|buddies|buddy|pals)\(e)")
    private static let myFriendsRe = TextPattern("\(b)(?:my|our) +friends?\(e)")
    private static let interestsRe = TextPattern(
        "\(b)(?:i'm +into|i +am +into|into|love|loves|enjoy|enjoys|i +like|like|likes|passionate +about|interested +in|fan +of|hobbies(?: +are| +include)?:?)" +
        " +([^,.;!?\\n]+)")
    private static let interestStop = TextPattern("(?:^| +)(?:and +)?(?:looking|seeking|who|that|but|want|wanting|hoping|searching)\(e).*$")
    private static let notInterest = TextPattern("^(?:to|be|it|that|this|you|me|\(men)|\(women)|\(anyone))\(e)")

    public static func parse(_ text: String) -> AboutYou {
        var s = BirthTextParser.normalizeSpaces(text)

        // 1. Seeking clauses (then removed so their gender words are not read as the user's own).
        var seekMale = false, seekFemale = false
        while let m = seekingRe.find(s) {
            let target = m.group(1).lowercased()
            if anyTarget.matchesEntire(target) { seekMale = true; seekFemale = true }
            else if menTarget.matchesEntire(target) { seekMale = true }
            else { seekFemale = true }
            s = s.utf16Prefix(m.range.location) + " , " + s.utf16Suffix(from: m.range.location + m.range.length)
        }
        if orientationBoth.contains(s) { seekMale = true; seekFemale = true }
        let seeking: Seeking? = seekMale && seekFemale ? .both : seekMale ? .male : seekFemale ? .female : nil

        // 2. First name.
        var firstName: String?
        let candidates = nameStated.findAll(s).map { $0.group(1) } +
            nameIm.findAll(s).map { $0.group(1) }.filter { $0.unicodeScalars.first!.properties.isUppercase }
        for w in candidates {
            if notNames.contains(w.lowercased().trimmingEnd(["'", "-"])) { continue }
            firstName = OnboardingExtraction.cleanFirstName(upperFirst(w))
            if firstName != nil { break }
        }

        // 3. Own gender.
        let male = maleRe.contains(s), female = femaleRe.contains(s)
        let gender: Gender? = male && !female ? .male : female && !male ? .female : nil

        // 4. Relationship status.
        let notSingle = notSingleRe.contains(s)
        let single = !notSingle && singleRe.contains(s)
        let inRel = notSingle || inRelationshipRe.contains(s)
        let relationship: RelationshipStatus? = single && !inRel ? .single : inRel && !single ? .inARelationship : nil

        // 5. Looking for (status words such as "in a relationship" / "my partner" are not dating intent).
        let intent = inRelationshipRe.replace(myFriendsRe.replace(BirthTextParser.normalizeSpaces(text), with: " "), with: " ")
        let dating = datingRe.contains(intent), friends = friendsRe.contains(intent)
        let lookingFor: LookingFor? = dating && friends ? .both : friends ? .friends : dating ? .dating : nil

        // 6. Interests.
        var parts: [String] = []
        for m in interestsRe.findAll(s) {
            let p = interestStop.replace(m.group(1), with: "").trim()
            if !p.isEmpty && !notInterest.contains(p) && !parts.contains(p) { parts.append(p) }
        }
        let interests = OnboardingExtraction.cleanInterests(parts.joined(separator: ", "))

        return AboutYou(firstName: firstName, gender: gender, seeking: seeking, relationship: relationship,
                        lookingFor: lookingFor, interests: interests)
    }

    /// Kotlin replaceFirstChar { it.uppercaseChar() }: a one-scalar uppercase mapping, else unchanged.
    private static func upperFirst(_ w: String) -> String {
        guard let first = w.unicodeScalars.first else { return w }
        let up = first.properties.uppercaseMapping.unicodeScalars
        guard up.count == 1 else { return w }
        return String(String.UnicodeScalarView(up)) + String(String.UnicodeScalarView(w.unicodeScalars.dropFirst()))
    }
}
