package com.metavirtuoso.conjunction.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import com.metavirtuoso.conjunction.nearby.FriendProfile
import com.metavirtuoso.conjunction.nearby.NearbyApi
import com.metavirtuoso.conjunction.nearby.NearbyException
import com.metavirtuoso.conjunction.ui.ChatTarget
import com.metavirtuoso.conjunction.ui.components.Art
import com.metavirtuoso.conjunction.ui.components.FinePrint
import com.metavirtuoso.conjunction.ui.components.GoldText
import com.metavirtuoso.conjunction.ui.components.OrnateTextField
import com.metavirtuoso.conjunction.ui.components.ProfilePhoto
import com.metavirtuoso.conjunction.ui.components.StarfieldBackground
import com.metavirtuoso.conjunction.ui.theme.GoldBrushes
import com.metavirtuoso.conjunction.ui.theme.Palette
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

/** A message I sent that the server held for review or blocked (shown only to me). */
private data class LocalNotice(val text: String, val status: String)

/**
 * Chat with a mutual match: live messages, timestamps, read state, moderated sending (held /
 * blocked messages are explained), and unmatch / block / report from the shield menu.
 */
@Composable
fun ChatScreen(container: AppContainer, target: ChatTarget, onClose: () -> Unit) {
    val api = container.nearbyApi
    val me = api.uid
    val scope = rememberCoroutineScope()
    val messages by remember(target.pairId) { api.messages(target.pairId).catch { emit(emptyList()) } }.collectAsState(initial = emptyList())
    val readAt by remember(target.pairId) { api.readState(target.pairId).catch { emit(emptyMap()) } }.collectAsState(initial = emptyMap())
    val friend by produceState<FriendProfile?>(initialValue = null, target.otherUid) {
        value = runCatching { api.getFriendProfile(target.otherUid) }.getOrNull()
    }
    var draft by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var safety by remember { mutableStateOf(false) }
    var tips by remember { mutableStateOf(false) }
    val notices = remember { mutableStateListOf<LocalNotice>() }
    var error by remember { mutableStateOf<String?>(null) }
    val list = rememberLazyListState()
    val heldText = stringResource(R.string.chat_held)
    val blockedText = stringResource(R.string.chat_blocked)
    BackHandler(onBack = onClose)

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            list.animateScrollToItem(messages.size + notices.size)
            if (messages.last().from != me) runCatching { api.markRead(target.pairId) }
        }
    }

    StarfieldBackground(dim = 0.7f) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Palette.GoldPale) }
                ProfilePhoto(friend?.photoUrl, Art.signKey(friend?.sun), contentDescription = null, modifier = Modifier.size(44.dp).clip(CircleShape).border(1.dp, GoldBrushes.leaf, CircleShape))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    GoldText(friend?.displayName ?: "Your match", style = MaterialTheme.typography.titleMedium)
                    Text(
                        listOfNotNull(friend?.sun?.let { "☉ ${Art.displaySign(it)}" }, friend?.moon?.let { "☽ ${Art.displaySign(it)}" }).joinToString("  "),
                        style = MaterialTheme.typography.bodySmall, color = Palette.MoonstoneDim,
                    )
                }
                IconButton(onClick = { tips = true }) { Text("?", color = Palette.GoldPale, style = MaterialTheme.typography.titleLarge) }
                IconButton(onClick = { safety = true }) { Icon(Icons.Outlined.Shield, contentDescription = stringResource(R.string.chat_block_report), tint = Palette.RoseCopper) }
            }
            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).background(GoldBrushes.leaf).padding(top = 1.dp))
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { FinePrint(stringResource(R.string.chat_first_open_notice), Modifier.fillMaxWidth().padding(bottom = 8.dp)) }
                items(messages, key = { it.id }) { m ->
                    val mine = m.from == me
                    val theirRead = readAt[target.otherUid]
                    val seen = mine && theirRead != null && m.createdAt != null && !theirRead.isBefore(m.createdAt)
                    Bubble(m.text, mine, m.createdAt?.atZone(ZoneId.systemDefault())?.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)), if (mine) (if (seen) "Seen" else "Sent") else null)
                }
                items(notices) { n -> Bubble(n.text, mine = true, time = null, status = if (n.status == "held") heldText else blockedText, faded = true) }
            }
            error?.let { Text(it, color = Palette.RoseCopper, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp)) }
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    OrnateTextField(draft, { draft = it.take(4000) }, label = "Message", singleLine = false)
                }
                IconButton(
                    enabled = draft.isNotBlank() && !sending,
                    onClick = {
                        val text = draft.trim()
                        sending = true
                        error = null
                        scope.launch {
                            try {
                                val status = api.sendMessage(target.pairId, text, NearbyApi.newClientId())
                                if (status != "delivered") notices += LocalNotice(text, status)
                                draft = ""
                            } catch (e: NearbyException) {
                                error = e.message
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
    if (safety) {
        SafetyActionsDialog(container, target.otherUid, friend?.displayName?.substringBefore(' ') ?: "this person", null, target.pairId) { closed ->
            safety = false
            if (closed) onClose()
        }
    }
    if (tips) LegalDocDialog(LegalDoc.SAFETY, onDismiss = { tips = false })
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
