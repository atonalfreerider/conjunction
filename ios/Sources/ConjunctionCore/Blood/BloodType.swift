import Foundation

/// ABO blood group (port of blood/BloodType.kt). [key] matches art/blood/<key>.png.
public enum BloodType: String, CaseIterable, Sendable {
    case a = "A", b = "B", ab = "AB", o = "O"

    public var displayName: String { rawValue }
    public var key: String { rawValue.lowercased() }
    /// Kotlin's enum name.
    public var name: String { rawValue }

    /// Parses "A", "ab", "O+" or "B-" style input (the Rh sign is ignored here; see RhFactor.parse).
    public static func parse(_ text: String) -> BloodType? {
        switch text.trim().uppercased().trimmingEnd(["+", "-", " "]) {
        case "A": return .a
        case "B": return .b
        case "AB": return .ab
        case "O", "0": return .o
        default: return nil
        }
    }
}

/// Optional Rh factor. It does not change the ketsueki-gata reading or the compatibility score.
public enum RhFactor: String, CaseIterable, Sendable {
    case positive = "POSITIVE", negative = "NEGATIVE"

    public var sign: String { self == .positive ? "+" : "\u{2212}" }

    public static func parse(_ text: String) -> RhFactor? {
        let t = text.trim()
        if t.hasSuffix("+") { return .positive }
        if t.hasSuffix("-") || t.hasSuffix("\u{2212}") { return .negative }
        return nil
    }
}

/// A blood-type personality profile (East-Asian ketsueki-gata folklore; entertainment only).
public struct BloodTypeTraits: Hashable, Sendable {
    public let type: BloodType
    public let title: String
    public let keywords: [String]
    public let strengths: String
    public let growth: String
    public let love: String
    public let work: String
    public let summary: String
}

public enum BloodTypeReading {
    /// Bundled traits for a blood type (text from content_en.json, shared with Android).
    public static func traits(_ type: BloodType) -> BloodTypeTraits {
        let m = Content.obj(Content.obj(Content.root["blood"])[type.key])
        return BloodTypeTraits(type: type, title: m["title"] as! String, keywords: Content.strings(m["keywords"]),
                               strengths: m["strengths"] as! String, growth: m["growth"] as! String,
                               love: m["love"] as! String, work: m["work"] as! String, summary: m["summary"] as! String)
    }

    /// "Type AB+" style label.
    public static func label(_ type: BloodType, _ rh: RhFactor?) -> String { "Type \(type.displayName)\(rh?.sign ?? "")" }
}
