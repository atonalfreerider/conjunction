package com.metavirtuoso.conjunction.core.p2p

import com.metavirtuoso.conjunction.core.p2p.P2pConstants.FRAME_HEADER_LEN
import com.metavirtuoso.conjunction.core.p2p.P2pConstants.FRAME_VERSION
import com.metavirtuoso.conjunction.core.p2p.P2pConstants.MAX_BLOB_LEN
import com.metavirtuoso.conjunction.core.p2p.P2pConstants.MAX_BODY_LEN
import com.metavirtuoso.conjunction.core.p2p.P2pConstants.MAX_FRAME_LEN
import com.metavirtuoso.conjunction.core.p2p.P2pConstants.MAX_JSON_LEN
import com.metavirtuoso.conjunction.core.p2p.P2pConstants.MIN_FRAME_LEN

/**
 * Frame codec (docs/P2P_PROTOCOL.md section 4).
 *
 * Frame = ver(1) | msgId(u16 BE) | index(u16 BE) | count(u16 BE) | payload(1..frameLen-7).
 * Body  = jsonLen(u16 BE) | canonical JSON | blob.
 */
object FrameCodec {

    fun packBody(json: ByteArray, blob: ByteArray = EMPTY): ByteArray {
        require(json.size in 2..MAX_JSON_LEN) { "json length ${json.size} out of range" }
        require(blob.size <= MAX_BLOB_LEN) { "blob too large" }
        val out = ByteArray(2 + json.size + blob.size)
        out[0] = (json.size ushr 8).toByte()
        out[1] = json.size.toByte()
        json.copyInto(out, 2)
        blob.copyInto(out, 2 + json.size)
        return out
    }

    /** Splits a body into (json, blob); throws malformed. */
    fun unpackBody(body: ByteArray): Pair<ByteArray, ByteArray> {
        if (body.size < 4) malformed("body shorter than 4 bytes")
        val jlen = u16(body, 0)
        if (jlen < 2 || jlen > MAX_JSON_LEN || 2 + jlen > body.size) malformed("bad json length $jlen")
        return body.copyOfRange(2, 2 + jlen) to body.copyOfRange(2 + jlen, body.size)
    }

    /** Splits [body] into frames no longer than [frameLen] bytes (header included). */
    fun encode(msgId: Int, body: ByteArray, frameLen: Int): List<ByteArray> {
        require(msgId in 0..0xFFFF) { "msgId out of range" }
        require(frameLen in MIN_FRAME_LEN..MAX_FRAME_LEN) { "frameLen out of range" }
        require(body.size in 1..MAX_BODY_LEN) { "body length out of range" }
        val chunk = frameLen - FRAME_HEADER_LEN
        val count = (body.size + chunk - 1) / chunk
        return List(count) { i ->
            val from = i * chunk
            val to = minOf(body.size, from + chunk)
            val f = ByteArray(FRAME_HEADER_LEN + (to - from))
            f[0] = FRAME_VERSION.toByte()
            put16(f, 1, msgId); put16(f, 3, i); put16(f, 5, count)
            body.copyInto(f, FRAME_HEADER_LEN, from, to)
            f
        }
    }

    /** Message id of a frame (for logging / grouping); -1 if too short. */
    fun msgIdOf(frame: ByteArray): Int = if (frame.size >= 3) u16(frame, 1) else -1

    internal fun u16(b: ByteArray, off: Int): Int = ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)

    private fun put16(b: ByteArray, off: Int, v: Int) {
        b[off] = (v ushr 8).toByte()
        b[off + 1] = v.toByte()
    }

    val EMPTY = ByteArray(0)
}

/**
 * Per-link, per-direction reassembly (section 4.3). [accept] returns a complete body, or null
 * while a message is incomplete, and throws [P2pException] on violations.
 */
class Reassembler {
    private class Partial(val count: Int) {
        var nextIndex = 0
        val data = java.io.ByteArrayOutputStream()
        var lastMs = 0L
    }

    private val partials = LinkedHashMap<Int, Partial>()

    val pending: Int get() = partials.size

    fun accept(frame: ByteArray, nowMs: Long): ByteArray? {
        if (frame.size < FRAME_HEADER_LEN + 1) malformed("frame shorter than header + 1")
        if (frame.size > MAX_FRAME_LEN) malformed("frame longer than 512 bytes")
        val ver = frame[0].toInt() and 0xFF
        if (ver != FRAME_VERSION) throw P2pException(P2pErrorCode.FRAME_VERSION, "unknown frame version $ver")
        val msgId = FrameCodec.u16(frame, 1)
        val index = FrameCodec.u16(frame, 3)
        val count = FrameCodec.u16(frame, 5)
        if (count == 0 || index >= count) {
            partials.remove(msgId)
            malformed("index $index / count $count")
        }
        var p = partials[msgId]
        if (index == 0) {
            partials.remove(msgId) // a new message replaces an unfinished one with the same id
            if (count == 1) return frame.copyOfRange(FRAME_HEADER_LEN, frame.size)
            if (partials.size >= P2pConstants.MAX_CONCURRENT_REASSEMBLIES) {
                throw P2pException(P2pErrorCode.RATE, "too many concurrent messages")
            }
            p = Partial(count)
            partials[msgId] = p
        } else {
            if (p == null) malformed("frame $index of unknown message $msgId")
            if (index != p.nextIndex || count != p.count) {
                partials.remove(msgId)
                malformed("out-of-order frame $index/$count for message $msgId")
            }
        }
        p.data.write(frame, FRAME_HEADER_LEN, frame.size - FRAME_HEADER_LEN)
        p.nextIndex++
        p.lastMs = nowMs
        if (p.data.size() > MAX_BODY_LEN) {
            partials.remove(msgId)
            throw P2pException(P2pErrorCode.TOO_LARGE, "message too large")
        }
        if (p.nextIndex == p.count) {
            partials.remove(msgId)
            return p.data.toByteArray()
        }
        return null
    }

    /** Drops partial messages idle for more than 15 s; returns how many were dropped. */
    fun expire(nowMs: Long): Int {
        val dead = partials.filterValues { nowMs - it.lastMs > P2pConstants.REASSEMBLY_TIMEOUT_MS }.keys.toList()
        dead.forEach { partials.remove(it) }
        return dead.size
    }
}
