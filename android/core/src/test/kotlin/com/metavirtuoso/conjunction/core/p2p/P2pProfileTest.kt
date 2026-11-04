package com.metavirtuoso.conjunction.core.p2p

import com.metavirtuoso.conjunction.core.TestData
import com.metavirtuoso.conjunction.core.p2p.Wire.arr
import com.metavirtuoso.conjunction.core.p2p.Wire.obj
import com.metavirtuoso.conjunction.core.p2p.Wire.str
import com.metavirtuoso.conjunction.core.profile.InstagramHandle
import com.metavirtuoso.conjunction.core.profile.InstagramShareState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDate

/** SPEC 5d.6: Instagram handle + chat `profile`, against golden_instagram.json and p2p_wire.json. */
class P2pProfileTest {
    private val root = Wire.root

    @Test
    fun goldenInstagram() {
        val g = TestData.json("golden_instagram.json")
        for (c in arr(g["normalize"]).map(::obj)) assertEquals(c["note"].toString(), c["handle"], InstagramHandle.normalize(str(c, "input")))
        for (c in arr(g["isValid"]).map(::obj)) assertEquals(c["handle"].toString(), c["valid"], InstagramHandle.isValid(str(c, "handle")))
        for (c in arr(root["normalize"]).map(::obj)) assertEquals(c["handle"], InstagramHandle.normalize(str(c, "input")))
        assertEquals("https://www.instagram.com/aurora.stars/", InstagramHandle.profileUrl("aurora.stars"))
    }

    @Test
    fun plaintextsAreCanonical() {
        for (p in arr(root["plaintexts"]).map(::obj)) {
            val msg = ChatPayload.Profile(str(p, "id"), p["ts"] as Long, (p["rev"] as Long).toInt(), p["ig"] as String?)
            assertEquals(p["json"], String(P2pMessages.encodeChatPayload(msg)))
            assertEquals(msg, P2pMessages.decodeChatPayload(str(p, "json").toByteArray()))
            assertTrue(str(p, "json").toByteArray().size <= 160)
        }
    }

    @Test
    fun receiverValidationAndMalformed() {
        for (c in arr(root["profileValidation"]).map(::obj)) {
            val got = P2pMessages.decodeChatPayload(str(c, "plaintext").toByteArray()) as ChatPayload.Profile
            assertEquals(c["ig"].toString(), c["stored"], got.ig)
        }
        for (c in arr(root["profileMalformed"]).map(::obj)) {
            try {
                P2pMessages.decodeChatPayload(str(c, "plaintext").toByteArray())
                fail("accepted ${c["name"]}")
            } catch (e: P2pException) {
                assertEquals(c["name"].toString(), c["code"], e.code.wire)
            }
        }
    }

    @Test
    fun shareStateLifecycle() {
        var st = InstagramShareState.initial("aurora", shareWithThisFriend = true)
        assertEquals(InstagramShareState("aurora", 0, -1), st)
        assertTrue(st.needsSend)
        st = st.sent("a".repeat(32))
        assertSame(st, st.onAck("b".repeat(32)))
        st = st.onAck("a".repeat(32))
        assertFalse(st.needsSend)
        assertSame(st, st.withEffective("aurora"))
        st = st.withEffective(InstagramShareState.effective("aurora", shareWithThisFriend = false))
        assertEquals(1, st.rev); assertNull(st.value); assertTrue(st.needsSend)
        st = st.sent("c".repeat(32)).withEffective("new.handle").onAck("c".repeat(32))
        assertEquals(2, st.rev); assertEquals(1, st.ackedRev); assertTrue(st.needsSend)
        assertNull(InstagramShareState.effective("Not Valid", true))
        assertTrue(InstagramShareState.acceptReceived(-1, 0))
        assertFalse(InstagramShareState.acceptReceived(3, 3))
    }

    @Test
    fun profileOnlyOnSecureLinksAndAcked() {
        val ha = Wire.hello("hello"); val hb = Wire.hello("hello_unknown_time")
        val a = P2pLink(LinkRole.CENTRAL, profileOf(ha, "Aurora"), SoftwareP256Key.generate(), TestPolicy(), 0)
        val b = P2pLink(LinkRole.PERIPHERAL, profileOf(hb, "Orion"), SoftwareP256Key.generate(), TestPolicy(), 0)
        a.setAttMtu(517); b.setAttMtu(517)
        a.start(); pump(a, b)
        assertEquals(LinkState.MATCHED, a.state)
        try { a.sendProfile(0, "aurora"); fail("profile before SECURE") } catch (e: IllegalStateException) { /* expected */ }
        b.swipe(SwipeDirection.RIGHT); a.swipe(SwipeDirection.RIGHT); pump(a, b)
        assertEquals(LinkState.SECURE, a.state)
        a.takeEvents(); b.takeEvents()
        try { a.sendProfile(0, "Aurora"); fail("unnormalised handle") } catch (e: IllegalArgumentException) { /* expected */ }
        val id = a.sendProfile(0, "aurora.stars")
        pump(a, b)
        val evB = b.takeEvents()
        assertEquals(listOf(LinkEvent.ProfileReceived(ChatPayload.Profile(id, (evB.single() as LinkEvent.ProfileReceived).profile.ts, 0, "aurora.stars"))), evB)
        assertEquals(listOf(LinkEvent.Delivered(id)), a.takeEvents())
        b.sendProfile(0, null); pump(a, b)
        assertNull((a.takeEvents().single() as LinkEvent.ProfileReceived).profile.ig)
    }

    @Test
    fun profileTranscriptAsCentral() = replay(LinkRole.CENTRAL)

    @Test
    fun profileTranscriptAsPeripheral() = replay(LinkRole.PERIPHERAL)

    /** Friend reconnect: hello (friend tag) -> keyx -> kc -> profile -> ack -> bye, byte-identical. */
    private fun replay(role: LinkRole) {
        val t = obj(root["profileTranscript"])
        val me = if (role == LinkRole.CENTRAL) "central" else "peripheral"
        val cfg = obj(t[me])
        val hello = Wire.hello(str(cfg, "profile"))
        val photo = str(cfg, "photoHex").takeIf { it.isNotEmpty() }?.let(::unhex)
        val identity = Wire.key(str(cfg, "identity"))
        val friendKey = Wire.key(str(cfg, "friend")).publicKey
        val friend = FriendRecord("friend", friendKey, P2pCrypto.friendSecret(identity, friendKey))
        val link = P2pLink(role, profileOf(hello, str(cfg, "firstName"), cfg["bio"] as String?, photo), identity,
            TestPolicy(friendList = listOf(friend), day = LocalDate.parse(str(t, "today"))), 0, ephemeral = Wire.key(str(cfg, "ephemeral")))
        link.setAttMtu((t["attMtu"] as Long).toInt())
        link.start()
        val pending = ArrayDeque<ByteArray>()
        fun refill() { pending.addAll(link.takeFrames()) }
        refill()
        var checked = 0
        val now = t["nowMs"] as Long
        for (step in arr(t["steps"]).map(::obj)) {
            val action = step["action"] as String?
            if (action != null) {
                if (step["side"] != me) continue
                when (action) {
                    "profile" -> link.sendProfile((step["rev"] as Long).toInt(), step["ig"] as String?, str(step, "id"), step["ts"] as Long)
                    "close" -> link.close(str(step, "reason"))
                    else -> fail("unknown action $action")
                }
                refill()
                continue
            }
            val frames = arr(step["frames"]).map { unhex(it as String) }
            if (step["from"] == me) {
                val got = frames.indices.map { pending.removeFirstOrNull() ?: throw AssertionError("$me: missing frame for ${step["type"]}") }
                if (step["type"] == "hello") {
                    val a = P2pMessages.decode(got.joinBody()).message as P2pMessage.Hello
                    val e = P2pMessages.decode(frames.joinBody()).message as P2pMessage.Hello
                    assertEquals(e.copy(ft = emptyList()), a.copy(ft = emptyList()))
                    assertTrue("own hello carries the friend tag", b64(P2pCrypto.friendTag(friend.friendSecret, a.eid)) in a.ft)
                } else {
                    frames.zip(got).forEach { (e, g) -> assertEquals("$me ${step["type"]}", hexOf(e), hexOf(g)) }
                }
                checked++
            } else {
                for (f in frames) link.onFrame(f, now)
                refill()
            }
        }
        val ev = link.takeEvents()
        assertTrue("$me: extra frames", pending.isEmpty())
        assertEquals(if (role == LinkRole.CENTRAL) 5 else 4, checked)
        assertTrue(ev.any { it is LinkEvent.FriendNearby })
        if (role == LinkRole.PERIPHERAL) {
            assertEquals("aurora.stars", ev.filterIsInstance<LinkEvent.ProfileReceived>().single().profile.ig)
        } else {
            assertTrue(ev.contains(LinkEvent.Delivered("9".repeat(32))))
        }
        assertFalse(ev.any { it is LinkEvent.ProtocolError || it is LinkEvent.ChatReceived })
        assertTrue(link.isClosed)
        // The verified link's hello is what the app stores as peerCompat (SPEC 5d.4 backfill).
        assertEquals(if (role == LinkRole.CENTRAL) Wire.hello("hello_unknown_time").compat else Wire.hello("hello").compat, link.peerHello!!.compat)
    }

    private fun List<ByteArray>.joinBody(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        forEach { out.write(it, 7, it.size - 7) }
        return out.toByteArray()
    }
}
