package com.metavirtuoso.conjunction.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.metavirtuoso.conjunction.R
import com.metavirtuoso.conjunction.nearby.EncounterCard
import com.metavirtuoso.conjunction.nearby.MatchCard
import com.metavirtuoso.conjunction.ui.match.MatchAnimation
import com.metavirtuoso.conjunction.ui.match.SwipeCardPage
import com.metavirtuoso.conjunction.ui.theme.ConjunctionTheme

/**
 * DEBUG BUILDS ONLY (src/debug): shows the match experience with a sample encounter so it can be
 * checked and screenshotted on a phone without a second phone nearby.
 *
 * ```
 * adb shell am start -n com.metavirtuoso.conjunction/.debug.MatchGalleryActivity --es screen match|twin|card
 * ```
 * The animation replays when it finishes. Not present in release builds.
 */
class MatchGalleryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        val screen = intent.getStringExtra("screen") ?: "match"
        val card = EncounterCard(
            encounterId = "demo", kind = "dating", status = "pending",
            total = 87, western = 84, chinese = 95, blood = 78,
            highlights = listOf("Your Venus trines their Sun", "Horse and Dog are trine allies"),
            birthdayTwin = screen == "twin", exactTwin = false,
            card = MatchCard("Orion", 31, "sagittarius", "leo", "aries", "horse", "single", "both", hasPhoto = false),
            photoPath = null,
        )
        setContent {
            ConjunctionTheme {
                var run by remember { mutableIntStateOf(0) }
                when (screen) {
                    "card" -> SwipeCardPage(card, "Fire meets fire: a bright, easy warmth.", onSwipe = {}, onChart = {}, onMenu = {}, onClose = { finish() })
                    else -> androidx.compose.runtime.key(run) {
                        MatchAnimation(card, R.drawable.zodiac_leo, null, "Fire meets fire: a bright, easy warmth.", onFinished = { run++ })
                    }
                }
            }
        }
    }
}
