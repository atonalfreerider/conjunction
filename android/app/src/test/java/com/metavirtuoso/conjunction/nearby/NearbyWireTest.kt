package com.metavirtuoso.conjunction.nearby

import com.metavirtuoso.conjunction.data.BirthInput
import com.metavirtuoso.conjunction.data.Charts
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The client side of the backend contract (backend/functions/src/schemas.ts, notify.ts). */
class NearbyWireTest {
    @Test
    fun compatProfileMatchesSchema() {
        val charts = Charts.compute(BirthInput(LocalDate.of(1992, 3, 4), LocalTime.of(6, 45), 51.5, -0.12, "Europe/London", "London"), null)
        val wire = NearbyApi.compatToWire(charts.compatProfile(includeBlood = false))
        assertEquals(setOf("sun", "moon", "venus", "mars", "ascendant", "chineseAnimal", "dayStem", "bloodType", "birthDate", "birthHour"), wire.keys)
        listOf("sun", "moon", "venus", "mars", "ascendant").forEach { assertTrue(it, (wire[it] as Double) in 0.0..360.0) }
        assertTrue(wire["chineseAnimal"] in listOf("rat", "ox", "tiger", "rabbit", "dragon", "snake", "horse", "goat", "monkey", "rooster", "dog", "pig"))
        assertTrue(wire["dayStem"] in listOf("jia", "yi", "bing", "ding", "wu", "ji", "geng", "xin", "ren", "gui"))
        assertEquals("1992-03-04", wire["birthDate"])
        assertEquals(6, wire["birthHour"])
        assertNull(wire["bloodType"])
    }

    @Test
    fun parsesEncounterCard() {
        val card = NearbyApi.parseCard(mapOf(
            "encounterId" to "a_b", "otherUid" to "b", "kind" to "dating", "status" to "pending",
            "score" to mapOf("total" to 87, "western" to 80, "chinese" to 95, "blood" to null, "highlights" to listOf("Sun trine Moon")),
            "birthdayTwin" to true, "exactTwin" to false,
            "card" to mapOf("firstName" to "Sam", "age" to 29, "sun" to "leo", "moon" to "pisces", "rising" to null, "chineseAnimal" to "horse",
                "relationshipStatus" to "single", "lookingFor" to "both", "hasPhoto" to false),
            "photoUrl" to null, "expiresAt" to "2026-11-04T10:00:00.000Z",
        ))
        assertEquals(87, card.total)
        assertNull(card.blood)
        assertEquals("Sam", card.card!!.firstName)
        assertEquals("leo", card.card!!.sun)
        assertTrue(card.birthdayTwin)
        assertEquals(listOf("Sun trine Moon"), card.highlights)
    }

    @Test
    fun pushKindsIncludingSpecAliases() {
        assertEquals(PushKind.MATCH, PushEvent.fromData(mapOf("kind" to "match", "encounterId" to "e"))!!.kind)
        assertEquals(PushKind.CONJUNCTION, PushEvent.fromData(mapOf("kind" to "connected"))!!.kind)
        assertEquals(PushKind.MESSAGE, PushEvent.fromData(mapOf("kind" to "intro", "pairId" to "p"))!!.kind)
        assertTrue(PushEvent.fromData(mapOf("kind" to "exact_twin"))!!.kind.isTwin)
        assertEquals(87, PushEvent.fromData(mapOf("kind" to "match", "total" to "87"))!!.total)
        assertNull(PushEvent.fromData(mapOf("kind" to "unknown")))
    }
}
