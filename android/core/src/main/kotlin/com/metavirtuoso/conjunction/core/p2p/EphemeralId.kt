package com.metavirtuoso.conjunction.core.p2p

import java.security.SecureRandom

/**
 * Ephemeral id (section 7.1): 16 CSPRNG bytes as 32 lowercase hex chars, redrawn every 15 min and
 * on every Nearby toggle. [current] returns the id for a link opened at [nowMs].
 */
class EphemeralIdSource(private val random: SecureRandom = SecureRandom()) {
    private var id: String = draw()
    private var drawnAtMs: Long = Long.MIN_VALUE

    fun current(nowMs: Long): String {
        if (drawnAtMs == Long.MIN_VALUE) drawnAtMs = nowMs
        if (nowMs - drawnAtMs >= P2pConstants.EPHEMERAL_ID_ROTATION_MS || nowMs < drawnAtMs) rotate(nowMs)
        return id
    }

    /** Call when Nearby is switched on. */
    fun rotate(nowMs: Long) {
        id = draw()
        drawnAtMs = nowMs
    }

    private fun draw(): String = ByteArray(P2pConstants.EPHEMERAL_ID_BYTES).also(random::nextBytes).let(::hexOf)

    companion object {
        private val HEX32 = Regex("^[0-9a-f]{32}$")
        fun isValid(eid: String): Boolean = HEX32.matches(eid)
    }
}

/** Section 7.2: of two links between the same phones keep the one whose central has the smaller eid. */
object LinkDedup {
    fun keep(myEid: String, peerEid: String, iAmCentral: Boolean): Boolean {
        require(myEid != peerEid) { "equal ephemeral ids" }
        return (myEid < peerEid) == iAmCentral
    }
}
