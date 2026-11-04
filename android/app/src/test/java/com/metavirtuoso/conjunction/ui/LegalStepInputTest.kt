package com.metavirtuoso.conjunction.ui

import android.graphics.Bitmap
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.test.core.app.ApplicationProvider
import com.metavirtuoso.conjunction.AppContainer
import com.metavirtuoso.conjunction.R
import com.metavirtuoso.conjunction.ai.GeneratedText
import com.metavirtuoso.conjunction.ai.ModelAvailability
import com.metavirtuoso.conjunction.ai.ModelUnavailableException
import com.metavirtuoso.conjunction.ai.OnboardingAssistant
import com.metavirtuoso.conjunction.ai.TextModel
import com.metavirtuoso.conjunction.core.match.Gender
import com.metavirtuoso.conjunction.core.match.LookingFor
import com.metavirtuoso.conjunction.core.match.RelationshipStatus
import com.metavirtuoso.conjunction.core.match.Seeking
import com.metavirtuoso.conjunction.data.BirthInput
import com.metavirtuoso.conjunction.data.PlaceResult
import com.metavirtuoso.conjunction.data.Profile
import com.metavirtuoso.conjunction.legal.ConsentKeys
import com.metavirtuoso.conjunction.legal.LegalTexts
import com.metavirtuoso.conjunction.ui.components.SheetHost
import com.metavirtuoso.conjunction.ui.components.formatLinked
import com.metavirtuoso.conjunction.ui.screens.BloodShareSwitch
import com.metavirtuoso.conjunction.ui.screens.NearbyConsentSheet
import com.metavirtuoso.conjunction.ui.screens.OnboardingDeps
import com.metavirtuoso.conjunction.ui.screens.OnboardingFlow
import com.metavirtuoso.conjunction.ui.screens.PlaceLookup
import com.metavirtuoso.conjunction.ui.theme.ConjunctionTheme
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

internal class NoModel : TextModel {
    override suspend fun availability() = ModelAvailability.Unavailable("test")
    override suspend fun generate(prompt: String, image: Bitmap?, onStatus: (String) -> Unit): GeneratedText =
        throw ModelUnavailableException("no on-device model in tests")
    override fun close() = Unit
}

/** A model that is "available" but never answers (O-3: nothing may wait for it). */
internal class HangingModel : TextModel {
    override suspend fun availability() = ModelAvailability.Available
    override suspend fun generate(prompt: String, image: Bitmap?, onStatus: (String) -> Unit): GeneratedText {
        kotlinx.coroutines.awaitCancellation()
    }
    override fun close() = Unit
}

/**
 * The DataStore delegates are process-wide singletons, so earlier tests' profile and ledger would
 * leak into this one: start every test from empty stores.
 */
internal fun freshContainer(model: TextModel): AppContainer {
    val app = ApplicationProvider.getApplicationContext<android.app.Application>()
    return AppContainer(app, model).also { c ->
        runBlocking {
            c.consentLedger.clear()
            c.profileStore.clear()
            c.nearby.store.clear()
        }
    }
}

internal val providence = PlaceResult("Providence, Rhode Island, United States", 41.824, -71.4128, "America/New_York", "UTC−04:00 (EDT) on that date")
internal val fakePlaces = PlaceLookup { _, _, _ -> listOf(providence, providence.copy(name = "Providence, Utah, United States", latitude = 41.70, longitude = -111.81, zoneId = "America/Denver")) }

/**
 * The consent controls must take input (the old waiver tick boxes once ignored every tap on a
 * Pixel 10): the L1 clickwrap gates Begin, the L2 checkbox gates Turn on, the L3 switch records.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-xxhdpi", application = android.app.Application::class)
class LegalStepInputTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private val app get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    private fun settle(ms: Long = 1_500) {
        compose.mainClock.advanceTimeBy(ms)
        Thread.sleep(250)
        compose.mainClock.advanceTimeBy(500)
    }

    private fun birthScreen(container: AppContainer, model: TextModel = NoModel()) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            ConjunctionTheme {
                SheetHost { OnboardingFlow(container, Profile(onboardingStep = "BIRTH"), onFinished = {}, deps = OnboardingDeps(fakePlaces, OnboardingAssistant(model))) }
            }
        }
        settle()
        compose.onNodeWithTag("birthText").performTextInput("Aug 7 1994, 4:53pm, Providence RI")
        settle(2_000)
    }

    /** L-2 / L-3 / O-3: Begin waits only for L1 (never for the model); the records hash the formatted sentence. */
    @Test
    fun clickwrapGatesBeginAndRecordsTheSentence() {
        val container = freshContainer(HangingModel())
        birthScreen(container, HangingModel())
        compose.onNodeWithText("7 Aug 1994").assertExists()
        compose.onNodeWithText(providence.name).assertExists()
        val begin = compose.onNodeWithTag("begin")
        begin.assertIsNotEnabled()
        val box = compose.onNodeWithTag("clickwrap")
        box.assertIsOff()
        // Tap the checkbox itself (the sentence holds the document links).
        box.performTouchInput { click(Offset(30f, 60f)) }
        settle(300)
        box.assertIsOn()
        begin.assertIsEnabled()
        begin.performClick()
        settle(2_000)
        val records = runBlocking { container.consentLedger.all() }
        val sentence = formatLinked(app.getString(R.string.legal_accept_checkbox), listOf(app.getString(R.string.legal_link_terms), app.getString(R.string.legal_link_guidelines), app.getString(R.string.legal_link_waiver)))
        assertEquals(setOf("WAIVER", "TERMS", "COMMUNITY_GUIDELINES", "PRIVACY_NOTICE_SEEN", ConsentKeys.AGE_18_CONFIRMED), records.map { it.key }.toSet())
        assertTrue(records.filter { it.key != "PRIVACY_NOTICE_SEEN" }.all { it.textSha256 == LegalTexts.sha256(sentence) })
        val privacyLine = formatLinked(app.getString(R.string.legal_privacy_line), listOf(app.getString(R.string.legal_link_privacy)))
        assertEquals(LegalTexts.sha256(privacyLine), records.first { it.key == "PRIVACY_NOTICE_SEEN" }.textSha256)
        // Each document record also carries the hash of the full bundled document.
        listOf(com.metavirtuoso.conjunction.legal.LegalDoc.WAIVER, com.metavirtuoso.conjunction.legal.LegalDoc.TERMS, com.metavirtuoso.conjunction.legal.LegalDoc.COMMUNITY, com.metavirtuoso.conjunction.legal.LegalDoc.PRIVACY).forEach { doc ->
            val key = if (doc == com.metavirtuoso.conjunction.legal.LegalDoc.PRIVACY) "PRIVACY_NOTICE_SEEN" else doc.key
            assertEquals(LegalTexts.load(app, doc).sha256, records.first { it.key == key }.docSha256)
        }
        assertEquals("2.1", records.first { it.key == "TERMS" }.version)
        assertEquals("2.0", records.first { it.key == "COMMUNITY_GUIDELINES" }.version)
        val p = runBlocking { container.profileStore.current() }
        assertEquals(LocalDate.of(1994, 8, 7), p.birth!!.date)
        assertEquals(LocalTime.of(16, 53), p.birth!!.time)
    }

    /** L-4: tapping a document link in the L1 sentence opens the reader and leaves the box unticked. */
    @Test
    fun clickwrapLinkOpensTheDocumentWithoutTicking() {
        val container = freshContainer(NoModel())
        birthScreen(container, NoModel())
        val box = compose.onNodeWithTag("clickwrap")
        box.assertIsOff()
        val waiver = app.getString(R.string.legal_link_waiver)
        val sentence = formatLinked(app.getString(R.string.legal_accept_checkbox), listOf(app.getString(R.string.legal_link_terms), app.getString(R.string.legal_link_guidelines), waiver))
        val textNode = compose.onNode(androidx.compose.ui.test.hasText(sentence) and androidx.compose.ui.test.hasAnyAncestor(androidx.compose.ui.test.hasTestTag("clickwrap")), useUnmergedTree = true)
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        textNode.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
        val start = sentence.indexOf(waiver)
        val rect = layouts.first().getBoundingBox(start + waiver.length / 2)
        textNode.performTouchInput { click(rect.center) }
        settle(800)
        box.assertIsOff()
        assertTrue("the Waiver reader opened", compose.onAllNodesWithText(com.metavirtuoso.conjunction.legal.LegalDoc.WAIVER.title, substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
    }

    /** L-2: an under-18 date on Begin blocks the install and keeps no date. */
    @Test
    fun underEighteenIsBlocked() {
        val container = freshContainer(NoModel())
        compose.mainClock.autoAdvance = false
        compose.setContent {
            ConjunctionTheme { SheetHost { OnboardingFlow(container, Profile(onboardingStep = "BIRTH"), onFinished = {}, deps = OnboardingDeps(fakePlaces, OnboardingAssistant(NoModel()))) } }
        }
        settle()
        val year = LocalDate.now().year - 15
        compose.onNodeWithTag("birthText").performTextInput("Aug 7 $year, Providence RI")
        settle(2_000)
        compose.onNodeWithTag("clickwrap").performTouchInput { click(Offset(30f, 60f)) }
        settle(300)
        compose.onNodeWithTag("begin").performClick()
        settle(1_500)
        val p = runBlocking { container.profileStore.current() }
        assertTrue(p.ageBlocked)
        assertEquals(null, p.birth)
        assertEquals(null, p.ageGateBirthDate)
    }

    /** L-5: Turn on is disabled until the L2 box is ticked; Not now records nothing. */
    @Test
    fun nearbySheetGatesTurnOn() {
        val container = freshContainer(NoModel())
        val profile = Profile(
            displayName = "Celeste", birth = BirthInput(LocalDate.of(1994, 8, 7), null, 1.0, 1.0, "UTC", "x"),
            gender = Gender.FEMALE, seeking = Seeking.MALE, relationshipStatus = RelationshipStatus.SINGLE, lookingFor = LookingFor.BOTH,
            onboardingComplete = true,
        )
        var dismissed = false
        compose.mainClock.autoAdvance = false
        compose.setContent { ConjunctionTheme { SheetHost { NearbyConsentSheet(container, profile, onTurnOn = {}, onDismiss = { dismissed = true }) } } }
        settle()
        val turnOn = compose.onNodeWithTag("turnOn")
        // Pinned under the points: on screen without scrolling the sheet.
        turnOn.assertIsDisplayed()
        turnOn.assertIsNotEnabled()
        compose.onNodeWithTag("nearbyConsent").performClick()
        settle(300)
        compose.onNodeWithTag("nearbyConsent").assertIsOn()
        turnOn.assertIsEnabled()
        compose.onNodeWithText(app.getString(R.string.nearby_not_now).uppercase()).performClick()
        settle(300)
        assertTrue(dismissed)
        assertTrue(runBlocking { container.consentLedger.all() }.isEmpty())
    }

    /** L-7: the blood switch starts off, records consent on and withdrawal off. */
    @Test
    fun bloodSwitchRecordsConsentAndWithdrawal() {
        val container = freshContainer(NoModel())
        compose.mainClock.autoAdvance = false
        compose.setContent {
            ConjunctionTheme {
                val p by container.profileStore.profile.collectAsState(initial = Profile())
                BloodShareSwitch(container, p)
            }
        }
        settle()
        val sw = compose.onNodeWithTag("bloodShare")
        sw.assertIsOff()
        sw.performClick()
        settle(800)
        sw.assertIsOn()
        sw.performClick()
        settle(800)
        sw.assertIsOff()
        val records = runBlocking { container.consentLedger.all() }.filter { it.key == ConsentKeys.BLOOD_TYPE_MATCHING }
        assertEquals(listOf(false, true), records.map { it.withdrawn })
    }
}

