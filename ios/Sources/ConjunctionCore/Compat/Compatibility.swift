import Foundation

// Deterministic compatibility score, ported from android/core .../compat/Compatibility.kt. Integer
// arithmetic throughout after quantising longitudes to centidegrees, so Kotlin, Swift and TypeScript
// agree exactly (golden_compat.json). docs/COMPATIBILITY.md is the specification.

/// The summary a phone uploads while Nearby is on (never raw birth time or place).
/// Longitudes are degrees [0, 360).
public struct CompatProfile: Hashable, Sendable {
    public let sun: Double
    public let moon: Double
    public let venus: Double
    public let mars: Double
    /// nil when the birth time is unknown.
    public let ascendant: Double?
    public let chineseAnimal: ChineseAnimal
    public let dayStem: HeavenlyStem
    public let bloodType: BloodType?
    public let birthDate: LocalDate
    /// Local clock hour of birth 0..23, nil when unknown.
    public let birthHour: Int?

    public enum InvalidProfile: Error, Equatable, CustomStringConvertible, Sendable {
        case longitude(Double), birthHour(Int)
        public var description: String {
            switch self {
            case .longitude(let v): return "longitude out of range: \(v)"
            case .birthHour(let h): return "birthHour out of range: \(h)"
            }
        }
    }

    /// Validates as Kotlin's init block does (throws instead of IllegalArgumentException), so a
    /// profile received from the backend can be rejected rather than crash the app.
    public init(sun: Double, moon: Double, venus: Double, mars: Double, ascendant: Double?,
                chineseAnimal: ChineseAnimal, dayStem: HeavenlyStem, bloodType: BloodType?,
                birthDate: LocalDate, birthHour: Int?) throws {
        for v in [sun, moon, venus, mars] + (ascendant.map { [$0] } ?? []) where !(0.0...360.0).contains(v) {
            throw InvalidProfile.longitude(v)
        }
        if let h = birthHour, !(0...23).contains(h) { throw InvalidProfile.birthHour(h) }
        self.sun = sun
        self.moon = moon
        self.venus = venus
        self.mars = mars
        self.ascendant = ascendant
        self.chineseAnimal = chineseAnimal
        self.dayStem = dayStem
        self.bloodType = bloodType
        self.birthDate = birthDate
        self.birthHour = birthHour
    }

    /// Builds the uploaded summary from on-device charts (longitudes rounded to 0.01 deg).
    public static func from(_ chart: NatalChart, _ chinese: ChineseChart, _ bloodType: BloodType?) -> CompatProfile {
        // Rounded longitudes are in [0, 360) and the hour comes from a LocalTime, so this cannot throw.
        try! CompatProfile(
            sun: round2(chart.body(.sun).longitude), moon: round2(chart.body(.moon).longitude),
            venus: round2(chart.body(.venus).longitude), mars: round2(chart.body(.mars).longitude),
            ascendant: chart.ascendant.map(round2), chineseAnimal: chinese.yearAnimal, dayStem: chinese.dayMaster,
            bloodType: bloodType, birthDate: chart.birth.date, birthHour: chart.birth.time?.hour)
    }

    /// Rounds to 0.01 deg, wrapping 360.00 to 0.00.
    public static func round2(_ lon: Double) -> Double { Double(Compatibility.centidegrees(lon)) / 100.0 }
}

/// Branch relation between two Chinese year animals (docs/COMPATIBILITY.md section 3).
public enum ChineseRelation: String, CaseIterable, Sendable {
    case same = "SAME", sameSelfPunishment = "SAME_SELF_PUNISHMENT", trine = "TRINE", harmony = "HARMONY",
         clash = "CLASH", harm = "HARM", neutral = "NEUTRAL"

    public var score: Int {
        switch self {
        case .same: return 70
        case .sameSelfPunishment: return 60
        case .trine: return 95
        case .harmony: return 90
        case .clash: return 25
        case .harm: return 40
        case .neutral: return 60
        }
    }
    /// Kotlin's enum name (as written to golden files).
    public var name: String { rawValue }
}

/// Day-master (day stem) relation.
public enum StemRelation: String, CaseIterable, Sendable {
    case combination = "COMBINATION", sameStem = "SAME_STEM", sameElement = "SAME_ELEMENT",
         generating = "GENERATING", controlling = "CONTROLLING"

    public var score: Int {
        switch self {
        case .combination: return 95
        case .sameStem: return 70
        case .sameElement: return 75
        case .generating: return 80
        case .controlling: return 45
        }
    }
    public var name: String { rawValue }
}

public struct CompatResult: Hashable, Sendable {
    /// Overall 0..100.
    public let total: Int
    public let western: Int
    public let chinese: Int
    /// nil when either blood type is unknown.
    public let blood: Int?
    /// Up to four short, deterministic English highlights (identical on every platform).
    public let highlights: [String]
    public let isBirthdayTwin: Bool
    public let isExactTwin: Bool
    /// Same month and day, different year (a lighter "birthday buddies" note).
    public let sharesBirthday: Bool
    public let chineseRelation: ChineseRelation
    public let stemRelation: StemRelation
}

public enum Compatibility {
    /// Chart points used in synastry, in canonical order (labels and tie-breaks).
    public enum Point: Int, CaseIterable, Sendable {
        case sun, moon, venus, mars, asc
        public var label: String { ["Sun", "Moon", "Venus", "Mars", "Ascendant"][rawValue] }
        public var ordinal: Int { rawValue }
    }

    /// (point of A, point of B, weight). Symmetric: every cross pair appears in both directions.
    public static let pairs: [(Point, Point, Int)] = [
        (.sun, .moon, 5), (.moon, .sun, 5),
        (.venus, .mars, 4), (.mars, .venus, 4),
        (.moon, .moon, 4),
        (.sun, .sun, 3),
        (.venus, .venus, 2),
        (.sun, .venus, 2), (.venus, .sun, 2),
        (.moon, .venus, 2), (.venus, .moon, 2),
        (.mars, .mars, 1),
        (.asc, .sun, 2), (.sun, .asc, 2),
        (.asc, .moon, 2), (.moon, .asc, 2),
        (.asc, .asc, 1),
    ]

    /// Aspect: exact angle and orb in centidegrees, value in permille. Order = tie-break order.
    public struct SynastryAspect: Hashable, Sendable {
        public let name: String
        public let angle: Int
        public let orb: Int
        public let value: Int
    }

    public static let aspects: [SynastryAspect] = [
        SynastryAspect(name: "conjunction", angle: 0, orb: 800, value: 600),
        SynastryAspect(name: "sextile", angle: 6000, orb: 500, value: 400),
        SynastryAspect(name: "square", angle: 9000, orb: 700, value: -400),
        SynastryAspect(name: "trine", angle: 12000, orb: 700, value: 500),
        SynastryAspect(name: "opposition", angle: 18000, orb: 800, value: 200),
    ]

    /// ELEMENT[a][b] in permille, order FIRE, EARTH, AIR, WATER (Element's case order).
    public static let element: [[Int]] = [
        [300, -50, 250, -150],
        [-50, 300, -150, 250],
        [250, -150, 300, -50],
        [-150, 250, -50, 300],
    ]

    /// MODALITY: same modality -50, different +100 (permille).
    public static func modality(_ a: Modality, _ b: Modality) -> Int { a == b ? -50 : 100 }

    /// BLOOD[a][b], order A, B, AB, O.
    public static let blood: [[Int]] = [
        [80, 50, 70, 85],
        [50, 80, 85, 75],
        [70, 85, 75, 60],
        [85, 75, 60, 80],
    ]

    public static let weightWestern = 55
    public static let weightChinese = 30
    public static let weightBlood = 15

    /// Mapping from the permille synastry average to 0..100: western = roundDiv(raw + OFFSET, DIVISOR), clamped.
    public static let westernOffset = 145
    public static let westernDivisor = 5

    /// floor(lon * 100 + 0.5) mod 36000 - the only floating-point step.
    public static func centidegrees(_ lon: Double) -> Int {
        let v = (lon * 100.0 + 0.5).rounded(.down).toLong()
        let m = v % 36_000
        return Int(m < 0 ? m + 36_000 : m)
    }

    /// Round half up for integer n / d (d > 0): floorDiv(2n + d, 2d).
    public static func roundDiv(_ n: Int, _ d: Int) -> Int { Math.floorDiv(2 * n + d, 2 * d) }

    public static func isBirthdayTwin(_ a: CompatProfile, _ b: CompatProfile) -> Bool { a.birthDate == b.birthDate }

    public static func isExactTwin(_ a: CompatProfile, _ b: CompatProfile) -> Bool {
        isBirthdayTwin(a, b) && a.birthHour != nil && a.birthHour == b.birthHour
    }

    public static func sharesBirthday(_ a: CompatProfile, _ b: CompatProfile) -> Bool {
        !isBirthdayTwin(a, b) && a.birthDate.month == b.birthDate.month && a.birthDate.day == b.birthDate.day
    }

    private static func lon(_ p: CompatProfile, _ point: Point) -> Double? {
        switch point {
        case .sun: return p.sun
        case .moon: return p.moon
        case .venus: return p.venus
        case .mars: return p.mars
        case .asc: return p.ascendant
        }
    }

    /// One synastry pair: sign part + aspect part (permille) and the in-orb aspect, if any.
    public struct PairScore: Hashable, Sendable {
        public let score: Int
        public let aspect: SynastryAspect?
        public let aspectPart: Int
    }

    public static func pairScore(_ lonA: Int, _ lonB: Int) -> PairScore {
        let signA = ZodiacSign(rawValue: lonA / 3000)!
        let signB = ZodiacSign(rawValue: lonB / 3000)!
        let signPart = element[elementIndex(signA.element)][elementIndex(signB.element)] + modality(signA.modality, signB.modality)
        let diff = abs(lonA - lonB)
        let sep = diff > 18_000 ? 36_000 - diff : diff
        var best: SynastryAspect?
        var bestDev = Int.max
        for asp in aspects {
            let dev = abs(sep - asp.angle)
            if dev <= asp.orb && dev < bestDev { best = asp; bestDev = dev }
        }
        // Integer division truncates toward zero in Kotlin, Swift and JS (Math.trunc).
        let aspectPart = best.map { $0.value * ($0.orb - bestDev) / $0.orb } ?? 0
        return PairScore(score: (signPart + aspectPart).coerceIn(-1000, 1000), aspect: best, aspectPart: aspectPart)
    }

    private static func elementIndex(_ e: Element) -> Int { Element.allCases.firstIndex(of: e)! }

    public static func chineseRelation(_ a: ChineseAnimal, _ b: ChineseAnimal) -> ChineseRelation {
        let x = a.ordinal, y = b.ordinal
        let d = abs(x - y)
        if x == y { return [4, 6, 9, 11].contains(x) ? .sameSelfPunishment : .same }
        if d % 4 == 0 { return .trine }
        if (x + y) % 12 == 1 { return .harmony }
        if d == 6 { return .clash }
        if (x + y) % 12 == 7 { return .harm }
        return .neutral
    }

    public static func stemRelation(_ a: HeavenlyStem, _ b: HeavenlyStem) -> StemRelation {
        let ea = a.element, eb = b.element
        if abs(a.ordinal - b.ordinal) == 5 { return .combination }
        if a == b { return .sameStem }
        if ea == eb { return .sameElement }
        if ea.generates == eb || eb.generates == ea { return .generating }
        return .controlling
    }

    public static func bloodScore(_ a: BloodType?, _ b: BloodType?) -> Int? {
        guard let a, let b else { return nil }
        return blood[BloodType.allCases.firstIndex(of: a)!][BloodType.allCases.firstIndex(of: b)!]
    }

    public static func score(_ a: CompatProfile, _ b: CompatProfile) -> CompatResult {
        // 1. Western synastry.
        var weighted = 0
        var weights = 0
        var contributions: [(label: String, value: Int)] = []
        for (pa, pb, w) in pairs {
            guard let la = lon(a, pa), let lb = lon(b, pb) else { continue }
            let ps = pairScore(centidegrees(la), centidegrees(lb))
            weighted += w * ps.score
            weights += w
            if let aspect = ps.aspect, ps.aspectPart > 0 {
                let (first, second) = pa.ordinal <= pb.ordinal ? (pa, pb) : (pb, pa)
                contributions.append(("\(first.label)\u{2013}\(second.label) \(aspect.name)", w * ps.aspectPart))
            }
        }
        let raw = Math.floorDiv(weighted, weights)
        let western = roundDiv(raw + westernOffset, westernDivisor).coerceIn(0, 100)

        // 2. Chinese: year-branch relation (3/5) and day-master relation (2/5).
        let cRel = chineseRelation(a.chineseAnimal, b.chineseAnimal)
        let sRel = stemRelation(a.dayStem, b.dayStem)
        let chinese = roundDiv(3 * cRel.score + 2 * sRel.score, 5)

        // 3. Blood type.
        let bloodValue = bloodScore(a.bloodType, b.bloodType)

        // 4. Blend.
        let total: Int
        if let bloodValue {
            total = roundDiv(weightWestern * western + weightChinese * chinese + weightBlood * bloodValue, 100)
        } else {
            total = roundDiv(weightWestern * western + weightChinese * chinese, weightWestern + weightChinese)
        }

        // 5. Highlights.
        let twin = isBirthdayTwin(a, b)
        let exact = isExactTwin(a, b)
        let shares = sharesBirthday(a, b)
        var highlights: [String] = []
        if exact { highlights.append("Exact twins: same day, same hour") }
        else if twin { highlights.append("Birthday twins") }
        else if shares { highlights.append("Same birthday") }
        // Descending value, then label in Kotlin String order (UTF-16 code units); then distinct, take 2.
        let ordered = contributions.enumerated().sorted { l, r in
            if l.element.value != r.element.value { return l.element.value > r.element.value }
            let lu = Array(l.element.label.utf16), ru = Array(r.element.label.utf16)
            if lu != ru { return lu.lexicographicallyPrecedes(ru) }
            return l.offset < r.offset
        }.map(\.element.label)
        var seen = Set<String>()
        for label in ordered where seen.insert(label).inserted {
            if seen.count > 2 { break }
            highlights.append(label)
        }
        if let h = chineseHighlight(cRel, a.chineseAnimal, b.chineseAnimal) { highlights.append(h) }
        if sRel == .combination { highlights.append("Day Masters combine (\(minStem(a.dayStem, b.dayStem)))") }
        if let bloodValue, bloodValue >= 80, let ba = a.bloodType, let bb = b.bloodType {
            let sorted = [ba, bb].stableSorted { BloodType.allCases.firstIndex(of: $0)! }
            highlights.append("Blood types \(sorted[0].displayName) + \(sorted[1].displayName)")
        }
        return CompatResult(
            total: total.coerceIn(0, 100), western: western, chinese: chinese, blood: bloodValue,
            highlights: Array(highlights.prefix(4)), isBirthdayTwin: twin, isExactTwin: exact, sharesBirthday: shares,
            chineseRelation: cRel, stemRelation: sRel)
    }

    private static func minStem(_ a: HeavenlyStem, _ b: HeavenlyStem) -> String {
        let (x, y) = a.ordinal <= b.ordinal ? (a, b) : (b, a)
        return "\(x.hanzi)\(y.hanzi)"
    }

    private static func chineseHighlight(_ rel: ChineseRelation, _ a: ChineseAnimal, _ b: ChineseAnimal) -> String? {
        let (x, y) = a.ordinal <= b.ordinal ? (a, b) : (b, a)
        switch rel {
        case .trine: return "\(x.displayName) & \(y.displayName): San He trine"
        case .harmony: return "\(x.displayName) & \(y.displayName): Liu He secret friends"
        case .clash: return "\(x.displayName) & \(y.displayName): opposites attract"
        case .same, .sameSelfPunishment: return "Both \(x.displayName)s"
        case .harm, .neutral: return nil
        }
    }
}
