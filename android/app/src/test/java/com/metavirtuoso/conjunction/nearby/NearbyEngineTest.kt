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

    private class Host(val profile: LocalPeerProfile) : NearbyHost {
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
        override fun send(frames: List<ByteArray>) { frames.forEach { f -> net.queue.addLast { deliver(f) } } }
        override fun close(outcome: LinkOutcome) { closedWith = outcome; net.queue.addLast { onClosed() } }
    }

    private var now = 1_762_171_200_000L
    private val today = LocalDate.of(2026, 11, 3)
    private lateinit var dir: File
    private lateinit var a: NearbyEngine
    private lateinit var b: NearbyEngine
    private lateinit var storeA: P2pStore
    private lateinit var storeB: P2pStore
    private lateinit var hostA: Host
    private lateinit var hostB: Host

    private val aurora = LocalPeerProfile(
        MatchProfile(Gender.FEMALE, Seeking.BOTH, RelationshipStatus.SINGLE, LookingFor.BOTH),
        WireCompat(35193, 25565, 3680, 35030, 32195, ChineseAnimal.RAT, HeavenlyStem.WU, BloodType.O, LocalDate.of(1996, 3, 12), 6),
        "Aurora", "Stargazer.", ByteArray(3000) { (it % 251).toByte() }, 480, 640,
    )
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
        return cId to { pId }
    }

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

    private fun queue(store: P2pStore, friendId: String, text: String) {
        store.update { s -> s.copy(messages = s.messages + ChatLine(NearbyEngine.newMessageId(), friendId, true, text, now, ChatStatus.QUEUED)) }
    }
}
