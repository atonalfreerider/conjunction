package com.metavirtuoso.conjunction.ui.screens

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.metavirtuoso.conjunction.AppContainer
import com.metavirtuoso.conjunction.BuildConfig
import com.metavirtuoso.conjunction.R
import com.metavirtuoso.conjunction.data.Charts
import com.metavirtuoso.conjunction.data.Profile
import com.metavirtuoso.conjunction.nearby.NearbyPermissions
import com.metavirtuoso.conjunction.nearby.ChatLine
import com.metavirtuoso.conjunction.nearby.EncounterCard
import com.metavirtuoso.conjunction.nearby.FriendSummary
import com.metavirtuoso.conjunction.nearby.NearbyEvent
import com.metavirtuoso.conjunction.nearby.NearbyEventKind
import com.metavirtuoso.conjunction.nearby.NearbyRepository
import com.metavirtuoso.conjunction.nearby.ReportEmail
import com.metavirtuoso.conjunction.nearby.ReportReason
import com.metavirtuoso.conjunction.ui.ChatTarget
import com.metavirtuoso.conjunction.ui.components.Art
import com.metavirtuoso.conjunction.ui.components.ChoiceChips
import com.metavirtuoso.conjunction.ui.components.FinePrint
import com.metavirtuoso.conjunction.ui.components.GhostButton
import com.metavirtuoso.conjunction.ui.components.GoldButton
import com.metavirtuoso.conjunction.ui.components.GoldCheckRow
import com.metavirtuoso.conjunction.ui.components.GoldSwitchRow
import com.metavirtuoso.conjunction.ui.components.GoldText
import com.metavirtuoso.conjunction.ui.components.OrnatePanel
import com.metavirtuoso.conjunction.ui.components.OrnateTextField
import com.metavirtuoso.conjunction.ui.components.ScreenedPhoto
import com.metavirtuoso.conjunction.ui.match.MatchExperience
import com.metavirtuoso.conjunction.ui.theme.Palette
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

/**
 * Nearby: the switch (off by default) with a plain explanation, live status (Bluetooth on/off,
 * paused, how many Conjunction phones are close by — never a distance), pause controls, the
 * Matches inbox (cards stay 24 h) and the friends you matched with (open the chat).
 */
@Composable
fun NearbyScreen(container: AppContainer, profile: Profile, charts: Charts?, openChat: (ChatTarget) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = container.nearby
    var setup by remember { mutableStateOf(false) }
    var openCard by remember { mutableStateOf<EncounterCard?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var safetyTips by remember { mutableStateOf(false) }
    val service by repo.serviceState.collectAsState()
    val radio by repo.radioStatus.collectAsState()
    val enableBluetooth = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        scope.launch { repo.resumeServiceIfNeeded() }
    }

    if (setup) {
        BackHandler { setup = false }
        NearbyConsentContent(container, profile, progress = null, onDone = { setup = false })
        return
    }

    TabPage(stringResource(R.string.nearby_title), subtitle = stringResource(R.string.tagline)) {
        if (!repo.supported || !repo.hasBluetoothLe) {
            OrnatePanel(Modifier.fillMaxWidth()) {
                Text(
                    if (!repo.supported) stringResource(R.string.nearby_android_old_version) else "This phone has no Bluetooth Low Energy, so Nearby can't run. Your charts and readings work as usual.",
                    style = MaterialTheme.typography.titleMedium, color = Palette.GoldPale, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                )
            }
            // Friends and chats made earlier (e.g. before an OS downgrade or on a restored phone) stay readable.
            Inbox(container, onOpenCard = { openCard = it }, openChat = openChat)
            return@TabPage
        }
        Image(painterResource(R.drawable.popup_match_nearby), contentDescription = null, modifier = Modifier.size(170.dp))
        OrnatePanel(Modifier.fillMaxWidth()) {
            GoldSwitchRow(
                checked = profile.nearbyEnabled,
                enabled = !busy,
                title = stringResource(R.string.nearby_switch_label),
                subtitle = stringResource(if (profile.nearbyEnabled) R.string.nearby_switch_on_desc else R.string.nearby_switch_off_desc),
                onChange = { on ->
                    error = null
                    if (!on) {
                        scope.launch { busy = true; repo.disable(); busy = false }
                    } else if (!profile.nearbyConsent || !profile.nearbyEligible || !NearbyPermissions.granted(context)) {
                        setup = true
                    } else {
                        scope.launch {
                            busy = true
                            try { repo.enable() } catch (e: Exception) { error = e.message } finally { busy = false }
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
                        service.bluetoothOff -> stringResource(R.string.nearby_bluetooth_off)
                        !service.running -> "Not running right now — it resumes while the app is open."
                        radio.closeBy > 0 -> "Looking for conjunctions… ${radio.closeBy} Conjunction ${if (radio.closeBy == 1) "phone is" else "phones are"} nearby."
                        else -> "Looking for conjunctions…"
                    },
                    style = MaterialTheme.typography.bodyMedium, color = Palette.GoldPale, modifier = Modifier.padding(top = 8.dp),
                )
                if (service.bluetoothOff && NearbyPermissions.granted(context)) {
                    GhostButton("Turn on Bluetooth", onClick = {
                        runCatching { enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
                    }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (paused) {
                        GoldButton(stringResource(R.string.nearby_resume), onClick = { scope.launch { repo.resume() } }, modifier = Modifier.weight(1f))
                    } else {
                        GhostButton(stringResource(R.string.nearby_pause_1h), onClick = { scope.launch { repo.pause(System.currentTimeMillis() + 3_600_000L) } }, modifier = Modifier.weight(1f))
                        GhostButton(stringResource(R.string.nearby_pause_tomorrow), onClick = { scope.launch { repo.pause(NearbyRepository.tomorrowMorning()) } }, modifier = Modifier.weight(1f))
                    }
                }
                (service.error ?: radio.error)?.let { FinePrint(it) }
            }
            if (!profile.nearbyEligible) FinePrint("Nearby needs your birth details and your dating & friends profile with its consent (Profile → Who you'd like to meet).")
            error?.let { Text(it, color = Palette.RoseCopper, style = MaterialTheme.typography.bodyMedium) }
        }
        Inbox(container, onOpenCard = { openCard = it }, openChat = openChat)
        GhostButton(stringResource(R.string.safety_tip_title), onClick = { safetyTips = true }, modifier = Modifier.fillMaxWidth())
    }
    openCard?.let { encounter ->
        val kind = when { encounter.exactTwin -> NearbyEventKind.EXACT_TWIN; encounter.birthdayTwin -> NearbyEventKind.BIRTHDAY_TWIN; else -> NearbyEventKind.MATCH }
        MatchExperience(
            container = container,
            event = NearbyEvent(kind, encounter.encounterId, null, encounter.kind, encounter.total),
            myCharts = charts,
            profile = profile,
            onDismiss = { openCard = null },
            onOpenChat = { openCard = null; openChat(it) },
            skipAnimation = true,
        )
    }
    if (safetyTips) {
        LegalDocDialog(com.metavirtuoso.conjunction.legal.LegalDoc.SAFETY, onDismiss = { safetyTips = false })
    }
}

@Composable
private fun Inbox(container: AppContainer, onOpenCard: (EncounterCard) -> Unit, openChat: (ChatTarget) -> Unit) {
    val repo = container.nearby
    val pending by remember { repo.inbox() }.collectAsState(initial = emptyList())
    val waiting by remember { repo.waiting() }.collectAsState(initial = emptyList())
    val friends by remember { repo.friends() }.collectAsState(initial = emptyList())
    val now = System.currentTimeMillis()
    GoldText("Matches", style = MaterialTheme.typography.headlineSmall, heading = true)
    if (pending.isEmpty() && waiting.isEmpty()) FinePrint("No cards waiting. When someone compatible is close by, their card appears here for 24 hours.")
    (pending + waiting).forEach { e ->
        OrnatePanel(Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "Open match card") { onOpenCard(e) }, contentPadding = 14.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ScreenedPhoto(e.photoPath, e.photoScreening, Art.signKey(e.card?.sun), contentDescription = null, modifier = Modifier.size(56.dp).clip(CircleShape), compact = true)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        (if (e.exactTwin) "Exact twin · " else if (e.birthdayTwin) "Birthday twin · " else "") + (e.card?.firstName ?: "Someone nearby"),
                        style = MaterialTheme.typography.titleMedium, color = Palette.GoldPale,
                    )
                    Text("${Art.displaySign(e.card?.sun)} · ${e.total}% · ${if (e.kind == "dating") "dating" else "friends"}", style = MaterialTheme.typography.bodySmall, color = Palette.Moonstone)
                    val hours = ((e.expiresAt - now) / 3_600_000L).coerceAtLeast(0)
                    Text(if (e.mySwipe == "right") "You said yes · waiting for them · ${hours} h left" else "$hours h left", style = MaterialTheme.typography.bodySmall, color = Palette.MoonstoneDim)
                }
                Text("›", style = MaterialTheme.typography.headlineMedium, color = Palette.Gold)
            }
        }
    }
    GoldText("Conjunctions", style = MaterialTheme.typography.headlineSmall, heading = true)
    if (friends.isEmpty()) FinePrint("When you both say yes, they appear here and you can chat.")
    friends.forEach { f -> FriendRow(f) { openChat(ChatTarget(f.id)) } }
}

@Composable
private fun FriendRow(friend: FriendSummary, onOpen: () -> Unit) {
    OrnatePanel(Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "Open chat", onClick = onOpen), contentPadding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ScreenedPhoto(friend.photoPath, friend.photoScreening, Art.signKey(friend.sun), contentDescription = null, modifier = Modifier.size(56.dp).clip(CircleShape), compact = true)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(friend.firstName, style = MaterialTheme.typography.titleMedium, color = Palette.GoldPale)
                Text(
                    listOfNotNull(friend.sun?.let { "☉ ${Art.displaySign(it)}" }, friend.moon?.let { "☽ ${Art.displaySign(it)}" }, if (friend.kind == "dating") "dating" else "friends").joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = Palette.Moonstone,
                )
                Text(
                    when {
                        friend.inRange -> "Nearby now"
                        friend.queued > 0 -> "${friend.queued} waiting to be delivered"
                        else -> friend.lastMessage ?: "Say hello"
                    },
                    style = MaterialTheme.typography.bodySmall, color = if (friend.inRange) Palette.Gold else Palette.MoonstoneDim, maxLines = 1,
                )
            }
            if (friend.unread > 0) {
                Box(Modifier.size(26.dp).clip(CircleShape).background(Palette.Gold), contentAlignment = Alignment.Center) {
                    Text(friend.unread.coerceAtMost(99).toString(), style = MaterialTheme.typography.labelMedium, color = Palette.Midnight)
                }
                Spacer(Modifier.width(6.dp))
            }
            Text("›", style = MaterialTheme.typography.headlineMedium, color = Palette.Gold)
        }
    }
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
fun SafetyActionsDialog(container: AppContainer, target: SafetyTarget, onDone: (Boolean) -> Unit, messages: List<ChatLine>? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = container.nearby
    var mode by remember { mutableStateOf("menu") }
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
