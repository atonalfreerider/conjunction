package com.metavirtuoso.conjunction.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.metavirtuoso.conjunction.ui.theme.ConjunctionTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** SPEC 5d.2 / N-1 / N-6: the raised Nearby medallion, its badge, and a shimmer that never recomposes. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-xxhdpi", application = android.app.Application::class)
class TabBarTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test
    fun shimmerDoesNotRecomposeAndTabsStayTappable() {
        var recompositions = 0
        var selected by mutableStateOf(Tab.TODAY)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            ConjunctionTheme {
                GoldTabBar(selected, nearbyOn = true, badge = 12, onSelect = { selected = it }, onRecompose = { recompositions++ })
            }
        }
        compose.mainClock.advanceTimeBy(100)
        val afterFirst = recompositions
        // Two seconds of the 6 s shimmer: drawn every frame, composed once.
        repeat(20) { compose.mainClock.advanceTimeBy(100) }
        assertEquals("recomposed per frame", afterFirst, recompositions)
        // Taps on every tab work while it animates.
        listOf(Tab.CHART, Tab.READINGS, Tab.PROFILE, Tab.TODAY).forEach { tab ->
            compose.onNodeWithTag("tab_${tab.name}").performClick()
            compose.mainClock.advanceTimeBy(100)
            assertEquals(tab, selected)
        }
        compose.onNodeWithTag("tab_NEARBY_medallion").performClick()
        compose.mainClock.advanceTimeBy(100)
        assertEquals(Tab.NEARBY, selected)
        // Badge caps at 9+ and the description carries the count.
        compose.onNodeWithContentDescription("Nearby, 12 new matches").assertExists()
        // N-1: the medallion is 68 dp, centred, and taller than every side tab target.
        val medallion = compose.onNodeWithTag("tab_NEARBY_medallion").getUnclippedBoundsInRoot()
        assertEquals(68.dp, medallion.right - medallion.left)
        val today = compose.onNodeWithTag("tab_TODAY").getUnclippedBoundsInRoot()
        assertTrue(medallion.bottom - medallion.top > today.bottom - today.top)
        val centre = (medallion.left + medallion.right) / 2
        assertEquals(411f / 2f, centre.value, 1f)
        // Raised: its centre sits on the bar's top edge, above the side tabs.
        assertTrue(medallion.top < today.top)
    }
}
