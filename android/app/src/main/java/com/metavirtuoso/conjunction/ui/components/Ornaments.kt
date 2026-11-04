package com.metavirtuoso.conjunction.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.metavirtuoso.conjunction.R
import com.metavirtuoso.conjunction.ui.theme.GoldBrushes
import com.metavirtuoso.conjunction.ui.theme.LocalReducedMotion
import com.metavirtuoso.conjunction.ui.theme.Palette
import com.metavirtuoso.conjunction.ui.theme.rememberGoldShimmer

/** Text painted with shimmering gold leaf. */
@Composable
fun GoldText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.headlineMedium,
    textAlign: TextAlign? = null,
    heading: Boolean = false,
) {
    val phase = rememberGoldShimmer()
    // The text is laid out once in plain gold; the moving gold-leaf band is painted over its glyphs
    // (SrcIn) in the draw phase, so the shimmer never triggers recomposition or relayout.
    Text(
        text = text,
        modifier = (if (heading) modifier.semantics { heading() } else modifier)
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(GoldBrushes.shimmer(phase.value), blendMode = BlendMode.SrcIn)
            },
        style = style.copy(color = Palette.Gold),
        textAlign = textAlign,
    )
}

/** Art Nouveau rule: gold hairline with a diamond and two dots in the middle. */
@Composable
fun OrnateDivider(modifier: Modifier = Modifier, width: Dp = 220.dp) {
    Canvas(modifier.width(width).height(16.dp)) {
        val cy = size.height / 2
        val cx = size.width / 2
        val line = Brush.horizontalGradient(listOf(Color.Transparent, Palette.Gold, Palette.GoldPale, Palette.Gold, Color.Transparent))
        drawLine(line, Offset(0f, cy), Offset(cx - 14.dp.toPx(), cy), strokeWidth = 1.2.dp.toPx())
        drawLine(line, Offset(cx + 14.dp.toPx(), cy), Offset(size.width, cy), strokeWidth = 1.2.dp.toPx())
        val d = 6.dp.toPx()
        val diamond = Path().apply {
            moveTo(cx, cy - d); lineTo(cx + d, cy); lineTo(cx, cy + d); lineTo(cx - d, cy); close()
        }
        drawPath(diamond, GoldBrushes.vertical)
        drawCircle(Palette.GoldPale, radius = 1.8.dp.toPx(), center = Offset(cx - 11.dp.toPx(), cy))
        drawCircle(Palette.GoldPale, radius = 1.8.dp.toPx(), center = Offset(cx + 11.dp.toPx(), cy))
    }
}

/** Draws a filigree frame: double gold rule with cut corners, corner fleurons and edge studs. */
fun DrawScope.drawFiligreeFrame(brush: Brush, inset: Float, corner: Float) {
    val w = size.width
    val h = size.height
    fun frame(o: Float, c: Float): Path = Path().apply {
        moveTo(o + c, o); lineTo(w - o - c, o); lineTo(w - o, o + c); lineTo(w - o, h - o - c)
        lineTo(w - o - c, h - o); lineTo(o + c, h - o); lineTo(o, h - o - c); lineTo(o, o + c); close()
    }
    drawPath(frame(inset, corner), brush, style = Stroke(width = 1.6f * density))
    drawPath(frame(inset + 5f * density, corner * 0.8f), brush, alpha = 0.55f, style = Stroke(width = 0.8f * density))
    // Corner fleurons: a small lily-like trefoil of circles at each cut corner.
    val r = 3.2f * density
    val corners = listOf(
        Offset(inset + corner * 0.5f, inset + corner * 0.5f),
        Offset(w - inset - corner * 0.5f, inset + corner * 0.5f),
        Offset(inset + corner * 0.5f, h - inset - corner * 0.5f),
        Offset(w - inset - corner * 0.5f, h - inset - corner * 0.5f),
    )
    corners.forEach { c ->
        drawCircle(brush, radius = r, center = c)
        drawCircle(Palette.Midnight, radius = r * 0.45f, center = c)
    }
    // Studs at the middle of each edge.
    listOf(Offset(w / 2, inset), Offset(w / 2, h - inset), Offset(inset, h / 2), Offset(w - inset, h / 2)).forEach { c ->
        rotate(45f, c) {
            drawRect(brush, topLeft = Offset(c.x - r, c.y - r), size = androidx.compose.ui.geometry.Size(r * 2, r * 2))
        }
    }
}

/**
 * The standard content panel: ink-violet glass with a hand-drawn gold filigree frame. Sized by its
 * content (unlike the painted card frame art, which is used where the aspect ratio is fixed).
 */
@Composable
fun OrnatePanel(
    modifier: Modifier = Modifier,
    contentPadding: Dp = 22.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val phase = rememberGoldShimmer(9000)
    Column(
        modifier
            .clip(CutCornerShape(14.dp))
            .background(
                Brush.verticalGradient(listOf(Palette.InkViolet.copy(alpha = 0.92f), Palette.Midnight.copy(alpha = 0.94f))),
            )
            .drawWithCache {
                val period = size.width.coerceAtLeast(300f)
                onDrawWithContent {
                    val brush = GoldBrushes.shimmer(phase.value, period = period)
                    drawContent()
                    drawFiligreeFrame(brush, inset = 4.dp.toPx(), corner = 14.dp.toPx())
                }
            }
            .padding(contentPadding),
        content = content,
    )
}

/** Section heading: engraved gold title with an ornamental rule under it. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, subtitle: String? = null) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        GoldText(text, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center, heading = true)
        OrnateDivider(Modifier.padding(top = 4.dp))
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium.copy(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic),
                color = Palette.MoonstoneDim,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/** Primary action: a gold-leaf ingot button with a slow shimmer. */
@Composable
fun GoldButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
    horizontalPadding: Dp = 24.dp,
) {
    val phase = rememberGoldShimmer()
    val shape = CutCornerShape(10.dp)
    Row(
        modifier
            .defaultMinSize(minHeight = 52.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .clip(shape)
            .drawBehind { drawRect(GoldBrushes.shimmer(phase.value, period = 500f)) }
            .border(1.dp, Palette.GoldPale.copy(alpha = 0.8f), shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = horizontalPadding, vertical = 14.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Box(Modifier.width(10.dp))
        }
        Text(text.uppercase(), style = MaterialTheme.typography.labelLarge, color = Palette.Midnight, textAlign = TextAlign.Center)
    }
}

/** Secondary action: gold outline on glass. */
@Composable
fun GhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = Palette.GoldPale,
    horizontalPadding: Dp = 20.dp,
) {
    val shape = CutCornerShape(10.dp)
    Box(
        modifier
            .defaultMinSize(minHeight = 48.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .clip(shape)
            .background(Palette.Midnight.copy(alpha = 0.55f))
            .border(1.dp, GoldBrushes.leaf, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = horizontalPadding, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text.uppercase(), style = MaterialTheme.typography.labelMedium, color = color, textAlign = TextAlign.Center)
    }
}

/** Round artwork (zodiac sign, animal, blood type, element) with a soft gold halo. */
@Composable
fun ArtMedallion(
    @DrawableRes art: Int,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = 120.dp,
) {
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(
                Brush.radialGradient(listOf(Palette.Gold.copy(alpha = 0.35f), Color.Transparent)),
                radius = this.size.minDimension / 2,
            )
        }
        Image(
            painter = painterResource(art),
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(size * 0.92f).clip(CircleShape),
        )
    }
}

/** Painted ornate card (art/frames/card_frame) at its native 2:3 ratio, content inside the frame. */
@Composable
fun FramedCard(
    modifier: Modifier = Modifier,
    @DrawableRes frame: Int = R.drawable.frame_card,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(modifier.aspectRatio(2f / 3f)) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(10.dp)
                .clip(CutCornerShape(12.dp))
                .background(Brush.verticalGradient(listOf(Palette.InkViolet, Palette.Midnight))),
        )
        Column(
            Modifier.fillMaxSize().padding(horizontal = 34.dp, vertical = 44.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            content = content,
        )
        Image(painterResource(frame), contentDescription = null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
    }
}

/** Waiting indicator: three planets orbiting a gold sun (static ring when motion is reduced). */
@Composable
fun OrreryLoader(modifier: Modifier = Modifier, size: Dp = 72.dp) {
    val reduced = LocalReducedMotion.current
    val transition = rememberInfiniteTransition(label = "orrery")
    val angle by transition.animateFloat(0f, 360f, infiniteRepeatable(tween(4000, easing = LinearEasing)), label = "orbit")
    Canvas(modifier.size(size).semantics { }) {
        val c = center
        val r = this.size.minDimension / 2
        drawCircle(GoldBrushes.vertical, radius = r * 0.16f, center = c)
        listOf(0.42f to 1f, 0.68f to 0.62f, 0.92f to 0.38f).forEachIndexed { i, (ring, speed) ->
            drawCircle(Palette.Gold.copy(alpha = 0.35f), radius = r * ring, center = c, style = Stroke(width = 0.8f * density))
            val a = Math.toRadians(((if (reduced) 90f * i else angle * speed) + i * 120f).toDouble())
            val p = Offset(c.x + (r * ring) * kotlin.math.cos(a).toFloat(), c.y + (r * ring) * kotlin.math.sin(a).toFloat())
            drawCircle(if (i == 1) Palette.RoseCopper else Palette.Moonstone, radius = r * 0.07f, center = p)
        }
    }
}

/** Small engraved label + value pair used in tables and cards. */
@Composable
fun LabelValue(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = Palette.Gold)
        Text(value, style = MaterialTheme.typography.bodyLarge, color = Palette.Moonstone)
    }
}
