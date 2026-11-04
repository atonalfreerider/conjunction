package com.metavirtuoso.conjunction.core.prompt

/**
 * Tidies a model answer before display (both platforms): strips a leading label such as
 * "Horoscope:", markdown heading lines and emphasis, wrapping quotes, and collapses blank-line runs.
 * Returns null when nothing usable is left, so the caller can use the fallback reading.
 */
object ReadingCleaner {
    private val LABEL = Regex("^(?:horoscope|reading|palm reading|daily horoscope|answer|response)\\s*[:\\-–]\\s*", RegexOption.IGNORE_CASE)
    /** A markdown heading line is a title the model added; drop the whole line. */
    private val HEADING = Regex("^#{1,6}[^\\n]*(\\n|$)", RegexOption.MULTILINE)
    private val EMPHASIS = Regex("(\\*\\*|__|\\*|_)(?=\\S)(.+?)(?<=\\S)\\1")
    private val BLANKS = Regex("\\n{3,}")

    fun clean(raw: String?): String? {
        var text = raw?.trim() ?: return null
        text = HEADING.replace(text, "")
        text = EMPHASIS.replace(text) { it.groupValues[2] }
        text = LABEL.replace(text, "")
        text = text.trim().removeSurrounding("\"").removeSurrounding("“", "”").trim()
        text = BLANKS.replace(text, "\n\n")
        return text.takeIf { it.isNotBlank() }
    }
}
