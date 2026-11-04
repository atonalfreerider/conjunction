package com.metavirtuoso.conjunction.ui

import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Brightness3
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.metavirtuoso.conjunction.AppContainer
import com.metavirtuoso.conjunction.R
import com.metavirtuoso.conjunction.data.Charts
import com.metavirtuoso.conjunction.data.Instagram
import com.metavirtuoso.conjunction.data.Profile
import com.metavirtuoso.conjunction.data.charts
import com.metavirtuoso.conjunction.legal.ConsentKeys
import com.metavirtuoso.conjunction.legal.ConsentRecord
import com.metavirtuoso.conjunction.legal.LegalTexts
import com.metavirtuoso.conjunction.legal.LegalUpdate
import com.metavirtuoso.conjunction.legal.LegalVersions
import com.metavirtuoso.conjunction.nearby.NearbyEvent
import com.metavirtuoso.conjunction.nearby.NearbyEventKind
import com.metavirtuoso.conjunction.ui.components.CelestialSheet
import com.metavirtuoso.conjunction.ui.components.GhostButton
import com.metavirtuoso.conjunction.ui.components.GoldButton
import com.metavirtuoso.conjunction.ui.components.GoldText
import com.metavirtuoso.conjunction.ui.components.SheetHost
import com.metavirtuoso.conjunction.ui.match.CompatibilityScreen
import com.metavirtuoso.conjunction.ui.match.MatchExperience
import com.metavirtuoso.conjunction.ui.screens.AgeBlockedScreen
import com.metavirtuoso.conjunction.ui.screens.ChartScreen
import com.metavirtuoso.conjunction.ui.screens.ChatScreen
import com.metavirtuoso.conjunction.ui.screens.LegalUpdateSheet
import com.metavirtuoso.conjunction.ui.screens.NearbyScreen
import com.metavirtuoso.conjunction.ui.screens.OnboardingFlow
import com.metavirtuoso.conjunction.ui.screens.ProfileScreen
import com.metavirtuoso.conjunction.ui.screens.ReadingsScreen
import com.metavirtuoso.conjunction.ui.screens.ReadingsSegment
import com.metavirtuoso.conjunction.ui.screens.SettingsPage
import com.metavirtuoso.conjunction.ui.screens.TodayScreen
import com.metavirtuoso.conjunction.ui.theme.GoldBrushes
import com.metavirtuoso.conjunction.ui.theme.LocalReducedMotion
import com.metavirtuoso.conjunction.ui.theme.Palette
import com.metavirtuoso.conjunction.ui.theme.rememberGoldShimmer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Bar order (SPEC 5d.2): Today · Chart · [Nearby] · Readings · Profile. */
enum class Tab(val label: Int, val icon: ImageVector?) {
    TODAY(R.string.tab_today, Icons.Outlined.Brightness3),
    CHART(R.string.tab_chart, Icons.Outlined.Explore),
    NEARBY(R.string.tab_nearby, null),
    READINGS(R.string.tab_readings, Icons.Outlined.AutoAwesome),
    PROFILE(R.string.tab_profile, Icons.Outlined.Person);

    companion object {
        /** A saved tab name; the old EASTERN / PALM tabs open Readings with that segment. */
        fun fromSaved(name: String?): Pair<Tab, ReadingsSegment?> = when (name) {
            "EASTERN" -> READINGS to ReadingsSegment.EASTERN
            "PALM" -> READINGS to ReadingsSegment.PALM
            else -> (entries.firstOrNull { it.name == name } ?: TODAY) to null
        }
    }
}

/** An open chat with a friend (after a mutual match), by local friend id. */
data class ChatTarget(val friendId: String)

/** App-wide navigation for screens deep in the tree (chat, compatibility, settings, tabs). */
class AppNav(
    val openChat: (String) -> Unit = {},
    val openCompat: (String) -> Unit = {},
    val openSettings: (SettingsPage) -> Unit = {},
    val openTab: (Tab) -> Unit = {},
)

val LocalAppNav = staticCompositionLocalOf { AppNav() }

/** Height of the bar itself (the Nearby medallion rises 34 dp above it). */
val TabBarHeight: Dp = 64.dp
val TabBarOverhang: Dp = 34.dp

/**
 * Top of the UI: waits for the stored profile, then shows the age block, onboarding or the main
 * tabs (with the legal update sheet when documents changed). Match events open the full-screen
 * match experience. [share] is an Instagram profile link shared to the app (SPEC 5d.6).
 */
@Composable
fun ConjunctionRoot(
    container: AppContainer,
    pendingEvent: NearbyEvent?,
    onEventConsumed: () -> Unit,
    onReady: () -> Unit,
    share: Instagram.ShareResult? = null,
    onShareConsumed: () -> Unit = {},
) {
    val profile by container.profileStore.profile.collectAsState(initial = null)
    val p = profile
    LaunchedEffect(p != null) { if (p != null) onReady() }
    if (p == null) {
        Box(Modifier.fillMaxSize().background(Palette.Midnight))
        return
    }
    SheetHost {
        when {
            p.ageBlocked -> AgeBlockedScreen()
            !p.onboardingComplete -> OnboardingFlow(container, p, onFinished = {})
            else -> {
                MainScaffold(container, p, pendingEvent, onEventConsumed)
                LegalUpdateGate(container)
            }
        }
        if (!p.ageBlocked) InstagramShareSheet(container, share, onShareConsumed)
    }
}

/** "Use @handle as your Instagram?" for a shared profile link; other shared text gets a toast. */
@Composable
private fun InstagramShareSheet(container: AppContainer, share: Instagram.ShareResult?, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    when (share) {
        null -> Unit
        Instagram.ShareResult.NotAProfile -> LaunchedEffect(share) {
            Toast.makeText(context, R.string.instagram_share_not_profile, Toast.LENGTH_LONG).show()
            onDone()
        }
        is Instagram.ShareResult.Handle -> CelestialSheet(onDismiss = onDone) {
            GoldText(stringResource(R.string.instagram_from_share_title, share.handle), style = MaterialTheme.typography.headlineSmall, heading = true)
            com.metavirtuoso.conjunction.ui.components.FinePrint(stringResource(R.string.instagram_note))
            GoldButton(stringResource(R.string.instagram_from_share_use), modifier = Modifier.fillMaxWidth(), onClick = {
                scope.launch { container.profileStore.update { it.copy(instagram = share.handle) } }
                onDone()
            })
            GhostButton(stringResource(R.string.not_now), onClick = onDone, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** SPEC 5d.1.4: re-accept sheet (major version changed) or one-time notice (minor change). */
@Composable
private fun LegalUpdateGate(container: AppContainer) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val records by container.consentLedger.records.collectAsState(initial = null)
    val bundled by produceState<Map<com.metavirtuoso.conjunction.legal.LegalDoc, String>?>(null) {
        value = withContext(Dispatchers.IO) { LegalTexts.bundledVersions(context) }
    }
    val r = records ?: return
    val b = bundled ?: return
    when (val update = LegalVersions.evaluate(r, b)) {
        LegalUpdate.None -> Unit
        is LegalUpdate.Reaccept -> LegalUpdateSheet(update.docs, reaccept = true) { body ->
            scope.launch {
                update.docs.forEach { doc ->
                    val text = withContext(Dispatchers.IO) { runCatching { LegalTexts.load(context, doc) }.getOrNull() } ?: return@forEach
                    container.consentLedger.record(ConsentRecord.now(doc.key, text.version, body, docSha256 = text.sha256))
                }
            }
        }
        is LegalUpdate.Notice -> LegalUpdateSheet(update.docs, reaccept = false) { body ->
            scope.launch {
                update.docs.forEach { doc ->
                    val text = withContext(Dispatchers.IO) { runCatching { LegalTexts.load(context, doc) }.getOrNull() } ?: return@forEach
                    container.consentLedger.record(ConsentRecord.now(ConsentKeys.noticeSeen(doc), text.version, body, docSha256 = text.sha256))
                }
            }
        }
    }
}

@Composable
fun MainScaffold(
    container: AppContainer,
    profile: Profile,
    pendingEvent: NearbyEvent?,
    onEventConsumed: () -> Unit,
    initialTab: Tab? = null,
    initialSettings: SettingsPage? = null,
    initialCompat: String? = null,
    initialSegment: ReadingsSegment = ReadingsSegment.EASTERN,
) {
    var tabName by rememberSaveable { mutableStateOf((initialTab ?: if (profile.landOnChart) Tab.CHART else Tab.TODAY).name) }
    val (tab, savedSegment) = Tab.fromSaved(tabName)
    var segment by rememberSaveable { mutableStateOf(savedSegment ?: initialSegment) }
    var settings by remember { mutableStateOf(initialSettings) }
    var chat by remember { mutableStateOf<ChatTarget?>(null) }
    var compat by remember { mutableStateOf(initialCompat) }
    var overlay by remember { mutableStateOf<NearbyEvent?>(null) }
    val charts by remember(container) { container.profileStore.profile.charts() }.collectAsState(initial = null)
    val pending by remember(container) { container.nearby.pendingCount() }.collectAsState(initial = 0)
    val scope = rememberCoroutineScope()
    fun select(t: Tab) { tabName = t.name; if (t != Tab.PROFILE) settings = null }

    LaunchedEffect(profile.landOnChart) {
        if (profile.landOnChart) container.profileStore.update { it.copy(landOnChart = false) }
    }
    // Matches found while the app is open, and tapped notifications.
    LaunchedEffect(Unit) {
        container.nearby.events.collect { event ->
            // A mutual match while that person's card is already on screen: the card moves on by itself.
            if (overlay?.encounterId == null || overlay?.encounterId != event.encounterId) overlay = event
        }
    }
    LaunchedEffect(pendingEvent) {
        val event = pendingEvent ?: return@LaunchedEffect
        val friend = event.friendId
        select(Tab.NEARBY)
        if (event.kind == NearbyEventKind.MESSAGE && friend != null) chat = ChatTarget(friend) else overlay = event
        onEventConsumed()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        scope.launch { container.nearby.resumeServiceIfNeeded() }
    }
    val nav = remember {
        AppNav(
            openChat = { compat = null; overlay = null; chat = ChatTarget(it) },
            openCompat = { compat = it },
            openSettings = { page -> select(Tab.PROFILE); settings = page },
            openTab = { select(it) },
        )
    }
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    androidx.compose.runtime.CompositionLocalProvider(LocalAppNav provides nav) {
        Box(Modifier.fillMaxSize().background(Palette.Midnight)) {
            // The raised medallion overhangs the bar by TabBarOverhang: pad for it too so nothing sits under it at rest (SPEC 5d.2).
            Box(Modifier.fillMaxSize().padding(bottom = TabBarHeight + TabBarOverhang + bottomInset)) {
                AnimatedContent(targetState = tab, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "tabs") { current ->
                    when (current) {
                        Tab.TODAY -> TodayScreen(container, profile, charts)
                        Tab.CHART -> ChartScreen(container, charts)
                        Tab.NEARBY -> NearbyScreen(container, profile, charts)
                        Tab.READINGS -> ReadingsScreen(container, profile, charts, segment) { segment = it }
                        Tab.PROFILE -> ProfileScreen(container, profile, charts, settings) { settings = it }
                    }
                }
            }
            GoldTabBar(tab, nearbyOn = profile.nearbyEnabled && !profile.isPaused(System.currentTimeMillis()), badge = pending, onSelect = ::select, modifier = Modifier.align(Alignment.BottomCenter))
            chat?.let { target -> ChatScreen(container, target, onClose = { chat = null }) }
            compat?.let { key -> CompatibilityScreen(container, profile, charts, key, onClose = { compat = null }) }
            overlay?.let { event ->
                MatchExperience(
                    container = container,
                    event = event,
                    myCharts = charts,
                    profile = profile,
                    onDismiss = { overlay = null },
                    onOpenChat = { target -> overlay = null; chat = target },
                    onOpenCompat = { key -> compat = key },
                )
            }
        }
    }
}

/**
 * The bar (SPEC 5d.2): four side tabs (36 dp circle, 22 dp icon, label) and the raised 68 dp
 * gold-leaf Nearby medallion in the centre, its centre on the bar's top edge. Off = desaturated;
 * on = a slow 6 s shimmer read only in the draw phase (no recomposition per frame); badge at
 * 1 o'clock for new matches. [onRecompose] is a test hook.
 */
@Composable
fun GoldTabBar(selected: Tab, nearbyOn: Boolean, badge: Int, onSelect: (Tab) -> Unit, modifier: Modifier = Modifier, onRecompose: (() -> Unit)? = null) {
    SideEffect { onRecompose?.invoke() }
    Box(modifier.fillMaxWidth()) {
        Column(
            Modifier
                .padding(top = TabBarOverhang)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Palette.InkViolet.copy(alpha = 0.97f), Palette.Midnight)))
                .navigationBarsPadding(),
        ) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(GoldBrushes.leaf))
            Row(Modifier.fillMaxWidth().height(TabBarHeight - 1.dp).selectableGroup(), verticalAlignment = Alignment.CenterVertically) {
                Tab.entries.forEach { tab ->
                    val isSelected = tab == selected
                    Column(
                        Modifier
                            .weight(1f)
                            .height(TabBarHeight - 1.dp)
                            .selectable(selected = isSelected, role = Role.Tab) { onSelect(tab) }
                            .testTag("tab_${tab.name}"),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Bottom,
                    ) {
                        if (tab.icon != null) {
                            Box(
                                Modifier.size(36.dp).then(if (isSelected) Modifier.border(1.dp, GoldBrushes.leaf, CircleShape) else Modifier),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(tab.icon, contentDescription = null, tint = if (isSelected) Palette.GoldPale else Palette.MoonstoneDim, modifier = Modifier.size(22.dp))
                            }
                        }
                        Text(
                            stringResource(tab.label).uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isSelected) Palette.Gold else Palette.MoonstoneDim,
                            maxLines = 1,
                            overflow = TextOverflow.Clip,
                            modifier = Modifier.padding(bottom = 6.dp),
                        )
                    }
                }
            }
        }
        NearbyMedallion(
            selected = selected == Tab.NEARBY, on = nearbyOn, badge = badge,
            onClick = { onSelect(Tab.NEARBY) },
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }
}

@Composable
private fun NearbyMedallion(selected: Boolean, on: Boolean, badge: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val reduced = LocalReducedMotion.current
    val phase = rememberGoldShimmer(6000)
    val grey = Color(0xFF8C8799)
    // Off: the gold desaturated by 40%.
    fun desat(c: Color) = if (on) c else lerp(c, grey, 0.4f)
    val ring = listOf(desat(Palette.GoldDeep), desat(Palette.GoldPale), desat(Palette.Gold))
    val description = if (badge > 0) stringResource(R.string.tab_nearby_badge_desc, badge) else stringResource(R.string.tab_nearby)
    Box(
        modifier
            .size(68.dp)
            .semantics { contentDescription = description }
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .testTag("tab_NEARBY_medallion"),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(68.dp)
                .clip(CircleShape)
                .drawBehind {
                    // Gradient #D4AF37 → #F5E6A8; when on, a light band sweeps across every 6 s.
                    if (on && !reduced) drawRect(GoldBrushes.shimmer(phase.value, period = size.width * 2f))
                    else drawRect(Brush.linearGradient(listOf(desat(Palette.Gold), desat(Palette.GoldPale), desat(Palette.Gold))))
                    // Filigree ring (2 dp) with a fine inner rule.
                    drawCircle(Brush.sweepGradient(ring + ring.first()), radius = size.minDimension / 2 - 1.dp.toPx(), style = Stroke(2.dp.toPx()))
                    drawCircle(Palette.Midnight.copy(alpha = 0.55f), radius = size.minDimension / 2 - 5.dp.toPx(), style = Stroke(0.8.dp.toPx()))
                    if (selected) drawCircle(Palette.Midnight.copy(alpha = 0.25f), radius = size.minDimension / 2 - 8.dp.toPx(), style = Stroke(1.dp.toPx()))
                },
            contentAlignment = Alignment.Center,
        ) {
            // The ☌ glyph (32 dp), drawn so it is crisp in every font: a ring with a stroke to the upper right.
            androidx.compose.foundation.Canvas(Modifier.size(32.dp)) {
                val w = size.minDimension
                val stroke = w * 0.085f
                val r = w * 0.27f
                val c = androidx.compose.ui.geometry.Offset(w * 0.40f, w * 0.60f)
                drawCircle(Palette.Midnight, radius = r, center = c, style = Stroke(stroke))
                val k = 0.7071f
                drawLine(Palette.Midnight, androidx.compose.ui.geometry.Offset(c.x + r * k, c.y - r * k), androidx.compose.ui.geometry.Offset(w * 0.92f, w * 0.08f), strokeWidth = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            }
        }
        if (badge > 0) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 2.dp, y = (-2).dp)
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(Palette.RoseCopper)
                    .border(1.dp, Palette.Midnight, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(if (badge > 9) "9+" else badge.toString(), style = MaterialTheme.typography.labelSmall, color = Palette.Moonstone)
            }
        }
    }
}
