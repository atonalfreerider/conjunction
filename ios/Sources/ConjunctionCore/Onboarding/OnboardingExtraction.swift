import Foundation

/// The on-device model's answer to `OnboardingPrompt.birth`, after validation.
public struct BirthExtraction: Hashable, Sendable {
    public var date: LocalDate?
    public var time: LocalTime?
    public var timeUnknown: Bool
    public var place: String?

    public init(date: LocalDate?, time: LocalTime?, timeUnknown: Bool, place: String?) {
        self.date = date
        self.time = time
        self.timeUnknown = timeUnknown
        self.place = place
    }
}

/// Validation of the on-device model's output for onboarding (SPEC 5d.5, golden
/// `testdata/golden_onboarding_ai.json`); port of Kotlin `onboarding.OnboardingExtraction`.
///
/// With FoundationModels guided generation (`@Generable`), pass the generated fields to
/// `validateBirth` / `validateAboutYou`; for a plain text answer use `parseBirth` / `parseAboutYou`
/// (the JSON object is taken from the first `{` to the last `}` and parsed strictly). A field that
/// fails its rule becomes nil; unparsable output gives nil (keep the deterministic chips silently).
public enum OnboardingExtraction {
    /// Model timeouts (SPEC 5d.5): birth and about-you extraction, bio.
    public static let extractionTimeoutMs: Int64 = 4_000
    public static let bioTimeoutMs: Int64 = 6_000
    public static let maxPlaceBytes = 120

    private static let timeRe = TextPattern("^([01][0-9]|2[0-3]):([0-5][0-9])$", ignoreCase: false)
    private static let dateRe = TextPattern("^([0-9]{4})-([0-9]{2})-([0-9]{2})$", ignoreCase: false)
    private static let nameChars = TextPattern("^[\\p{L}\\p{M}][\\p{L}\\p{M} '\u{2019}-]*$", ignoreCase: false)

    /// The first `{` .. last `}` of `raw` parsed as a strict JSON object, or nil.
    public static func jsonObject(_ raw: String?) -> WireObject? {
        guard let raw else { return nil }
        let u = Array(raw.unicodeScalars)
        guard let start = u.firstIndex(of: "{"), let end = u.lastIndex(of: "}"), end >= start else { return nil }
        let text = String(String.UnicodeScalarView(u[start...end]))
        guard case .object(let o)? = try? WireJSON.parse(text) else { return nil }
        return o
    }

    private static func str(_ o: WireObject, _ k: String) -> String? {
        if case .string(let s)? = o[k] { return s }
        return nil
    }

    public static func parseBirth(_ raw: String?, today: LocalDate) -> BirthExtraction? {
        guard let o = jsonObject(raw) else { return nil }
        var unknown = false
        if case .bool(true)? = o["timeUnknown"] { unknown = true }
        return validateBirth(date: str(o, "date"), time: str(o, "time"), timeUnknown: unknown, place: str(o, "place"), today: today)
    }

    public static func validateBirth(date: String?, time: String?, timeUnknown: Bool, place: String?, today: LocalDate) -> BirthExtraction {
        var d: LocalDate?
        if let m = date.flatMap({ dateRe.find($0.trim()) }),
           let v = BirthTextParser.ymd(Int(m.group(1))!, Int(m.group(2))!, Int(m.group(3))!),
           BirthTextParser.isAcceptableDate(v, today: today) {
            d = v
        }
        let t = time.flatMap { timeRe.find($0.trim()) }.map { LocalTime(Int($0.group(1))!, Int($0.group(2))!) }
        return BirthExtraction(date: d, time: t, timeUnknown: timeUnknown && t == nil, place: cleanPlace(place))
    }

    public static func parseAboutYou(_ raw: String?) -> AboutYou? {
        guard let o = jsonObject(raw) else { return nil }
        return validateAboutYou(firstName: str(o, "firstName"), gender: str(o, "gender"), seeking: str(o, "seeking"),
                                relationship: str(o, "relationship"), lookingFor: str(o, "lookingFor"), interests: str(o, "interests"))
    }

    /// Enum values must be exactly the wire strings; names and interests must pass their rules.
    public static func validateAboutYou(firstName: String?, gender: String?, seeking: String?, relationship: String?,
                                        lookingFor: String?, interests: String?) -> AboutYou {
        AboutYou(
            firstName: cleanFirstName(firstName),
            gender: Gender.allCases.first { $0.wire == gender },
            seeking: Seeking.allCases.first { $0.wire == seeking },
            relationship: RelationshipStatus.allCases.first { $0.wire == relationship },
            lookingFor: LookingFor.allCases.first { $0.wire == lookingFor },
            interests: interests.map { $0.trim() }.flatMap { $0.isEmpty || $0.utf8.count > AboutYouParser.maxInterestsBytes ? nil : $0 })
    }

    /// 1..64 UTF-8 bytes of letters (with marks), spaces, apostrophes and hyphens, trimmed; else nil.
    public static func cleanFirstName(_ s: String?) -> String? {
        guard let t = s?.trim(), (1...AboutYouParser.maxFirstNameBytes).contains(t.utf8.count), nameChars.matchesEntire(t) else { return nil }
        return t
    }

    /// Trimmed, cut at a scalar boundary to <= 200 UTF-8 bytes (the parser's own interests).
    public static func cleanInterests(_ s: String?) -> String? {
        guard var t = s?.trim() else { return nil }
        while t.utf8.count > AboutYouParser.maxInterestsBytes {
            t = String(String.UnicodeScalarView(t.unicodeScalars.dropLast())).trimEndKotlin()
        }
        return t.isEmpty ? nil : t
    }

    /// A place query: trimmed, 1..120 UTF-8 bytes, no control characters; else nil.
    public static func cleanPlace(_ s: String?) -> String? {
        guard let t = s?.trim(), (1...maxPlaceBytes).contains(t.utf8.count),
              !t.unicodeScalars.contains(where: { $0.value < 0x20 || $0.value == 0x7F }) else { return nil }
        return t
    }

    /// Merges a model answer into the parser result: the model only fills what is still empty
    /// (never a parsed or user-edited value); a parsed time or "time unknown" is kept.
    public static func fillEmpty(_ parsed: BirthTextResult, _ model: BirthExtraction?) -> BirthTextResult {
        guard let model else { return parsed }
        let hasTime = parsed.time != nil || parsed.timeUnknown
        var out = parsed
        out.date = parsed.date ?? model.date
        out.time = hasTime ? parsed.time : model.time
        out.timeUnknown = hasTime ? parsed.timeUnknown : model.time == nil && model.timeUnknown
        out.placeQuery = parsed.placeQuery ?? model.place
        return out
    }
}

extension String {
    /// Kotlin's trimEnd(): drops trailing Kotlin-whitespace scalars.
    func trimEndKotlin() -> String {
        let scalars = unicodeScalars
        guard let last = scalars.lastIndex(where: { !$0.isKotlinWhitespace }) else { return "" }
        return String(scalars[...last])
    }
}
