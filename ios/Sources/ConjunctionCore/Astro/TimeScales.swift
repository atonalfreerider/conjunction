import Foundation

/// Julian dates and Delta T (port of astro/TimeScales.kt). UT is taken as UTC (the < 1 s difference
/// is irrelevant at our accuracy). Terrestrial Time = UT + Delta T. An instant is a Foundation Date.
public enum TimeScales {
    public static let jdUnixEpoch = 2_440_587.5
    public static let j2000 = 2_451_545.0

    public static func julianDayUt(_ instant: Date) -> Double {
        jdUnixEpoch + Double(instant.epochSecond) / 86_400.0 + instant.nano / 86_400.0e9
    }

    public static func instantFromJulianDayUt(_ jd: Double) -> Date {
        Date(timeIntervalSince1970: (jd - jdUnixEpoch) * 86_400.0)
    }

    /// Julian Ephemeris Day (TT) for an instant.
    public static func julianDayTt(_ instant: Date) -> Double { julianDayUt(instant) + deltaTSeconds(instant) / 86_400.0 }

    /// Julian centuries of TT since J2000.0.
    public static func centuriesTt(_ jde: Double) -> Double { (jde - j2000) / 36_525.0 }

    /// Delta T (TT - UT) in seconds. 1900-2101: linear interpolation in the yearly Swiss Ephemeris
    /// table bundled as `delta_t.txt`. Outside the table: Espenak & Meeus (NASA, 2006) polynomials.
    public static func deltaTSeconds(_ instant: Date) -> Double {
        let utc = ZonedDateTime(instant, utcZone)
        let year = utc.year
        let table = EphemerisData.shared.deltaT
        let first = table.firstYear
        let last = first + table.seconds.count - 1
        if year >= first && year < last {
            let start = jdAtJan1(year)
            let end = jdAtJan1(year + 1)
            let f = (julianDayUt(instant) - start) / (end - start)
            let a = table.seconds[year - first]
            let b = table.seconds[year - first + 1]
            return a + (b - a) * f
        }
        let y = Double(year) + (Double(utc.dayOfYear) - 0.5) / (utc.date.isLeapYear ? 366.0 : 365.0)
        return espenakMeeus(y)
    }

    static let utcZone = TimeZone(identifier: "UTC")!

    private static func jdAtJan1(_ year: Int) -> Double { jdUnixEpoch + Double(LocalDate(year, 1, 1).epochDay) }

    /// Espenak & Meeus polynomial expressions for Delta T, decimal year [y].
    public static func espenakMeeus(_ y: Double) -> Double {
        if y < 1860.0 {
            let t = y - 1800.0
            return 13.72 - 0.332447 * t + 0.0068612 * t * t + 0.0041116 * t * t * t - 0.00037436 * t * t * t * t +
                0.0000121272 * pow(t, 5.0) - 0.0000001699 * pow(t, 6.0) + 0.000000000875 * pow(t, 7.0)
        } else if y < 1900.0 {
            let t = y - 1860.0
            return 7.62 + 0.5737 * t - 0.251754 * t * t + 0.01680668 * t * t * t - 0.0004473624 * t * t * t * t +
                pow(t, 5.0) / 233_174.0
        } else if y < 1920.0 {
            let t = y - 1900.0
            return -2.79 + 1.494119 * t - 0.0598939 * t * t + 0.0061966 * t * t * t - 0.000197 * t * t * t * t
        } else if y < 1941.0 {
            let t = y - 1920.0
            return 21.20 + 0.84493 * t - 0.076100 * t * t + 0.0020936 * t * t * t
        } else if y < 1961.0 {
            let t = y - 1950.0
            return 29.07 + 0.407 * t - t * t / 233.0 + t * t * t / 2547.0
        } else if y < 1986.0 {
            let t = y - 1975.0
            return 45.45 + 1.067 * t - t * t / 260.0 - t * t * t / 718.0
        } else if y < 2005.0 {
            let t = y - 2000.0
            return 63.86 + 0.3345 * t - 0.060374 * t * t + 0.0017275 * t * t * t + 0.000651814 * t * t * t * t +
                0.00002373599 * pow(t, 5.0)
        } else if y < 2050.0 {
            let t = y - 2000.0
            return 62.92 + 0.32217 * t + 0.005589 * t * t
        } else if y < 2150.0 {
            let u = (y - 1820.0) / 100.0
            return -20.0 + 32.0 * u * u - 0.5628 * (2150.0 - y)
        } else {
            let u = (y - 1820.0) / 100.0
            return -20.0 + 32.0 * u * u
        }
    }
}
