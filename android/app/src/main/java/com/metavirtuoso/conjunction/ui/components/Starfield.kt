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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.metavirtuoso.conjunction.R
import com.metavirtuoso.conjunction.ui.theme.LocalReducedMotion
import com.metavirtuoso.conjunction.ui.theme.Palette
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/** One procedurally placed star (positions are 0..1 fractions of the screen). */
private data class Star(val x: Float, val y: Float, val radius: Float, val depth: Float, val twinkle: Float, val warm: Boolean)

private fun starLayer(seed: Int, count: Int): List<Star> {
    val random = Random(seed)
    return List(count) {
        Star(
            x = random.nextFloat(),
            y = random.nextFloat(),
            radius = 0.6f + random.nextFloat() * 1.8f,
            depth = 0.2f + random.nextFloat() * 0.8f,
            twinkle = random.nextFloat() * 2f * PI.toFloat(),
            warm = random.nextFloat() < 0.35f,
        )
    }
}

/**
 * The app's living backdrop: the painted starfield ([background] art) drifting very slowly, with
 * two procedural star layers on top that move at different speeds (parallax) and twinkle, and a
 * soft vignette so foreground text stays readable. With reduced motion everything holds still.
 */
@Composable
fun StarfieldBackground(
    modifier: Modifier = Modifier,
    @DrawableRes background: Int = R.drawable.bg_starfield,
    dim: Float = 0.35f,
    content: @Composable BoxScope.() -> Unit,
) {
    val reduced = LocalReducedMotion.current
    val stars = remember { starLayer(seed = 7, count = 90) }
    val transition = rememberInfiniteTransition(label = "starfield")
    val drift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(90_000, easing = LinearEasing), RepeatMode.Reverse),
        label = "drift",
    )
    val twinkle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 2f * PI.toFloat(),
        animationSpec = infiniteRepeatable(tween(7_000, easing = LinearEasing), RepeatMode.Restart),
        label = "twinkle",
    )
    val d = if (reduced) 0.5f else drift
    val t = if (reduced) 0f else twinkle

    Box(modifier.fillMaxSize()) {
        Image(
            painter = painterResource(background),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    // Slight over-scale so the slow pan never reveals an edge.
                    scaleX = 1.08f
                    scaleY = 1.08f
                    translationX = (d - 0.5f) * size.width * 0.05f
                    translationY = (d - 0.5f) * size.height * 0.03f
                },
        )
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            for (star in stars) {
                val px = ((star.x + d * 0.04f * star.depth) % 1f) * w
                val py = ((star.y + d * 0.025f * star.depth) % 1f) * h
                val glow = 0.45f + 0.55f * ((sin(t + star.twinkle) + 1f) / 2f)
                val color = if (star.warm) Palette.GoldPale else Palette.Moonstone
                drawCircle(color.copy(alpha = 0.18f * glow), radius = star.radius * 3.2f, center = Offset(px, py))
                drawCircle(color.copy(alpha = 0.85f * glow), radius = star.radius, center = Offset(px, py))
            }
            drawRect(
                Brush.verticalGradient(
                    0f to Palette.Midnight.copy(alpha = dim * 0.6f),
                    0.45f to Color.Transparent,
                    1f to Palette.Midnight.copy(alpha = dim + 0.25f),
                ),
            )
        }
        content()
    }
}
