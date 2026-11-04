package com.metavirtuoso.conjunction.ui.screens

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.metavirtuoso.conjunction.AppContainer
import com.metavirtuoso.conjunction.BuildConfig
import com.metavirtuoso.conjunction.R
import com.metavirtuoso.conjunction.data.Charts
import com.metavirtuoso.conjunction.data.Instagram
import com.metavirtuoso.conjunction.data.Profile
import com.metavirtuoso.conjunction.nearby.ChatLine
import com.metavirtuoso.conjunction.nearby.EncounterCard
import com.metavirtuoso.conjunction.nearby.FriendSummary
import com.metavirtuoso.conjunction.nearby.NearbyPermissions
import com.metavirtuoso.conjunction.nearby.ReportEmail
import com.metavirtuoso.conjunction.nearby.ReportReason
import com.metavirtuoso.conjunction.ui.LocalAppNav
import com.metavirtuoso.conjunction.ui.components.Art
import com.metavirtuoso.conjunction.ui.components.ChoiceChips
import com.metavirtuoso.conjunction.ui.components.FinePrint
import com.metavirtuoso.conjunction.ui.components.GhostButton
import com.metavirtuoso.conjunction.ui.components.GoldButton
import com.metavirtuoso.conjunction.ui.components.GoldCheckRow
import com.metavirtuoso.conjunction.ui.components.GoldText
import com.metavirtuoso.conjunction.ui.components.MoreButton
import com.metavirtuoso.conjunction.ui.components.OrnatePanel
import com.metavirtuoso.conjunction.ui.components.OrnateTextField
import com.metavirtuoso.conjunction.ui.components.ScreenedPhoto
import com.metavirtuoso.conjunction.ui.components.StarfieldBackground
import com.metavirtuoso.conjunction.ui.match.CardMenu
import com.metavirtuoso.conjunction.ui.match.CardMenuAction
import com.metavirtuoso.conjunction.ui.match.InstagramChip
import com.metavirtuoso.conjunction.ui.match.MatchCardFace
import com.metavirtuoso.conjunction.ui.match.ScoreMedallion
import com.metavirtuoso.conjunction.ui.match.SwipeableMatchCard
import com.metavirtuoso.conjunction.ui.theme.GoldBrushes
import com.metavirtuoso.conjunction.ui.theme.Palette
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

/** Where Nearby stands, for the header dot, the status card and the empty state (SPEC 5d.3). */
enum class NearbyStatus { ON, OFF, PAUSED, BLUETOOTH_OFF, DENIED, UNSUPPORTED }

/**
 * Nearby (SPEC 5d.3): only match cards. Header (title + status dot → Settings › Nearby), a status
 * card when Nearby is not running and there are cards, then New matches (swipe stack), Waiting and
 * Conjunctions; or the empty state with its one button. No switch, no paragraphs.
 */
@Composable
fun NearbyScreen(container: AppContainer, profile: Profile, charts: Charts?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val nav = LocalAppNav.current
    val repo = container.nearby
    val turnOn = rememberNearbyTurnOn(container, profile)
    val service by repo.serviceState.collectAsState()
    val denied by repo.permissionDenied.collectAsState()
    val pending by remember { repo.inbox() }.collectAsState(initial = emptyList())
    val waiting by remember { repo.waiting() }.collectAsState(initial = emptyList())
    val friends by remember { repo.friends() }.collectAsState(initial = emptyList())
    var safety by remember { mutableStateOf<Triple<SafetyTarget, String, Any?>?>(null) }
    var safetyCode by remember { mutableStateOf<FriendSummary?>(null) }
    var tips by remember { mutableStateOf(false) }
    val enableBluetooth = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { scope.launch { repo.resumeServiceIfNeeded() } }
    val now = System.currentTimeMillis()
    val status = when {
        !repo.supported || !repo.hasBluetoothLe -> NearbyStatus.UNSUPPORTED
        profile.nearbyEnabled && !NearbyPermissions.granted(context) -> NearbyStatus.DENIED
        profile.nearbyEnabled && service.bluetoothOff -> NearbyStatus.BLUETOOTH_OFF
        profile.nearbyEnabled && profile.isPaused(now) -> NearbyStatus.PAUSED
        profile.nearbyEnabled -> NearbyStatus.ON
        denied -> NearbyStatus.DENIED
        else -> NearbyStatus.OFF
    }
    val pausedUntil = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(profile.nearbyPausedUntil))
    val action: (() -> Unit)? = when (status) {
        NearbyStatus.OFF -> ({ turnOn.request() })
        NearbyStatus.PAUSED -> ({ scope.launch { repo.resume() } })
        NearbyStatus.BLUETOOTH_OFF -> ({ runCatching { enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) } })
        NearbyStatus.DENIED -> ({ context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))) })
        else -> null
    }
    val empty = pending.isEmpty() && waiting.isEmpty() && friends.isEmpty()
    val actionLabel = when (status) {
        NearbyStatus.OFF -> stringResource(if (empty) R.string.nearby_turn_on_button else R.string.nearby_turn_on)
        NearbyStatus.PAUSED -> stringResource(R.string.nearby_resume)
        NearbyStatus.BLUETOOTH_OFF -> stringResource(R.string.nearby_turn_on_bluetooth)
        NearbyStatus.DENIED -> stringResource(R.string.nearby_open_settings)
        else -> ""
    }
    val statusLine = when (status) {
        NearbyStatus.OFF -> stringResource(R.string.nearby_off_line)
        NearbyStatus.PAUSED -> stringResource(R.string.nearby_paused_line, pausedUntil)
        NearbyStatus.BLUETOOTH_OFF -> stringResource(R.string.nearby_bluetooth_off)
        NearbyStatus.DENIED -> stringResource(R.string.perm_bluetooth_denied)
        NearbyStatus.UNSUPPORTED -> stringResource(R.string.nearby_android_old_version)
        NearbyStatus.ON -> ""
    }

    StarfieldBackground {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                GoldText(stringResource(R.string.tab_nearby), style = MaterialTheme.typography.headlineMedium, heading = true, modifier = Modifier.weight(1f))
                StatusDot(status) { nav.openSettings(SettingsPage.NEARBY) }
            }
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Column(Modifier.widthIn(max = 640.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (empty) {
                        EmptyState(
                            line = when (status) {
                                NearbyStatus.OFF -> stringResource(R.string.nearby_empty_off)
                                NearbyStatus.ON -> stringResource(R.string.nearby_empty_listening)
                                NearbyStatus.PAUSED -> stringResource(R.string.nearby_empty_paused, pausedUntil)
                                else -> statusLine
                            },
                            button = actionLabel.takeIf { action != null },
                            onClick = action,
                        )
                    } else {
                        if (status != NearbyStatus.ON) StatusCard(statusLine, actionLabel.takeIf { action != null }, action)
                        if (pending.isNotEmpty()) {
                            SectionLabel(stringResource(R.string.nearby_section_new))
                            SwipeStack(
                                pending,
                                onAnswer = { card, accept -> scope.launch { repo.swipe(card.encounterId, accept) } },
                                onChart = { nav.openCompat(it.encounterId) },
                                onMenu = { card, a ->
                                    when (a) {
                                        CardMenuAction.SNOOZE -> scope.launch { repo.snooze(card.encounterId) }
                                        CardMenuAction.TIPS -> tips = true
                                        CardMenuAction.BLOCK -> safety = Triple(SafetyTarget.stranger(card), "block", null)
                                        CardMenuAction.REPORT -> safety = Triple(SafetyTarget.stranger(card), "report", null)
                                    }
                                },
                            )
                        }
                        if (waiting.isNotEmpty()) {
                            SectionLabel(stringResource(R.string.nearby_section_waiting))
                            waiting.forEach { card ->
                                WaitingCard(card, onChart = { nav.openCompat(card.encounterId) }) { a ->
                                    when (a) {
                                        CardMenuAction.SNOOZE -> scope.launch { repo.snooze(card.encounterId) }
                                        CardMenuAction.TIPS -> tips = true
                                        CardMenuAction.BLOCK -> safety = Triple(SafetyTarget.stranger(card), "block", null)
                                        CardMenuAction.REPORT -> safety = Triple(SafetyTarget.stranger(card), "report", null)
                                    }
                                }
                            }
                        }
                        if (friends.isNotEmpty()) {
                            SectionLabel(stringResource(R.string.nearby_section_conjunctions))
                            friends.forEach { f ->
                                FriendCard(
                                    f,
                                    onOpen = { nav.openChat(f.id) },
                                    onChart = { nav.openCompat(f.id) },
                                    onInstagram = { h -> Instagram.open(context, h) },
                                    onMenu = { a ->
                                        when (a) {
                                            FriendMenuAction.UNMATCH -> safety = Triple(SafetyTarget.friend(f), "unmatch", null)
                                            FriendMenuAction.BLOCK -> safety = Triple(SafetyTarget.friend(f), "block", null)
                                            FriendMenuAction.REPORT -> safety = Triple(SafetyTarget.friend(f), "report", null)
                                            FriendMenuAction.SAFETY_CODE -> safetyCode = f
                                            FriendMenuAction.SHARE_INSTAGRAM -> scope.launch { repo.setInstagramShare(f.id, !f.igShareOn) }
                                        }
                                    },
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(44.dp))
                }
            }
        }
    }
    safety?.let { (target, mode, _) -> SafetyActionsDialog(container, target, onDone = { safety = null }, initialMode = mode) }
    safetyCode?.let { f -> SafetyCodeDialog(f.firstName, f.safetyCode) { safetyCode = null } }
    if (tips) SafetyTipsDialog(container) { tips = false }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text.uppercase(), style = MaterialTheme.typography.titleSmall, color = Palette.Gold, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
}

/** 10 dp dot: gold = on, grey = off / paused, rose-copper = Bluetooth off or permission missing. */
@Composable
private fun StatusDot(status: NearbyStatus, onClick: () -> Unit) {
    val color = when (status) {
        NearbyStatus.ON -> Palette.Gold
        NearbyStatus.BLUETOOTH_OFF, NearbyStatus.DENIED -> Palette.RoseCopper
        else -> Palette.MoonstoneDim
    }
    Box(
        Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Nearby status: ${status.name.lowercase().replace('_', ' ')}. Opens Nearby settings." }.testTag("statusDot"),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
    }
}

@Composable
private fun EmptyState(line: String, button: String?, onClick: (() -> Unit)?) {
    Column(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Image(painterResource(R.drawable.popup_match_nearby), contentDescription = null, modifier = Modifier.size(240.dp).graphicsLayer { alpha = 0.55f })
        GoldText(line, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        if (button != null && onClick != null) GoldButton(button, onClick = onClick, modifier = Modifier.fillMaxWidth().widthIn(max = 360.dp).testTag("emptyAction"))
    }
}

@Composable
private fun StatusCard(line: String, button: String?, onClick: (() -> Unit)?) {
    OrnatePanel(Modifier.fillMaxWidth(), contentPadding = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(line, style = MaterialTheme.typography.bodyMedium, color = Palette.Moonstone, modifier = Modifier.weight(1f))
            if (button != null && onClick != null) {
                Spacer(Modifier.width(10.dp))
                GhostButton(button, onClick = onClick)
            }
        }
    }
}

/** New matches: the top card is interactive, the next one peeks out behind it. */
@Composable
private fun SwipeStack(cards: List<EncounterCard>, onAnswer: (EncounterCard, Boolean) -> Unit, onChart: (EncounterCard) -> Unit, onMenu: (EncounterCard, CardMenuAction) -> Unit) {
    val top = cards.first()
    // About 55% of the screen, so Waiting / Conjunctions peek above the tab bar.
    val maxCard = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * 0.55f).dp
    Box(Modifier.fillMaxWidth().padding(top = 0.dp), contentAlignment = Alignment.TopCenter) {
        cards.getOrNull(1)?.let { next ->
            Box(
                Modifier.widthIn(max = minOf(420.dp, maxCard * 0.70f)).fillMaxWidth().padding(horizontal = 30.dp)
                    .graphicsLayer { alpha = 0.9f }
                    .clip(CutCornerShape(14.dp)).background(Palette.InkViolet).border(1.dp, GoldBrushes.leaf, CutCornerShape(14.dp)),
            ) {
                MatchCardFace(next, Modifier.fillMaxWidth().padding(28.dp), photoSize = 120.dp)
            }
        }
        androidx.compose.runtime.key(top.encounterId) {
            // The next card peeks out above the top one.
            SwipeableMatchCard(
                top, onAnswer = { onAnswer(top, it) }, onChart = { onChart(top) },
                modifier = Modifier.padding(top = if (cards.size > 1) 22.dp else 0.dp),
                onMenu = { onMenu(top, it) }, maxCardHeight = maxCard,
            )
        }
    }
}

@Composable
private fun WaitingCard(card: EncounterCard, onChart: () -> Unit, onMenu: (CardMenuAction) -> Unit) {
    val name = card.card?.firstName ?: "them"
    OrnatePanel(Modifier.fillMaxWidth(), contentPadding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ScreenedPhoto(card.photoPath, card.photoScreening, Art.signKey(card.card?.sun), contentDescription = null, modifier = Modifier.size(56.dp).clip(CircleShape), compact = true)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(card.card?.firstName ?: "Someone nearby", style = MaterialTheme.typography.titleMedium, color = Palette.GoldPale)
                Text(stringResource(R.string.nearby_waiting_for, name), style = MaterialTheme.typography.bodySmall, color = Palette.MoonstoneDim)
            }
            ScoreMedallion(card.total, 44.dp)
            ChartChip(onChart)
            CardMenu(onMenu)
        }
    }
}

enum class FriendMenuAction { SHARE_INSTAGRAM, SAFETY_CODE, UNMATCH, BLOCK, REPORT }

@Composable
fun FriendMenu(friend: FriendSummary, onAction: (FriendMenuAction) -> Unit, extra: (@Composable (close: () -> Unit) -> Unit)? = null) {
    var open by remember { mutableStateOf(false) }
    Box {
        MoreButton("More options for ${friend.firstName}", onClick = { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = Palette.InkViolet) {
            extra?.invoke { open = false }
            DropdownMenuItem(
                text = { Text((if (friend.igShareOn) "✓ " else "") + stringResource(R.string.instagram_share_with_match, friend.firstName), color = Palette.Moonstone) },
                onClick = { open = false; onAction(FriendMenuAction.SHARE_INSTAGRAM) },
            )
            listOf(
                FriendMenuAction.SAFETY_CODE to R.string.menu_safety_code, FriendMenuAction.UNMATCH to R.string.menu_unmatch,
                FriendMenuAction.BLOCK to R.string.menu_block, FriendMenuAction.REPORT to R.string.menu_report,
            ).forEach { (a, label) ->
                DropdownMenuItem(
                    text = { Text(stringResource(label), color = if (a == FriendMenuAction.BLOCK || a == FriendMenuAction.REPORT) Palette.RoseCopper else Palette.Moonstone) },
                    onClick = { open = false; onAction(a) },
                )
            }
        }
    }
}

@Composable
private fun ChartChip(onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).border(1.dp, GoldBrushes.leaf, CircleShape)
            .clickable(role = Role.Button, onClickLabel = "Compatibility chart", onClick = onClick)
            .semantics { contentDescription = "Compatibility chart" },
        contentAlignment = Alignment.Center,
    ) {
        Text("☌︎", style = MaterialTheme.typography.titleLarge, color = Palette.GoldPale)
    }
}

/** A mutual match: tap → chat; ☌ → Compatibility; Instagram chip when they shared one. */
@Composable
private fun FriendCard(friend: FriendSummary, onOpen: () -> Unit, onChart: () -> Unit, onInstagram: (String) -> Unit, onMenu: (FriendMenuAction) -> Unit) {
    OrnatePanel(Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "Open chat", onClick = onOpen), contentPadding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box {
                ScreenedPhoto(friend.photoPath, friend.photoScreening, Art.signKey(friend.sun), contentDescription = null, modifier = Modifier.size(56.dp).clip(CircleShape), compact = true)
                if (friend.unread > 0) Box(Modifier.align(Alignment.TopEnd).size(14.dp).clip(CircleShape).background(Palette.Gold).border(1.dp, Palette.Midnight, CircleShape).semantics { contentDescription = "${friend.unread} unread" })
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(friend.firstName, style = MaterialTheme.typography.titleMedium, color = Palette.GoldPale)
                Text(
                    (if (friend.queued > 0) "⏱ " else "") + (friend.lastMessage ?: stringResource(R.string.nearby_say_hi)),
                    style = MaterialTheme.typography.bodySmall, color = if (friend.unread > 0) Palette.Moonstone else Palette.MoonstoneDim, maxLines = 1,
                )
                friend.peerIg?.let { h -> InstagramChip(h) { onInstagram(h) } }
            }
            friend.total?.let { ScoreMedallion(it, 44.dp) }
            ChartChip(onChart)
            FriendMenu(friend, onMenu)
        }
    }
}

/** The safety code to compare in person (chat ⋯ and the friend card ⋯). */
@Composable
fun SafetyCodeDialog(name: String, code: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.InkViolet,
        title = { GoldText(stringResource(R.string.menu_safety_code), style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(code, style = MaterialTheme.typography.headlineSmall, color = Palette.GoldPale)
                FinePrint("When you meet, check that $name's phone shows the same digits. If they differ, someone may have interfered when you matched: block and report.")
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("CLOSE", color = Palette.Gold) } },
    )
}

/** Who a safety action is about: a stranger's match card (by fingerprint) or a friend. */
data class SafetyTarget(
    val name: String,
    val fingerprint: String?,
    val friendId: String?,
    val report: ReportEmail.Subject,
) {
    companion object {
        fun stranger(card: EncounterCard) = SafetyTarget(
            name = card.card?.firstName ?: "this person",
            fingerprint = card.encounterId,
            friendId = null,
            report = ReportEmail.Subject(
                card.card?.firstName, card.card?.age, card.card?.sun, card.card?.moon, card.card?.rising, card.kind,
                localId = card.encounterId, identityFingerprint = null, hasPhoto = card.photoPath != null,
            ),
        )

        fun friend(f: FriendSummary) = SafetyTarget(
            name = f.firstName,
            fingerprint = null,
            friendId = f.id,
            report = ReportEmail.Subject(f.firstName, null, f.sun, f.moon, f.rising, f.kind, localId = f.id, identityFingerprint = f.safetyCode, hasPhoto = f.photoPath != null),
        )
    }
}

/**
 * Block / report / snooze / unmatch for one person (from a match card or a chat). Report = block
 * + an email the person writes and sends themselves (there is no server). [messages] are offered
 * for the report only from a chat. [onDone] gets true when the person is now hidden.
 */
@Composable
fun SafetyActionsDialog(container: AppContainer, target: SafetyTarget, onDone: (Boolean) -> Unit, messages: List<ChatLine>? = null, initialMode: String = "menu") {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = container.nearby
    var mode by remember { mutableStateOf(initialMode) }
    var reason by remember { mutableStateOf<ReportReason?>(null) }
    var details by remember { mutableStateOf("") }
    var includeMessages by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    val name = target.name
    val noEmailApp = stringResource(R.string.report_no_email_app, BuildConfig.SUPPORT_EMAIL)

    suspend fun block() {
        target.friendId?.let { repo.blockFriend(it) }
        target.fingerprint?.let { repo.blockStranger(it) }
    }

    fun run(block: suspend () -> Unit) {
        busy = true
        scope.launch {
            try { block(); onDone(true) } catch (e: Exception) { note = e.message ?: "That didn't work. Try again." } finally { busy = false }
        }
    }
    AlertDialog(
        onDismissRequest = { onDone(false) },
        containerColor = Palette.InkViolet,
        title = {
            GoldText(
                when (mode) {
                    "report" -> stringResource(R.string.report_title, name)
                    "block" -> stringResource(R.string.block_title, name)
                    else -> stringResource(R.string.chat_block_report)
                },
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (mode) {
                    "menu" -> {
                        FinePrint(stringResource(R.string.safety_tip_emergency))
                        GhostButton(stringResource(R.string.report_title, name), onClick = { mode = "report" }, modifier = Modifier.fillMaxWidth(), color = Palette.RoseCopper)
                        GhostButton(stringResource(R.string.block_title, name).trimEnd('?'), enabled = !busy, onClick = { mode = "block" }, modifier = Modifier.fillMaxWidth())
                        if (target.fingerprint != null) GhostButton("Snooze for 7 days", enabled = !busy, onClick = { run { repo.snooze(target.fingerprint) } }, modifier = Modifier.fillMaxWidth())
                        if (target.friendId != null) GhostButton(stringResource(R.string.chat_unmatch), enabled = !busy, onClick = { mode = "unmatch" }, modifier = Modifier.fillMaxWidth())
                    }
                    "block" -> {
                        Text(stringResource(R.string.block_body), style = MaterialTheme.typography.bodyMedium, color = Palette.Moonstone)
                        GoldButton(stringResource(R.string.block_confirm), enabled = !busy, onClick = { run { block() } }, modifier = Modifier.fillMaxWidth())
                    }
                    "unmatch" -> {
                        Text(stringResource(R.string.chat_unmatch_confirm, name), style = MaterialTheme.typography.bodyMedium, color = Palette.Moonstone)
                        GoldButton(stringResource(R.string.chat_unmatch), enabled = !busy, onClick = { run { repo.unmatch(target.friendId!!) } }, modifier = Modifier.fillMaxWidth())
                    }
                    else -> {
                        Text(stringResource(R.string.report_body, name), style = MaterialTheme.typography.bodySmall, color = Palette.Moonstone)
                        FinePrint(stringResource(R.string.report_emergency))
                        Text(stringResource(R.string.report_reason_label).uppercase(), style = MaterialTheme.typography.labelMedium, color = Palette.Gold)
                        Box(Modifier.fillMaxWidth()) {
                            ChoiceChips(ReportReason.entries.map { it to stringResource(it.label) }, reason, { reason = it })
                        }
                        OrnateTextField(details, { details = it.take(2000) }, label = "Details (optional)", singleLine = false, minLines = 3)
                        if (messages != null && messages.isNotEmpty()) GoldCheckRow(includeMessages, { includeMessages = it }, stringResource(R.string.report_include_messages))
                        GoldButton(stringResource(R.string.report_open_email), enabled = reason != null && !busy, onClick = {
                            val r = reason ?: return@GoldButton
                            val body = ReportEmail.body(context, target.report, r, details, if (includeMessages) messages else null)
                            val subject = ReportEmail.subjectLine(context, r)
                            busy = true
                            scope.launch {
                                try {
                                    block()
                                    if (ReportEmail.open(context, subject, body)) onDone(true)
                                    else note = noEmailApp
                                } finally {
                                    busy = false
                                }
                            }
                        }, modifier = Modifier.fillMaxWidth())
                    }
                }
                note?.let { Text(it, color = Palette.RoseCopper, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = { onDone(note != null && mode == "report") }) { Text("CLOSE", color = Palette.MoonstoneDim) } },
    )
}
