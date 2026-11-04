import Foundation

public enum Element: String, CaseIterable, Sendable {
    case fire = "FIRE", earth = "EARTH", air = "AIR", water = "WATER"
    public var displayName: String { rawValue.prefix(1) + rawValue.dropFirst().lowercased() }
}

public enum Modality: String, CaseIterable, Sendable {
    case cardinal = "CARDINAL", fixed = "FIXED", mutable = "MUTABLE"
    public var displayName: String { rawValue.prefix(1) + rawValue.dropFirst().lowercased() }
}

/// The twelve tropical signs (port of chart/ZodiacSign.kt). [key] matches the art asset names
/// (art/zodiac/<key>.png).
public enum ZodiacSign: Int, CaseIterable, Sendable {
    case aries, taurus, gemini, cancer, leo, virgo, libra, scorpio, sagittarius, capricorn, aquarius, pisces

    private static let table: [(String, String, Element, Modality)] = [
        ("Aries", "♈", .fire, .cardinal), ("Taurus", "♉", .earth, .fixed), ("Gemini", "♊", .air, .mutable),
        ("Cancer", "♋", .water, .cardinal), ("Leo", "♌", .fire, .fixed), ("Virgo", "♍", .earth, .mutable),
        ("Libra", "♎", .air, .cardinal), ("Scorpio", "♏", .water, .fixed), ("Sagittarius", "♐", .fire, .mutable),
        ("Capricorn", "♑", .earth, .cardinal), ("Aquarius", "♒", .air, .fixed), ("Pisces", "♓", .water, .mutable),
    ]

    public var displayName: String { Self.table[rawValue].0 }
    public var glyph: String { Self.table[rawValue].1 }
    public var element: Element { Self.table[rawValue].2 }
    public var modality: Modality { Self.table[rawValue].3 }
    /// Kotlin's ordinal (Aries = 0).
    public var ordinal: Int { rawValue }
    /// Lower-case key ("leo"), as Kotlin's name.lowercase().
    public var key: String { displayName.lowercased() }

    /// Sign containing an ecliptic longitude (degrees).
    public static func of(_ longitude: Double) -> ZodiacSign {
        ZodiacSign(rawValue: (Angles.norm360(longitude) / 30.0).rounded(.down).toInt().coerceIn(0, 11))!
    }

    /// Degree within the sign, [0, 30).
    public static func degreeIn(_ longitude: Double) -> Double {
        Angles.norm360(longitude).truncatingRemainder(dividingBy: 30.0)
    }

    /// The sign with this key, case-insensitive; nil when there is none (Kotlin's fromKey throws).
    public static func fromKey(_ key: String) -> ZodiacSign? {
        let k = key.lowercased()
        return allCases.first { $0.key == k }
    }
}

/// "14°07′ Leo" style label (truncated, not rounded, so it never shows 30°).
public func formatLongitude(_ longitude: Double) -> String {
    let sign = ZodiacSign.of(longitude)
    let inSign = ZodiacSign.degreeIn(longitude)
    let totalMinutes = (inSign * 60.0).rounded(.down).toInt()
    let minutes = totalMinutes % 60
    return "\(totalMinutes / 60)°\(minutes < 10 ? "0" : "")\(minutes)′ \(sign.displayName)"
}
