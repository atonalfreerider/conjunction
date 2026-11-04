package com.metavirtuoso.conjunction.core.p2p

/** Error codes of the `error` message (docs/P2P_PROTOCOL.md section 5.3). */
enum class P2pErrorCode(val wire: String) {
    VERSION("version"), MALFORMED("malformed"), FRAME_VERSION("frame_version"), STATE("state"),
    RATE("rate"), TOO_LARGE("too_large"), CRYPTO("crypto"), INTERNAL("internal");

    companion object {
        fun fromWire(v: String): P2pErrorCode? = entries.firstOrNull { it.wire == v }
    }
}

/** A protocol violation detected while handling received bytes. */
class P2pException(val code: P2pErrorCode, val detail: String) : Exception("${code.wire}: $detail")

internal fun malformed(detail: String): Nothing = throw P2pException(P2pErrorCode.MALFORMED, detail)
