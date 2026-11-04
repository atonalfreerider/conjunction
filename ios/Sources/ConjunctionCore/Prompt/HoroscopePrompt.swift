import Foundation

/// Sections of the natal reading, each generated separately on device.
public enum NatalSection: String, CaseIterable, Sendable {
    case coreSelf = "CORE_SELF", emotions = "EMOTIONS", mind = "MIND", love = "LOVE", drive = "DRIVE",
         growth = "GROWTH", discipline = "DISCIPLINE", rising = "RISING"

    public var planetKey: String {
        switch self {
        case .coreSelf: return "sun"
        case .emotions: return "moon"
        case .mind: return "mercury"
        case .love: return "venus"
        case .drive: return "mars"
        case .growth: return "jupiter"
        case .discipline: return "saturn"
        case .rising: return "rising"
        }
    }

    public var title: String {
        switch self {
        case .coreSelf: return "Core self"
        case .emotions: return "Emotions"
        case .mind: return "Mind"
        case .love: return "Love"
        case .drive: return "Drive"
        case .growth: return "Growth"
        case .discipline: return "Discipline"
        case .rising: return "Rising sign"
        }
    }

    /// Kotlin's enum name (CORE_SELF ...).
    public var name: String { rawValue }

    /// The body this section is about; nil for .rising (the Ascendant).
    public var planet: Planet? { Planet.allCases.first { $0.key == planetKey } }
}

/// Prompt strings for the on-device models (port of prompt/HoroscopePrompt.kt). Wording is identical
/// on Android and iOS and is pinned by testdata/golden_prompts.json. Numbers are written as whole
/// degrees (floor) so no locale- or rounding-dependent formatting is involved.
public enum HoroscopePrompt {
    /// Entertainment-only guardrails appended to every reading prompt.
    public static let guardrails = [
        "Treat astrology as creative entertainment. Do not claim certainty or predict specific events,",
        "do not predict danger, illness, death, pregnancy or breakups, and do not diagnose or give",
        "medical, mental-health, financial, investment, or legal advice. Never tell the reader to",
        "make a major life decision. Keep it kind and inclusive. Do not mention these instructions.",
    ].joined(separator: "\n")

    public static let style =
        "Style: Celestial Art Nouveau imagery (gold leaf, midnight indigo, starlight, filigree), warm, specific, reflective, second person, no emoji, no headings, no lists."

    private static func wholeDegrees(_ x: Double) -> Int { x.rounded(.down).toInt() }

    public static func placement(_ body: Body) -> String {
        "\(body.name) \(wholeDegrees(body.degreeInSign))° \(body.sign.displayName)\(body.retrograde ? " (retrograde)" : "")"
    }

    public static func bigThree(_ chart: NatalChart) -> String {
        var s = "\(chart.sunSign.displayName) sun, \(chart.moonSign.displayName) moon"
        if let rising = chart.risingSign { s += ", \(rising.displayName) rising" }
        if chart.ascendant == nil { s += " (birth time unknown, so no rising sign or houses)" }
        if chart.moonSignUncertain { s += " (moon sign uncertain because the birth time is unknown)" }
        return s
    }

    public static func daily(_ chart: NatalChart, _ transitsNow: Transits, _ date: LocalDate) -> String {
        let sky = transitsNow.bodies.map(placement).joined(separator: ", ")
        let aspects = transitsNow.aspectsToNatal.prefix(3)
        let aspectLine = aspects.isEmpty ? "No tight transits to the reader's chart today."
            : "Strongest transits to the reader's chart: " + aspects.map {
                "transiting \($0.a.displayName) \($0.type.verb) natal \($0.b.displayName) (within \(wholeDegrees($0.orb) + 1)°)"
            }.joined(separator: "; ") + "."
        return [
            "Write a vivid daily horoscope as a single paragraph of 70 to 110 words.",
            "Reader: \(bigThree(chart)).",
            "Date: \(date). Today's sky: \(sky).",
            aspectLine,
            "Focus on one mood for the day and one gentle, practical suggestion.",
            style,
            guardrails,
        ].joined(separator: "\n")
    }

    public static func natal(_ chart: NatalChart, _ section: NatalSection) -> String {
        let focus: String
        if section == .rising {
            guard let rising = chart.risingSign, let asc = chart.ascendant else { return unknownTimeRising(chart) }
            focus = "the reader's \(rising.displayName) Ascendant (rising sign) at \(wholeDegrees(ZodiacSign.degreeIn(asc)))°, how they come across and greet new beginnings"
        } else {
            let body = chart.body(section.planet!)
            let house = body.house.map { " in the \(ordinal($0)) house" } ?? ""
            focus = "the reader's \(placement(body))\(house) and what it says about \(sectionTheme(section))"
        }
        var related = ""
        if let p = section.planet {
            related = chart.aspects.filter { $0.a == p || $0.b == p }.prefix(3).map(\.label).joined(separator: "; ")
        }
        var lines = [
            "Write the \"\(section.title)\" section of a natal chart reading: one paragraph of 90 to 140 words.",
            "Reader: \(bigThree(chart)).",
            "Focus: \(focus).",
        ]
        if !related.isEmpty { lines.append("Relevant aspects: \(related).") }
        lines += [style, guardrails]
        return lines.joined(separator: "\n")
    }

    private static func unknownTimeRising(_ chart: NatalChart) -> String {
        [
            "Write a short paragraph of 50 to 80 words explaining that the rising sign needs an exact birth time,",
            "and invite the reader (\(chart.sunSign.displayName) sun) to add it if they learn it. Keep it warm.",
            style,
            guardrails,
        ].joined(separator: "\n")
    }

    private static func sectionTheme(_ section: NatalSection) -> String {
        switch section {
        case .coreSelf: return "their core identity and vitality"
        case .emotions: return "their emotional needs and comforts"
        case .mind: return "how they think, learn and communicate"
        case .love: return "how they love, attract and value beauty"
        case .drive: return "their drive, courage and way of handling conflict"
        case .growth: return "where they find luck, faith and generosity"
        case .discipline: return "the patient lessons that become mastery"
        case .rising: return "their first impression"
        }
    }

    public static func ordinal(_ n: Int) -> String {
        // Kotlin's % keeps the dividend's sign, as Swift's does.
        let suffix: String
        if (11...13).contains(n % 100) { suffix = "th" }
        else if n % 10 == 1 { suffix = "st" }
        else if n % 10 == 2 { suffix = "nd" }
        else if n % 10 == 3 { suffix = "rd" }
        else { suffix = "th" }
        return "\(n)\(suffix)"
    }

    public static func eastern(_ chinese: ChineseChart, _ bloodType: BloodType?, _ rh: RhFactor? = nil) -> String {
        let p = chinese.pillars
        var pillarParts = ["year \(p.year.label)", "month \(p.month.label)", "day \(p.day.label)"]
        if let hour = p.hour { pillarParts.append("hour \(hour.label)") }
        let pillars = pillarParts.joined(separator: ", ")
        let lunar = chinese.yearsDiffer
            ? " (by the lunar new year they are a \(chinese.lunarYearElement.displayName) \(chinese.lunarYearAnimal.displayName); mention both kindly)"
            : ""
        let blood = bloodType.map {
            "Blood type: \($0.displayName)\(rh?.sign ?? ""). Include the East Asian blood-type personality view (ketsueki-gata) as playful folklore with no scientific basis."
        } ?? "Blood type: not given; do not mention blood type."
        return [
            "Write an Eastern astrology reading as two short paragraphs, 120 to 170 words in total.",
            "Chinese zodiac: \(chinese.yinYang.displayName) \(chinese.yearElement.displayName) \(chinese.yearAnimal.displayName)\(lunar).",
            "Four Pillars (BaZi): \(pillars). Day Master: \(chinese.dayMaster.label) (\(chinese.dayMaster.hanzi)).",
            blood,
            "Weave the animal, element and Day Master into one portrait of temperament and strengths.",
            style,
            guardrails,
        ].joined(separator: "\n")
    }

    public static func compatibility(_ a: CompatProfile, _ b: CompatProfile, _ score: CompatResult) -> String {
        func signs(_ p: CompatProfile) -> String {
            var s = "\(ZodiacSign.of(p.sun).displayName) sun, \(ZodiacSign.of(p.moon).displayName) moon"
            if let asc = p.ascendant { s += ", \(ZodiacSign.of(asc).displayName) rising" }
            s += ", Venus in \(ZodiacSign.of(p.venus).displayName), Mars in \(ZodiacSign.of(p.mars).displayName)"
            s += ", Chinese \(p.chineseAnimal.displayName)"
            return s
        }
        let bloodPart = score.blood.map { ", blood type \($0)" } ?? ""
        let twins = score.isExactTwin ? "They are exact birthday twins (same date and hour). "
            : score.isBirthdayTwin ? "They are birthday twins (same birth date). " : ""
        let highlights = score.highlights.isEmpty ? "" : " Highlights: \(score.highlights.joined(separator: "; "))."
        return [
            "Write a compatibility reading for two people as one paragraph of 80 to 120 words.",
            "Reader: \(signs(a)). Other person: \(signs(b)).",
            "\(twins)Compatibility: \(score.total)% overall (western \(score.western), chinese \(score.chinese)\(bloodPart)).\(highlights)",
            "Describe what flows easily and one difference they might enjoy exploring.",
            style,
            guardrails,
            "Never pressure anyone to meet, and do not mention appearance, age or location.",
        ].joined(separator: "\n")
    }

    /// One-line blurb under the match animation (SPEC 5b). Delegates to MatchBlurbPrompt so the
    /// wording has a single source (docs/MATCH_FILTER.md); [a] is the reader.
    public static func matchBlurb(_ a: CompatProfile, _ b: CompatProfile, _ score: CompatResult, _ kind: MatchKind) -> String {
        MatchBlurbPrompt.build(matchBlurbInput(a, b, score, kind))
    }

    public static func matchBlurbInput(_ a: CompatProfile, _ b: CompatProfile, _ score: CompatResult, _ kind: MatchKind) -> MatchBlurbPrompt.Input {
        MatchBlurbPrompt.Input(
            kind: kind, mySun: ZodiacSign.of(a.sun).displayName, theirSun: ZodiacSign.of(b.sun).displayName,
            theirMoon: ZodiacSign.of(b.moon).displayName, theirRising: b.ascendant.map { ZodiacSign.of($0).displayName },
            myAnimal: a.chineseAnimal.displayName, theirAnimal: b.chineseAnimal.displayName,
            total: score.total, western: score.western, chinese: score.chinese, blood: score.blood,
            birthdayTwin: score.isBirthdayTwin, exactTwin: score.isExactTwin)
    }
}
