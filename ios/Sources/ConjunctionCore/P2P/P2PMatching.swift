import Foundation

/// Result of evaluating a peer's hello against ours (section 9).
public struct P2PMatch: Hashable, Sendable {
    public let kind: MatchKind
    /// Compatibility.score(me, peer): total / sub-scores / highlights / twins flags.
    public let score: CompatResult
    public let peerAge: Int
    public let peerSun: ZodiacSign
    public let peerMoon: ZodiacSign
    /// nil when the peer's birth time is unknown.
    public let peerRising: ZodiacSign?

    public var isBirthdayTwin: Bool { score.isBirthdayTwin }
    public var isExactTwin: Bool { score.isExactTwin }
}

public enum P2PMatching {
    /// nil = no match (under 18 on [today], or no qualifying intent): show nothing, keep nothing.
    public static func evaluate(mine: Hello, peer: Hello, today: LocalDate) -> P2PMatch? {
        guard Age.isAdult(mine.compat.birthDate, today: today), Age.isAdult(peer.compat.birthDate, today: today),
              let peerAge = try? Age.yearsOn(peer.compat.birthDate, today: today),
              let kind = MatchFilter.kind(mine.match, peer.match) else { return nil }
        let score = Compatibility.score(mine.compat.toCompatProfile(), peer.compat.toCompatProfile())
        return P2PMatch(kind: kind, score: score, peerAge: peerAge,
                        peerSun: sign(peer.compat.sun), peerMoon: sign(peer.compat.moon),
                        peerRising: peer.compat.asc.map(sign))
    }

    /// Sign of a longitude in centidegrees (0..35999).
    public static func sign(_ centideg: Int) -> ZodiacSign { ZodiacSign.allCases[centideg / 3000] }
}
