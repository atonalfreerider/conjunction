package com.metavirtuoso.conjunction.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metavirtuoso.conjunction.ui.theme.GoldBrushes
import com.metavirtuoso.conjunction.ui.theme.Palette

/**
 * A celestial bottom sheet drawn inside the current window (so it stays in the app's look, works
 * in Robolectric screenshots and never needs a second window). [onDismiss] null = only the sheet's
 * own buttons close it; otherwise back and a tap on the scrim close it. [scrimDismisses] false = only
 * back calls [onDismiss] and a tap on the scrim does nothing (the re-acceptance sheet). [footer] is
 * pinned under the scrolling [content] so the actions never fall below the fold.
 */
@Composable
fun CelestialSheet(
    onDismiss: (() -> Unit)?,
    modifier: Modifier = Modifier,
    maxHeightFraction: Float = 0.92f,
    scrimDismisses: Boolean = true,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val host = LocalSheetHost.current
    if (host == null) {
        SheetLayer(onDismiss, scrimDismisses, modifier, maxHeightFraction, footer, content)
        return
    }
    // Shown by the nearest SheetHost (over the whole screen, tab bar included), wherever it is called.
    val entry = remember { SheetEntry() }
    val body: @Composable () -> Unit = { SheetLayer(onDismiss, scrimDismisses, modifier, maxHeightFraction, footer, content) }
    SideEffect { entry.content = body }
    DisposableEffect(entry) {
        entry.content = body
        host.entries.add(entry)
        onDispose { host.entries.remove(entry) }
    }
}

/** Where [CelestialSheet]s draw: over [content], in call order. */
class SheetHostState {
    internal val entries = mutableStateListOf<SheetEntry>()
    val isShowing: Boolean get() = entries.isNotEmpty()
}

internal class SheetEntry {
    var content: @Composable () -> Unit by mutableStateOf({})
}

val LocalSheetHost = staticCompositionLocalOf<SheetHostState?> { null }

@Composable
fun SheetHost(content: @Composable () -> Unit) {
    val state = remember { SheetHostState() }
    CompositionLocalProvider(LocalSheetHost provides state) {
        Box(Modifier.fillMaxSize()) {
            content()
            state.entries.forEach { entry -> key(entry) { entry.content() } }
        }
    }
}

@Composable
private fun SheetLayer(
    onDismiss: (() -> Unit)?,
    scrimDismisses: Boolean,
    modifier: Modifier,
    maxHeightFraction: Float,
    footer: (@Composable ColumnScope.() -> Unit)?,
    content: @Composable ColumnScope.() -> Unit,
) {
    BackHandler(enabled = true) { onDismiss?.invoke() }
    Box(
        Modifier
            .fillMaxSize()
            .background(Palette.Scrim)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, enabled = true) { if (scrimDismisses) onDismiss?.invoke() },
    ) {
        SheetPanel(modifier, maxHeightFraction, footer, content)
    }
}

@Composable
private fun BoxScope.SheetPanel(modifier: Modifier, maxHeightFraction: Float, footer: (@Composable ColumnScope.() -> Unit)?, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
        Column(
            modifier
                .align(Alignment.BottomCenter)
                .widthIn(max = 640.dp)
                .fillMaxWidth()
                .heightIn(max = maxHeight * maxHeightFraction)
                .clip(shape)
                .background(Brush.verticalGradient(listOf(Palette.InkViolet, Palette.Midnight)))
                .drawWithCache {
                    onDrawWithContent {
                        drawContent()
                        // Gold hairline along the top edge and a small filigree handle.
                        drawLine(GoldBrushes.leaf, Offset(22.dp.toPx(), 0.6.dp.toPx()), Offset(size.width - 22.dp.toPx(), 0.6.dp.toPx()), strokeWidth = 1.2.dp.toPx())
                        drawCircle(Palette.Gold.copy(alpha = 0.6f), radius = 3.dp.toPx(), center = Offset(size.width / 2, 12.dp.toPx()), style = Stroke(1.dp.toPx()))
                    }
                }
                // Swallow taps so they don't reach the scrim.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .navigationBarsPadding()
                .imePadding(),
        ) {
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 20.dp, end = 20.dp, top = 26.dp, bottom = if (footer == null) 16.dp else 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content,
            )
            if (footer != null) {
                Column(
                    Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    content = footer,
                )
            }
        }
    }
}

/** A full-screen page drawn over the app (Settings sub-pages, Compatibility view), with its own back. */
@Composable
fun OverlayPage(onBack: () -> Unit, content: @Composable () -> Unit) {
    BackHandler(onBack = onBack)
    Box(Modifier.fillMaxSize().background(Palette.Midnight).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}) {
        content()
    }
}

/**
 * Body text with gold, underlined links (SPEC 5d.1.1): [template] uses `%1$s`, `%2$s`... and each
 * [links] entry is (label, onClick). Lines are padded so each link is a >= 48 dp touch target.
 */
@Composable
fun LinkedText(template: String, links: List<Pair<String, () -> Unit>>, modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.bodyLarge) {
    val linkStyle = TextLinkStyles(SpanStyle(color = Palette.Gold, textDecoration = TextDecoration.Underline))
    val text = buildAnnotatedString {
        var rest = template
        while (true) {
            val m = Regex("%(\\d)\\\$s").find(rest) ?: break
            append(rest.substring(0, m.range.first))
            val index = m.groupValues[1].toInt() - 1
            val (label, onClick) = links.getOrNull(index) ?: ("" to {})
            withLink(LinkAnnotation.Clickable(tag = "doc$index", styles = linkStyle, linkInteractionListener = { onClick() })) { append(label) }
            rest = rest.substring(m.range.last + 1)
        }
        append(rest)
    }
    Text(text, style = style.copy(lineHeight = 30.sp), color = Palette.Moonstone, modifier = modifier.padding(vertical = 6.dp))
}

/** Formats [template] the way [LinkedText] shows it (for the consent hash of the displayed sentence). */
fun formatLinked(template: String, labels: List<String>): String {
    var out = template
    labels.forEachIndexed { i, label -> out = out.replace("%${i + 1}\$s", label) }
    return out
}

/** Small round "⋯" menu button. */
@Composable
fun MoreButton(contentDescription: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Text("⋯", style = MaterialTheme.typography.titleLarge, color = Palette.GoldPale)
    }
}

/** Page header for overlay pages: back arrow + gold title. */
@Composable
fun PageHeader(title: String, onBack: () -> Unit, trailing: (@Composable () -> Unit)? = null) {
    androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.IconButton(onClick = onBack) {
            androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Palette.GoldPale)
        }
        GoldText(title, style = MaterialTheme.typography.titleLarge, heading = true, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}
