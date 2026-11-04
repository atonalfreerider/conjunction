package com.metavirtuoso.conjunction.ux

import com.metavirtuoso.conjunction.core.compat.Compatibility
import com.metavirtuoso.conjunction.core.match.Gender
import com.metavirtuoso.conjunction.core.match.LookingFor
import com.metavirtuoso.conjunction.core.match.RelationshipStatus
import com.metavirtuoso.conjunction.core.match.Seeking
import com.metavirtuoso.conjunction.data.BirthInput
import com.metavirtuoso.conjunction.data.Charts
import com.metavirtuoso.conjunction.data.Profile
import com.metavirtuoso.conjunction.legal.ConsentKeys
import com.metavirtuoso.conjunction.legal.ConsentRecord
import com.metavirtuoso.conjunction.legal.LegalDoc
import com.metavirtuoso.conjunction.legal.LegalUpdate
import com.metavirtuoso.conjunction.legal.LegalVersions
import com.metavirtuoso.conjunction.nearby.CompatSubject
import com.metavirtuoso.conjunction.nearby.Screening
import com.metavirtuoso.conjunction.nearby.StoredScore
import com.metavirtuoso.conjunction.core.p2p.WireCompat
import com.metavirtuoso.conjunction.ui.Tab
import com.metavirtuoso.conjunction.ui.components.formatLinked
import com.metavirtuoso.conjunction.ui.match.CompatView
import com.metavirtuoso.conjunction.ui.match.aspectRow
import com.metavirtuoso.conjunction.ui.screens.OnboardingStep
import com.metavirtuoso.conjunction.ui.screens.ReadingsSegment
import com.metavirtuoso.conjunction.ui.screens.listTemplate
import com.metavirtuoso.conjunction.ui.screens.resumeStep
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** SPEC 5d logic that needs no UI: legal versions, migration, navigation, compatibility view. */
class UxLogicTest {
    private fun rec(key: String, version: String, withdrawn: Boolean = false) = ConsentRecord(key, version, "2026-11-01T10:00:00Z", "1.0.0", "android", "ab".repeat(32), withdrawn)
    private val bundled = mapOf(LegalDoc.WAIVER to "2.1", LegalDoc.TERMS to "2.1", LegalDoc.PRIVACY to "2.1", LegalDoc.COMMUNITY to "2.0", LegalDoc.SAFETY to "2.1")
    private val v20 = listOf(rec("WAIVER", "2.0"), rec("TERMS", "2.0"), rec("PRIVACY", "2.0"), rec(ConsentKeys.AGE_18_CONFIRMED, "2.0"))

    /** L-8: a 2.0 user gets one notice (minor bump), shown once; Guidelines are covered by the Terms. */
    @Test
    fun minorBumpIsANoticeShownOnce() {
        val update = LegalVersions.evaluate(v20, bundled)
        assertEquals(LegalUpdate.Notice(listOf(LegalDoc.TERMS, LegalDoc.WAIVER, LegalDoc.PRIVACY)), update)
        val seen = v20 + listOf(LegalDoc.TERMS, LegalDoc.WAIVER, LegalDoc.PRIVACY).map { rec(ConsentKeys.noticeSeen(it), "2.1") }
        assertEquals(LegalUpdate.None, LegalVersions.evaluate(seen, bundled))
    }

    /** L-8: a simulated major bump shows the re-accept sheet instead; accepting clears it. */
    @Test
    fun majorBumpNeedsReacceptance() {
        val major = bundled + (LegalDoc.TERMS to "3.0")
        val update = LegalVersions.evaluate(v20, major)
        assertTrue(update is LegalUpdate.Reaccept)
        assertEquals(listOf(LegalDoc.TERMS, LegalDoc.COMMUNITY), (update as LegalUpdate.Reaccept).docs)
        val after = v20 + rec("TERMS", "3.0") + rec("COMMUNITY_GUIDELINES", "2.0") + listOf(LegalDoc.WAIVER, LegalDoc.PRIVACY).map { rec(ConsentKeys.noticeSeen(it), "2.1") }
        assertEquals(LegalUpdate.None, LegalVersions.evaluate(after, major))
        // Never accepted at all (while onboarded) -> re-accept.
        assertTrue(LegalVersions.evaluate(emptyList(), bundled) is LegalUpdate.Reaccept)
    }

    /** The Privacy Policy is read, not agreed to: even a major change is a notice, never re-acceptance. */
    @Test
    fun privacyChangeIsAlwaysANotice() {
        val seen = v20 + listOf(LegalDoc.TERMS, LegalDoc.WAIVER, LegalDoc.PRIVACY).map { rec(ConsentKeys.noticeSeen(it), "2.1") }
        val major = bundled + (LegalDoc.PRIVACY to "3.0")
        assertEquals(LegalUpdate.Notice(listOf(LegalDoc.PRIVACY)), LegalVersions.evaluate(seen, major))
        assertEquals(LegalUpdate.None, LegalVersions.evaluate(seen + rec(ConsentKeys.noticeSeen(LegalDoc.PRIVACY), "3.0"), major))
        // A new (2.1) install records PRIVACY_NOTICE_SEEN, not PRIVACY.
        val fresh = listOf(rec("WAIVER", "2.1"), rec("TERMS", "2.1"), rec("COMMUNITY_GUIDELINES", "2.0"), rec(ConsentKeys.noticeSeen(LegalDoc.PRIVACY), "2.1"), rec(ConsentKeys.AGE_18_CONFIRMED, "2.1"))
        assertEquals(LegalUpdate.None, LegalVersions.evaluate(fresh, bundled))
    }

    @Test
    fun clickwrapAndNearbyConsentFromRecords() {
        assertTrue(LegalVersions.clickwrapAlreadyAccepted(v20, bundled))
        assertFalse(LegalVersions.clickwrapAlreadyAccepted(v20.filter { it.key != ConsentKeys.AGE_18_CONFIRMED }, bundled))
        val given = listOf(rec(ConsentKeys.ORIENTATION_DATA, "2.0"), rec(ConsentKeys.NEARBY_BLUETOOTH, "2.0"))
        assertTrue(LegalVersions.nearbyConsentGiven(given))
        assertFalse(LegalVersions.nearbyConsentGiven(given + rec(ConsentKeys.ORIENTATION_DATA, "2.1", withdrawn = true)))
        assertNull(LegalVersions.nearbyConsentAt(given.take(1)))
    }

    /** L-3: the hash covers the formatted sentence with the link labels substituted. */
    @Test
    fun clickwrapSentenceIsFormatted() {
        val t = "I'm 18+ and agree to the %1\$s, %2\$s and %3\$s, including a release of claims and individual arbitration."
        assertEquals(
            "I'm 18+ and agree to the Terms, Community Guidelines and Waiver, including a release of claims and individual arbitration.",
            formatLinked(t, listOf("Terms", "Community Guidelines", "Waiver")),
        )
        assertEquals("%1\$s, %2\$s and %3\$s", listTemplate(3))
        assertEquals("%1\$s", listTemplate(1))
    }

    /** SPEC 5d.1.4 resume mapping and O-7. */
    @Test
    fun onboardingResumesAtTheFirstMissingItem() {
        val birth = BirthInput(LocalDate.of(1994, 8, 7), LocalTime.of(16, 53), 41.8, -71.4, "America/New_York", "Providence")
        assertEquals(OnboardingStep.WELCOME, resumeStep(Profile()))
        assertEquals(OnboardingStep.BIRTH, resumeStep(Profile(ageGateBirthDate = birth.date, legalAccepted = true)))
        assertEquals(OnboardingStep.ABOUT, resumeStep(Profile(ageGateBirthDate = birth.date, birth = birth)))
        val datingDone = Profile(birth = birth, gender = Gender.MALE, seeking = Seeking.FEMALE, relationshipStatus = RelationshipStatus.SINGLE, lookingFor = LookingFor.DATING)
        assertNull(resumeStep(datingDone))
        assertEquals(OnboardingStep.CARD, resumeStep(datingDone.copy(onboardingStep = "CARD")))
        assertNull(resumeStep(Profile(onboardingComplete = true)))
    }

    /** N-1 order and N-5: old EASTERN / PALM tabs open Readings with that segment. */
    @Test
    fun tabsAndSavedTabMapping() {
        assertEquals(listOf("TODAY", "CHART", "NEARBY", "READINGS", "PROFILE"), Tab.entries.map { it.name })
        assertEquals(Tab.READINGS to ReadingsSegment.EASTERN, Tab.fromSaved("EASTERN"))
        assertEquals(Tab.READINGS to ReadingsSegment.PALM, Tab.fromSaved("PALM"))
        assertEquals(Tab.TODAY to null, Tab.fromSaved(null))
        assertEquals(Tab.NEARBY to null, Tab.fromSaved("NEARBY"))
    }

    private val me = Charts.compute(BirthInput(LocalDate.of(1994, 8, 7), LocalTime.of(5, 42), 41.8781, -87.6298, "America/Chicago", "Chicago"), null).compatProfile(false)
    private val them = WireCompat(6020, 14000, 7200, 9000, 12000, com.metavirtuoso.conjunction.core.chinese.ChineseAnimal.MONKEY, com.metavirtuoso.conjunction.core.chinese.HeavenlyStem.REN, null, LocalDate.of(1992, 5, 21), 9)
    private fun subject(peer: WireCompat?) = CompatSubject(
        "f1", "f1", null, "Lyra", "dating", null, Screening.SAFE, "gemini", "monkey", peer,
        StoredScore(71, 70, 60, null, listOf("Sun–Venus trine"), false, false), null, null,
    )

    /** SPEC 5d.4: recompute with the stored chart; fall back to the stored numbers without it (C-4). */
    @Test
    fun compatibilityViewRecomputesOrFallsBack() {
        val full = CompatView.of(subject(them), me)
        val expected = Compatibility.score(me, them.toCompatProfile())
        assertEquals(expected.total, full.score.total)
        assertEquals(Compatibility.synastry(me, them.toCompatProfile()), full.lines)
        assertEquals(expected.chineseRelation, full.chineseRelation)
        val old = CompatView.of(subject(null), me)
        assertEquals(71, old.score.total)
        assertTrue(old.lines.isEmpty())
        assertNull(old.them)
        assertNull(old.stemRelation)
        assertEquals(Compatibility.chineseRelation(me.chineseAnimal, com.metavirtuoso.conjunction.core.chinese.ChineseAnimal.MONKEY), old.chineseRelation)
    }

    @Test
    fun aspectRowWording() {
        val line = Compatibility.SynastryLine(Compatibility.Point.VENUS, Compatibility.Point.MARS, Compatibility.ASPECTS[3], 210, 4, 500)
        assertEquals("Your Venus △︎ their Mars — trine, 2.1°", aspectRow(line))
        assertTrue(line.isHarmonious)
    }
}
