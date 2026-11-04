package com.metavirtuoso.conjunction.core.p2p

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.random.Random

class P2pFrameTest {

    @Test
    fun onlyTheFirstFrameOfMessageZeroStartsALink() {
        val hello = FrameCodec.encode(0, ByteArray(600) { 1 }, 182)
        assertTrue(FrameCodec.isLinkStart(hello[0]))
        assertTrue("later frames of message 0 do not", hello.drop(1).none(FrameCodec::isLinkStart))
        // Leftovers of an ended link (round 1 OTA: frame 2/16 of message 2 after a dedup bye).
        val photo = FrameCodec.encode(2, ByteArray(3000) { 2 }, 182)
        assertTrue(photo.none(FrameCodec::isLinkStart))
        assertTrue(FrameCodec.encode(1, byteArrayOf(0, 2, 0x7b, 0x7d), 182).none(FrameCodec::isLinkStart))
        assertTrue("header only is not a frame", !FrameCodec.isLinkStart(hello[0].copyOf(7)))
    }

    @Test
    fun randomBodiesRoundTripAtEveryMtu() {
        val rnd = Random(42)
        repeat(500) {
            val mtu = listOf(23, 27, 185, 247, 517, rnd.nextInt(23, 700)).random(rnd)
            val frameLen = P2pConstants.maxFrameLen(mtu)
            val body = rnd.nextBytes(rnd.nextInt(1, 6000))
            val id = rnd.nextInt(0, 65536)
            val frames = FrameCodec.encode(id, body, frameLen)
            assertTrue(frames.all { it.size <= frameLen && it.size >= 8 })
            assertTrue(frames.dropLast(1).all { it.size == frameLen })
            val r = Reassembler()
            val out = frames.map { r.accept(it, 0) }
            assertArrayEquals(body, out.last())
            assertTrue(out.dropLast(1).all { it == null })
            assertEquals(0, r.pending)
        }
    }

    @Test
    fun maxBodyAtSmallestMtu() {
        val body = Random(1).nextBytes(P2pConstants.MAX_BODY_LEN)
        val frames = FrameCodec.encode(9, body, 20)
        assertEquals((body.size + 12) / 13, frames.size)
        val r = Reassembler()
        var got: ByteArray? = null
        frames.forEach { got = r.accept(it, 0) ?: got }
        assertArrayEquals(body, got)
    }

    @Test
    fun interleavingUpToFourMessages() {
        val rnd = Random(5)
        val bodies = (0 until 4).map { rnd.nextBytes(300 + it * 50) }
        val streams = bodies.mapIndexed { i, b -> ArrayDeque(FrameCodec.encode(100 + i, b, 20)) }
        val r = Reassembler()
        val done = mutableMapOf<Int, ByteArray>()
        while (streams.any { it.isNotEmpty() }) {
            val i = streams.indices.filter { streams[it].isNotEmpty() }.random(rnd)
            r.accept(streams[i].removeFirst(), 0)?.let { done[i] = it }
        }
        bodies.forEachIndexed { i, b -> assertArrayEquals(b, done[i]) }
        // A fifth concurrent message is refused.
        val r2 = Reassembler()
        for (i in 0 until 4) r2.accept(FrameCodec.encode(i, ByteArray(100), 20)[0], 0)
        expect(P2pErrorCode.RATE) { r2.accept(FrameCodec.encode(4, ByteArray(100), 20)[0], 0) }
    }

    @Test
    fun violations() {
        val frames = FrameCodec.encode(5, ByteArray(200) { it.toByte() }, 20)
        val r = Reassembler()
        r.accept(frames[0], 0)
        expect(P2pErrorCode.MALFORMED) { r.accept(frames[2], 0) } // gap
        expect(P2pErrorCode.MALFORMED) { r.accept(frames[1], 0) } // partial was dropped
        val v2 = frames[0].copyOf().also { it[0] = 2 }
        expect(P2pErrorCode.FRAME_VERSION) { Reassembler().accept(v2, 0) }
        expect(P2pErrorCode.MALFORMED) { Reassembler().accept(ByteArray(7), 0) }
        expect(P2pErrorCode.MALFORMED) { Reassembler().accept(ByteArray(513).also { it[0] = 1; it[6] = 1 }, 0) }
        // Count mismatch mid-message.
        val r3 = Reassembler()
        r3.accept(frames[0], 0)
        val bad = frames[1].copyOf().also { it[6] = (it[6] + 1).toByte() }
        expect(P2pErrorCode.MALFORMED) { r3.accept(bad, 0) }
        // Restart with index 0 replaces the unfinished message.
        val r4 = Reassembler()
        r4.accept(frames[0], 0)
        val single = FrameCodec.encode(5, byteArrayOf(1, 2, 3), 20).single()
        assertArrayEquals(byteArrayOf(1, 2, 3), r4.accept(single, 0))
        assertEquals(0, r4.pending)
    }

    @Test
    fun partialTimesOut() {
        val frames = FrameCodec.encode(1, ByteArray(50), 20)
        val r = Reassembler()
        assertNull(r.accept(frames[0], 0))
        assertEquals(0, r.expire(P2pConstants.REASSEMBLY_TIMEOUT_MS))
        assertEquals(1, r.expire(P2pConstants.REASSEMBLY_TIMEOUT_MS + 1))
        expect(P2pErrorCode.MALFORMED) { r.accept(frames[1], 20_000) }
    }

    @Test
    fun randomGarbageOnlyThrowsProtocolErrors() {
        val rnd = Random(99)
        repeat(20_000) {
            val r = Reassembler()
            repeat(rnd.nextInt(1, 6)) {
                val junk = rnd.nextBytes(rnd.nextInt(0, 60))
                if (junk.isNotEmpty() && rnd.nextBoolean()) junk[0] = 1
                try {
                    val body = r.accept(junk, 0)
                    if (body != null) try { P2pMessages.decode(body) } catch (e: P2pException) { /* fine */ }
                } catch (e: P2pException) { /* fine */ }
            }
        }
    }

    @Test
    fun bodyPacking() {
        val json = """{"t":"x"}""".toByteArray()
        val body = FrameCodec.packBody(json, byteArrayOf(9, 9))
        assertEquals(0, body[0].toInt()); assertEquals(json.size, body[1].toInt())
        val (j, b) = FrameCodec.unpackBody(body)
        assertArrayEquals(json, j); assertArrayEquals(byteArrayOf(9, 9), b)
        expect(P2pErrorCode.MALFORMED) { FrameCodec.unpackBody(byteArrayOf(0, 1, 0)) }
        expect(P2pErrorCode.MALFORMED) { FrameCodec.unpackBody(byteArrayOf(0, 9, 0x7b, 0x7d)) }
    }
}

internal fun expect(code: P2pErrorCode, block: () -> Unit) {
    try {
        block()
        fail("expected ${code.wire}")
    } catch (e: P2pException) {
        assertEquals(code, e.code)
    }
}
