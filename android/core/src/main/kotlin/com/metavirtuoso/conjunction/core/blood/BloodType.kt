package com.metavirtuoso.conjunction.core.blood

import com.metavirtuoso.conjunction.core.fallback.Content

/** ABO blood group. [key] matches art/blood/<key>.png. */
enum class BloodType(val displayName: String) {
    A("A"), B("B"), AB("AB"), O("O");

    val key: String get() = name.lowercase()

    companion object {
        /** Parses "A", "ab", "O+" or "B-" style input (the Rh sign is ignored here; see [RhFactor.parse]). */
        fun parse(text: String): BloodType? = when (text.trim().uppercase().trimEnd('+', '-', ' ')) {
            "A" -> A; "B" -> B; "AB" -> AB; "O", "0" -> O
            else -> null
        }
    }
}

/** Optional Rh factor. It does not change the ketsueki-gata reading or the compatibility score. */
enum class RhFactor(val sign: String) {
    POSITIVE("+"), NEGATIVE("−");

    companion object {
        fun parse(text: String): RhFactor? = when {
            text.trim().endsWith("+") -> POSITIVE
            text.trim().endsWith("-") || text.trim().endsWith("−") -> NEGATIVE
            else -> null
        }
    }
}

/** A blood-type personality profile (East-Asian ketsueki-gata folklore; entertainment only). */
data class BloodTypeTraits(
    val type: BloodType,
    val title: String,
    val keywords: List<String>,
    val strengths: String,
    val growth: String,
    val love: String,
    val work: String,
    val summary: String,
)

object BloodTypeReading {
    /** Bundled traits for a blood type (text from fallback/content_en.json, shared with iOS). */
    fun traits(type: BloodType): BloodTypeTraits {
        val m = Content.obj(Content.obj(Content.root["blood"])[type.key])
        return BloodTypeTraits(
            type = type,
            title = m["title"] as String,
            keywords = Content.strings(m["keywords"]),
            strengths = m["strengths"] as String,
            growth = m["growth"] as String,
            love = m["love"] as String,
            work = m["work"] as String,
            summary = m["summary"] as String,
        )
    }

    /** "Type AB+" style label. */
    fun label(type: BloodType, rh: RhFactor?): String = "Type ${type.displayName}${rh?.sign ?: ""}"
}
