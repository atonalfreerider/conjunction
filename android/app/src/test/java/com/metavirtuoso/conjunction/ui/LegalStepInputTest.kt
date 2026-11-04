package com.metavirtuoso.conjunction.ui

import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import android.graphics.Bitmap
import com.metavirtuoso.conjunction.AppContainer
import com.metavirtuoso.conjunction.ai.GeneratedText
import com.metavirtuoso.conjunction.ai.ModelAvailability
import com.metavirtuoso.conjunction.ai.ModelUnavailableException
import com.metavirtuoso.conjunction.ai.TextModel
import com.metavirtuoso.conjunction.data.Profile
import com.metavirtuoso.conjunction.ui.screens.OnboardingFlow
import com.metavirtuoso.conjunction.ui.theme.ConjunctionTheme
import java.time.LocalDate
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private class NoModel : TextModel {
    override suspend fun availability() = ModelAvailability.Unavailable("test")
    override suspend fun generate(prompt: String, image: Bitmap?, onStatus: (String) -> Unit): GeneratedText =
        throw ModelUnavailableException("no on-device model in tests")
    override fun close() = Unit
}

/** The waiver page's tick boxes must toggle (they once ignored every tap on a Pixel 10). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-xxhdpi", application = android.app.Application::class)
class LegalStepInputTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test
    fun tickBoxesToggle() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val container = AppContainer(context, NoModel())
        compose.mainClock.autoAdvance = false
        compose.setContent {
            ConjunctionTheme {
                OnboardingFlow(container, Profile(ageGateBirthDate = LocalDate.of(1994, 8, 7)), onFinished = {})
            }
        }
        compose.mainClock.advanceTimeBy(2_000)
        val age = compose.onNodeWithText("I am 18 or older.")
        age.performScrollTo()
        compose.mainClock.advanceTimeBy(500)
        age.assertIsOff()
        age.performClick()
        compose.mainClock.advanceTimeBy(500)
        age.assertIsOn()
    }
}
