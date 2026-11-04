package com.metavirtuoso.conjunction.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.metavirtuoso.conjunction.AppContainer
import com.metavirtuoso.conjunction.R
import com.metavirtuoso.conjunction.data.Charts
import com.metavirtuoso.conjunction.data.Profile
import com.metavirtuoso.conjunction.nearby.EncounterSummary
import com.metavirtuoso.conjunction.nearby.FriendLink
import com.metavirtuoso.conjunction.nearby.FriendProfile
import com.metavirtuoso.conjunction.nearby.NearbyRepository
import com.metavirtuoso.conjunction.nearby.NearbyService
import com.metavirtuoso.conjunction.nearby.PushEvent
import com.metavirtuoso.conjunction.nearby.PushKind
import com.metavirtuoso.conjunction.nearby.ReportReason
import com.metavirtuoso.conjunction.ui.ChatTarget
import com.metavirtuoso.conjunction.ui.components.Art
import com.metavirtuoso.conjunction.ui.components.ChoiceChips
import com.metavirtuoso.conjunction.ui.components.FinePrint
import com.metavirtuoso.conjunction.ui.components.GhostButton
import com.metavirtuoso.conjunction.ui.components.GoldButton
import com.metavirtuoso.conjunction.ui.components.GoldSwitchRow
import com.metavirtuoso.conjunction.ui.components.GoldText
import com.metavirtuoso.conjunction.ui.components.OrnatePanel
import com.metavirtuoso.conjunction.ui.components.OrnateTextField
import com.metavirtuoso.conjunction.ui.components.ProfilePhoto
import com.metavirtuoso.conjunction.ui.match.MatchExperience
import com.metavirtuoso.conjunction.ui.theme.Palette
import java.text.DateFormat
import java.time.Duration
import java.time.Instant
import java.util.Date
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

/**
 * Nearby: the switch (off by default) with a plain explanation, live status, pause controls,
 * the Matches inbox (pending cards for 24 h) and mutual matches (open the chat).
 */
@Composable
fun NearbyScreen(container: AppContainer, profile: Profile, charts: Charts?, openChat: (ChatTarget) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var setup by remember { mutableStateOf(false) }
    var openCard by remember { mutableStateOf<EncounterSummary?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var setupSafety by remember { mutableStateOf(false) }
    val runtime by NearbyService.state.collectAsState()
    val api = container.nearbyApi

    if (setup) {
        BackHandler { setup = false }
        NearbyConsentContent(container, profile, progress = null, onDone = { setup = false })
        return
    }

    TabPage(stringResource(R.string.nearby_title), subtitle = stringResource(R.string.tagline)) {
        if (!api.isConfigured) {
            OrnatePanel(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.nearby_not_configured), style = MaterialTheme.typography.titleMedium, color = Palette.GoldPale, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.nearby_not_configured_body), style = MaterialTheme.typography.bodyMedium, color = Palette.Moonstone, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
            }
            return@TabPage
        }
        androidx.compose.foundation.Image(painterResource(R.drawable.popup_match_nearby), contentDescription = null, modifier = Modifier.size(170.dp))
        OrnatePanel(Modifier.fillMaxWidth()) {
            GoldSwitchRow(
                checked = profile.nearbyEnabled,
                enabled = !busy,
                title = stringResource(R.string.nearby_switch_label),
                subtitle = stringResource(if (profile.nearbyEnabled) R.string.nearby_switch_on_desc else R.string.nearby_switch_off_desc),
                onChange = { on ->
                    error = null
                    if (!on) {
                        scope.launch { busy = true; container.nearby.disable(); busy = false }
                    } else {
                        val permitted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                        if (!profile.nearbyConsent || !permitted || !profile.nearbyEligible) setup = true
                        else scope.launch {
                            busy = true
                            try { container.nearby.enable(charts) } catch (e: Exception) { error = e.message } finally { busy = false }
                        }
                    }
                },
            )
            Text(stringResource(R.string.nearby_body), style = MaterialTheme.typography.bodyMedium, color = Palette.Moonstone)
            if (profile.nearbyEnabled) {
                val paused = profile.isPaused(System.currentTimeMillis())
                Text(
                    when {
                        paused -> stringResource(R.string.nearby_paused_until, DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(profile.nearbyPausedUntil)))
                        !runtime.running -> "Not running right now — it resumes while the app is open."
                        runtime.lastReportAt != null -> "Looking for conjunctions… last checked ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(runtime.lastReportAt!!))}."
                        else -> "Looking for conjunctions…"
                    },
                    style = MaterialTheme.typography.bodyMedium, color = Palette.GoldPale, modifier = Modifier.padding(top = 8.dp),
                )
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (paused) {
                        GoldButton("Resume", onClick = { scope.launch { container.nearby.resume() } }, modifier = Modifier.weight(1f))
                    } else {
                        GhostButton(stringResource(R.string.nearby_pause_1h), onClick = { scope.launch { container.nearby.pause(System.currentTimeMillis() + 3_600_000L) } }, modifier = Modifier.weight(1f))
                        GhostButton(stringResource(R.string.nearby_pause_tomorrow), onClick = { scope.launch { container.nearby.pause(NearbyRepository.tomorrowMorning()) } }, modifier = Modifier.weight(1f))
                    }
                }
                runtime.error?.let { FinePrint(it) }
            }
            if (!profile.nearbyEligible) FinePrint("Nearby needs your dating & friends profile and its consent (Profile → Who you'd like to meet).")
            error?.let { Text(it, color = Palette.RoseCopper, style = MaterialTheme.typography.bodyMedium) }
        }
        val uid = api.uid
        if (uid != null) {
            Inbox(container, uid, onOpenCard = { openCard = it }, openChat = openChat)
        } else {
            FinePrint("Your matches appear here once Nearby has been turned on.")
        }
        GhostButton(stringResource(R.string.safety_tip_title), onClick = { setupSafety = true }, modifier = Modifier.fillMaxWidth())
    }
    openCard?.let { encounter ->
        val kind = when { encounter.exactTwin -> PushKind.EXACT_TWIN; encounter.birthdayTwin -> PushKind.BIRTHDAY_TWIN; encounter.status == "matched" -> PushKind.CONJUNCTION; else -> PushKind.MATCH }
        MatchExperience(
            container = container,
            event = PushEvent(kind, encounter.id, null, encounter.kind, encounter.total, null, null),
            myCharts = charts,
            profile = profile,
            onDismiss = { openCard = null },
            onOpenChat = { openCard = null; openChat(it) },
            skipAnimation = true,
        )
    }
    if (setupSafety) {
        LegalDocDialog(com.metavirtuoso.conjunction.legal.LegalDoc.SAFETY, onDismiss = { setupSafety = false })
    }
}

@Composable
private fun Inbox(container: AppContainer, uid: String, onOpenCard: (EncounterSummary) -> Unit, openChat: (ChatTarget) -> Unit) {
    val api = container.nearbyApi
    val encounters by remember(uid) { api.encounters(uid).catch { emit(emptyList()) } }.collectAsState(initial = emptyList())
    val friends by remember(uid) { api.friends(uid).catch { emit(emptyList()) } }.collectAsState(initial = emptyList())
    val now = Instant.now()
    val pending = encounters.filter { it.status == "pending" && it.mySwipe == null && (it.expiresAt == null || it.expiresAt.isAfter(now)) }
    GoldText("Matches", style = MaterialTheme.typography.headlineSmall, heading = true)
    if (pending.isEmpty()) FinePrint("No cards waiting. When someone compatible is close by, their card appears here for 24 hours.")
    pending.forEach { e ->
        OrnatePanel(Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "Open match card") { onOpenCard(e) }, contentPadding = 14.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.foundation.Image(painterResource(Art.signKey(e.card?.sun)), contentDescription = null, modifier = Modifier.size(56.dp).clip(CircleShape))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        (if (e.exactTwin) "Exact twin · " else if (e.birthdayTwin) "Birthday twin · " else "") + (e.card?.firstName ?: "Someone nearby"),
                        style = MaterialTheme.typography.titleMedium, color = Palette.GoldPale,
                    )
                    Text("${Art.displaySign(e.card?.sun)} · ${e.total}% · ${if (e.kind == "dating") "dating" else "friends"}", style = MaterialTheme.typography.bodySmall, color = Palette.Moonstone)
                    e.expiresAt?.let { Text("${Duration.between(now, it).toHours().coerceAtLeast(0)} h left", style = MaterialTheme.typography.bodySmall, color = Palette.MoonstoneDim) }
                }
                Text("›", style = MaterialTheme.typography.headlineMedium, color = Palette.Gold)
            }
        }
    }
    GoldText("Conjunctions", style = MaterialTheme.typography.headlineSmall, heading = true)
    if (friends.isEmpty()) FinePrint("When you both say yes, they appear here and you can chat.")
    friends.forEach { link -> FriendRow(container, link, openChat) }
}

@Composable
private fun FriendRow(container: AppContainer, link: FriendLink, openChat: (ChatTarget) -> Unit) {
    val friend by produceState<FriendProfile?>(initialValue = null, link.otherUid) {
        value = runCatching { container.nearbyApi.getFriendProfile(link.otherUid) }.getOrNull()
    }
    OrnatePanel(Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "Open chat") { openChat(ChatTarget(link.pairId, link.otherUid)) }, contentPadding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProfilePhoto(friend?.photoUrl, Art.signKey(friend?.sun), contentDescription = null, modifier = Modifier.size(56.dp).clip(CircleShape))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(friend?.displayName ?: "Your match", style = MaterialTheme.typography.titleMedium, color = Palette.GoldPale)
                Text(
                    listOfNotNull(friend?.sun?.let { "☉ ${Art.displaySign(it)}" }, friend?.moon?.let { "☽ ${Art.displaySign(it)}" }, if (link.kind == "dating") "dating" else "friends").joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = Palette.Moonstone,
                )
            }
            Text("›", style = MaterialTheme.typography.headlineMedium, color = Palette.Gold)
        }
    }
}

/**
 * Block / report / snooze / unmatch for one person (from a match card or a chat).
 * [onDone] gets true when the person is now hidden (blocked, reported, snoozed or unmatched).
 */
@Composable
fun SafetyActionsDialog(container: AppContainer, otherUid: String, name: String, encounterId: String?, pairId: String?, onDone: (Boolean) -> Unit) {
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf("menu") }
    var reason by remember { mutableStateOf<ReportReason?>(null) }
    var details by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun run(block: suspend () -> Unit) {
        busy = true
        scope.launch {
            try { block(); onDone(true) } catch (e: Exception) { error = e.message ?: "That didn't work. Try again." } finally { busy = false }
        }
    }
    AlertDialog(
        onDismissRequest = { onDone(false) },
        containerColor = Palette.InkViolet,
        title = { GoldText(if (mode == "report") "Report $name" else stringResource(R.string.chat_block_report), style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (mode) {
                    "menu" -> {
                        FinePrint(stringResource(R.string.safety_tip_emergency))
                        GhostButton("Report $name", onClick = { mode = "report" }, modifier = Modifier.fillMaxWidth(), color = Palette.RoseCopper)
                        GhostButton("Block $name", enabled = !busy, onClick = { run { container.nearbyApi.block(otherUid) } }, modifier = Modifier.fillMaxWidth())
                        GhostButton("Snooze for 7 days", enabled = !busy, onClick = { run { container.nearbyApi.snooze(otherUid, 24 * 7) } }, modifier = Modifier.fillMaxWidth())
                        if (pairId != null) GhostButton(stringResource(R.string.chat_unmatch), enabled = !busy, onClick = { mode = "unmatch" }, modifier = Modifier.fillMaxWidth())
                    }
                    "unmatch" -> {
                        Text(stringResource(R.string.chat_unmatch_confirm, name), style = MaterialTheme.typography.bodyMedium, color = Palette.Moonstone)
                        GoldButton(stringResource(R.string.chat_unmatch), enabled = !busy, onClick = { run { container.nearbyApi.unmatch(pairId!!) } }, modifier = Modifier.fillMaxWidth())
                    }
                    else -> {
                        Box(Modifier.fillMaxWidth()) {
                            ChoiceChips(ReportReason.entries.map { it to it.label }, reason, { reason = it })
                        }
                        OrnateTextField(details, { details = it.take(2000) }, label = "What happened? (optional)", singleLine = false, minLines = 3)
                        FinePrint("Reporting also blocks $name. Our team reviews every report.")
                        GoldButton("Send report", enabled = reason != null && !busy, onClick = {
                            run { container.nearbyApi.report(otherUid, reason!!, details, pairId = pairId, encounterId = encounterId, alsoBlock = true) }
                        }, modifier = Modifier.fillMaxWidth())
                    }
                }
                error?.let { Text(it, color = Palette.RoseCopper, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = { onDone(false) }) { Text("CLOSE", color = Palette.MoonstoneDim) } },
    )
}
