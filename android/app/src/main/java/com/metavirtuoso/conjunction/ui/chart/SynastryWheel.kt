package com.metavirtuoso.conjunction.ui.chart

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import com.metavirtuoso.conjunction.core.chart.ZodiacSign
import com.metavirtuoso.conjunction.core.compat.CompatProfile
import com.metavirtuoso.conjunction.core.compat.Compatibility
import com.metavirtuoso.conjunction.ui.theme.LocalReducedMotion
import com.metavirtuoso.conjunction.ui.theme.Palette
import kotlin.math.cos
import kotlin.math.sin

/** Planet glyph for a compatibility point. */
fun pointGlyph(p: Compatibility.Point): String = when (p) {
    Compatibility.Point.SUN -> textGlyph("☉")
    Compatibility.Point.MOON -> textGlyph("☽")
    Compatibility.Point.VENUS -> textGlyph("♀")
    Compatibility.Point.MARS -> textGlyph("♂")
    Compatibility.Point.ASC -> "AC"
}

/** Aspect glyph by core aspect name. */
fun aspectGlyph(name: String): String = when (name) {
    "conjunction" -> textGlyph("☌")
    "sextile" -> textGlyph("⚹")
    "square" -> textGlyph("□")
    "trine" -> textGlyph("△")
    "opposition" -> textGlyph("☍")
    else -> "·"
}

/** The five compatibility points that are known, with their longitudes. */
fun compatPoints(p: CompatProfile): List<Pair<Compatibility.Point, Double>> = listOfNotNull(
    Compatibility.Point.SUN to p.sun, Compatibility.Point.MOON to p.moon, Compatibility.Point.VENUS to p.venus,
    Compatibility.Point.MARS to p.mars, p.ascendant?.let { Compatibility.Point.ASC to it },
)

/**
 * Synastry bi-wheel (SPEC 5d.4): zodiac ring; inner ring = me, outer ring = them, each with Sun,
 * Moon, Venus, Mars and the Ascendant (when known) at their true longitudes; cross-aspect lines for
 * every in-orb [lines] entry, gold for conjunction / sextile / trine, rose-copper for square /
 * opposition, width by weight. Labels show glyphs only (no degrees). It draws itself in 1.2 s; the
 * animation value is read only inside the Canvas draw lambda.
 */
@Composable
fun SynastryWheel(me: CompatProfile, them: CompatProfile, lines: List<Compatibility.SynastryLine>, modifier: Modifier = Modifier) {
    val reduced = LocalReducedMotion.current
    val progress = remember(me, them) { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(me, them, reduced) { if (!reduced) progress.animateTo(1f, tween(1200, easing = FastOutSlowInEasing)) }
    val measurer = rememberTextMeasurer()
    val description = remember(lines) {
        "Synastry wheel: your points inside, theirs outside, ${lines.size} aspects between you."
    }
    Canvas(modifier.aspectRatio(1f).semantics { contentDescription = description }) {
        drawBiWheel(me, them, lines, progress.value, measurer)
    }
}

private fun DrawScope.polar(center: Offset, radius: Float, angleDeg: Double): Offset {
    val a = Math.toRadians(angleDeg)
    return Offset(center.x + radius * cos(a).toFloat(), center.y - radius * sin(a).toFloat())
}

private fun DrawScope.drawBiWheel(me: CompatProfile, them: CompatProfile, lines: List<Compatibility.SynastryLine>, t: Float, measurer: TextMeasurer) {
    val c = center
    val outer = size.minDimension / 2f * 0.98f
    val signInner = outer * 0.84f
    val theirRing = outer * 0.70f
    val myRing = outer * 0.46f
    val ring = (t / 0.5f).coerceIn(0f, 1f)
    val pointsT = ((t - 0.3f) / 0.4f).coerceIn(0f, 1f)
    val linesT = ((t - 0.6f) / 0.4f).coerceIn(0f, 1f)
    val gold = Brush.sweepGradient(listOf(Palette.GoldDeep, Palette.GoldPale, Palette.Gold, Palette.GoldDeep, Palette.GoldPale, Palette.GoldDeep), c)

    drawCircle(Brush.radialGradient(listOf(Palette.Dusk.copy(alpha = 0.85f), Palette.Midnight.copy(alpha = 0.92f)), c, outer), outer, c)
    listOf(outer to 2.2f, signInner to 1.1f, theirRing - outer * 0.08f to 0.8f, myRing + outer * 0.07f to 0.8f).forEach { (r, w) ->
        drawArc(gold, 180f, 360f * ring, false, Offset(c.x - r, c.y - r), Size(r * 2, r * 2), style = Stroke(w * density, cap = StrokeCap.Round))
    }
    val glyphStyle = TextStyle(color = Palette.GoldPale, fontSize = (outer / 14f / density).sp)
    ZodiacSign.entries.forEach { sign ->
        val start = wheelAngle(sign.ordinal * 30.0, null)
        val reveal = ((ring * 12f) - sign.ordinal).coerceIn(0f, 1f)
        if (reveal <= 0f) return@forEach
        drawLine(Palette.Gold.copy(alpha = 0.6f * reveal), polar(c, signInner, start), polar(c, outer, start), strokeWidth = 1f * density)
        val mid = polar(c, (outer + signInner) / 2f, start + 15.0)
        val layout = measurer.measure(textGlyph(sign.glyph), glyphStyle)
        drawText(layout, topLeft = Offset(mid.x - layout.size.width / 2f, mid.y - layout.size.height / 2f), alpha = reveal)
    }
    val mine = compatPoints(me).toMap()
    val theirs = compatPoints(them).toMap()
    // Cross-aspect lines between my point (inner ring) and theirs (outer ring).
    if (linesT > 0f) {
        lines.forEach { l ->
            val a = mine[l.pointA] ?: return@forEach
            val b = theirs[l.pointB] ?: return@forEach
            val pa = polar(c, myRing, wheelAngle(a, null))
            val pb = polar(c, theirRing - outer * 0.08f, wheelAngle(b, null))
            val end = Offset(pa.x + (pb.x - pa.x) * linesT, pa.y + (pb.y - pa.y) * linesT)
            val tight = (1f - l.orbCentideg / l.aspect.orb.toFloat()).coerceIn(0.3f, 1f)
            val color = if (l.isHarmonious) Palette.Gold else Palette.RoseCopper
            drawLine(color.copy(alpha = 0.85f * tight), pa, end, strokeWidth = (0.6f + l.weight * 0.4f) * density, cap = StrokeCap.Round)
        }
    }
    fun drawPoints(points: Map<Compatibility.Point, Double>, radius: Float, color: androidx.compose.ui.graphics.Color, withSign: Boolean) {
        val keys = points.keys.toList()
        val fontPx = outer / 16f
        // Label de-collision: the gap is the angle one label ("AC" is the widest) needs at this
        // radius, so the inner ring (smaller radius) spreads its labels further apart.
        val minGap = maxOf(11.0, Math.toDegrees(1.6 * fontPx / radius.toDouble()))
        val spread = spreadLongitudes(keys.map { points.getValue(it) }, minGap = minGap)
        val style = TextStyle(color = color, fontSize = (fontPx / density).sp)
        val signStyle = TextStyle(color = Palette.MoonstoneDim, fontSize = (outer / 24f / density).sp)
        keys.forEachIndexed { i, p ->
            val lon = points.getValue(p)
            val trueA = wheelAngle(lon, null)
            val dotR = if (withSign) theirRing - outer * 0.08f else myRing
            drawCircle(color.copy(alpha = pointsT), radius = 2.4f * density, center = polar(c, dotR, trueA))
            val shownA = wheelAngle(spread[i], null)
            // A leader tick from the true longitude to a label that was nudged aside.
            val nudge = ((spread[i] - lon) % 360 + 540) % 360 - 180
            if (kotlin.math.abs(nudge) > 1.0) {
                // The label's edge facing the dot (their labels sit outside their dots, mine inside).
                val labelEdge = if (withSign) radius - fontPx * 0.6f else radius + fontPx * 0.6f
                drawLine(color.copy(alpha = 0.55f * pointsT), polar(c, dotR, trueA), polar(c, labelEdge, shownA), strokeWidth = 0.8f * density)
            }
            val pos = polar(c, radius, shownA)
            val layout = measurer.measure(pointGlyph(p), style)
            drawText(layout, topLeft = Offset(pos.x - layout.size.width / 2f, pos.y - layout.size.height / 2f), alpha = pointsT)
            if (withSign) {
                // Their points: sign glyph only, never degrees.
                val sp = polar(c, radius + outer * 0.075f, wheelAngle(spread[i], null))
                val sl = measurer.measure(textGlyph(ZodiacSign.of(lon).glyph), signStyle)
                drawText(sl, topLeft = Offset(sp.x - sl.size.width / 2f, sp.y - sl.size.height / 2f), alpha = pointsT)
            }
        }
    }
    drawPoints(theirs, theirRing, Palette.RoseCopper.copy(red = 0.92f), withSign = true)
    drawPoints(mine, myRing - outer * 0.1f, Palette.GoldPale, withSign = false)
    drawCircle(gold, radius = outer * 0.03f * (0.5f + 0.5f * t), center = c)
}
