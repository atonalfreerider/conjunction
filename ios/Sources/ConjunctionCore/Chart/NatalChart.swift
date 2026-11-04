import Foundation

/// A body in the chart (port of chart/NatalChart.kt). [house] is nil when the birth time is unknown.
public struct Body: Hashable, Sendable {
    public let planet: Planet
    public let longitude: Double
    public let latitude: Double
    public let speed: Double
    public let house: Int?

    public init(planet: Planet, longitude: Double, latitude: Double, speed: Double, house: Int?) {
        self.planet = planet
        self.longitude = longitude
        self.latitude = latitude
        self.speed = speed
        self.house = house
    }

    public var name: String { planet.displayName }
    public var sign: ZodiacSign { ZodiacSign.of(longitude) }
    public var degreeInSign: Double { ZodiacSign.degreeIn(longitude) }
    public var retrograde: Bool { speed < 0.0 }
}

/// Ptolemaic aspects with their exact angles and base orbs (degrees).
public enum AspectType: String, CaseIterable, Sendable {
    case conjunction = "CONJUNCTION", sextile = "SEXTILE", square = "SQUARE", trine = "TRINE", opposition = "OPPOSITION"

    private var row: (angle: Double, orb: Double, displayName: String, glyph: String, verb: String) {
        switch self {
        case .conjunction: return (0.0, 8.0, "Conjunction", "☌", "conjunct")
        case .sextile: return (60.0, 5.0, "Sextile", "⚹", "sextile")
        case .square: return (90.0, 7.0, "Square", "□", "square")
        case .trine: return (120.0, 7.0, "Trine", "△", "trine")
        case .opposition: return (180.0, 8.0, "Opposition", "☍", "opposite")
        }
    }

    public var angle: Double { row.angle }
    public var orb: Double { row.orb }
    public var displayName: String { row.displayName }
    public var glyph: String { row.glyph }
    public var verb: String { row.verb }

    /// Extra orb when the Sun or Moon is involved (the luminaries are given wider orbs).
    public static let luminaryBonus = 2.0
}

public struct Aspect: Hashable, Sendable {
    public let a: Planet
    public let b: Planet
    public let type: AspectType
    /// Distance from exact, degrees (>= 0).
    public let orb: Double
    /// True when the faster body is moving toward exactness.
    public let applying: Bool

    public var label: String { "\(a.displayName) \(type.verb) \(b.displayName)" }
}

public struct NatalChart: Hashable, Sendable {
    public let birth: BirthData
    public let bodies: [Body]
    /// nil when the birth time is unknown.
    public let ascendant: Double?
    public let midheaven: Double?
    public let houseSystem: HouseSystem
    /// Twelve cusp longitudes (cusp of house 1 first); nil when the birth time is unknown.
    public let houses: [Double]?
    public let aspects: [Aspect]
    /// True when Placidus was requested but undefined at this latitude (Porphyry used).
    public let housesFellBack: Bool
    /// Unknown time only: true when the Moon changes sign during the birth date, so its sign is uncertain.
    public let moonSignUncertain: Bool

    public func body(_ planet: Planet) -> Body { bodies.first { $0.planet == planet }! }
    public var sun: Body { body(.sun) }
    public var moon: Body { body(.moon) }
    public var sunSign: ZodiacSign { sun.sign }
    public var moonSign: ZodiacSign { moon.sign }
    public var risingSign: ZodiacSign? { ascendant.map(ZodiacSign.of) }

    /// Computes a chart. Whole-sign houses are the product default.
    public static func compute(_ birth: BirthData, houseSystem: HouseSystem = .wholeSign) -> NatalChart {
        let instant = birth.instant
        let houseResult: HouseResult? = birth.timeKnown
            ? Houses.compute(instant, latitude: birth.latitude, longitude: birth.longitude, system: houseSystem) : nil
        let bodies = Planet.allCases.map { planet -> Body in
            let p = Ephemeris.position(planet, instant)
            return Body(planet: planet, longitude: p.longitude, latitude: p.latitude, speed: p.speed,
                        house: houseResult.map { houseFor(p.longitude, $0) })
        }
        var moonUncertain = false
        if !birth.timeKnown {
            let dayStart = birth.date.atStartOfDay(birth.zone)
            let dayEnd = birth.date.plusDays(1).atStartOfDay(birth.zone).addingTimeInterval(-1)
            moonUncertain = ZodiacSign.of(Ephemeris.position(.moon, dayStart).longitude) !=
                ZodiacSign.of(Ephemeris.position(.moon, dayEnd).longitude)
        }
        return NatalChart(
            birth: birth, bodies: bodies, ascendant: houseResult?.ascendant, midheaven: houseResult?.midheaven,
            houseSystem: houseSystem, houses: houseResult?.cusps, aspects: Aspects.between(bodies),
            housesFellBack: houseResult?.fellBackToPorphyry ?? false, moonSignUncertain: moonUncertain)
    }

    static func houseFor(_ longitude: Double, _ houses: HouseResult) -> Int {
        switch houses.system {
        case .wholeSign:
            return Math.floorMod(ZodiacSign.of(longitude).ordinal - ZodiacSign.of(houses.ascendant).ordinal, 12) + 1
        default:
            return Houses.houseOf(longitude, cusps: houses.cusps)
        }
    }
}

/// Kotlin's stable sortedBy.
extension Array {
    func stableSorted<K: Comparable>(by key: (Element) -> K) -> [Element] {
        enumerated().sorted { l, r in
            let a = key(l.element), b = key(r.element)
            return a < b || (a == b && l.offset < r.offset)
        }.map(\.element)
    }
}

/// Aspect finding between the bodies of one chart, or between two sets (transits, synastry).
public enum Aspects {
    private static let speedProbeDays = 0.01

    public static func orbFor(_ type: AspectType, _ a: Planet, _ b: Planet) -> Double {
        let luminary = a == .sun || a == .moon || b == .sun || b == .moon
        return type.orb + (luminary ? AspectType.luminaryBonus : 0.0)
    }

    /// The tightest Ptolemaic aspect between two longitudes within [orbFor], or nil (first of
    /// equal deviations in AspectType order, as Kotlin's minByOrNull).
    public static func find(_ a: Planet, _ lonA: Double, _ b: Planet, _ lonB: Double) -> (type: AspectType, deviation: Double)? {
        let sep = Angles.separation(lonA, lonB)
        var best: (type: AspectType, deviation: Double)?
        for type in AspectType.allCases {
            let dev = abs(sep - type.angle)
            guard dev <= orbFor(type, a, b) else { continue }
            if best == nil || dev < best!.deviation { best = (type, dev) }
        }
        return best
    }

    public static func between(_ bodies: [Body]) -> [Aspect] {
        var out: [Aspect] = []
        for i in bodies.indices {
            for j in (i + 1)..<bodies.count {
                let x = bodies[i], y = bodies[j]
                guard let (type, dev) = find(x.planet, x.longitude, y.planet, y.longitude) else { continue }
                let later = abs(Angles.separation(x.longitude + x.speed * speedProbeDays, y.longitude + y.speed * speedProbeDays) - type.angle)
                out.append(Aspect(a: x.planet, b: y.planet, type: type, orb: dev, applying: later < dev))
            }
        }
        return out.stableSorted { $0.orb }
    }

    /// Aspects from moving bodies (e.g. today's sky) to fixed natal bodies.
    public static func crossing(_ moving: [Body], _ fixed: [Body]) -> [Aspect] {
        var out: [Aspect] = []
        for m in moving {
            for f in fixed {
                guard let (type, dev) = find(m.planet, m.longitude, f.planet, f.longitude) else { continue }
                let later = abs(Angles.separation(m.longitude + m.speed * speedProbeDays, f.longitude) - type.angle)
                out.append(Aspect(a: m.planet, b: f.planet, type: type, orb: dev, applying: later < dev))
            }
        }
        return out.stableSorted { $0.orb }
    }
}

/// Today's sky relative to a natal chart.
public struct Transits: Hashable, Sendable {
    public let instant: Date
    public let bodies: [Body]
    public let aspectsToNatal: [Aspect]

    /// Transit-to-natal aspects use tighter orbs: half the natal orb.
    public static func compute(_ natal: NatalChart, _ instant: Date) -> Transits {
        let sky = Planet.allCases.map { planet -> Body in
            let p = Ephemeris.position(planet, instant)
            var house: Int?
            if let cusps = natal.houses {
                if natal.houseSystem == .wholeSign, let asc = natal.ascendant {
                    house = Math.floorMod(ZodiacSign.of(p.longitude).ordinal - ZodiacSign.of(asc).ordinal, 12) + 1
                } else {
                    house = Houses.houseOf(p.longitude, cusps: cusps)
                }
            }
            return Body(planet: planet, longitude: p.longitude, latitude: p.latitude, speed: p.speed, house: house)
        }
        let aspects = Aspects.crossing(sky, natal.bodies).filter { $0.orb <= Aspects.orbFor($0.type, $0.a, $0.b) / 2.0 }
        return Transits(instant: instant, bodies: sky, aspectsToNatal: aspects)
    }

    /// Convenience for "the sky at noon today" in a zone.
    public static func noonOf(_ date: LocalDate, _ zone: TimeZone) -> Date {
        ZonedDateTime.instant(date, LocalTime(12, 0), zone)
    }
}
