import Foundation

/// What the Compatibility view shows (SPEC 5d.4), computed from this phone's record of the person
/// (Android `ui/match/CompatibilityScreen.kt` `CompatView`). Pure, so it is tested on Linux.
public struct CompatView: Equatable, Sendable {
    public let score: StoredScore
    public let me: CompatProfile?
    public let them: CompatProfile?
    public let lines: [Compatibility.SynastryLine]
    public let chineseRelation: ChineseRelation?
    public let stemRelation: StemRelation?

    /// The wheel can be drawn: both charts are known.
    public var hasWheel: Bool { me != nil && them != nil }

    /// Always recomputes from both profiles when the peer's chart is stored (so a change of my
    /// birth data shows), else the stored numbers and no wheel.
    public static func of(_ subject: CompatSubject, me: CompatProfile?) -> CompatView {
        let them = subject.peerCompat?.toCompatProfile()
        if let me, let them {
            let r = Compatibility.score(me, them)
            return CompatView(score: StoredScore(r), me: me, them: them, lines: Compatibility.synastry(me, them),
                              chineseRelation: r.chineseRelation, stemRelation: r.stemRelation)
        }
        let theirAnimal = subject.animal.flatMap(ChineseAnimal.fromKey)
        var relation = subject.score.chineseRelation.flatMap(ChineseRelation.init(rawValue:))
        if relation == nil, let mine = me?.chineseAnimal, let theirAnimal {
            relation = Compatibility.chineseRelation(mine, theirAnimal)
        }
        return CompatView(score: subject.score, me: me, them: nil, lines: [], chineseRelation: relation,
                          stemRelation: subject.score.stemRelation.flatMap(StemRelation.init(rawValue:)))
    }

    /// Cache key of the reading: both compatibility profiles and the kind.
    public static func blurbHash(me: CompatProfile, them: CompatProfile, kind: String) -> String {
        func key(_ p: CompatProfile) -> String {
            let w = WireCompat.from(p)
            return [w.sun, w.moon, w.venus, w.mars, w.asc ?? -1].map(String.init).joined(separator: ",")
                + ",\(w.animal.key),\(w.stem.name),\(w.blood?.name ?? "-"),\(w.birthDate),\(w.birthHour ?? -1)"
        }
        return String(LegalText.sha256Hex("\(key(me))|\(key(them))|\(kind)").prefix(32))
    }

    /// The up-to-8 aspect rows, "Your Venus △ their Mars — trine, 2.1°" (SPEC 5d.4 item 4).
    public var aspectRows: [Compatibility.SynastryLine] { Array(lines.prefix(8)) }

    public static func aspectRow(_ l: Compatibility.SynastryLine) -> String {
        "Your \(l.pointA.label) \(aspectGlyph(l.aspect.name)) their \(l.pointB.label) — \(l.aspect.name), "
            + String(format: "%.1f", locale: Locale(identifier: "en_US_POSIX"), Double(l.orbCentideg) / 100.0) + "°"
    }

    public static func chineseRelationLabel(_ r: ChineseRelation) -> String {
        switch r {
        case .same: return "Same animal — a mirror"
        case .sameSelfPunishment: return "Same animal — mirrors that test each other"
        case .trine: return "Trine allies (San He)"
        case .harmony: return "Secret friends (Liu He)"
        case .clash: return "Clash (Chong) — opposites that spark"
        case .harm: return "Harm (Hai) — handle with care"
        case .neutral: return "Neutral — room to grow"
        }
    }

    public static func stemRelationLabel(_ r: StemRelation) -> String {
        switch r {
        case .combination: return "Day masters combine (He)"
        case .sameStem: return "The same day master"
        case .sameElement: return "Day masters share an element"
        case .generating: return "One day master nourishes the other"
        case .controlling: return "One day master tempers the other"
        }
    }

    /// Planet glyph for a compatibility point (text presentation).
    public static func pointGlyph(_ p: Compatibility.Point) -> String {
        switch p {
        case .sun: return ChartWheelGeometry.textGlyph("☉")
        case .moon: return ChartWheelGeometry.textGlyph("☽")
        case .venus: return ChartWheelGeometry.textGlyph("♀")
        case .mars: return ChartWheelGeometry.textGlyph("♂")
        case .asc: return "AC"
        }
    }

    /// Aspect glyph by core aspect name.
    public static func aspectGlyph(_ name: String) -> String {
        switch name {
        case "conjunction": return ChartWheelGeometry.textGlyph("☌")
        case "sextile": return ChartWheelGeometry.textGlyph("⚹")
        case "square": return ChartWheelGeometry.textGlyph("□")
        case "trine": return ChartWheelGeometry.textGlyph("△")
        case "opposition": return ChartWheelGeometry.textGlyph("☍")
        default: return "·"
        }
    }

    /// The five compatibility points that are known, with their longitudes (wheel order).
    public static func points(_ p: CompatProfile) -> [(Compatibility.Point, Double)] {
        var out: [(Compatibility.Point, Double)] = [(.sun, p.sun), (.moon, p.moon), (.venus, p.venus), (.mars, p.mars)]
        if let asc = p.ascendant { out.append((.asc, asc)) }
        return out
    }
}
