package com.metavirtuoso.conjunction.nearby

import com.metavirtuoso.conjunction.core.chart.ZodiacSign
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NearbyLogicTest {
    @Test
    fun proximityNeedsTwoStrongSightingsWithin30s() {
        val g = ProximityGate()
        assertFalse(g.observe("A", -70, 0))
        assertFalse("weak sighting does not count", g.observe("A", -85, 1_000))
        assertTrue(g.observe("A", -79, 2_000))
        assertEquals(1, g.closeBy(2_000))
        // A second sighting more than 30 s after the first is not enough on its own.
        assertFalse(g.observe("B", -60, 0))
        assertFalse(g.observe("B", -60, 31_000))
        g.rssiThreshold = -95
        assertTrue(g.observe("C", -90, 0) || g.observe("C", -90, 100))
        g.prune(100_000)
        assertEquals(0, g.closeBy(100_000))
    }

    @Test
    fun reconnectCooldownsAndBackoff() {
        val r = ReconnectPolicy()
        assertTrue(r.mayConnect("X", 0))
        r.finished("X", LinkOutcome.NO_MATCH, 0)
        assertFalse(r.mayConnect("X", ReconnectPolicy.SETTLED_COOLDOWN_MS - 1))
        assertTrue(r.mayConnect("X", ReconnectPolicy.SETTLED_COOLDOWN_MS))
        r.finished("Y", LinkOutcome.ERROR, 0)
        assertTrue(r.mayConnect("Y", 120_000))
        r.failed("Z", 0)
        assertFalse(r.mayConnect("Z", 4_999))
        assertTrue(r.mayConnect("Z", 5_000))
        r.failed("Z", 5_000)
        assertFalse(r.mayConnect("Z", 14_999))
        repeat(10) { r.failed("Z", 0) }
        assertTrue("capped at 120 s", r.mayConnect("Z", 120_000))
        r.finished("W", LinkOutcome.DONE, 0)
        r.expedite(1)
        assertTrue(r.mayConnect("W", 1))
        assertFalse("failures keep their backoff", r.mayConnect("Z", 1))
        // Rotated-away addresses are forgotten once their cool-down is long over.
        r.prune(ReconnectPolicy.SETTLED_COOLDOWN_MS + ReconnectPolicy.FORGET_AFTER_MS + 1)
        assertTrue(r.mayConnect("X", 0))
        r.failed("Z", 0)
        assertFalse("pruning forgot the failure count too", r.mayConnect("Z", 4_999))
    }

    @Test
    fun textHeuristicsFlagLuresAndAbuseButNotOrdinaryChat() {
        listOf(
            "add me on whatsapp", "call me +1 415 555 0134", "my email is a.b@example.com", "check www.example.com",
            "send me money for the ticket", "send nudes", "i know where you live", "pay with a gift card",
        ).forEach { assertTrue(it, TextHeuristics.flags(it)) }
        listOf(
            "Hi! Loved your Saturn return joke ☌", "Coffee on Saturday at 3?", "I'm a Pisces with Leo rising",
            "See you at the observatory", "my cat is called Mars",
        ).forEach { assertFalse(it, TextHeuristics.flags(it)) }
    }

    @Test
    fun modelVerdictParsing() {
        assertEquals(Screening.SAFE, ContentScreener.parseVerdict("SAFE"))
        assertEquals(Screening.FLAGGED, ContentScreener.parseVerdict("flag"))
        assertEquals(Screening.FLAGGED, ContentScreener.parseVerdict("FLAG — not SAFE"))
        assertNull(ContentScreener.parseVerdict("I can't help with that"))
        assertNull(ContentScreener.parseVerdict(null))
    }

    @Test
    fun cardNameAndUtf8Trimming() {
        assertEquals("A Leo", NearbyRepository.cardName("  ", ZodiacSign.LEO))
        assertEquals("Ada", NearbyRepository.cardName("Ada", ZodiacSign.LEO))
        val long = "☌".repeat(30) // 3 bytes each
        val t = NearbyRepository.trimUtf8(long, 64)
        assertEquals(21, t.length)
        assertTrue(t.toByteArray().size <= 64)
        val emoji = "a" + "🌙".repeat(20) // 4 bytes, surrogate pairs
        val e = NearbyRepository.trimUtf8(emoji, 10)
        assertEquals("a🌙🌙", e)
    }

    @Test
    fun eventExtrasRoundTrip() {
        val ev = NearbyEvent(NearbyEventKind.EXACT_TWIN, "fp", null, "friends", 91)
        val map = mapOf(
            NearbyEvent.EXTRA_KIND to ev.kind.wire, NearbyEvent.EXTRA_ENCOUNTER to "fp",
            NearbyEvent.EXTRA_MATCH_KIND to "friends", NearbyEvent.EXTRA_TOTAL to "91",
        )
        assertEquals(ev, NearbyEvent.fromExtras { map[it] })
        assertTrue(NearbyEvent.fromExtras { map[it] }!!.kind.isTwin)
        assertNull(NearbyEvent.fromExtras { null })
        assertEquals("msg:f1", Notifications.tagFor(NearbyEvent(NearbyEventKind.MESSAGE, null, "f1")))
    }
}
