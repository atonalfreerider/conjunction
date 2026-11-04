import Foundation

/// The ten bodies of a natal chart, in conventional order.
public enum Planet: String, CaseIterable, Sendable, Codable {
    case sun, moon, mercury, venus, mars, jupiter, saturn, uranus, neptune, pluto

    public var displayName: String {
        switch self {
        case .sun: return "Sun"
        case .moon: return "Moon"
        case .mercury: return "Mercury"
        case .venus: return "Venus"
        case .mars: return "Mars"
        case .jupiter: return "Jupiter"
        case .saturn: return "Saturn"
        case .uranus: return "Uranus"
        case .neptune: return "Neptune"
        case .pluto: return "Pluto"
        }
    }

    public var glyph: String {
        switch self {
        case .sun: return "☉"
        case .moon: return "☽"
        case .mercury: return "☿"
        case .venus: return "♀"
        case .mars: return "♂"
        case .jupiter: return "♃"
        case .saturn: return "♄"
        case .uranus: return "♅"
        case .neptune: return "♆"
        case .pluto: return "♇"
        }
    }

    /// Lower-case key used in JSON (golden files, uploaded summaries).
    public var key: String { rawValue }

    /// Kotlin's enum name (MERCURY ...), also the VSOP section name.
    var kotlinName: String { rawValue.uppercased() }

    /// Kotlin's ordinal.
    public var ordinal: Int { Planet.allCases.firstIndex(of: self)! }
}

/// Apparent geocentric ecliptic coordinates of date.
public struct EclipticPosition: Hashable, Sendable {
    /// Apparent longitude, true equinox of date, degrees [0, 360).
    public let longitude: Double
    /// Apparent latitude, degrees.
    public let latitude: Double
    /// Geocentric distance: AU for planets and the Sun, km for the Moon.
    public let distance: Double
    /// Daily motion in longitude, degrees per day (negative = retrograde).
    public let speed: Double
}

/// Apparent geocentric positions (port of astro/Ephemeris.kt), accurate to a few arcseconds
/// against Swiss Ephemeris over 1900-2100 (golden-tested to 0.05 deg):
///  - Sun and Mercury-Neptune: VSOP87D (ecliptic of date) truncated at ~2e-7 rad, Earth and planet
///    both taken at t - light-time (light-time AND annual aberration, Meeus ch. 33), FK5 frame
///    correction (Meeus 32.3) and nutation in longitude.
///  - Moon: Meeus ch. 47 (ELP-2000/82 main terms) + nutation.
///  - Pluto: Meeus ch. 37 (J2000 heliocentric), precessed to the ecliptic of date (Meeus 21.5).
/// Nutation: IAU 1980 leading terms (Meeus ch. 22 low-precision form, < 0.5").
public enum Ephemeris {
    private static let lightTimeDaysPerAu = 0.0057755183
    private static let speedStepDays = 0.05
    private static let deg = Angles.deg
    private static let rad = Angles.rad
    private static let arcsec = Angles.arcsec

    /// Mean obliquity, nutation in longitude and true obliquity at a JDE (degrees).
    public struct Nutation: Hashable, Sendable {
        public let deltaPsi: Double
        public let deltaEpsilon: Double
        public let meanObliquity: Double
        public var trueObliquity: Double { meanObliquity + deltaEpsilon }
    }

    public static func position(_ planet: Planet, _ instant: Date) -> EclipticPosition {
        let jde = TimeScales.julianDayTt(instant)
        let now = positionAtJde(planet, jde)
        let before = positionAtJde(planet, jde - speedStepDays)
        let after = positionAtJde(planet, jde + speedStepDays)
        let speed = Angles.norm180(after.lon - before.lon) / (2.0 * speedStepDays)
        return EclipticPosition(longitude: now.lon, latitude: now.lat, distance: now.dist, speed: speed)
    }

    public static func nutation(_ jde: Double) -> Nutation {
        let t = TimeScales.centuriesTt(jde)
        let omega = (125.04452 - 1934.136261 * t + 0.0020708 * t * t + t * t * t / 450_000.0) * deg
        let l = (280.4665 + 36_000.7698 * t) * deg
        let lp = (218.3165 + 481_267.8813 * t) * deg
        let dPsi = (-17.20 * sin(omega) - 1.32 * sin(2 * l) - 0.23 * sin(2 * lp) + 0.21 * sin(2 * omega)) * arcsec
        let dEps = (9.20 * cos(omega) + 0.57 * cos(2 * l) + 0.10 * cos(2 * lp) - 0.09 * cos(2 * omega)) * arcsec
        let eps0 = 23.0 + 26.0 / 60.0 + 21.448 / 3600.0 +
            (-46.8150 * t - 0.00059 * t * t + 0.001813 * t * t * t) * arcsec
        return Nutation(deltaPsi: dPsi, deltaEpsilon: dEps, meanObliquity: eps0)
    }

    /// (longitude deg, latitude deg, distance) for one JDE.
    private static func positionAtJde(_ planet: Planet, _ jde: Double) -> (lon: Double, lat: Double, dist: Double) {
        let nut = nutation(jde)
        if planet == .moon {
            let m = moonMeanEquinox(jde)
            return (Angles.norm360(m.lon + nut.deltaPsi), m.lat, m.dist)
        }
        // Light-time iteration. Earth is taken at the same retarded time as the body, so the result
        // also includes annual aberration (Meeus ch. 33).
        var tau = 0.0
        var geo = Vec3(x: 0, y: 0, z: 0)
        for _ in 0..<3 {
            let t = jde - tau
            let earth = vsopRect("EARTH", t)
            let body = heliocentricRect(planet, t)
            geo = body - earth
            tau = lightTimeDaysPerAu * geo.length
        }
        var lon = atan2(geo.y, geo.x) * rad
        let lat = atan2(geo.z, (geo.x * geo.x + geo.y * geo.y).squareRoot()) * rad
        // VSOP87 dynamical ecliptic -> FK5 (Meeus 32.3).
        let tc = TimeScales.centuriesTt(jde)
        let lp = (lon - 1.397 * tc - 0.00031 * tc * tc) * deg
        lon += (-0.09033 + 0.03916 * (cos(lp) + sin(lp)) * tan(lat * deg)) * arcsec
        let latFk5 = lat + 0.03916 * (cos(lp) - sin(lp)) * arcsec
        return (Angles.norm360(lon + nut.deltaPsi), latFk5, geo.length)
    }

    struct Vec3 {
        let x: Double, y: Double, z: Double
        static func - (a: Vec3, b: Vec3) -> Vec3 { Vec3(x: a.x - b.x, y: a.y - b.y, z: a.z - b.z) }
        var length: Double { (x * x + y * y + z * z).squareRoot() }
    }

    /// Heliocentric rectangular coordinates, ecliptic and mean equinox of date, AU.
    private static func heliocentricRect(_ planet: Planet, _ jde: Double) -> Vec3 {
        switch planet {
        case .sun: return Vec3(x: 0, y: 0, z: 0)
        case .pluto: return plutoOfDate(jde)
        case .moon: fatalError("Moon is geocentric")
        default: return vsopRect(planet.kotlinName, jde)
        }
    }

    /// VSOP87D heliocentric spherical (L, B radians; R AU) for a body at a JDE.
    static func vsopSpherical(_ body: String, _ jde: Double) -> (l: Double, b: Double, r: Double) {
        let tau = (jde - TimeScales.j2000) / 365_250.0
        guard let data = EphemerisData.shared.vsop[body] else { fatalError("No VSOP87 series for \(body)") }
        func sum(_ variable: Int) -> Double {
            var total = 0.0
            var tPow = 1.0
            for series in data.series[variable] {
                var s = 0.0
                for k in series.a.indices { s += series.a[k] * cos(series.b[k] + series.c[k] * tau) }
                total += s * tPow
                tPow *= tau
            }
            return total
        }
        return (Angles.normRadians(sum(0)), sum(1), sum(2))
    }

    private static func vsopRect(_ body: String, _ jde: Double) -> Vec3 {
        let (l, b, r) = vsopSpherical(body, jde)
        return Vec3(x: r * cos(b) * cos(l), y: r * cos(b) * sin(l), z: r * sin(b))
    }

    /// Meeus ch. 47: geocentric Moon, mean equinox of date (no nutation). Degrees, degrees, km.
    static func moonMeanEquinox(_ jde: Double) -> (lon: Double, lat: Double, dist: Double) {
        let t = TimeScales.centuriesTt(jde)
        func poly(_ c: Double...) -> Double {
            var r = 0.0
            for k in c.indices.reversed() { r = r * t + c[k] }
            return r
        }
        let lp = Angles.norm360(poly(218.3164477, 481_267.88123421, -0.0015786, 1.0 / 538_841.0, -1.0 / 65_194_000.0))
        let d = Angles.norm360(poly(297.8501921, 445_267.1114034, -0.0018819, 1.0 / 545_868.0, -1.0 / 113_065_000.0))
        let m = Angles.norm360(poly(357.5291092, 35_999.0502909, -0.0001535, 1.0 / 24_490_000.0))
        let mp = Angles.norm360(poly(134.9633964, 477_198.8675055, 0.0087414, 1.0 / 69_699.0, -1.0 / 14_712_000.0))
        let f = Angles.norm360(poly(93.2720950, 483_202.0175233, -0.0036539, -1.0 / 3_526_000.0, 1.0 / 863_310_000.0))
        let a1 = (119.75 + 131.849 * t) * deg
        let a2 = (53.09 + 479_264.290 * t) * deg
        let a3 = (313.45 + 481_266.484 * t) * deg
        let e = 1.0 - 0.002516 * t - 0.0000074 * t * t
        let tables = EphemerisData.shared.moon
        var sl = 0.0
        var sr = 0.0
        for row in tables.lr {
            let arg = (Double(row[0]) * d + Double(row[1]) * m + Double(row[2]) * mp + Double(row[3]) * f) * deg
            let ef = eccentricityFactor(row[1], e)
            sl += Double(row[4]) * ef * sin(arg)
            sr += Double(row[5]) * ef * cos(arg)
        }
        var sb = 0.0
        for row in tables.b {
            let arg = (Double(row[0]) * d + Double(row[1]) * m + Double(row[2]) * mp + Double(row[3]) * f) * deg
            sb += Double(row[4]) * eccentricityFactor(row[1], e) * sin(arg)
        }
        let lpr = lp * deg
        let fr = f * deg
        let mpr = mp * deg
        sl += 3958 * sin(a1) + 1962 * sin(lpr - fr) + 318 * sin(a2)
        sb += -2235 * sin(lpr) + 382 * sin(a3) + 175 * sin(a1 - fr) + 175 * sin(a1 + fr) +
            127 * sin(lpr - mpr) - 115 * sin(lpr + mpr)
        return (Angles.norm360(lp + sl * 1e-6), sb * 1e-6, 385_000.56 + sr * 1e-3)
    }

    private static func eccentricityFactor(_ mMultiple: Int, _ e: Double) -> Double {
        switch mMultiple {
        case 0: return 1.0
        case 1, -1: return e
        default: return e * e
        }
    }

    /// Meeus ch. 37 Pluto: heliocentric ecliptic J2000 (l, b degrees; r AU). Valid 1885-2099.
    static func plutoJ2000(_ jde: Double) -> (l: Double, b: Double, r: Double) {
        let t = TimeScales.centuriesTt(jde)
        let j = 34.35 + 3034.9057 * t
        let s = 50.08 + 1222.1138 * t
        let p = 238.96 + 144.9600 * t
        var l = 0.0, b = 0.0, r = 0.0
        for row in EphemerisData.shared.pluto {
            let alpha = (row[0] * j + row[1] * s + row[2] * p) * deg
            let sa = sin(alpha)
            let ca = cos(alpha)
            l += row[3] * sa + row[4] * ca
            b += row[5] * sa + row[6] * ca
            r += row[7] * sa + row[8] * ca
        }
        return (238.958116 + 144.96 * t + l, -3.908239 + b, 40.7241346 + r)
    }

    /// Pluto heliocentric rectangular, ecliptic and mean equinox of date.
    private static func plutoOfDate(_ jde: Double) -> Vec3 {
        let (l0, b0, r) = plutoJ2000(jde)
        let (l, b) = precessEclipticFromJ2000(l0, b0, jde)
        let lr = l * deg
        let br = b * deg
        return Vec3(x: r * cos(br) * cos(lr), y: r * cos(br) * sin(lr), z: r * sin(br))
    }

    /// Rigorous ecliptic precession from J2000.0 to the mean ecliptic of date (Meeus 21.5, 21.6).
    static func precessEclipticFromJ2000(_ lonDeg: Double, _ latDeg: Double, _ jde: Double) -> (Double, Double) {
        let t = TimeScales.centuriesTt(jde)  // T (start epoch J2000) = 0
        let eta = (47.0029 * t - 0.03302 * t * t + 0.000060 * t * t * t) * arcsec * deg
        let bigPi = (174.876384 - 869.8089 * arcsec * t + 0.03536 * arcsec * t * t) * deg
        let p = (5029.0966 * t + 1.11113 * t * t - 0.000006 * t * t * t) * arcsec * deg
        let lon = lonDeg * deg
        let lat = latDeg * deg
        let a = cos(eta) * cos(lat) * sin(bigPi - lon) - sin(eta) * sin(lat)
        let bb = cos(lat) * cos(bigPi - lon)
        let c = cos(eta) * sin(lat) + sin(eta) * cos(lat) * sin(bigPi - lon)
        let newLon = p + bigPi - atan2(a, bb)
        return (Angles.norm360(newLon * rad), asin(c.coerceIn(-1.0, 1.0)) * rad)
    }

    /// Greenwich apparent sidereal time in degrees (Meeus 12.4 + equation of the equinoxes).
    public static func greenwichApparentSiderealDegrees(_ instant: Date) -> Double {
        let jd = TimeScales.julianDayUt(instant)
        let t = (jd - TimeScales.j2000) / 36_525.0
        let gmst = 280.46061837 + 360.98564736629 * (jd - TimeScales.j2000) + 0.000387933 * t * t - t * t * t / 38_710_000.0
        let nut = nutation(TimeScales.julianDayTt(instant))
        return Angles.norm360(gmst + nut.deltaPsi * cos(nut.trueObliquity * deg))
    }
}
