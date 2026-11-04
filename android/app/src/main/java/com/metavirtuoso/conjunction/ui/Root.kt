package com.metavirtuoso.conjunction.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Brightness3
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.PanTool
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.metavirtuoso.conjunction.AppContainer
import com.metavirtuoso.conjunction.R
import com.metavirtuoso.conjunction.data.Charts
import com.metavirtuoso.conjunction.data.Profile
import com.metavirtuoso.conjunction.data.charts
import com.metavirtuoso.conjunction.legal.ConsentRecord
import com.metavirtuoso.conjunction.legal.LegalDoc
import com.metavirtuoso.conjunction.legal.LegalTexts
import com.metavirtuoso.conjunction.nearby.PushEvent
import com.metavirtuoso.conjunction.nearby.PushKind
import com.metavirtuoso.conjunction.ui.match.MatchExperience
import com.metavirtuoso.conjunction.ui.screens.AgeBlockedScreen
import com.metavirtuoso.conjunction.ui.screens.ChartScreen
import com.metavirtuoso.conjunction.ui.screens.ChatScreen
import com.metavirtuoso.conjunction.ui.screens.EasternScreen
import com.metavirtuoso.conjunction.ui.screens.NearbyScreen
import com.metavirtuoso.conjunction.ui.screens.OnboardingFlow
import com.metavirtuoso.conjunction.ui.screens.PalmScreen
import com.metavirtuoso.conjunction.ui.screens.ProfileScreen
import com.metavirtuoso.conjunction.ui.screens.ReacceptScreen
import com.metavirtuoso.conjunction.ui.screens.TodayScreen
import com.metavirtuoso.conjunction.ui.theme.GoldBrushes
import com.metavirtuoso.conjunction.ui.theme.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Tab(val label: Int, val icon: ImageVector) {
    CHART(R.string.tab_chart, Icons.Outlined.Explore),
    TODAY(R.string.tab_today, Icons.Outlined.Brightness3),
    EASTERN(R.string.tab_eastern, Icons.Outlined.AutoAwesome),
    PALM(R.string.tab_palm, Icons.Outlined.PanTool),
    NEARBY(R.string.tab_nearby, Icons.Outlined.Favorite),
    PROFILE(R.string.tab_profile, Icons.Outlined.Person),
}

/** An open chat (after a mutual match). */
data class ChatTarget(val pairId: String, val otherUid: String)

/**
 * Top of the UI: waits for the stored profile, then shows the age block, onboarding, a legal
 * re-acceptance when the bundled documents changed version, or the main tabs. Match / twin /
 * conjunction events (FCM in the foreground, or a tapped notification) open the full-screen
 * match experience over everything.
 */
@Composable
fun ConjunctionRoot(container: AppContainer, pendingEvent: PushEvent?, onEventConsumed: () -> Unit, onReady: () -> Unit) {
    val profile by container.profileStore.profile.collectAsState(initial = null)
    val p = profile
    LaunchedEffect(p != null) { if (p != null) onReady() }
    if (p == null) {
        Box(Modifier.fillMaxSize().background(Palette.Midnight))
        return
    }
    when {
        p.ageBlocked -> AgeBlockedScreen()
        !p.onboardingComplete -> OnboardingFlow(container, p, onFinished = {})
        else -> {
            val changed = rememberChangedLegalDocs(container)
            if (changed.isNotEmpty()) {
                val context = LocalContext.current
                val scope = rememberCoroutineScope()
                ReacceptScreen(changed) {
                    scope.launch {
                        changed.forEach { doc ->
                            val text = withContext(Dispatchers.IO) { LegalTexts.load(context, doc) }
                            container.consentLedger.record(ConsentRecord.now(doc.key, text.version, text.markdown))
                        }
                    }
                }
            } else {
                MainScaffold(container, p, pendingEvent, onEventConsumed)
            }
        }
    }
}

/** Accepted documents whose bundled version is newer than the accepted one. */
@Composable
private fun rememberChangedLegalDocs(container: AppContainer): List<LegalDoc> {
    val context = LocalContext.current
    val records by container.consentLedger.records.collectAsState(initial = null)
    val state = produceState(initialValue = emptyList<LegalDoc>(), records) {
        val list = records ?: return@produceState
        value = withContext(Dispatchers.IO) {
            listOf(LegalDoc.WAIVER, LegalDoc.TERMS, LegalDoc.PRIVACY).filter { doc ->
                val accepted = list.lastOrNull { it.key == doc.key && !it.withdrawn }?.version
                val bundled = runCatching { LegalTexts.load(context, doc).version }.getOrNull()
                bundled != null && accepted != null && accepted != bundled
            }
        }
    }
    return state.value
}

@Composable
private fun MainScaffold(container: AppContainer, profile: Profile, pendingEvent: PushEvent?, onEventConsumed: () -> Unit) {
    val context = LocalContext.current
    var tab by rememberSaveable { mutableStateOf(Tab.TODAY) }
    var chat by remember { mutableStateOf<ChatTarget?>(null) }
    var overlay by remember { mutableStateOf<PushEvent?>(null) }
    val charts by remember(container) { container.profileStore.profile.charts() }.collectAsState(initial = null)
    val scope = rememberCoroutineScope()

    // Foreground pushes and tapped notifications.
    LaunchedEffect(Unit) { container.nearby.events.collect { overlay = it } }
    LaunchedEffect(pendingEvent) {
        val event = pendingEvent ?: return@LaunchedEffect
        if (event.kind == PushKind.MESSAGE && event.pairId != null) {
            tab = Tab.NEARBY
        } else {
            overlay = event
        }
        onEventConsumed()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        scope.launch { container.nearby.resumeServiceIfNeeded(granted) }
    }

    Box(Modifier.fillMaxSize().background(Palette.Midnight)) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                AnimatedContent(targetState = tab, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "tabs") { current ->
                    TabContent(current, container, profile, charts, openChat = { chat = it }, openTab = { tab = it })
                }
            }
            GoldTabBar(tab) { tab = it }
        }
        chat?.let { target ->
            ChatScreen(container, target, onClose = { chat = null })
        }
        overlay?.let { event ->
            MatchExperience(
                container = container,
                event = event,
                myCharts = charts,
                profile = profile,
                onDismiss = { overlay = null },
                onOpenChat = { target -> overlay = null; chat = target },
            )
        }
    }
}

@Composable
private fun TabContent(tab: Tab, container: AppContainer, profile: Profile, charts: Charts?, openChat: (ChatTarget) -> Unit, openTab: (Tab) -> Unit) {
    when (tab) {
        Tab.CHART -> ChartScreen(container, charts)
        Tab.TODAY -> TodayScreen(container, charts)
        Tab.EASTERN -> EasternScreen(container, profile, charts)
        Tab.PALM -> PalmScreen(container)
        Tab.NEARBY -> NearbyScreen(container, profile, charts, openChat)
        Tab.PROFILE -> ProfileScreen(container, profile, charts)
    }
}

/** Ornate bottom bar: indigo glass, gold hairline, the selected tab lit in shimmering gold. */
@Composable
private fun GoldTabBar(selected: Tab, onSelect: (Tab) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Palette.InkViolet.copy(alpha = 0.96f), Palette.Midnight)))
            .navigationBarsPadding(),
    ) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(GoldBrushes.leaf))
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp).selectableGroup(), verticalAlignment = Alignment.CenterVertically) {
            Tab.entries.forEach { tab ->
                val isSelected = tab == selected
                Column(
                    Modifier
                        .weight(1f)
                        .selectable(selected = isSelected, role = Role.Tab) { onSelect(tab) }
                        .padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier
                            .size(36.dp)
                            .then(if (isSelected) Modifier.border(1.dp, GoldBrushes.leaf, androidx.compose.foundation.shape.CircleShape) else Modifier),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(tab.icon, contentDescription = null, tint = if (isSelected) Palette.GoldPale else Palette.MoonstoneDim, modifier = Modifier.size(22.dp))
                    }
                    Text(
                        stringResource(tab.label).uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isSelected) Palette.Gold else Palette.MoonstoneDim,
                        maxLines = 1,
                        overflow = TextOverflow.Clip,
                    )
                }
            }
        }
    }
}
