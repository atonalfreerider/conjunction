package com.metavirtuoso.conjunction.ui

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.metavirtuoso.conjunction.AppContainer
import com.metavirtuoso.conjunction.R
import com.metavirtuoso.conjunction.ai.GeneratedText
import com.metavirtuoso.conjunction.ai.ModelAvailability
import com.metavirtuoso.conjunction.ai.ModelUnavailableException
import com.metavirtuoso.conjunction.ai.TextModel
import com.metavirtuoso.conjunction.core.blood.BloodType
import com.metavirtuoso.conjunction.core.blood.RhFactor
import com.metavirtuoso.conjunction.core.match.Gender
import com.metavirtuoso.conjunction.core.match.LookingFor
import com.metavirtuoso.conjunction.core.match.RelationshipStatus
import com.metavirtuoso.conjunction.core.match.Seeking
import com.metavirtuoso.conjunction.data.BirthInput
import com.metavirtuoso.conjunction.data.Charts
import com.metavirtuoso.conjunction.data.DatingProfile
import com.metavirtuoso.conjunction.data.Profile
import com.metavirtuoso.conjunction.nearby.EncounterCard
import com.metavirtuoso.conjunction.nearby.MatchCard
import com.metavirtuoso.conjunction.ui.match.MatchAnimation
import com.metavirtuoso.conjunction.ui.match.SwipeCardPage
import com.metavirtuoso.conjunction.ui.screens.ChartScreen
import com.metavirtuoso.conjunction.ui.screens.EasternScreen
import com.metavirtuoso.conjunction.ui.screens.NearbyScreen
import com.metavirtuoso.conjunction.ui.screens.OnboardingFlow
import com.metavirtuoso.conjunction.ui.screens.PalmScreen
import com.metavirtuoso.conjunction.ui.screens.ProfileScreen
import com.metavirtuoso.conjunction.ui.screens.TodayScreen
import com.metavirtuoso.conjunction.ui.theme.ConjunctionTheme
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A phone without Gemini Nano: every reading comes from core's bundled fallback text. */
private class NoNanoModel : TextModel {
    override suspend fun availability() = ModelAvailability.Unavailable("test")
    override suspend fun generate(prompt: String, image: Bitmap?, onStatus: (String) -> Unit): GeneratedText =
        throw ModelUnavailableException("no on-device model in tests")
    override fun close() = Unit
}

/**
 * Renders every main screen with Robolectric's native graphics (a smoke test that each screen
 * composes and draws with real data). With -Pconjunction.screenshots=<dir> it also writes PNGs,
 * which is how the screenshots in android/screenshots/ were made on a machine with no phone
 * emulator image.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-xxhdpi", application = android.app.Application::class)
class ScreenCaptureTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private val outDir = System.getProperty("conjunction.screenshots").orEmpty().takeIf { it.isNotBlank() }?.let(::File)

    private val birth = BirthInput(LocalDate.of(1994, 8, 7), LocalTime.of(5, 42), 41.8781, -87.6298, "America/Chicago", "Chicago, Illinois, United States")
    private val profile = Profile(
        ageGateBirthDate = birth.date,
        legalAccepted = true,
        birth = birth,
        bloodType = BloodType.AB,
        rh = RhFactor.POSITIVE,
        dating = DatingProfile(Gender.FEMALE, Seeking.MALE, RelationshipStatus.SINGLE, LookingFor.BOTH),
        orientationConsent = true,
        honestyConfirmed = true,
        displayName = "Celeste",
        bio = "Amateur astronomer, professional daydreamer.",
        onboardingComplete = true,
    )
    private val card = EncounterCard(
        encounterId = "demo", otherUid = "other", kind = "dating", status = "pending",
        total = 87, western = 84, chinese = 95, blood = 78,
        highlights = listOf("Your Venus trines their Sun", "Horse and Dog are trine allies"),
        birthdayTwin = false, exactTwin = false,
        card = MatchCard("Orion", 31, "sagittarius", "leo", "aries", "horse", "single", "both", hasPhoto = false),
        photoUrl = null, expiresAt = null,
    )

    private fun capture(name: String) {
        // Draw the window directly: captureToImage() waits for a new frame, which never comes while
        // the test clock is paused (the screens run infinite shimmer/parallax animations).
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width.coerceAtLeast(1), view.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(android.graphics.Canvas(bitmap)) }
        assertTrue("$name rendered empty", bitmap.width > 0 && bitmap.height > 0)
        outDir?.let { dir ->
            dir.mkdirs()
            File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test
    fun renderEveryScreen() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val container = AppContainer(context, NoNanoModel())
        val charts = Charts.compute(birth, profile.bloodType)
        var screen by mutableStateOf("onboarding_welcome")
        compose.mainClock.autoAdvance = false
        compose.setContent {
            ConjunctionTheme {
                when (screen) {
                    "onboarding_welcome" -> OnboardingFlow(container, Profile(), onFinished = {})
                    "onboarding_birth" -> OnboardingFlow(container, profile.copy(birth = null, onboardingComplete = false), onFinished = {})
                    "chart" -> ChartScreen(container, charts)
                    "today" -> TodayScreen(container, charts)
                    "eastern" -> EasternScreen(container, profile, charts)
                    "palm" -> PalmScreen(container)
                    "nearby" -> NearbyScreen(container, profile, charts, openChat = {})
                    "profile" -> ProfileScreen(container, profile, charts)
                    "match_animation" -> MatchAnimation(card, R.drawable.zodiac_leo, null, "Fire meets fire: a bright, easy warmth.", onFinished = {})
                    "twin_animation" -> MatchAnimation(card.copy(birthdayTwin = true, kind = "friends"), R.drawable.zodiac_leo, null, "Born on the same day — the sky remembers.", onFinished = {})
                    "match_card" -> SwipeCardPage(card, "Fire meets fire: a bright, easy warmth.", onSwipe = {}, onSafety = {}, onClose = {})
                }
            }
        }
        listOf(
            "onboarding_welcome" to 1500L,
            "onboarding_birth" to 1500L,
            "chart" to 3500L,
            "today" to 3000L,
            "eastern" to 3000L,
            "palm" to 1000L,
            "nearby" to 1000L,
            "profile" to 1000L,
            "match_animation" to 1400L,
            "twin_animation" to 2400L,
            "match_card" to 800L,
        ).forEach { (name, settle) ->
            screen = name
            compose.mainClock.advanceTimeBy(settle)
            // Let off-main work (DataStore, fallback readings) finish, then draw the next frames.
            Thread.sleep(300)
            compose.mainClock.advanceTimeBy(1200)
            capture(name)
        }
    }
}
