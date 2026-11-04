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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
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
import com.metavirtuoso.conjunction.core.astro.Planet
import com.metavirtuoso.conjunction.core.chart.AspectType
import com.metavirtuoso.conjunction.core.chart.NatalChart
import com.metavirtuoso.conjunction.core.chart.ZodiacSign
import com.metavirtuoso.conjunction.core.chart.formatLongitude
import com.metavirtuoso.conjunction.ui.theme.LocalReducedMotion
import com.metavirtuoso.conjunction.ui.theme.Palette
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** U+FE0E asks for the text (not emoji) presentation of astrological symbols. */
internal fun textGlyph(glyph: String): String = glyph + "︎"

/**
 * Screen angle (degrees, 0 = east, counter-clockwise positive as in a chart) for an ecliptic
 * longitude, with the Ascendant (or 0 deg Aries when the birth time is unknown) on the left.
 */
internal fun wheelAngle(longitude: Double, ascendant: Double?): Double = 180.0 + (longitude - (ascendant ?: 0.0))

/**
 * Spreads planet positions apart so glyphs never overlap: any two closer than [minGap] degrees
 * are pushed apart symmetrically (a few relaxation passes), keeping the order around the wheel.
 */
internal fun spreadLongitudes(longitudes: List<Double>, minGap: Double = 7.0): List<Double> {
    if (longitudes.size < 2) return longitudes
    val order = longitudes.indices.sortedBy { ((longitudes[it] % 360) + 360) % 360 }
    val pos = order.map { ((longitudes[it] % 360) + 360) % 360 }.toMutableList()
    repeat(40) {
        var moved = false
        for (i in pos.indices) {
            val j = (i + 1) % pos.size
            var gap = pos[j] - pos[i]
            if (j == 0) gap += 360.0
            if (gap < minGap) {
                val push = (minGap - gap) / 2
                pos[i] -= push
                pos[j] += push
                moved = true
            }
        }
        if (!moved) return@repeat
    }
    val out = MutableList(longitudes.size) { 0.0 }
    order.forEachIndexed { k, idx -> out[idx] = pos[k] }
    return out
}

private fun aspectColor(type: AspectType): Color = when (type) {
    AspectType.TRINE, AspectType.SEXTILE -> Palette.Gold
    AspectType.SQUARE, AspectType.OPPOSITION -> Palette.RoseCopper
    AspectType.CONJUNCTION -> Palette.Moonstone
}

/**
 * The natal chart wheel, drawn with Canvas and animated in: the zodiac ring sweeps around, the
 * house spokes and planets appear, then the aspect web is drawn across the centre. Reduced
 * motion shows the finished wheel at once.
 */
@Composable
fun ChartWheel(chart: NatalChart, modifier: Modifier = Modifier) {
    val reduced = LocalReducedMotion.current
    val progress = remember(chart) { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(chart, reduced) {
        if (!reduced) progress.animateTo(1f, tween(2600, easing = FastOutSlowInEasing))
    }
    val measurer = rememberTextMeasurer()
    val description = remember(chart) {
        buildString {
            append("Birth chart wheel. ")
            chart.bodies.forEach { append("${it.name} ${formatLongitude(it.longitude)}. ") }
            chart.ascendant?.let { append("Ascendant ${formatLongitude(it)}.") }
        }
    }
    Canvas(modifier.aspectRatio(1f).semantics { contentDescription = description }) {
        drawWheel(chart, progress.value, measurer)
    }
}

private fun DrawScope.polar(center: Offset, radius: Float, angleDeg: Double): Offset {
    val a = Math.toRadians(angleDeg)
    // Screen y grows downward, so counter-clockwise chart angles subtract.
    return Offset(center.x + radius * cos(a).toFloat(), center.y - radius * sin(a).toFloat())
}

private fun DrawScope.drawWheel(chart: NatalChart, t: Float, measurer: TextMeasurer) {
    val c = center
    val outer = size.minDimension / 2f * 0.98f
    val signInner = outer * 0.82f
    val houseInner = outer * 0.70f
    val aspectRadius = outer * 0.50f
    val asc = chart.ascendant
    val ring = (t / 0.45f).coerceIn(0f, 1f)
    val bodiesT = ((t - 0.35f) / 0.35f).coerceIn(0f, 1f)
    val aspectsT = ((t - 0.65f) / 0.35f).coerceIn(0f, 1f)
    val gold = Brush.sweepGradient(listOf(Palette.GoldDeep, Palette.GoldPale, Palette.Gold, Palette.GoldDeep, Palette.GoldPale, Palette.GoldDeep), c)

    // Glow and ground.
    drawCircle(Brush.radialGradient(listOf(Palette.Dusk.copy(alpha = 0.85f), Palette.Midnight.copy(alpha = 0.9f)), c, outer), outer, c)
    // Ring outlines sweep in from the Ascendant.
    val sweep = 360f * ring
    listOf(outer, signInner, houseInner, aspectRadius).forEachIndexed { i, r ->
        drawArc(
            brush = gold,
            startAngle = 180f,
            sweepAngle = sweep,
            useCenter = false,
            topLeft = Offset(c.x - r, c.y - r),
            size = Size(r * 2, r * 2),
            style = Stroke(width = (if (i == 0) 2.2f else 1.1f) * density, cap = StrokeCap.Round),
        )
    }
    // Sign segments: alternating tint, boundary spokes and glyphs.
    val glyphStyle = TextStyle(color = Palette.GoldPale, fontSize = (outer / 14f / density).sp)
    ZodiacSign.entries.forEach { sign ->
        val start = wheelAngle(sign.ordinal * 30.0, asc)
        val reveal = ((ring * 12f) - sign.ordinal).coerceIn(0f, 1f)
        if (reveal <= 0f) return@forEach
        val p1 = polar(c, signInner, start)
        val p2 = polar(c, outer, start)
        drawLine(Palette.Gold.copy(alpha = 0.7f * reveal), p1, p2, strokeWidth = 1f * density)
        // Degree ticks every 5 degrees.
        for (d in 5 until 30 step 5) {
            val a = wheelAngle(sign.ordinal * 30.0 + d, asc)
            val len = if (d == 15) 0.05f else 0.025f
            drawLine(Palette.Gold.copy(alpha = 0.45f * reveal), polar(c, signInner, a), polar(c, signInner + outer * len, a), strokeWidth = 0.8f * density)
        }
        val mid = polar(c, (outer + signInner) / 2f, start + 15.0)
        val layout = measurer.measure(textGlyph(sign.glyph), glyphStyle)
        drawText(layout, topLeft = Offset(mid.x - layout.size.width / 2f, mid.y - layout.size.height / 2f), alpha = reveal)
    }
    // House cusps (whole-sign or quadrant) and angles.
    chart.houses?.forEachIndexed { index, cusp ->
        val a = wheelAngle(cusp, asc)
        val isAngle = index == 0 || index == 3 || index == 6 || index == 9
        drawLine(
            if (isAngle) Palette.GoldPale.copy(alpha = bodiesT) else Palette.Gold.copy(alpha = 0.35f * bodiesT),
            polar(c, if (isAngle) aspectRadius * 0.15f else aspectRadius, a),
            polar(c, signInner, a),
            strokeWidth = (if (isAngle) 1.6f else 0.8f) * density,
            pathEffect = if (isAngle) null else PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
        )
        val label = measurer.measure("${index + 1}", TextStyle(color = Palette.MoonstoneDim, fontSize = (outer / 30f / density).sp))
        val lp = polar(c, houseInner - outer * 0.045f, a + 15.0)
        drawText(label, topLeft = Offset(lp.x - label.size.width / 2f, lp.y - label.size.height / 2f), alpha = bodiesT * 0.8f)
    }
    if (asc != null) {
        val ascLabel = measurer.measure("ASC", TextStyle(color = Palette.GoldPale, fontSize = (outer / 26f / density).sp))
        val p = polar(c, outer * 1.0f - ascLabel.size.width / 2f, 180.0)
        drawText(ascLabel, topLeft = Offset(p.x + 4f, p.y - ascLabel.size.height - 2f), alpha = bodiesT)
    }
    // Aspect web.
    if (aspectsT > 0f) {
        chart.aspects.filter { it.type != AspectType.CONJUNCTION }.forEach { aspect ->
            val a = chart.body(aspect.a)
            val b = chart.body(aspect.b)
            val pa = polar(c, aspectRadius, wheelAngle(a.longitude, asc))
            val pb = polar(c, aspectRadius, wheelAngle(b.longitude, asc))
            val end = Offset(pa.x + (pb.x - pa.x) * aspectsT, pa.y + (pb.y - pa.y) * aspectsT)
            val tight = (1f - (aspect.orb / 8.0).toFloat()).coerceIn(0.25f, 1f)
            drawLine(aspectColor(aspect.type).copy(alpha = 0.75f * tight), pa, end, strokeWidth = (0.6f + tight) * density)
        }
    }
    // Planets: glyph on the house ring, a tick at the true longitude.
    val spread = spreadLongitudes(chart.bodies.map { it.longitude })
    val planetStyle = TextStyle(color = Palette.Moonstone, fontSize = (outer / 15f / density).sp)
    chart.bodies.forEachIndexed { i, body ->
        val trueA = wheelAngle(body.longitude, asc)
        val shownA = wheelAngle(spread[i], asc)
        drawLine(Palette.GoldPale.copy(alpha = bodiesT), polar(c, signInner, trueA), polar(c, signInner - outer * 0.03f, trueA), strokeWidth = 1.6f * density)
        drawCircle(Palette.GoldPale.copy(alpha = bodiesT), radius = 2f * density, center = polar(c, aspectRadius, trueA))
        val r = (houseInner + aspectRadius) / 2f + (1f - bodiesT) * outer * 0.2f
        val p = polar(c, r, shownA)
        val color = when (body.planet) {
            Planet.SUN -> Palette.Gold
            Planet.MOON -> Palette.Moonstone
            Planet.VENUS, Planet.MARS -> Palette.RoseCopper
            else -> Palette.Moonstone
        }
        val glyph = textGlyph(body.planet.glyph) + if (body.retrograde) "℞" else ""
        val layout = measurer.measure(glyph, planetStyle.copy(color = color))
        drawText(layout, topLeft = Offset(p.x - layout.size.width / 2f, p.y - layout.size.height / 2f), alpha = bodiesT)
    }
    // Centre jewel.
    drawCircle(gold, radius = outer * 0.035f * (0.5f + 0.5f * t), center = c)
    if (abs(t - 1f) < 0.001f) drawCircle(Palette.Midnight, radius = outer * 0.014f, center = c)
}
