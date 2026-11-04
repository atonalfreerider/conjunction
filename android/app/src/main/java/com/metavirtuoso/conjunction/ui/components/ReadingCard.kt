package com.metavirtuoso.conjunction.ui.components

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
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
    val context = LocalContext.current
    val showFront = rotation.value < 90f
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
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(if (state.reading.source == ReadingSource.ON_DEVICE_AI) R.string.ai_source_device else R.string.ai_source_fallback),
                        style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
                        color = Palette.MoonstoneDim,
                        modifier = Modifier.weight(1f),
                    )
                }
                state.reading.note?.let { FinePrint(it) }
                FinePrint(stringResource(R.string.entertainment_note), Modifier.padding(top = 4.dp))
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (onRefresh != null) GhostButton("Write again", onClick = onRefresh, modifier = Modifier.weight(1f))
                    GhostButton(stringResource(R.string.report_reading), onClick = { reportReading(context, title, state.reading.text) }, modifier = Modifier.weight(1f), color = Palette.RoseCopper)
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
 * "Report this reading" (Google Play AI-generated content policy): opens an email to the support
 * address set at build time (gradle property conjunction.supportEmail), or the share sheet when no
 * address is configured. Nothing is sent without the person choosing to send it.
 */
fun reportReading(context: Context, title: String, text: String) {
    val subject = "Report a ${context.getString(R.string.app_name)} reading (${BuildConfig.VERSION_NAME})"
    val body = "What was wrong with this reading?\n\n\n---\n$title\n$text"
    val email = BuildConfig.SUPPORT_EMAIL
    val intent = if (email.isNotBlank()) {
        Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).apply {
            putExtra(Intent.EXTRA_EMAIL, arrayOf(email))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
        }
    } else {
        Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, subject).putExtra(Intent.EXTRA_TEXT, body), "Report this reading")
    }
    try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
    }
}
