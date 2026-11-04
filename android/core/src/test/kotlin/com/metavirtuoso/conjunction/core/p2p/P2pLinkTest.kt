package com.metavirtuoso.conjunction.core.p2p

import com.metavirtuoso.conjunction.core.match.Gender
import com.metavirtuoso.conjunction.core.match.LookingFor
import com.metavirtuoso.conjunction.core.match.MatchKind
import com.metavirtuoso.conjunction.core.match.MatchProfile
import com.metavirtuoso.conjunction.core.match.RelationshipStatus
import com.metavirtuoso.conjunction.core.match.Seeking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import kotlin.random.Random

class P2pLinkTest {
    private val photo = Random(3).nextBytes(20_000)
    private val aurora = profileOf(Wire.hello("hello"), "Aurora", "Stargazer", photo)
    private val orion = profileOf(Wire.hello("hello_unknown_time"), "Orion")

    private class Pair2(val a: P2pLink, val b: P2pLink, val ida: SoftwareP256Key, val idb: SoftwareP256Key)

    private fun pair(
        pa: LocalP2pProfile = aurora, pb: LocalP2pProfile = orion,
        pola: TestPolicy = TestPolicy(), polb: TestPolicy = TestPolicy(), mtu: Int = 517,
        ida: SoftwareP256Key = SoftwareP256Key.generate(), idb: SoftwareP256Key = SoftwareP256Key.generate(),
    ): Pair2 {
        val a = P2pLink(LinkRole.CENTRAL, pa, ida, pola, 0)
        val b = P2pLink(LinkRole.PERIPHERAL, pb, idb, polb, 0)
        a.setAttMtu(mtu); b.setAttMtu(mtu)
        return Pair2(a, b, ida, idb)
    }

    private fun matched(mtu: Int = 517): Pair2 = pair(mtu = mtu).also { it.a.start(); pump(it.a, it.b) }

    private fun friends(): Triple<Pair2, LinkEvent.FriendEstablished, LinkEvent.FriendEstablished> {
        val p = matched()
        p.a.takeEvents(); p.b.takeEvents()
        p.a.swipe(SwipeDirection.RIGHT); p.b.swipe(SwipeDirection.RIGHT); pump(p.a, p.b)
        val fa = p.a.takeEvents().filterIsInstance<LinkEvent.FriendEstablished>().single()
        val fb = p.b.takeEvents().filterIsInstance<LinkEvent.FriendEstablished>().single()
        return Triple(p, fa, fb)
    }

    @Test
    fun matchSwipeKeyxChat() {
        val p = matched()
        val ea = p.a.takeEvents(); val eb = p.b.takeEvents()
        assertEquals(listOf("HelloReceived", "Match", "CardReceived"), ea.map { it::class.simpleName })
        assertEquals(listOf("HelloReceived", "Match", "CardReceived", "PhotoReceived"), eb.map { it::class.simpleName })
        val ma = ea.filterIsInstance<LinkEvent.Match>().single().match
        val mb = eb.filterIsInstance<LinkEvent.Match>().single().match
        assertEquals(MatchKind.DATING, ma.kind)
        assertEquals(ma.score.total, mb.score.total)
        assertArrayEquals(photo, eb.filterIsInstance<LinkEvent.PhotoReceived>().single().jpeg)
        assertEquals("Aurora", eb.filterIsInstance<LinkEvent.CardReceived>().single().card.firstName)
        assertNull(ma.peerRising) // Orion's birth time is unknown
        assertEquals(com.metavirtuoso.conjunction.core.chart.ZodiacSign.ARIES, mb.peerRising)

        p.a.swipe(SwipeDirection.RIGHT); pump(p.a, p.b)
        assertEquals(listOf(LinkEvent.PeerSwiped(SwipeDirection.RIGHT)), p.b.takeEvents())
        p.b.swipe(SwipeDirection.RIGHT); pump(p.a, p.b)
        val fa = p.a.takeEvents().filterIsInstance<LinkEvent.FriendEstablished>().single()
        val fb = p.b.takeEvents().filterIsInstance<LinkEvent.FriendEstablished>().single()
        assertArrayEquals(fa.friendSecret, fb.friendSecret)
        assertEquals(fa.safetyCode, fb.safetyCode)
        assertArrayEquals(p.idb.publicKey, fa.peerIdentityKey)
        assertArrayEquals(photo, fb.photo)
        assertEquals(LinkState.SECURE, p.a.state)

        val id = p.a.sendChat("Hi Orion ✨", tsMs = 5)
        pump(p.a, p.b)
        val got = p.b.takeEvents().filterIsInstance<LinkEvent.ChatReceived>().single().payload as ChatPayload.Text
        assertEquals("Hi Orion ✨", got.text)
        assertEquals(id, got.id)
        assertEquals(listOf(LinkEvent.Delivered(id)), p.a.takeEvents())
        p.b.sendRead(listOf(id)); pump(p.a, p.b)
        assertEquals(listOf(id), (p.a.takeEvents().filterIsInstance<LinkEvent.ChatReceived>().single().payload as ChatPayload.Read).refs)
        p.a.close(); pump(p.a, p.b)
        assertTrue(p.b.isClosed)
        assertEquals(LinkEvent.Closed("done", byPeer = true), p.b.takeEvents().last())
    }

    @Test
    fun leftSwipeClosesQuietly() {
        val p = matched()
        p.a.takeEvents(); p.b.takeEvents()
        p.b.swipe(SwipeDirection.RIGHT); p.a.swipe(SwipeDirection.LEFT); pump(p.a, p.b)
        assertTrue(p.a.isClosed && p.b.isClosed)
        assertFalse(p.b.takeEvents().any { it is LinkEvent.FriendEstablished })
    }

    @Test
    fun noMatchClosesWithoutCards() {
        val friendsOnly = MatchProfile(Gender.FEMALE, Seeking.MALE, RelationshipStatus.IN_A_RELATIONSHIP, LookingFor.FRIENDS)
        val p = pair(pa = withMatch(aurora, friendsOnly))
        p.a.start(); pump(p.a, p.b)
        val ea = p.a.takeEvents()
        assertTrue(ea.contains(LinkEvent.NoMatch))
        assertFalse(ea.any { it is LinkEvent.CardReceived })
        assertTrue(p.a.isClosed && p.b.isClosed)
    }

    @Test
    fun underageIsNoMatch() {
        val p = pair(pola = TestPolicy(day = LocalDate.of(2010, 1, 1)))
        p.a.start(); pump(p.a, p.b)
        assertTrue(p.a.takeEvents().contains(LinkEvent.NoMatch))
    }

    @Test
    fun smallMtuSkipsPhoto() {
        val p = matched(mtu = 23)
        val eb = p.b.takeEvents()
        assertFalse(eb.any { it is LinkEvent.PhotoReceived })
        assertNull(eb.filterIsInstance<LinkEvent.CardReceived>().single().card.photo)
        assertEquals(LinkState.MATCHED, p.a.state)
    }

    @Test
    fun hiddenStrangerAndPresetSwipe() {
        val fp = P2pCrypto.profileFingerprint(orion.compat)
        val hidden = pair(pola = TestPolicy(hidden = setOf(fp)))
        hidden.a.start(); pump(hidden.a, hidden.b)
        assertTrue(hidden.a.isClosed)
        assertFalse(hidden.a.takeEvents().any { it is LinkEvent.Match })

        val fpA = P2pCrypto.profileFingerprint(aurora.compat)
        val preset = pair(pola = TestPolicy(preset = mapOf(fp to SwipeDirection.RIGHT)), polb = TestPolicy(preset = mapOf(fpA to SwipeDirection.RIGHT)))
        preset.a.start(); pump(preset.a, preset.b)
        assertEquals(LinkState.SECURE, preset.a.state)
        assertEquals(LinkState.SECURE, preset.b.state)
    }

    @Test
    fun friendsRecogniseEachOtherWithNewEphemeralIds() {
        val (p, fa, fb) = friends()
        val fra = listOf(FriendRecord("orion", fa.peerIdentityKey, fa.friendSecret))
        val frb = listOf(FriendRecord("aurora", fb.peerIdentityKey, fb.friendSecret))
        val src = EphemeralIdSource()
        val a2 = profileOf(Wire.hello("hello"), "Aurora", eid = src.current(0).also { src.rotate(1) })
        val b2 = profileOf(Wire.hello("hello_unknown_time"), "Orion", eid = src.current(2))
        val q = pair(a2, b2, TestPolicy(fra), TestPolicy(frb), ida = p.ida, idb = p.idb)
        q.a.start(); pump(q.a, q.b)
        assertEquals(LinkState.SECURE, q.a.state)
        assertEquals("orion", q.a.takeEvents().filterIsInstance<LinkEvent.FriendNearby>().single().friend.id)
        assertEquals("aurora", q.b.takeEvents().filterIsInstance<LinkEvent.FriendNearby>().single().friend.id)
        q.b.sendChat("queued while apart"); pump(q.a, q.b)
        assertEquals("queued while apart", (q.a.takeEvents().filterIsInstance<LinkEvent.ChatReceived>().single().payload as ChatPayload.Text).text)
    }

    @Test
    fun blockedFriendGetsByeDone() {
        val (p, fa, fb) = friends()
        val fra = listOf(FriendRecord("orion", fa.peerIdentityKey, fa.friendSecret, blocked = true))
        // Orion still lists Aurora as a friend; Aurora has blocked Orion.
        val frb = listOf(FriendRecord("aurora", fb.peerIdentityKey, fb.friendSecret))
        val q = pair(pola = TestPolicy(fra), polb = TestPolicy(frb), ida = p.ida, idb = p.idb)
        q.a.start(); pump(q.a, q.b)
        assertTrue(q.a.isClosed && q.b.isClosed)
        assertFalse(q.b.takeEvents().any { it is LinkEvent.FriendNearby })
    }

    @Test
    fun impostorReplayingFriendTagsFailsKeyConfirmation() {
        val (p, fa, fb) = friends()
        val fra = listOf(FriendRecord("orion", fa.peerIdentityKey, fa.friendSecret))
        val frb = listOf(FriendRecord("aurora", fb.peerIdentityKey, fb.friendSecret))
        // Mallory has Aurora's profile and friend list (replayed tags) but not her identity key.
        val q = pair(pola = TestPolicy(fra), polb = TestPolicy(frb), ida = SoftwareP256Key.generate(), idb = p.idb)
        q.a.start(); pump(q.a, q.b)
        assertTrue(q.b.isClosed)
        val err = q.b.takeEvents().filterIsInstance<LinkEvent.ProtocolError>().first()
        assertEquals("crypto", err.code)
        assertFalse(q.b.state == LinkState.SECURE)
    }

    @Test
    fun tamperedChatIsRejected() {
        val (p, _, _) = friends()
        p.a.sendChat("hello")
        val frames = p.a.takeFrames()
        val body = java.io.ByteArrayOutputStream().apply { frames.forEach { write(it, 7, it.size - 7) } }.toByteArray()
        val chat = P2pMessages.decode(body).message as P2pMessage.Chat
        val ct = chat.ctBytes.also { it[3] = (it[3].toInt() xor 1).toByte() }
        FrameCodec.encode(77, P2pMessages.encode(P2pMessage.Chat(chat.ctr, b64(ct))), p.b.frameLen).forEach { p.b.onFrame(it, 1_000) }
        assertEquals("crypto", p.b.takeEvents().filterIsInstance<LinkEvent.ProtocolError>().single().code)
        assertTrue(p.b.isClosed)
    }

    @Test
    fun replayedChatCounterIsRejected() {
        val (p, _, _) = friends()
        p.a.sendChat("one")
        val frames = p.a.takeFrames()
        frames.forEach { p.b.onFrame(it, 1_000) }
        p.b.takeEvents()
        frames.forEach { p.b.onFrame(it.copyOf().also { f -> f[2] = 0x55 }, 1_000) } // same ctr, new msgId
        assertEquals("crypto", p.b.takeEvents().filterIsInstance<LinkEvent.ProtocolError>().single().code)
    }

    @Test
    fun tamperedCardPhotoHashIsRejected() {
        val p = pair()
        p.a.start()
        // Flip a byte in the last frame Aurora sends (inside the JPEG blob).
        var count = 0
        pump(p.a, p.b, tamper = { f, fromA ->
            if (fromA && FrameCodec.msgIdOf(f) == 2 && ++count == 3) f.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() } else f
        })
        assertEquals("malformed", p.b.takeEvents().filterIsInstance<LinkEvent.ProtocolError>().single().code)
    }

    @Test
    fun messagesOutOfStateAreRejected() {
        val p = matched()
        p.b.takeEvents()
        FrameCodec.encode(50, P2pMessages.encode(P2pMessage.Chat(0, b64(ByteArray(32)))), 512).forEach { p.b.onFrame(it, 1_000) }
        assertEquals("state", p.b.takeEvents().filterIsInstance<LinkEvent.ProtocolError>().single().code)

        val q = pair()
        FrameCodec.encode(0, P2pMessages.encode(P2pMessage.Swipe(SwipeDirection.RIGHT)), 512).forEach { q.b.onFrame(it, 0) }
        assertEquals("state", q.b.takeEvents().filterIsInstance<LinkEvent.ProtocolError>().single().code)
    }

    @Test
    fun unknownMessageTypesAreIgnoredButRateLimited() {
        val p = matched()
        p.b.takeEvents()
        val unknown = FrameCodec.encode(9, FrameCodec.packBody("""{"t":"future","x":1}""".toByteArray()), 512).single()
        repeat(20) { p.b.onFrame(unknown, 1_000) }
        assertEquals(LinkState.MATCHED, p.b.state)
        repeat(400) { p.b.onFrame(unknown, 1_000) }
        assertTrue(p.b.isClosed)
        assertEquals("rate", p.b.takeEvents().filterIsInstance<LinkEvent.ProtocolError>().single().code)
    }

    @Test
    fun versionNegotiation() {
        val q = pair()
        val future = P2pMessage.Hello(5, 3, "ab".repeat(16), orion.match, orion.compat, emptyList())
        FrameCodec.encode(0, P2pMessages.encode(future), 512).forEach { q.b.onFrame(it, 0) }
        assertEquals("version", q.b.takeEvents().filterIsInstance<LinkEvent.ProtocolError>().single().code)
        // A peer speaking 1..3 is fine.
        val q2 = pair()
        val newer = P2pMessage.Hello(3, 1, "ab".repeat(16), aurora.match, aurora.compat, emptyList())
        FrameCodec.encode(0, P2pMessages.encode(newer), 512).forEach { q2.b.onFrame(it, 0) }
        assertTrue(q2.b.takeEvents().any { it is LinkEvent.Match })
    }

    @Test
    fun timeouts() {
        val q = pair()
        q.b.tick(P2pConstants.HELLO_TIMEOUT_MS + 1)
        assertTrue(q.b.isClosed)
        val p = matched()
        p.a.tick(P2pConstants.MATCH_LINK_TIMEOUT_MS + 2_000)
        assertTrue(p.a.isClosed)
        val k = matched()
        k.a.swipe(SwipeDirection.RIGHT); pump(k.a, k.b)
        k.b.swipe(SwipeDirection.RIGHT)
        k.b.takeFrames() // lost: Aurora never sees Orion's swipe/keyx
        k.b.tick(1_000_000)
        assertEquals(LinkState.KEYX, k.b.state)
        k.b.tick(1_000_000 + P2pConstants.KEYX_TIMEOUT_MS + 1)
        assertTrue(k.b.isClosed)
    }

    @Test
    fun fuzzedFramesNeverCrash() {
        val rnd = Random(11)
        repeat(300) {
            val p = pair()
            p.a.start()
            for (f in p.a.takeFrames()) {
                val g = if (rnd.nextInt(3) == 0) f.copyOf().also { x -> val i = rnd.nextInt(x.size); x[i] = (x[i].toInt() xor (1 shl rnd.nextInt(8))).toByte() } else f
                p.b.onFrame(g, 1_000)
            }
            p.b.takeEvents()
        }
    }

    @Test
    fun dedupRule() {
        val lo = "00".repeat(16); val hi = "ff".repeat(16)
        assertTrue(LinkDedup.keep(lo, hi, iAmCentral = true))
        assertFalse(LinkDedup.keep(lo, hi, iAmCentral = false))
        assertTrue(LinkDedup.keep(hi, lo, iAmCentral = false))
        assertFalse(LinkDedup.keep(hi, lo, iAmCentral = true))
    }

    @Test
    fun ephemeralIdRotates() {
        val src = EphemeralIdSource()
        val a = src.current(0)
        assertEquals(a, src.current(P2pConstants.EPHEMERAL_ID_ROTATION_MS - 1))
        val b = src.current(P2pConstants.EPHEMERAL_ID_ROTATION_MS)
        assertTrue(a != b && EphemeralIdSource.isValid(b))
        src.rotate(P2pConstants.EPHEMERAL_ID_ROTATION_MS + 5)
        assertTrue(src.current(P2pConstants.EPHEMERAL_ID_ROTATION_MS + 6) != b)
    }
}
