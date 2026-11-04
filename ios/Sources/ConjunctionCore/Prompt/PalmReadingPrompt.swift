import Foundation

/// Prompt for the on-device palm reading, built only from measured PalmFeatures (no image needed).
/// Port of prompt/PalmReadingPrompt.kt.
public enum PalmReadingPrompt {
    /// Palm-specific guardrails on top of HoroscopePrompt.guardrails: no lifespan, health or fate claims.
    public static let palmGuardrails =
        "Palmistry is folklore: never connect any line to lifespan, health, illness, accidents, wealth or death, and say nothing is fixed."

    public static func describe(_ line: PalmLine, _ f: LineFeature) -> String {
        if !f.present { return "\(line.displayName) line: not clearly visible" }
        let length: String
        switch f.lengthClass {
        case .long: length = "long"
        case .medium: length = "medium length"
        case .short: length = "short"
        case .absent: length = "not visible"
        }
        let shape = f.isCurved ? "curved" : "fairly straight"
        let depth = f.depth >= LineFeature.deepFrom ? ", deep" : (f.depth < LineFeature.faintBelow ? ", faint" : "")
        var extras: [String] = []
        if f.breaks > 0 { extras.append("\(f.breaks) break\(f.breaks > 1 ? "s" : "")") }
        if f.forked { extras.append("forked end") }
        let extra = extras.isEmpty ? "" : ", " + extras.joined(separator: ", ")
        return "\(line.displayName) line: \(length), \(shape)\(depth)\(extra)"
    }

    public static func fingerNote(_ features: PalmFeatures) -> String {
        let r = features.fingers.indexToRing
        if r > 1.02 { return "index finger longer than ring finger" }
        if r < 0.98 { return "ring finger longer than index finger" }
        return "index and ring fingers about even"
    }

    public static func build(_ features: PalmFeatures) -> String {
        let lines = PalmLine.allCases.map { describe($0, features.line($0)) }.joined(separator: "; ")
        var mounts: String?
        if !features.mounts.isEmpty {
            // Descending prominence; equal values in Mount order (Kotlin keeps its map's insertion
            // order there, which the app builds in Mount order).
            let named = features.mounts.filter { $0.value >= 0.6 }
                .sorted { $0.value != $1.value ? $0.value > $1.value : $0.key.rawValue < $1.key.rawValue }
                .map { "Mount of \($0.key.displayName)" }.joined(separator: ", ")
            mounts = "Prominent mounts: " + (named.isEmpty ? "none stand out" : named) + "."
        }
        var out = [
            "Write a playful palm reading as one paragraph of 100 to 150 words.",
            "Hand: \(features.hand.name.lowercased()) hand, \(features.handShape.displayName) hand shape (palmistry's four elements).",
            "Lines: \(lines).",
            "Fingers: \(fingerNote(features)).",
        ]
        if let mounts { out.append(mounts) }
        if features.quality < 0.5 { out.append("The photo was unclear, so keep the reading light and general.") }
        out += [HoroscopePrompt.style, HoroscopePrompt.guardrails, palmGuardrails]
        return out.joined(separator: "\n")
    }
}
