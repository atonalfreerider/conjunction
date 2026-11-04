package com.metavirtuoso.conjunction.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.metavirtuoso.conjunction.BuildConfig
import com.metavirtuoso.conjunction.R
import com.metavirtuoso.conjunction.ai.Reading
import com.metavirtuoso.conjunction.data.ReadingSource
import com.metavirtuoso.conjunction.nearby.ReportEmail
import com.metavirtuoso.conjunction.ui.theme.LocalReducedMotion
import com.metavirtuoso.conjunction.ui.theme.Palette

/** UI state of one reading. */
sealed interface ReadingState {
    data class Loading(val status: String = "Consulting the stars…") : ReadingState
    data class Ready(val reading: Reading) : ReadingState
    data class Failed(val message: String) : ReadingState
}

/**
 * A reading presented as a tarot-like card: while it is being written the card shows its gilded
 * back with the orrery; when ready it flips over to the text (cross-fade with reduced motion).
 */
@Composable
fun ReadingCard(
    title: String,
    state: ReadingState,
    modifier: Modifier = Modifier,
    onRefresh: (() -> Unit)? = null,
) {
    val reduced = LocalReducedMotion.current
    val ready = state is ReadingState.Ready
    val rotation = remember { Animatable(if (ready) 0f else 180f) }
    LaunchedEffect(ready) {
        val target = if (ready) 0f else 180f
        if (reduced) rotation.snapTo(target) else rotation.animateTo(target, tween(900, easing = FastOutSlowInEasing))
    }
    var reporting by remember { mutableStateOf(false) }
    val showFront = rotation.value < 90f
    if (reporting && state is ReadingState.Ready) ReportReadingDialog(title, state.reading.text) { reporting = false }
    Box(
        modifier
            .fillMaxWidth()
            .graphicsLayer {
                rotationY = rotation.value
                cameraDistance = 14f * density
            },
    ) {
        if (showFront && state is ReadingState.Ready) {
            OrnatePanel(Modifier.fillMaxWidth()) {
                GoldText(title, style = MaterialTheme.typography.titleLarge, heading = true)
                OrnateDivider(Modifier.padding(vertical = 6.dp))
                Text(
                    state.reading.text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Palette.Moonstone,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                // One fine-print line per reading (SPEC 5d.1): no provenance or model-availability notes.
                // The only extra note kept is the palm one, which says what the on-device model looked at.
                state.reading.note?.takeIf { state.reading.source == ReadingSource.ON_DEVICE_AI }?.let { FinePrint(it, Modifier.padding(top = 12.dp)) }
                FinePrint(stringResource(R.string.entertainment_note), Modifier.padding(top = 8.dp))
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (onRefresh != null) GhostButton("Write again", onClick = onRefresh, modifier = Modifier.weight(1f))
                    GhostButton(stringResource(R.string.report_reading), onClick = { reporting = true }, modifier = Modifier.weight(1f), color = Palette.RoseCopper)
                }
            }
        } else {
            // Card back (drawn mirrored so it reads correctly while rotated past 90 degrees).
            OrnatePanel(Modifier.fillMaxWidth().heightIn(min = 260.dp).graphicsLayer { rotationY = if (showFront) 0f else 180f }) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Image(painterResource(R.drawable.splash_mark), contentDescription = null, modifier = Modifier.size(96.dp))
                    GoldText(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                    when (state) {
                        is ReadingState.Loading -> {
                            OrreryLoader(Modifier.padding(top = 10.dp), size = 56.dp)
                            Text(state.status, style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic), color = Palette.MoonstoneDim, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp).semantics { liveRegion = LiveRegionMode.Polite })
                        }
                        is ReadingState.Failed -> {
                            Text(state.message, style = MaterialTheme.typography.bodyMedium, color = Palette.RoseCopper, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 10.dp))
                            if (onRefresh != null) GhostButton("Try again", onClick = onRefresh, modifier = Modifier.padding(top = 10.dp))
                        }
                        is ReadingState.Ready -> Unit
                    }
                }
            }
        }
    }
}

/**
 * "Report this reading" (Google Play AI-generated content policy): asks what was wrong, then opens
 * an email to the support address with the reading and the reason (legal/IN_APP_CONSENT_COPY.md
 * section 8: report_reading_body). Birth details are not included. Nothing is sent until the person
 * presses Send in their email app.
 */
@Composable
fun ReportReadingDialog(title: String, text: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var reason by remember { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }
    val appName = stringResource(R.string.app_name)
    val noEmailApp = stringResource(R.string.report_no_email_app, BuildConfig.SUPPORT_EMAIL)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.InkViolet,
        title = { GoldText(stringResource(R.string.report_reading), style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.report_reading_body), style = MaterialTheme.typography.bodyMedium, color = Palette.Moonstone)
                OrnateTextField(reason, { reason = it.take(1000) }, label = "What was wrong? (optional)", singleLine = false, minLines = 3)
                note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.RoseCopper) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val subject = "$appName report: reading"
                val body = "What was wrong with this reading?\n${reason.trim().ifEmpty { "(not given)" }}\n\n---\n$title\n$text\n\n" +
                    "App: $appName ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}), Android ${android.os.Build.VERSION.RELEASE}"
                if (ReportEmail.open(context, subject, body)) onDismiss()
                else note = noEmailApp
            }) { Text("WRITE EMAIL", color = Palette.Gold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("CANCEL", color = Palette.MoonstoneDim) } },
    )
}
