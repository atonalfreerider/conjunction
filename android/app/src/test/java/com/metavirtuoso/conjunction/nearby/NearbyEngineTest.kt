package com.metavirtuoso.conjunction.nearby

import android.graphics.Bitmap
import com.metavirtuoso.conjunction.ai.GeneratedText
import com.metavirtuoso.conjunction.ai.ModelAvailability
import com.metavirtuoso.conjunction.ai.TextModel
import com.metavirtuoso.conjunction.core.blood.BloodType
import com.metavirtuoso.conjunction.core.chinese.ChineseAnimal
import com.metavirtuoso.conjunction.core.chinese.HeavenlyStem
import com.metavirtuoso.conjunction.core.match.Gender
import com.metavirtuoso.conjunction.core.match.LookingFor
import com.metavirtuoso.conjunction.core.match.MatchProfile
import com.metavirtuoso.conjunction.core.match.RelationshipStatus
import com.metavirtuoso.conjunction.core.match.Seeking
import com.metavirtuoso.conjunction.core.p2p.LinkRole
import com.metavirtuoso.conjunction.core.p2p.P2pMessage
import com.metavirtuoso.conjunction.core.p2p.P2pMessages
import com.metavirtuoso.conjunction.core.p2p.Reassembler
import com.metavirtuoso.conjunction.core.p2p.SoftwareP256Key
import com.metavirtuoso.conjunction.core.p2p.SwipeDirection
import com.metavirtuoso.conjunction.core.p2p.WireCompat
import java.io.File
import java.nio.file.Files
import java.security.SecureRandom
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Two complete engines (core P2pLink + app logic + stores) wired back to back through in-memory
 * ports, as BleTransport would wire them over GATT: match → cards and photo → swipes → keys →
 * encrypted chat with receipts → disconnect → queued message → friend recognised on a new link
 * with fresh ephemeral ids → delivery. Also link de-duplication, hide-after-left and blocking.
 */
class NearbyEngineTest {
    private object NoModel : TextModel {
        override suspend fun availability() = ModelAvailability.Unavailable("test")
        override suspend fun generate(prompt: String, image: Bitmap?, onStatus: (String) -> Unit) = GeneratedText("SAFE", false)
        override fun close() = Unit
    }

    private class Host(var profile: LocalPeerProfile) : NearbyHost {
        val announced = ArrayList<NearbyEvent>()
        val logs = ArrayList<String>()
        override fun localProfile() = profile
        override fun announce(event: NearbyEvent, peerName: String?, peerSun: String?) { announced += event }
        override fun log(message: String) { logs += message }
    }

    /** Frames travel through one FIFO, so engines are never re-entered (like the single BLE thread). */
    private inner class Net {
        val queue = ArrayDeque<() -> Unit>()
        fun pump() { var n = 0; while (queue.isNotEmpty()) { queue.removeFirst()(); check(++n < 100_000) } }
    }

    private inner class Port(val net: Net, val name: String) : LinkPort {
        lateinit var deliver: (ByteArray) -> Unit
        var closedWith: LinkOutcome? = null
        var onClosed: () -> Unit = {}
        override val label = name
        val sent = ArrayList<ByteArray>()
        override fun send(frames: List<ByteArray>) { sent += frames; frames.forEach { f -> net.queue.addLast { deliver(f) } } }
        override fun close(outcome: LinkOutcome) { closedWith = outcome; net.queue.addLast { onClosed() } }
    }

    /**
     * A port like a real GATT queue: frames wait until [writeOne] hands the next one to the radio
     * (one Write Request / notification at a time), so a close can find frames not yet written.
     */
    private class SlowPort(override val label: String) : LinkPort {
        val pending = ArrayDeque<ByteArray>()
        val written = ArrayList<ByteArray>()
        var discarded = 0
        var closedWith: LinkOutcome? = null
        override fun send(frames: List<ByteArray>) { pending.addAll(frames) }
        override fun close(outcome: LinkOutcome) { closedWith = outcome }
        override fun discardPending() { discarded += pending.size; pending.clear() }
        fun writeOne(): ByteArray? = pending.removeFirstOrNull()?.also { written += it }
    }

    private fun messagesIn(frames: List<ByteArray>): List<P2pMessage> {
        val r = Reassembler()
        return frames.mapNotNull { r.accept(it, 0) }.map { P2pMessages.decode(it).message }
    }

    private fun helloEid(frames: List<ByteArray>): String = messagesIn(frames).filterIsInstance<P2pMessage.Hello>().first().eid

    private var now = 1_762_171_200_000L
    private val today = LocalDate.of(2026, 11, 3)
    private lateinit var dir: File
    private lateinit var a: NearbyEngine
    private lateinit var b: NearbyEngine
    private lateinit var storeA: P2pStore
    private lateinit var storeB: P2pStore
    private lateinit var hostA: Host
    private lateinit var hostB: Host

    private fun auroraWith(ig: String?) = LocalPeerProfile(
        MatchProfile(Gender.FEMALE, Seeking.BOTH, RelationshipStatus.SINGLE, LookingFor.BOTH),
        WireCompat(35193, 25565, 3680, 35030, 32195, ChineseAnimal.RAT, HeavenlyStem.WU, BloodType.O, LocalDate.of(1996, 3, 12), 6),
        "Aurora", "Stargazer.", ByteArray(3000) { (it % 251).toByte() }, 480, 640, instagram = ig,
    )
    private val aurora = auroraWith("aurora.stars")
    private val vega = LocalPeerProfile(
        MatchProfile(Gender.MALE, Seeking.FEMALE, RelationshipStatus.SINGLE, LookingFor.BOTH),
        WireCompat(6020, 14000, 7200, 9000, 12000, ChineseAnimal.MONKEY, HeavenlyStem.REN, null, LocalDate.of(1992, 5, 21), 9),
        "Vega", null, null, 0, 0,
    )

    private fun engine(store: P2pStore, host: Host) = NearbyEngine(
        store = store,
        identity = SoftwareP256Key.generate().let { k -> { k } },
        host = host,
        screener = ContentScreener(NoModel) { true },
        scope = CoroutineScope(Dispatchers.Unconfined),
        clock = { now },
        today = { today },
        random = SecureRandom(),
        decodePhoto = { null },
    )

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("p2p").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        storeA = P2pStore.create(File(dir, "a"), scope = scope)
        storeB = P2pStore.create(File(dir, "b"), scope = scope)
        runBlocking { storeA.load(); storeB.load() }
        hostA = Host(aurora)
        hostB = Host(vega)
        a = engine(storeA, hostA)
        b = engine(storeB, hostB)
        a.start()
        b.start()
    }

    /** A connects to B as central; B's peripheral link opens on the first INBOX write. Returns (A id, B id holder). */
    private fun connect(net: Net, central: NearbyEngine, peripheral: NearbyEngine): Pair<Int, () -> Int?> {
        val cPort = Port(net, "c")
        val pPort = Port(net, "p")
        var pId: Int? = null
        var cId = 0
        cPort.deliver = { f ->
            val id = pId ?: peripheral.openLink(LinkRole.PERIPHERAL, pPort, 185).also { pId = it }
            if (id != null) peripheral.onFrame(id, f)
        }
        pPort.deliver = { f -> central.onFrame(cId, f) }
        // Central disconnects once its link is done; both sides then see the disconnect.
        cPort.onClosed = { central.onDisconnected(cId); pId?.let(peripheral::onDisconnected) }
        pPort.onClosed = {}
        cId = central.openLink(LinkRole.CENTRAL, cPort, 185)!!
        lastPorts = cPort to pPort
        return cId to { pId }
    }

    /** (central, peripheral) ports of the last [connect]. */
    private lateinit var lastPorts: Pair<Port, Port>

    private fun drop(central: NearbyEngine, cId: Int, peripheral: NearbyEngine, pId: Int?) {
        central.onDisconnected(cId)
        pId?.let(peripheral::onDisconnected)
    }

    @Test
    fun matchSwipeChatQueueAndFriendRecognition() {
        val net = Net()
        val (cId, pId) = connect(net, a, b)
        net.pump()

        // Both phones computed the same match, kept the card and announced it.
        val encA = storeA.state.value.encounters.single()
        val encB = storeB.state.value.encounters.single()
        assertEquals("dating", encA.kind)
        assertEquals(encA.total, encB.total)
        assertEquals("Vega", encA.firstName)
        assertEquals("Aurora", encB.firstName)
        assertEquals(30, encB.age)
        assertNotNull("photo stored", encB.photoPath)
        assertTrue(File(encB.photoPath!!).readBytes().contentEquals(aurora.photo!!))
        assertEquals(NearbyEventKind.MATCH, hostA.announced.single().kind)
        assertEquals(NearbyEventKind.MATCH, hostB.announced.single().kind)

        // Mutual right swipe -> keys -> friends on both sides with the same safety code.
        a.swipe(encA.fingerprint, SwipeDirection.RIGHT)
        net.pump()
        assertTrue(storeA.state.value.friends.isEmpty())
        b.swipe(encB.fingerprint, SwipeDirection.RIGHT)
        net.pump()
        val fA = storeA.state.value.friends.single()
        val fB = storeB.state.value.friends.single()
        assertEquals(fA.safetyCode, fB.safetyCode)
        assertEquals("Vega", fA.firstName)
        assertEquals("Aurora", fB.firstName)
        assertEquals(fA.id, storeA.state.value.encounters.single().friendId)
        assertTrue(hostA.announced.any { it.kind == NearbyEventKind.CONJUNCTION && it.friendId == fA.id })

        // Encrypted chat while in range: delivered and acknowledged.
        queue(storeA, fA.id, "Hi Vega ☌")
        a.deliver(fA.id)
        net.pump()
        assertEquals(listOf("Hi Vega ☌"), storeB.state.value.messages.map { it.text })
        assertEquals(ChatStatus.DELIVERED, storeA.state.value.messages.single().status)
        assertEquals(Screening.SAFE, storeB.state.value.messages.single().screening)
        assertTrue(hostB.announced.any { it.kind == NearbyEventKind.MESSAGE && it.friendId == fB.id })

        // Out of range: the message waits.
        drop(a, cId, b, pId())
        net.queue.clear()
        queue(storeB, fB.id, "Saw you at the observatory!")
        b.deliver(fB.id)
        assertEquals(ChatStatus.QUEUED, storeB.state.value.messages.last().status)

        // 16 minutes later (new ephemeral ids), B finds A: friend tags recognised, keys confirmed, queue delivered.
        now += 16 * 60_000L
        val net2 = Net()
        connect(net2, b, a)
        net2.pump()
        assertEquals(ChatStatus.DELIVERED, storeB.state.value.messages.last().status)
        assertEquals("Saw you at the observatory!", storeA.state.value.messages.last().text)
        assertEquals(1, storeA.state.value.friends.size)
        assertEquals("no new match card for a friend", 1, storeA.state.value.encounters.size)

        // Read receipt: A looks at the chat; B's message becomes READ.
        storeA.update { s -> s.copy(messages = s.messages.map { if (!it.mine) it.copy(seen = true) else it }) }
        a.deliver(fA.id)
        net2.pump()
        assertEquals(ChatStatus.READ, storeB.state.value.messages.last().status)
    }

    @Test
    fun outOfRangeSwipeIsAppliedAtNextEncounter() {
        val net = Net()
        val (cId, pId) = connect(net, a, b)
        net.pump()
        val fpOfB = storeA.state.value.encounters.single().fingerprint
        val fpOfA = storeB.state.value.encounters.single().fingerprint
        b.swipe(fpOfA, SwipeDirection.RIGHT)
        net.pump()
        drop(a, cId, b, pId())
        // A answers while B is gone; it is stored and sent next time.
        a.swipe(fpOfB, SwipeDirection.RIGHT)
        assertEquals("right", storeA.state.value.encounter(fpOfB)!!.mySwipe)
        now += 3 * 60_000L
        val net2 = Net()
        connect(net2, a, b)
        net2.pump()
        // B's own right swipe is also stored, so both presets apply and they become friends.
        assertEquals(1, storeA.state.value.friends.size)
        assertEquals(1, storeB.state.value.friends.size)
        assertEquals("announced once per 24 h", 1, hostA.announced.count { it.kind == NearbyEventKind.MATCH })
    }

    @Test
    fun leftSwipeHidesThePersonAndBlockedFriendIsRefused() {
        val net = Net()
        val (cId, pId) = connect(net, a, b)
        net.pump()
        val fpOfB = storeA.state.value.encounters.single().fingerprint
        a.swipe(fpOfB, SwipeDirection.LEFT)
        net.pump()
        assertTrue(storeA.state.value.isHidden(fpOfB, now))
        drop(a, cId, b, pId())
        now += 5 * 60_000L
        hostA.announced.clear()
        val net2 = Net()
        connect(net2, b, a)
        net2.pump()
        assertTrue("hidden person is not announced again", hostA.announced.isEmpty())
    }

    @Test
    fun duplicateLinksBetweenTheSamePhonesAreDeduplicated() {
        val net = Net()
        connect(net, a, b)
        connect(net, b, a)
        net.pump()
        assertTrue("a dedup bye was logged", (hostA.logs + hostB.logs).any { it.startsWith("dedup:") })
        assertEquals(1, storeA.state.value.encounters.size)
        assertEquals(1, storeB.state.value.encounters.size)
        assertEquals(1, hostA.announced.count { it.kind == NearbyEventKind.MATCH })
        assertEquals(1, a.openLinkCount)
        assertEquals(1, b.openLinkCount)
    }

    /**
     * Regression (OTA round 1, scenarios 3/9): the duplicate link's card and photo frames, queued
     * in the GATT client together with its hello, kept being written for seconds after both sides
     * had closed it with `bye dedup`. On the LE connection the pair shares with the kept link they
     * reached a peripheral with no link for them ("frame 1 of unknown message 2"), whose error
     * handling dropped the connection and the kept link with it. Now only the bye goes out.
     */
    @Test
    fun dedupLoserWritesNothingButItsByeOverASlowConnection() {
        val net = Net()
        connect(net, a, b)
        net.pump()
        val eidA = helloEid(lastPorts.first.sent)
        val eidB = helloEid(lastPorts.second.sent)
        // The duplicate whose central has the larger eid is the one closed (protocol 7.2).
        val (central, peripheral) = if (eidB > eidA) b to a else a to b
        val cPort = SlowPort("c2")
        val pPort = SlowPort("p2")
        val cId = central.openLink(LinkRole.CENTRAL, cPort, 185)!!
        var pId: Int? = null
        var guard = 0
        while (cPort.pending.isNotEmpty() || pPort.pending.isNotEmpty()) {
            check(++guard < 10_000)
            cPort.writeOne()?.let { f ->
                val id = pId ?: peripheral.openLink(LinkRole.PERIPHERAL, pPort, 185).also { pId = it }
                if (id != null) peripheral.onFrame(id, f)
            }
            pPort.writeOne()?.let { f -> central.onFrame(cId, f) }
        }
        val pHost = if (peripheral === a) hostA else hostB
        assertTrue("the peripheral detected the duplicate", pHost.logs.any { it.startsWith("dedup:") })
        assertTrue("its queued hello/card/photo were dropped", pPort.discarded >= 2)
        assertEquals(listOf<P2pMessage>(P2pMessage.Bye("dedup")), messagesIn(pPort.written))
        assertEquals(LinkOutcome.DEDUP, pPort.closedWith)
        assertEquals(LinkOutcome.DEDUP, cPort.closedWith)
        assertTrue("no protocol errors", (hostA.logs + hostB.logs).none { "protocol error" in it })
        central.onDisconnected(cId)
        pId?.let(peripheral::onDisconnected)

        // The kept link is untouched: one card each, and the mutual swipe still makes friends on it.
        assertEquals(1, a.openLinkCount)
        assertEquals(1, b.openLinkCount)
        a.swipe(storeA.state.value.encounters.single().fingerprint, SwipeDirection.RIGHT)
        b.swipe(storeB.state.value.encounters.single().fingerprint, SwipeDirection.RIGHT)
        net.pump()
        assertEquals(1, storeA.state.value.friends.size)
        assertEquals(storeA.state.value.friends.single().safetyCode, storeB.state.value.friends.single().safetyCode)
    }

    /** OTA scenario 10: blocking a friend ends the link; later links and chats from them are refused. */
    @Test
    fun blockedFriendIsRefusedOnLaterLinksAndTheirChatsAreDropped() {
        val net = Net()
        connect(net, a, b)
        net.pump()
        val fpOfB = storeA.state.value.encounters.single().fingerprint
        a.swipe(fpOfB, SwipeDirection.RIGHT)
        b.swipe(storeB.state.value.encounters.single().fingerprint, SwipeDirection.RIGHT)
        net.pump()
        val fA = storeA.state.value.friends.single()
        val fB = storeB.state.value.friends.single()
        queue(storeB, fB.id, "before the block")
        b.deliver(fB.id)
        net.pump()
        assertEquals(1, storeA.state.value.messagesWith(fA.id).size)

        a.blockFriend(fA.id)
        net.pump()
        val sA = storeA.state.value
        assertTrue(sA.friend(fA.id)!!.blocked)
        assertTrue("chat deleted", sA.messagesWith(fA.id).isEmpty())
        assertTrue("card deleted", sA.encounters.isEmpty())
        assertTrue("card fingerprint hidden for good", sA.isHidden(fpOfB, Long.MAX_VALUE - 1))
        assertEquals("the link was closed", 0, a.openLinkCount)
        assertEquals(0, b.openLinkCount)

        // B comes back later (new ephemeral ids) with a queued message, in both directions.
        now += 16 * 60_000L
        hostA.announced.clear()
        queue(storeB, fB.id, "are you there?")
        val net2 = Net()
        connect(net2, b, a)
        net2.pump()
        connect(net2, a, b)
        net2.pump()
        assertTrue("nothing reaches A", storeA.state.value.messages.isEmpty())
        assertEquals(ChatStatus.QUEUED, storeB.state.value.messages.last().status)
        assertTrue("no match card, no message notification", hostA.announced.isEmpty() && storeA.state.value.encounters.isEmpty())
        assertEquals(0, a.openLinkCount)
        assertEquals(0, b.openLinkCount)
    }

    @Test
    fun restartDropsLinksWhoseDisconnectWasNeverReported() {
        // Nearby paused while links were open, and the transport could not report the
        // disconnects (GATT server closed): the next start must not count them against MAX_LINKS.
        repeat(NearbyEngine.MAX_LINKS) { connect(Net(), a, b) }
        assertEquals(NearbyEngine.MAX_LINKS, a.openLinkCount)
        assertEquals(null, a.openLink(LinkRole.CENTRAL, Port(Net(), "x"), 185))
        a.stop()
        a.start()
        assertEquals(0, a.openLinkCount)
        assertTrue(a.openLink(LinkRole.CENTRAL, Port(Net(), "y"), 185) != null)
    }

    @Test
    fun storeRoundTripsThroughJson() {
        val net = Net()
        connect(net, a, b)
        net.pump()
        val s = storeA.state.value.copy(
            messages = listOf(ChatLine("0".repeat(32), "f", true, "hé ☌ \"q\"", 5L, ChatStatus.SENT, Screening.FLAGGED, seen = true)),
            hidden = listOf(HiddenPeer("ab", Long.MAX_VALUE, "block")),
        )
        assertEquals(s, P2pStore.StateSerializer.decode(P2pStore.StateSerializer.encode(s)))
        assertNull(P2pStore.StateSerializer.decode("").friends.firstOrNull())
    }

    private fun befriend(): Pair<StoredFriend, StoredFriend> {
        val net = Net()
        val (cId, pId) = connect(net, a, b)
        net.pump()
        a.swipe(storeA.state.value.encounters.single().fingerprint, SwipeDirection.RIGHT)
        b.swipe(storeB.state.value.encounters.single().fingerprint, SwipeDirection.RIGHT)
        net.pump()
        drop(a, cId, b, pId())
        return storeA.state.value.friends.single() to storeB.state.value.friends.single()
    }

    /** C-2: the match keeps the peer's chart and relations; the friend inherits them; block deletes them. */
    @Test
    fun matchKeepsPeerChartAndFriendInheritsIt() {
        val net = Net()
        connect(net, a, b)
        net.pump()
        val encA = storeA.state.value.encounters.single()
        assertEquals(vega.compat, encA.peerCompat)
        assertNotNull(encA.chineseRelation)
        assertNotNull(encA.stemRelation)
        a.swipe(encA.fingerprint, SwipeDirection.RIGHT)
        b.swipe(storeB.state.value.encounters.single().fingerprint, SwipeDirection.RIGHT)
        net.pump()
        val fA = storeA.state.value.friends.single()
        assertEquals(vega.compat, fA.peerCompat)
        assertEquals(encA.total, fA.score!!.total)
        assertEquals(encA.chineseRelation, fA.score!!.chineseRelation)
        a.blockFriend(fA.id)
        val blocked = storeA.state.value.friends.single()
        assertNull(blocked.peerCompat)
        assertNull(blocked.peerIg)
    }

    /** I-2 / I-3: no handle before the mutual match; then each side sends one `profile` (rev 0) and it is acked. */
    @Test
    fun instagramIsExchangedOnlyAfterTheMutualMatch() {
        val net = Net()
        connect(net, a, b)
        net.pump()
        val plain = messagesIn(lastPorts.first.sent) + messagesIn(lastPorts.second.sent)
        assertTrue(plain.none { it.toWire().toString().contains("aurora.stars") })
        a.swipe(storeA.state.value.encounters.single().fingerprint, SwipeDirection.RIGHT)
        b.swipe(storeB.state.value.encounters.single().fingerprint, SwipeDirection.RIGHT)
        net.pump()
        val fA = storeA.state.value.friends.single()
        val fB = storeB.state.value.friends.single()
        assertEquals("aurora.stars", fB.peerIg)
        assertEquals(0, fB.peerIgRev)
        assertNull(fA.peerIg)
        assertEquals(0, fA.peerIgRev)
        assertEquals(0, fA.igState!!.ackedRev)
        assertEquals(0, fB.igState!!.ackedRev)
        assertTrue("profile is not a chat message", storeA.state.value.messages.isEmpty() && storeB.state.value.messages.isEmpty())
    }

    /** I-3: a new handle goes out as rev 1 on the next secure link; switching the friend off sends ig:null now. */
    @Test
    fun handleChangesAndTheFriendSwitchAreSent() {
        befriend()
        hostA.profile = auroraWith("aurora.night")
        now += 16 * 60_000L
        val net = Net()
        connect(net, b, a)
        net.pump()
        var fB = storeB.state.value.friends.single()
        assertEquals("aurora.night", fB.peerIg)
        assertEquals(1, fB.peerIgRev)
        // Per-friend switch off while the link is open: rev 2 with ig null, the chip disappears.
        storeA.update { s -> s.copy(friends = s.friends.map { it.copy(igShareOn = false) }) }
        a.refreshProfiles()
        net.pump()
        fB = storeB.state.value.friends.single()
        assertNull(fB.peerIg)
        assertEquals(2, fB.peerIgRev)
    }

    /** C-4: a friend from before UX 2.0 (no stored chart) gets it from the next verified friend link. */
    @Test
    fun friendNearbyBackfillsThePeerChart() {
        val (fA, _) = befriend()
        storeA.update { s -> s.copy(friends = s.friends.map { it.copy(peerCompat = null, score = null) }) }
        now += 16 * 60_000L
        val net = Net()
        connect(net, b, a)
        net.pump()
        val f = storeA.state.value.friend(fA.id)!!
        assertEquals(vega.compat, f.peerCompat)
        assertEquals(
            com.metavirtuoso.conjunction.core.compat.Compatibility.score(aurora.compat.toCompatProfile(), vega.compat.toCompatProfile()).total,
            f.score!!.total,
        )
    }

    /** Old store files (no UX 2.0 fields) decode, and the new fields round-trip. */
    @Test
    fun storeMigratesAndRoundTripsNewFields() {
        val old = """{"v":1,"friends":[{"id":"f1","ik":"AA==","fs":"AA==","safety":"1","kind":"dating","name":"Lyra","photoScreen":"safe","since":1,"lastSeen":2,"blocked":false}],
            "encounters":[{"fp":"e1","kind":"friends","total":80,"western":70,"chinese":90,"highlights":["Sun–Venus trine"],"twin":false,"exactTwin":false,"age":30,"sun":"leo","moon":"aries","animal":"rat","relationship":"single","lookingFor":"both","created":1,"expires":2}]}"""
        val decoded = P2pStore.StateSerializer.decode(old)
        val f = decoded.friends.single()
        assertNull(f.peerCompat); assertNull(f.score); assertNull(f.peerIg); assertEquals(-1, f.peerIgRev); assertNull(f.igShareOn); assertNull(f.igState)
        assertNull(decoded.encounters.single().peerCompat)
        val rich = decoded.copy(
            friends = listOf(f.copy(peerCompat = vega.compat, score = StoredScore(1, 2, 3, null, listOf("x"), true, false, "TRINE", "COMBINATION"),
                blurb = StoredBlurb("t", "model", "h"), peerIg = "lyra", peerIgRev = 3, igShareOn = false,
                igState = com.metavirtuoso.conjunction.core.profile.InstagramShareState("me", 2, 1, "ab".repeat(16), 2))),
            encounters = listOf(decoded.encounters.single().copy(peerCompat = aurora.compat, chineseRelation = "CLASH", stemRelation = "SAME_STEM", blurb = StoredBlurb("b", "fallback", "k"))),
        )
        assertEquals(rich, P2pStore.StateSerializer.decode(P2pStore.StateSerializer.encode(rich)))
    }

    private fun queue(store: P2pStore, friendId: String, text: String) {
        store.update { s -> s.copy(messages = s.messages + ChatLine(NearbyEngine.newMessageId(), friendId, true, text, now, ChatStatus.QUEUED)) }
    }
}
