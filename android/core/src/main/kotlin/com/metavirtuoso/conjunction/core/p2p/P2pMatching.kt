package com.metavirtuoso.conjunction.core.p2p

import com.metavirtuoso.conjunction.core.chart.ZodiacSign
import com.metavirtuoso.conjunction.core.compat.CompatResult
import com.metavirtuoso.conjunction.core.compat.Compatibility
import com.metavirtuoso.conjunction.core.match.Age
import com.metavirtuoso.conjunction.core.match.MatchFilter
import com.metavirtuoso.conjunction.core.match.MatchKind
import java.time.LocalDate

/** Result of evaluating a peer's hello against ours (section 9). */
data class P2pMatch(
    val kind: MatchKind,
    /** Compatibility.score(me, peer): total / sub-scores / highlights / twins flags. */
    val score: CompatResult,
    val peerAge: Int,
    val peerSun: ZodiacSign,
    val peerMoon: ZodiacSign,
    /** Null when the peer's birth time is unknown. */
    val peerRising: ZodiacSign?,
) {
    val isBirthdayTwin: Boolean get() = score.isBirthdayTwin
    val isExactTwin: Boolean get() = score.isExactTwin
}

object P2pMatching {
    /** Null = no match (under 18 on [today], or no qualifying intent): show nothing, keep nothing. */
    fun evaluate(mine: P2pMessage.Hello, peer: P2pMessage.Hello, today: LocalDate): P2pMatch? {
        if (!Age.isAdult(mine.compat.birthDate, today) || !Age.isAdult(peer.compat.birthDate, today)) return null
        val kind = MatchFilter.kind(mine.match, peer.match) ?: return null
        val score = Compatibility.score(mine.compat.toCompatProfile(), peer.compat.toCompatProfile())
        return P2pMatch(
            kind = kind,
            score = score,
            peerAge = Age.yearsOn(peer.compat.birthDate, today),
            peerSun = sign(peer.compat.sun),
            peerMoon = sign(peer.compat.moon),
            peerRising = peer.compat.asc?.let(::sign),
        )
    }

    fun sign(centideg: Int): ZodiacSign = ZodiacSign.entries[centideg / 3000]
}
