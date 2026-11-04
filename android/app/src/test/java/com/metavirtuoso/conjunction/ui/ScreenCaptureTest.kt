package com.metavirtuoso.conjunction.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.test.core.app.ApplicationProvider
import com.metavirtuoso.conjunction.AppContainer
import com.metavirtuoso.conjunction.R
import com.metavirtuoso.conjunction.ai.OnboardingAssistant
import com.metavirtuoso.conjunction.core.blood.BloodType
import com.metavirtuoso.conjunction.core.blood.RhFactor
import com.metavirtuoso.conjunction.core.compat.Compatibility
import com.metavirtuoso.conjunction.core.match.Gender
import com.metavirtuoso.conjunction.core.match.LookingFor
import com.metavirtuoso.conjunction.core.match.RelationshipStatus
import com.metavirtuoso.conjunction.core.match.Seeking
import com.metavirtuoso.conjunction.core.p2p.WireCompat
import com.metavirtuoso.conjunction.data.BirthInput
import com.metavirtuoso.conjunction.data.Charts
import com.metavirtuoso.conjunction.data.Profile
import com.metavirtuoso.conjunction.legal.LegalDoc
import com.metavirtuoso.conjunction.nearby.ChatLine
import com.metavirtuoso.conjunction.nearby.ChatStatus
import com.metavirtuoso.conjunction.nearby.NearbyEngine
import com.metavirtuoso.conjunction.nearby.NearbyEvent
import com.metavirtuoso.conjunction.nearby.NearbyEventKind
import com.metavirtuoso.conjunction.nearby.Screening
import com.metavirtuoso.conjunction.nearby.StoredEncounter
import com.metavirtuoso.conjunction.nearby.StoredFriend
import com.metavirtuoso.conjunction.ui.components.SheetHost
import com.metavirtuoso.conjunction.ui.match.CompatibilityScreen
import com.metavirtuoso.conjunction.ui.match.MatchExperience
import com.metavirtuoso.conjunction.ui.screens.AgeBlockedScreen
import com.metavirtuoso.conjunction.ui.screens.ChatScreen
import com.metavirtuoso.conjunction.ui.screens.LegalUpdateSheet
import com.metavirtuoso.conjunction.ui.screens.NearbyConsentSheet
import com.metavirtuoso.conjunction.ui.screens.NearbyScreen
import com.metavirtuoso.conjunction.ui.screens.OnboardingDeps
import com.metavirtuoso.conjunction.ui.screens.OnboardingFlow
import com.metavirtuoso.conjunction.ui.screens.ReadingsSegment
import com.metavirtuoso.conjunction.ui.screens.SettingsPage
import com.metavirtuoso.conjunction.ui.theme.ConjunctionTheme
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the SPEC 5d.10 screens with Robolectric's native graphics (1233×2742 = 411×914 dp at
 * xxhdpi; the L1 small-screen check at 360×640 dp). A smoke test that each screen composes and
 * draws with real data; with -Pconjunction.screenshots=<dir> it also writes the PNGs. Screens with
 * a tab bar are rendered inside the app scaffold.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-xxhdpi", application = android.app.Application::class)
class ScreenCaptureTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private val outDir = System.getProperty("conjunction.screenshots").orEmpty().takeIf { it.isNotBlank() }?.let(::File)
    private val app get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    private val birth = BirthInput(LocalDate.of(1994, 8, 7), LocalTime.of(5, 42), 41.8781, -87.6298, "America/Chicago", "Chicago, Illinois, United States")
    private val profile = Profile(
        ageGateBirthDate = birth.date,
        legalAccepted = true,
        birth = birth,
        bloodType = BloodType.AB,
        rh = RhFactor.POSITIVE,
        gender = Gender.FEMALE, seeking = Seeking.MALE, relationshipStatus = RelationshipStatus.SINGLE, lookingFor = LookingFor.BOTH,
        orientationConsent = true,
        nearbyConsent = true,
        displayName = "Celeste",
        bio = "Amateur astronomer, professional daydreamer.",
        instagram = "celeste.stars",
        notificationsPrompted = true,
        onboardingComplete = true,
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

    private fun settle(ms: Long) {
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(ms)
        // Let off-main work (DataStore, fallback readings) finish, then draw the next frames.
        Thread.sleep(300)
        compose.mainClock.advanceTimeBy(1200)
    }

    private fun photo(res: Int, name: String): String {
        val file = File(app.filesDir, "$name.jpg")
        val bmp = BitmapFactory.decodeResource(app.resources, res)
        val scaled = Bitmap.createScaledBitmap(bmp, 512, 512, true)
        file.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        return file.absolutePath
    }

    private fun peer(date: LocalDate, time: LocalTime?, lat: Double, lon: Double, zone: String, blood: BloodType? = null): WireCompat =
        WireCompat.from(Charts.compute(BirthInput(date, time, lat, lon, zone, "x"), blood).compatProfile(includeBlood = true))

    private var screen by mutableStateOf("")

    /** Shows screen [show] (default [name]) and captures it as [name]. */
    private fun stage(name: String, settleMs: Long = 1500, show: String = name) {
        screen = show
        compose.waitForIdle()
        settle(settleMs)
        capture(name)
    }

    @Composable
    private fun Shell(content: @Composable () -> Unit) {
        ConjunctionTheme { SheetHost { content() } }
    }

    @Test
    fun renderEveryScreen() {
        Shadows.shadowOf(app.packageManager).setSystemFeature(android.content.pm.PackageManager.FEATURE_BLUETOOTH_LE, true)
        val container = freshContainer(NoModel())
        val deps = OnboardingDeps(fakePlaces, OnboardingAssistant(NoModel()))
        val charts = Charts.compute(birth, profile.bloodType)
        val me = charts.compatProfile(includeBlood = false)
        val myPhoto = photo(R.drawable.zodiac_leo, "me")
        val orionPhoto = photo(R.drawable.zodiac_sagittarius, "orion")
        val lyraPhoto = photo(R.drawable.zodiac_libra, "lyra")
        val orion = peer(LocalDate.of(1995, 12, 5), LocalTime.of(14, 10), 40.71, -74.0, "America/New_York")
        val nova = peer(LocalDate.of(1996, 3, 12), LocalTime.of(6, 0), 51.5, -0.12, "Europe/London")
        val vega = peer(LocalDate.of(1992, 5, 21), LocalTime.of(9, 0), 34.05, -118.24, "America/Los_Angeles")
        val lyra = peer(LocalDate.of(1993, 10, 12), LocalTime.of(21, 30), 48.85, 2.35, "Europe/Paris")
        fun score(c: WireCompat) = Compatibility.score(me, c.toCompatProfile())
        val now = System.currentTimeMillis()
        // The scaffold computes the charts from the stored profile, as in the app.
        kotlinx.coroutines.runBlocking { container.nearby.store.load(); container.profileStore.update { profile.copy(localPhotoPath = myPhoto) } }
        fun enc(fp: String, c: WireCompat, name: String, age: Int, photo: String?, mySwipe: String? = null, friend: String? = null, created: Long = now): StoredEncounter {
            val r = score(c)
            val sun = com.metavirtuoso.conjunction.core.chart.ZodiacSign.of(c.sun / 100.0).name.lowercase()
            val moon = com.metavirtuoso.conjunction.core.chart.ZodiacSign.of(c.moon / 100.0).name.lowercase()
            val rising = c.asc?.let { com.metavirtuoso.conjunction.core.chart.ZodiacSign.of(it / 100.0).name.lowercase() }
            return StoredEncounter(
                fp, "dating", r.total, r.western, r.chinese, r.blood, r.highlights, r.isBirthdayTwin, r.isExactTwin, age, sun, moon, rising,
                c.animal.key, "single", "both", firstName = name, bio = "Chasing comets on weekends.", photoPath = photo, photoScreening = Screening.SAFE,
                mySwipe = mySwipe, friendId = friend, createdAt = created, expiresAt = now + 20 * 3_600_000L,
                peerCompat = c, chineseRelation = r.chineseRelation.name, stemRelation = r.stemRelation.name,
            )
        }

        screen = "onb_1_welcome"
        compose.mainClock.autoAdvance = false
        compose.setContent {
            key(screen) {
                Shell {
                    when (screen) {
                        "onb_1_welcome" -> OnboardingFlow(container, Profile(), onFinished = {}, deps = deps)
                        "onb_2_birth" -> OnboardingFlow(container, Profile(onboardingStep = "BIRTH"), onFinished = {}, deps = deps)
                        "onb_blocked_under18" -> AgeBlockedScreen()
                        "onb_3_about_you" -> OnboardingFlow(container, Profile(birth = birth, onboardingStep = "ABOUT"), onFinished = {}, deps = deps)
                        "onb_4_photo_bio" -> OnboardingFlow(
                            container,
                            Profile(birth = birth, onboardingStep = "CARD", localPhotoPath = myPhoto, bio = "Leo sun with a spark to match. Aries moon. Into stargazing and jazz.", instagram = "celeste.stars"),
                            onFinished = {}, deps = deps,
                        )
                        "tab_today_off" -> MainScaffold(container, profile.copy(localPhotoPath = myPhoto), null, {}, initialTab = Tab.TODAY)
                        "tab_today_on" -> MainScaffold(container, profile.copy(nearbyEnabled = true, localPhotoPath = myPhoto), null, {}, initialTab = Tab.TODAY)
                        "nearby_off" -> MainScaffold(container, profile, null, {}, initialTab = Tab.NEARBY)
                        "nearby_on" -> MainScaffold(container, profile.copy(nearbyEnabled = true), null, {}, initialTab = Tab.NEARBY)
                        "nearby_consent_sheet" -> Box {
                            NearbyScreen(container, profile.copy(gender = null, seeking = null, relationshipStatus = null, lookingFor = null), charts)
                            NearbyConsentSheet(container, profile.copy(gender = Gender.FEMALE, seeking = null, relationshipStatus = null, lookingFor = null, orientationConsent = false, nearbyConsent = false), onTurnOn = {}, onDismiss = {})
                        }
                        "match_popup_conjunction" -> MatchExperience(
                            container, NearbyEvent(NearbyEventKind.CONJUNCTION, "fpLyra", "f1", "dating", score(lyra).total), charts, profile, onDismiss = {}, onOpenChat = {},
                        )
                        "compat_full" -> CompatibilityScreen(container, profile.copy(localPhotoPath = myPhoto), charts, "f1", onClose = {})
                        "compat_pending_wheel" -> CompatibilityScreen(container, profile.copy(localPhotoPath = myPhoto), charts, "f2", onClose = {})
                        "chat_header" -> ChatScreen(container, ChatTarget("f1"), onClose = {})
                        "profile_tab" -> MainScaffold(container, profile.copy(localPhotoPath = myPhoto), null, {}, initialTab = Tab.PROFILE)
                        "settings_nearby" -> MainScaffold(container, profile.copy(nearbyEnabled = true), null, {}, initialTab = Tab.PROFILE, initialSettings = SettingsPage.NEARBY)
                        "settings_privacy_legal" -> MainScaffold(container, profile, null, {}, initialTab = Tab.PROFILE, initialSettings = SettingsPage.PRIVACY)
                        "legal_notice_sheet" -> Box {
                            MainScaffold(container, profile, null, {}, initialTab = Tab.TODAY)
                            LegalUpdateSheet(listOf(LegalDoc.TERMS, LegalDoc.WAIVER, LegalDoc.PRIVACY), reaccept = false) {}
                        }
                        "legal_reaccept_sheet" -> Box {
                            MainScaffold(container, profile, null, {}, initialTab = Tab.TODAY)
                            LegalUpdateSheet(listOf(LegalDoc.TERMS, LegalDoc.COMMUNITY), reaccept = true) {}
                        }
                        "readings_eastern" -> MainScaffold(container, profile, null, {}, initialTab = Tab.READINGS, initialSegment = ReadingsSegment.EASTERN)
                        "readings_palm" -> MainScaffold(container, profile, null, {}, initialTab = Tab.READINGS, initialSegment = ReadingsSegment.PALM)
                        "today" -> MainScaffold(container, profile, null, {}, initialTab = Tab.TODAY)
                        "chart" -> MainScaffold(container, profile, null, {}, initialTab = Tab.CHART)
                    }
                }
            }
        }

        // Onboarding (empty ledger, so L1 shows).
        stage("onb_1_welcome")
        screen = "onb_2_birth"; settle(1500); capture("onb_2_birth_empty")
        compose.onNodeWithTag("birthText").performTextInput("Aug 7 1994, 4:53 pm, Providence RI")
        settle(2500); capture("onb_2_birth_parsed")
        compose.onNodeWithTag("clickwrap").performTouchInput { click(Offset(30f, 60f)) }
        settle(600); capture("onb_2_birth_ready")
        stage("onb_blocked_under18")
        screen = "onb_3_about_you"; settle(1500)
        compose.onNodeWithTag("aboutText").performTextInput("I'm Sam, a single guy into hiking and jazz, looking to date women")
        settle(2000); capture("onb_3_about_you")
        stage("onb_4_photo_bio", 2000)

        // Tab bar and Nearby, empty.
        stage("tabbar_nearby_off", 3000, show = "tab_today_off")
        stage("nearby_empty_off", show = "nearby_off")
        Shadows.shadowOf(app).grantPermissions(android.Manifest.permission.BLUETOOTH_SCAN, android.Manifest.permission.BLUETOOTH_ADVERTISE, android.Manifest.permission.BLUETOOTH_CONNECT)
        stage("nearby_empty_listening", show = "nearby_on")

        // Sample Nearby data on this (Robolectric) phone.
        val lyraScore = score(lyra)
        container.nearby.store.update { st ->
            st.copy(
                encounters = listOf(
                    enc("fpOrion", orion, "Orion", 30, orionPhoto, created = now),
                    enc("fpNova", nova, "Nova", 30, null, created = now - 60_000),
                    enc("fpVega", vega, "Vega", 34, null, mySwipe = "right"),
                    enc("fpLyra", lyra, "Lyra", 33, lyraPhoto, mySwipe = "right", friend = "f1"),
                ),
                friends = listOf(
                    StoredFriend(
                        "f1", "AA==", "AA==", "20854 15157 29182 95769 12447 76044", "dating", "Lyra", "Tea and telescopes.", "libra", "pisces", null, "rooster", lyraPhoto, Screening.SAFE, now, now,
                        peerCompat = lyra, score = NearbyEngine.storedScoreOf(lyraScore), peerIg = "lyra.skies", peerIgRev = 0,
                    ),
                    // A friend from before UX 2.0: no stored chart yet.
                    StoredFriend("f2", "AQ==", "AQ==", "11111 22222 33333 44444 55555 66666", "friends", "Sol", null, "capricorn", "virgo", null, "horse", null, Screening.SAFE, now - 86_400_000, now - 86_400_000,
                        score = com.metavirtuoso.conjunction.nearby.StoredScore(74, 70, 82, null, listOf("Sun–Moon trine"), false, false)),
                ),
                messages = listOf(
                    ChatLine("a".repeat(32), "f1", false, "Hi! Was that you by the planetarium?", now - 600_000, ChatStatus.RECEIVED, Screening.SAFE, seen = true),
                    ChatLine("b".repeat(32), "f1", true, "Yes! The one with the gold scarf ☌", now - 540_000, ChatStatus.READ),
                    ChatLine("d".repeat(32), "f1", true, "See you Saturday", now - 60_000, ChatStatus.QUEUED),
                ),
            )
        }
        stage("tabbar_nearby_on_badge", 2000, show = "tab_today_on")
        stage("nearby_cards", 2000, show = "nearby_off")
        stage("nearby_consent_sheet")
        // The Details link is pinned in the sheet footer (always on screen).
        compose.onNodeWithText(app.getString(R.string.nearby_details)).performClick()
        settle(800); capture("nearby_details")
        stage("match_popup_conjunction", 2000)
        stage("compat_full", 2500)
        stage("compat_pending_wheel", 2000)
        stage("chat_header")
        stage("profile_tab", 2000)
        // The Nearby consent as the ledger holds it, so the settings rows show "Given <date>".
        kotlinx.coroutines.runBlocking {
            listOf(com.metavirtuoso.conjunction.legal.ConsentKeys.NEARBY_BLUETOOTH, com.metavirtuoso.conjunction.legal.ConsentKeys.ORIENTATION_DATA).forEach {
                container.consentLedger.record(com.metavirtuoso.conjunction.legal.ConsentRecord.now(it, com.metavirtuoso.conjunction.legal.ConsentKeys.COPY_VERSION, "fixture"))
            }
        }
        stage("settings_nearby")
        stage("settings_privacy_legal", 2000)
        stage("legal_notice_sheet", 2500)
        stage("legal_reaccept_sheet", 2500)
        stage("readings_eastern", 3000)
        stage("readings_palm", 1500)
        stage("today", 3000)
        stage("chart", 3500)
    }

    /** L-4: at 360×640 dp the L1 line and Begin are on screen together, without scrolling. */
    @Test
    @Config(qualifiers = "w360dp-h640dp-xxhdpi")
    fun clickwrapFitsASmallScreen() {
        val container = freshContainer(NoModel())
        compose.mainClock.autoAdvance = false
        compose.setContent {
            Shell { OnboardingFlow(container, Profile(onboardingStep = "BIRTH"), onFinished = {}, deps = OnboardingDeps(fakePlaces, OnboardingAssistant(NoModel()))) }
        }
        settle(1500)
        compose.onNodeWithTag("birthText").performTextInput("Aug 7 1994, 4:53 pm, Providence RI")
        settle(2500)
        compose.onNodeWithTag("clickwrap").performTouchInput { click(Offset(30f, 60f)) }
        settle(600)
        val root = compose.onRoot().getUnclippedBoundsInRoot()
        val box = compose.onNodeWithTag("clickwrap").assertIsDisplayed().getUnclippedBoundsInRoot()
        val begin = compose.onNodeWithTag("begin").assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue("L1 above Begin", box.bottom <= begin.top)
        assertTrue("Begin on screen", begin.bottom <= root.bottom)
        assertTrue("L1 on screen", box.top >= root.top)
        capture("onb_2_birth_small_screen")
    }
}
