package com.metavirtuoso.conjunction.core.chart

import com.metavirtuoso.conjunction.core.astro.Angles
import kotlin.math.floor

enum class Element { FIRE, EARTH, AIR, WATER;
    val displayName: String get() = name.lowercase().replaceFirstChar(Char::uppercase)
}

enum class Modality { CARDINAL, FIXED, MUTABLE;
    val displayName: String get() = name.lowercase().replaceFirstChar(Char::uppercase)
}

/** The twelve tropical signs. [key] matches the art asset names (art/zodiac/<key>.png). */
enum class ZodiacSign(val displayName: String, val glyph: String, val element: Element, val modality: Modality) {
    ARIES("Aries", "♈", Element.FIRE, Modality.CARDINAL),
    TAURUS("Taurus", "♉", Element.EARTH, Modality.FIXED),
    GEMINI("Gemini", "♊", Element.AIR, Modality.MUTABLE),
    CANCER("Cancer", "♋", Element.WATER, Modality.CARDINAL),
    LEO("Leo", "♌", Element.FIRE, Modality.FIXED),
    VIRGO("Virgo", "♍", Element.EARTH, Modality.MUTABLE),
    LIBRA("Libra", "♎", Element.AIR, Modality.CARDINAL),
    SCORPIO("Scorpio", "♏", Element.WATER, Modality.FIXED),
    SAGITTARIUS("Sagittarius", "♐", Element.FIRE, Modality.MUTABLE),
    CAPRICORN("Capricorn", "♑", Element.EARTH, Modality.CARDINAL),
    AQUARIUS("Aquarius", "♒", Element.AIR, Modality.FIXED),
    PISCES("Pisces", "♓", Element.WATER, Modality.MUTABLE);

    val key: String get() = name.lowercase()

    companion object {
        /** Sign containing an ecliptic longitude (degrees). */
        fun of(longitude: Double): ZodiacSign = entries[floor(Angles.norm360(longitude) / 30.0).toInt().coerceIn(0, 11)]

        /** Degree within the sign, [0, 30). */
        fun degreeIn(longitude: Double): Double = Angles.norm360(longitude) % 30.0

        fun fromKey(key: String): ZodiacSign = entries.first { it.key == key.lowercase() }
    }
}

/** "14°07′ Leo" style label (truncated, not rounded, so it never shows 30°). */
fun formatLongitude(longitude: Double): String {
    val sign = ZodiacSign.of(longitude)
    val inSign = ZodiacSign.degreeIn(longitude)
    val totalMinutes = floor(inSign * 60.0).toInt()
    return "${totalMinutes / 60}°${(totalMinutes % 60).toString().padStart(2, '0')}′ ${sign.displayName}"
}
