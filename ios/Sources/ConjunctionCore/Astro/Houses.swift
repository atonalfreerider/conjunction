import Foundation

/// House systems offered by the chart engine (port of astro/Houses.kt).
public enum HouseSystem: String, CaseIterable, Sendable {
    /// Default. Each sign is one house, starting with the Ascendant's sign.
    case wholeSign = "WHOLE_SIGN"
    /// Placidus. Undefined inside the polar circles; there the engine returns Porphyry cusps and
    /// reports `HouseResult.fellBackToPorphyry`.
    case placidus = "PLACIDUS"
    /// Porphyry: each quadrant between the angles trisected in ecliptic longitude.
    case porphyry = "PORPHYRY"
}

public struct HouseResult: Hashable, Sendable {
    public let system: HouseSystem
    public let ascendant: Double
    public let midheaven: Double
    /// Twelve cusp longitudes, cusps[0] = 1st house.
    public let cusps: [Double]
    public var fellBackToPorphyry: Bool = false
}

/// Ascendant, Midheaven and house cusps (degrees, true ecliptic of date).
public enum Houses {
    private static let deg = Angles.deg
    private static let rad = Angles.rad

    /// Local apparent sidereal time (RAMC), degrees, for east-positive longitude.
    public static func ramc(_ instant: Date, longitude: Double) -> Double {
        Angles.norm360(Ephemeris.greenwichApparentSiderealDegrees(instant) + longitude)
    }

    public static func compute(_ instant: Date, latitude: Double, longitude: Double, system: HouseSystem) -> HouseResult {
        let eps = Ephemeris.nutation(TimeScales.julianDayTt(instant)).trueObliquity
        return computeFromRamc(ramc(instant, longitude: longitude), latitude: latitude, obliquity: eps, system: system)
    }

    public static func computeFromRamc(_ ramcDeg: Double, latitude: Double, obliquity: Double, system: HouseSystem) -> HouseResult {
        let mc = midheaven(ramcDeg, obliquity: obliquity)
        var asc = ascendant(ramcDeg, latitude: latitude, obliquity: obliquity)
        // Inside the polar circles the formula can return the descending point; like Swiss
        // Ephemeris, keep the Ascendant in the eastern half (0..180 deg ahead of the MC).
        if abs(latitude) >= 90.0 - obliquity && Angles.norm360(asc - mc) > 180.0 {
            asc = Angles.norm360(asc + 180.0)
        }
        switch system {
        case .wholeSign:
            let first = (asc / 30.0).rounded(.down) * 30.0
            return HouseResult(system: system, ascendant: asc, midheaven: mc,
                               cusps: (0..<12).map { Angles.norm360(first + 30.0 * Double($0)) })
        case .porphyry:
            return HouseResult(system: system, ascendant: asc, midheaven: mc, cusps: porphyry(asc, mc))
        case .placidus:
            if let cusps = placidus(ramcDeg, latitude, obliquity, asc, mc) {
                return HouseResult(system: system, ascendant: asc, midheaven: mc, cusps: cusps)
            }
            return HouseResult(system: system, ascendant: asc, midheaven: mc, cusps: porphyry(asc, mc), fellBackToPorphyry: true)
        }
    }

    /// Ecliptic longitude culminating on the meridian.
    public static func midheaven(_ ramcDeg: Double, obliquity: Double) -> Double {
        let r = ramcDeg * deg
        return Angles.norm360(atan2(sin(r), cos(r) * cos(obliquity * deg)) * rad)
    }

    /// Ecliptic longitude rising on the eastern horizon.
    public static func ascendant(_ ramcDeg: Double, latitude: Double, obliquity: Double) -> Double {
        let r = ramcDeg * deg
        let e = obliquity * deg
        let phi = latitude * deg
        return Angles.norm360(atan2(cos(r), -(sin(e) * tan(phi) + cos(e) * sin(r))) * rad)
    }

    private static func porphyry(_ asc: Double, _ mc: Double) -> [Double] {
        let ic = Angles.norm360(mc + 180.0)
        let q1 = Angles.norm360(ic - asc)  // houses 1-3 span (Asc -> IC)
        var cusps = [Double](repeating: 0, count: 12)
        cusps[0] = asc
        cusps[1] = Angles.norm360(asc + q1 / 3.0)
        cusps[2] = Angles.norm360(asc + 2.0 * q1 / 3.0)
        cusps[3] = ic
        let q4 = Angles.norm360(asc + 180.0 - ic)  // houses 4-6 (IC -> Desc)
        cusps[4] = Angles.norm360(ic + q4 / 3.0)
        cusps[5] = Angles.norm360(ic + 2.0 * q4 / 3.0)
        for k in 6...11 { cusps[k] = Angles.norm360(cusps[k - 6] + 180.0) }
        return cusps
    }

    /// Placidus cusps by fixed-point iteration on right ascension: a cusp's hour angle is a fixed
    /// fraction of its own semi-arc. nil where the semi-arc is undefined (circumpolar).
    private static func placidus(_ ramcDeg: Double, _ latitude: Double, _ obliquity: Double, _ asc: Double, _ mc: Double) -> [Double]? {
        let e = obliquity * deg
        let tanPhi = tan(latitude * deg)
        if abs(latitude) >= 90.0 - obliquity { return nil }

        func cusp(_ fraction: Double, _ aboveHorizon: Bool) -> Double? {
            // Initial guess: equal division of right ascension.
            var ra = aboveHorizon ? ramcDeg + 90.0 * fraction : ramcDeg + 180.0 - 90.0 * fraction
            for _ in 0..<50 {
                let lon = atan2(sin(ra * deg), cos(ra * deg) * cos(e)) * rad
                let dec = asin(sin(e) * sin(lon * deg))
                let x = tanPhi * tan(dec)
                if abs(x) >= 1.0 { return nil }
                let ad = asin(x) * rad  // ascensional difference
                let next = aboveHorizon ? ramcDeg + fraction * (90.0 + ad) : ramcDeg + 180.0 - fraction * (90.0 - ad)
                if abs(Angles.norm180(next - ra)) < 1e-9 { ra = next; break }
                ra = next
            }
            return Angles.norm360(atan2(sin(ra * deg), cos(ra * deg) * cos(e)) * rad)
        }

        guard let c11 = cusp(1.0 / 3.0, true), let c12 = cusp(2.0 / 3.0, true),
              let c2 = cusp(2.0 / 3.0, false), let c3 = cusp(1.0 / 3.0, false) else { return nil }
        var cusps = [Double](repeating: 0, count: 12)
        cusps[0] = asc; cusps[1] = c2; cusps[2] = c3; cusps[3] = Angles.norm360(mc + 180.0)
        cusps[9] = mc; cusps[10] = c11; cusps[11] = c12
        cusps[4] = Angles.norm360(c11 + 180.0); cusps[5] = Angles.norm360(c12 + 180.0)
        cusps[6] = Angles.norm360(asc + 180.0); cusps[7] = Angles.norm360(c2 + 180.0); cusps[8] = Angles.norm360(c3 + 180.0)
        return cusps
    }

    /// 1-based house of a longitude given 12 cusps (works for unequal houses).
    public static func houseOf(_ longitude: Double, cusps: [Double]) -> Int {
        for i in 0..<12 {
            let start = cusps[i]
            let end = cusps[(i + 1) % 12]
            let span = Angles.norm360(end - start)
            if Angles.norm360(longitude - start) < span { return i + 1 }
        }
        return 1
    }
}
