package com.metavirtuoso.conjunction.ui.theme

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.metavirtuoso.conjunction.R

/**
 * "Celestial Art Nouveau" (SPEC, Look and feel): midnight indigo grounds, burnished gold leaf,
 * rose-copper accents and moonstone-pearl text. Every screen draws on these tokens; nothing uses
 * Material's default purple.
 */
object Palette {
    val Midnight = Color(0xFF0B0A2A)
    val InkViolet = Color(0xFF1C1442)
    val Dusk = Color(0xFF2A1F5C)
    val Twilight = Color(0xFF3B2E78)
    val Gold = Color(0xFFD4AF37)
    val GoldDeep = Color(0xFFA8842A)
    val GoldPale = Color(0xFFF5E6A8)
    val RoseCopper = Color(0xFFC97B63)
    val Moonstone = Color(0xFFEDE6F7)
    val MoonstoneDim = Color(0xFFB9B0CC)
    val Garnet = Color(0xFFB23A48)
    val Verdigris = Color(0xFF6FB3A2)
    val Scrim = Color(0xCC07061C)
}

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun variable(res: Int, weight: Int, style: FontStyle = FontStyle.Normal) = Font(
    resId = res,
    weight = FontWeight(weight),
    style = style,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

/** Cinzel Decorative (OFL): ornamental capitals for titles and big numbers. */
val DisplayFamily = FontFamily(
    Font(R.font.cinzel_decorative_regular, FontWeight.Normal),
    Font(R.font.cinzel_decorative_bold, FontWeight.Bold),
)

/** Cinzel (OFL, variable): engraved small caps for labels, tabs and buttons. */
val LabelFamily = FontFamily(
    variable(R.font.cinzel_variable, 400),
    variable(R.font.cinzel_variable, 600),
    variable(R.font.cinzel_variable, 700),
)

/** Cormorant Garamond (OFL, variable + italic): body text and readings. */
val BodyFamily = FontFamily(
    variable(R.font.cormorant_garamond_variable, 400),
    variable(R.font.cormorant_garamond_variable, 500),
    variable(R.font.cormorant_garamond_variable, 600),
    variable(R.font.cormorant_garamond_variable, 700),
    variable(R.font.cormorant_garamond_italic_variable, 400, FontStyle.Italic),
    variable(R.font.cormorant_garamond_italic_variable, 600, FontStyle.Italic),
)

private val ConjunctionTypography = Typography(
    displayLarge = TextStyle(fontFamily = DisplayFamily, fontWeight = FontWeight.Bold, fontSize = 44.sp, lineHeight = 52.sp, letterSpacing = 0.02.em),
    displayMedium = TextStyle(fontFamily = DisplayFamily, fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 42.sp),
    displaySmall = TextStyle(fontFamily = DisplayFamily, fontWeight = FontWeight.Normal, fontSize = 28.sp, lineHeight = 36.sp),
    headlineLarge = TextStyle(fontFamily = DisplayFamily, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 36.sp),
    headlineMedium = TextStyle(fontFamily = DisplayFamily, fontWeight = FontWeight.Normal, fontSize = 24.sp, lineHeight = 32.sp),
    headlineSmall = TextStyle(fontFamily = LabelFamily, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 28.sp, letterSpacing = 0.06.em),
    titleLarge = TextStyle(fontFamily = LabelFamily, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp, letterSpacing = 0.08.em),
    titleMedium = TextStyle(fontFamily = LabelFamily, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = 0.08.em),
    titleSmall = TextStyle(fontFamily = LabelFamily, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.em),
    bodyLarge = TextStyle(fontFamily = BodyFamily, fontWeight = FontWeight.Medium, fontSize = 20.sp, lineHeight = 28.sp),
    bodyMedium = TextStyle(fontFamily = BodyFamily, fontWeight = FontWeight.Medium, fontSize = 18.sp, lineHeight = 25.sp),
    bodySmall = TextStyle(fontFamily = BodyFamily, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp),
    labelLarge = TextStyle(fontFamily = LabelFamily, fontWeight = FontWeight.Bold, fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = 0.12.em),
    labelMedium = TextStyle(fontFamily = LabelFamily, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.1.em),
    labelSmall = TextStyle(fontFamily = LabelFamily, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.12.em),
)

private val ConjunctionColors: ColorScheme = darkColorScheme(
    primary = Palette.Gold,
    onPrimary = Palette.Midnight,
    primaryContainer = Palette.Dusk,
    onPrimaryContainer = Palette.GoldPale,
    secondary = Palette.RoseCopper,
    onSecondary = Palette.Midnight,
    secondaryContainer = Color(0xFF4A2A33),
    onSecondaryContainer = Color(0xFFFFDCD2),
    tertiary = Palette.Moonstone,
    onTertiary = Palette.Midnight,
    background = Palette.Midnight,
    onBackground = Palette.Moonstone,
    surface = Palette.InkViolet,
    onSurface = Palette.Moonstone,
    surfaceVariant = Palette.Dusk,
    onSurfaceVariant = Palette.MoonstoneDim,
    surfaceContainerLowest = Palette.Midnight,
    surfaceContainerLow = Color(0xFF151036),
    surfaceContainer = Palette.InkViolet,
    surfaceContainerHigh = Color(0xFF241A50),
    surfaceContainerHighest = Palette.Dusk,
    outline = Palette.GoldDeep,
    outlineVariant = Color(0xFF4D3F86),
    error = Color(0xFFFF8A8A),
    onError = Palette.Midnight,
    scrim = Palette.Scrim,
)

private val ConjunctionShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = CutCornerShape(6.dp),
    medium = CutCornerShape(10.dp),
    large = CutCornerShape(14.dp),
    extraLarge = CutCornerShape(20.dp),
)

/** Gold-leaf brushes. [shimmer] is a 0..1 phase from [rememberGoldShimmer]. */
object GoldBrushes {
    val leaf = Brush.linearGradient(listOf(Palette.GoldDeep, Palette.Gold, Palette.GoldPale, Palette.Gold, Palette.GoldDeep))
    val vertical = Brush.verticalGradient(listOf(Palette.GoldPale, Palette.Gold, Palette.GoldDeep))
    val copper = Brush.linearGradient(listOf(Palette.RoseCopper, Color(0xFFE8B49A), Palette.RoseCopper))

    /** A diagonal band of light sweeping across gold, repeating every [period] px. */
    fun shimmer(phase: Float, period: Float = 600f): Brush {
        val start = -period + phase * period * 2
        return Brush.linearGradient(
            colors = listOf(Palette.GoldDeep, Palette.Gold, Palette.GoldPale, Color.White.copy(alpha = 0.95f), Palette.GoldPale, Palette.Gold, Palette.GoldDeep),
            start = Offset(start, 0f),
            end = Offset(start + period, period * 0.35f),
            tileMode = TileMode.Mirror,
        )
    }
}

/** Whether motion should be reduced (system "remove animations" / animator scale 0). */
val LocalReducedMotion = staticCompositionLocalOf { false }

/** Slow, continuous 0..1 phase for gold shimmer (6 s per sweep); frozen when motion is reduced. */
@Composable
fun rememberGoldShimmer(durationMillis: Int = 6000): Float {
    if (LocalReducedMotion.current) return 0.35f
    val transition = rememberInfiniteTransition(label = "gold-shimmer")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis, easing = LinearEasing), RepeatMode.Restart),
        label = "gold-shimmer-phase",
    )
    return phase
}

@Composable
fun ConjunctionTheme(reducedMotion: Boolean = false, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalReducedMotion provides reducedMotion) {
        MaterialTheme(
            colorScheme = ConjunctionColors,
            typography = ConjunctionTypography,
            shapes = ConjunctionShapes,
            content = content,
        )
    }
}
