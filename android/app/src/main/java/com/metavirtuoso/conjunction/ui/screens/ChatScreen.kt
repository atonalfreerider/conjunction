package com.metavirtuoso.conjunction.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.metavirtuoso.conjunction.AppContainer
import com.metavirtuoso.conjunction.R
import com.metavirtuoso.conjunction.legal.LegalDoc
import com.metavirtuoso.conjunction.nearby.ChatStatus
import com.metavirtuoso.conjunction.nearby.Screening
import com.metavirtuoso.conjunction.ui.ChatTarget
import com.metavirtuoso.conjunction.ui.components.Art
import com.metavirtuoso.conjunction.ui.components.FinePrint
import com.metavirtuoso.conjunction.ui.components.GoldText
import com.metavirtuoso.conjunction.ui.components.OrnateTextField
import com.metavirtuoso.conjunction.ui.components.ScreenedPhoto
import com.metavirtuoso.conjunction.ui.components.StarfieldBackground
import com.metavirtuoso.conjunction.ui.theme.GoldBrushes
import com.metavirtuoso.conjunction.ui.theme.Palette
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.launch

/**
 * Chat with a friend (mutual match), SPEC 5c: end-to-end encrypted over Bluetooth, stored only on
 * the two phones. Messages to a friend who is out of range are queued and delivered the next time
 * the phones are near each other, and the bubble says so. Incoming messages are screened on this
 * phone and hidden behind "Show anyway" when they look harmful. Unmatch / block / report from the
 * shield menu; the "verify" button shows the safety code to compare in person.
 */
@Composable
fun ChatScreen(container: AppContainer, target: ChatTarget, onClose: () -> Unit) {
    val repo = container.nearby
    val scope = rememberCoroutineScope()
    val friend by remember(target.friendId) { repo.friend(target.friendId) }.collectAsState(initial = null)
    val messages by remember(target.friendId) { repo.messages(target.friendId) }.collectAsState(initial = emptyList())
    var draft by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var safety by remember { mutableStateOf<String?>(null) }
    var tips by remember { mutableStateOf(false) }
    var verify by remember { mutableStateOf(false) }
    val nav = com.metavirtuoso.conjunction.ui.LocalAppNav.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val list = rememberLazyListState()
    BackHandler(onBack = onClose)

    DisposableEffect(target.friendId) {
        repo.openChatFriendId = target.friendId
        onDispose { if (repo.openChatFriendId == target.friendId) repo.openChatFriendId = null }
    }
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) list.animateScrollToItem(messages.size)
        repo.markSeen(target.friendId)
    }
    val f = friend
    val name = f?.firstName ?: "Your match"

    StarfieldBackground(dim = 0.7f) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            // SPEC 5d.8: Back · photo + name (→ Compatibility) · score chip (→ Compatibility) · ⋯
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Palette.GoldPale) }
                Row(
                    Modifier.weight(1f).clickable(role = androidx.compose.ui.semantics.Role.Button, onClickLabel = "Compatibility chart") { nav.openCompat(target.friendId) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ScreenedPhoto(f?.photoPath, f?.photoScreening ?: Screening.SAFE, Art.signKey(f?.sun), contentDescription = null, modifier = Modifier.size(44.dp).clip(CircleShape).border(1.dp, GoldBrushes.leaf, CircleShape), compact = true)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        GoldText(name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (f?.inRange == true) "Nearby now" else listOfNotNull(f?.sun?.let { "☉ ${Art.displaySign(it)}" }, f?.moon?.let { "☽ ${Art.displaySign(it)}" }).joinToString("  "),
                            style = MaterialTheme.typography.bodySmall, color = if (f?.inRange == true) Palette.Gold else Palette.MoonstoneDim,
                        )
                    }
                }
                f?.total?.let { total ->
                    Text(
                        "$total ☌\uFE0E", style = MaterialTheme.typography.titleSmall, color = Palette.Midnight,
                        modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(GoldBrushes.leaf)
                            .clickable(role = androidx.compose.ui.semantics.Role.Button, onClickLabel = "Compatibility chart") { nav.openCompat(target.friendId) }
                            .padding(horizontal = 10.dp, vertical = 6.dp).testTag("scoreChip"),
                    )
                }
                if (f != null) {
                    FriendMenu(f, onAction = { a ->
                        when (a) {
                            FriendMenuAction.SHARE_INSTAGRAM -> scope.launch { repo.setInstagramShare(f.id, !f.igShareOn) }
                            FriendMenuAction.SAFETY_CODE -> verify = true
                            FriendMenuAction.UNMATCH -> safety = "unmatch"
                            FriendMenuAction.BLOCK -> safety = "block"
                            FriendMenuAction.REPORT -> safety = "report"
                        }
                    }, extra = { close ->
                        f.peerIg?.let { h ->
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text("${stringResource(R.string.instagram_chip)} @$h", color = Palette.GoldPale) },
                                onClick = { close(); com.metavirtuoso.conjunction.data.Instagram.open(context, h) },
                            )
                        }
                        androidx.compose.material3.DropdownMenuItem(text = { Text(stringResource(R.string.menu_safety_tips), color = Palette.Moonstone) }, onClick = { close(); tips = true })
                    })
                }
            }
            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).background(GoldBrushes.leaf).padding(top = 1.dp))
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // One centred line at the top of a new chat (SPEC 5d.8).
                if (messages.isEmpty()) item {
                    Text(
                        stringResource(R.string.chat_first_open_notice),
                        style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic), color = Palette.MoonstoneDim,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp, horizontal = 24.dp).testTag("chatNotice"),
                    )
                }
                items(messages, key = { it.id }) { m ->
                    val time = Instant.ofEpochMilli(m.ts).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
                    if (m.mine) {
                        val status = when (m.status) {
                            ChatStatus.QUEUED -> stringResource(R.string.chat_queued, name)
                            ChatStatus.SENT -> "Sending…"
                            ChatStatus.DELIVERED -> "Delivered"
                            ChatStatus.READ -> "Seen"
                            ChatStatus.RECEIVED -> null
                        }
                        Bubble(m.text, mine = true, time = time, status = status, faded = m.status == ChatStatus.QUEUED)
                    } else when (m.screening) {
                        Screening.SAFE -> Bubble(m.text, mine = false, time = time, status = null)
                        Screening.FLAGGED -> HiddenBubble(stringResource(R.string.chat_flagged), time) { scope.launch { repo.reveal(m.id) } }
                        else -> HiddenBubble("Checking this message on your phone…", time, onShow = null)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    OrnateTextField(draft, { draft = it.take(2000) }, label = "Message", singleLine = false)
                }
                IconButton(
                    enabled = draft.isNotBlank() && !sending && f != null,
                    onClick = {
                        val text = draft.trim()
                        sending = true
                        scope.launch {
                            try {
                                repo.sendChat(target.friendId, text)
                                draft = ""
                            } finally {
                                sending = false
                            }
                        }
                    },
                    modifier = Modifier.semantics { contentDescription = "Send message" },
                ) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, tint = if (draft.isNotBlank()) Palette.Gold else Palette.MoonstoneDim) }
            }
        }
    }
    val mode = safety
    if (mode != null && f != null) {
        SafetyActionsDialog(container, SafetyTarget.friend(f), messages = messages, initialMode = mode, onDone = { closed ->
            safety = null
            if (closed) onClose()
        })
    }
    if (tips) SafetyTipsDialog(container) { tips = false }
    if (verify && f != null) SafetyCodeDialog(name, f.safetyCode) { verify = false }
}

@Composable
private fun HiddenBubble(text: String, time: String?, onShow: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Column(
            Modifier
                .widthIn(max = 300.dp)
                .clip(RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp))
                .background(Palette.InkViolet.copy(alpha = 0.6f))
                .border(1.dp, Palette.RoseCopper.copy(alpha = 0.6f), RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(text, style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic), color = Palette.MoonstoneDim)
            if (onShow != null) TextButton(onClick = onShow) { Text(stringResource(R.string.chat_flagged_show).uppercase(), color = Palette.Gold, style = MaterialTheme.typography.labelMedium) }
            time?.let { Text(it, style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic), color = Palette.MoonstoneDim, modifier = Modifier.align(Alignment.End)) }
        }
    }
}

@Composable
private fun Bubble(text: String, mine: Boolean, time: String?, status: String?, faded: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Column(
            Modifier
                .widthIn(max = 300.dp)
                .clip(RoundedCornerShape(16.dp, 16.dp, if (mine) 4.dp else 16.dp, if (mine) 16.dp else 4.dp))
                .background(if (mine) Palette.Dusk.copy(alpha = if (faded) 0.5f else 0.95f) else Palette.InkViolet.copy(alpha = 0.95f))
                .border(1.dp, if (mine) Palette.GoldDeep else Palette.Twilight, RoundedCornerShape(16.dp, 16.dp, if (mine) 4.dp else 16.dp, if (mine) 16.dp else 4.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(text, style = MaterialTheme.typography.bodyMedium, color = Palette.Moonstone)
            val meta = listOfNotNull(time, status).joinToString(" · ")
            if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic), color = Palette.MoonstoneDim, modifier = Modifier.align(Alignment.End))
        }
    }
}
