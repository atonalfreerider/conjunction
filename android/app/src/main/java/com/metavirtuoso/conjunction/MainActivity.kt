package com.metavirtuoso.conjunction

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.metavirtuoso.conjunction.nearby.PushEvent
import com.metavirtuoso.conjunction.ui.ConjunctionRoot
import com.metavirtuoso.conjunction.ui.theme.ConjunctionTheme

class MainActivity : ComponentActivity() {
    /** A notification tap or deep link waiting to be shown (match popup, chat). */
    private val pendingEvent = mutableStateOf<PushEvent?>(null)
    private var ready = false

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        splash.setKeepOnScreenCondition { !ready }
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        pendingEvent.value = PushEvent.fromIntent(intent)
        val container = (application as ConjunctionApp).container
        val reducedMotion = Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        setContent {
            ConjunctionTheme(reducedMotion = reducedMotion) {
                ConjunctionRoot(
                    container = container,
                    pendingEvent = pendingEvent.value,
                    onEventConsumed = { pendingEvent.value = null },
                    onReady = { ready = true },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        PushEvent.fromIntent(intent)?.let { pendingEvent.value = it }
    }
}
