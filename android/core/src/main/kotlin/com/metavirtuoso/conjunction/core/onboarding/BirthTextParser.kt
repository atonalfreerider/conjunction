package com.metavirtuoso.conjunction.core.onboarding

import com.metavirtuoso.conjunction.core.profile.InstagramHandle
import java.time.DateTimeException
import java.time.LocalDate
import java.time.LocalTime

/** What [BirthTextParser] found in one free-text birth entry (SPEC 5d.5 screen 2). */
data class BirthTextResult(
    val date: LocalDate?,
    val time: LocalTime?,
    /** "time unknown", "don't know", "unknown time" ... (false whenever a time was found). */
    val timeUnknown: Boolean,
    /** The text left for the platform geocoder, or null. */
    val placeQuery: String?,
    /** A numeric M/D or D/M date whose two parts are both <= 12 (and differ): highlight the chip. */
    val ambiguousDate: Boolean,
)

/**
 * Deterministic parser for "Aug 7 1994, 4:53 pm, Providence RI" style birth entries (SPEC 5d.5,
 * golden `testdata/golden_birth_text.json`). Twin of Swift `BirthTextParser`: the regexes are the
 * same text on both platforms and use only ASCII classes ([0-9], explicit boundaries), so
 * java.util.regex and ICU agree.
 *
 * Steps: (1) Unicode spaces -> ' ', curly apostrophes -> '\''; (2) a "time unknown" phrase; (3) the
 * first date (ISO Y-M-D, month-name forms, D.M.Y, numeric M/D/Y or D/M/Y by [region]); (4) the first
 * time (noon/midnight, 12-hour with or without minutes, 24-hour H:MM); each found span is replaced
 * by ','; (5) the place = what is left, with "born"/"in"/"at"/"on" and commas/whitespace removed at
 * the edges. The date is returned only when it exists in the calendar; use [isAcceptableDate] for
 * the 1900-01-01..today rule.
 */
object BirthTextParser {
    /** Device regions that write numeric dates month first. */
    val MONTH_FIRST_REGIONS = setOf("US", "PH", "FM", "MH", "PW")

    val EARLIEST_DATE: LocalDate = LocalDate.of(1900, 1, 1)

    private const val B = "(?<![A-Za-z0-9])"
    private const val E = "(?![A-Za-z0-9])"
    private const val MONTH =
        "(january|february|march|april|may|june|july|august|september|october|november|december|jan|feb|mar|apr|jun|jul|aug|sept|sep|oct|nov|dec)"

    private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

    private fun re(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    private val TIME_UNKNOWN = re(
        "$B(?:(?:i +)?(?:don't|dont|do +not) +know(?: +(?:the|my))?(?: +(?:birth +)?time)?|(?:birth +)?time +(?:is +)?unknown" +
            "|unknown +(?:birth +)?time|(?:birth +)?time +not +known|no +(?:birth +)?time)$E",
    )
    private val ISO = re("$B([0-9]{4})-([0-9]{1,2})-([0-9]{1,2})$E")
    private val MONTH_DAY_YEAR = re("$B$MONTH\\.? *([0-9]{1,2})(?:st|nd|rd|th)?(?: *, *| +)([0-9]{4})$E")
    private val DAY_MONTH_YEAR = re("$B([0-9]{1,2})(?:st|nd|rd|th)?(?: +of)? +$MONTH\\.?(?: *, *| +)([0-9]{4})$E")
    private val DOTTED = re("$B([0-9]{1,2})\\.([0-9]{1,2})\\.([0-9]{4}|[0-9]{2})$E")
    private val NUMERIC = re("$B([0-9]{1,2})([/-])([0-9]{1,2})\\2([0-9]{4}|[0-9]{2})$E")
    private val NOON = re("$B(?:noon|midday)$E")
    private val MIDNIGHT = re("${B}midnight$E")
    private val TIME_12_MIN = re("$B([0-9]{1,2})[:.]([0-9]{2}) *([ap])\\.? ?m\\.?$E")
    private val TIME_12 = re("$B([0-9]{1,2}) *([ap])\\.? ?m\\.?$E")
    private val TIME_24 = re("$B([0-9]{1,2}):([0-9]{2})$E")
    private val LEADING_WORD = re("^(?:i +was +born|was +born|born|in|at|on)$E")
    private val TRAILING_WORD = re("${B}(?:born|in|at|on)$")
    private val COMMAS = Regex(" *,(?: *,)* *")
    private val SPACES = Regex(" {2,}")
    private const val EDGE = " ,;.:-"

    fun parse(text: String, region: String?, today: LocalDate): BirthTextResult {
        var s = normalizeSpaces(text)
        var timeUnknown = false
        TIME_UNKNOWN.find(s)?.let { timeUnknown = true; s = cut(s, it.range) }

        var date: LocalDate? = null
        var ambiguous = false
        val dm = ISO.find(s)?.also { date = ymd(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toInt()) }
            ?: MONTH_DAY_YEAR.find(s)?.also { date = ymd(it.groupValues[3].toInt(), month(it.groupValues[1]), it.groupValues[2].toInt()) }
            ?: DAY_MONTH_YEAR.find(s)?.also { date = ymd(it.groupValues[3].toInt(), month(it.groupValues[2]), it.groupValues[1].toInt()) }
            ?: DOTTED.find(s)?.also { date = ymd(year(it.groupValues[3], today), it.groupValues[2].toInt(), it.groupValues[1].toInt()) }
            ?: NUMERIC.find(s)?.also {
                val p = it.groupValues[1].toInt()
                val q = it.groupValues[3].toInt()
                val monthFirst = region?.uppercase() in MONTH_FIRST_REGIONS
                date = if (monthFirst) ymd(year(it.groupValues[4], today), p, q) else ymd(year(it.groupValues[4], today), q, p)
                ambiguous = date != null && p <= 12 && q <= 12 && p != q
            }
        if (dm != null) s = cut(s, dm.range)

        var time: LocalTime? = null
        val tm = NOON.find(s)?.also { time = LocalTime.of(12, 0) }
            ?: MIDNIGHT.find(s)?.also { time = LocalTime.of(0, 0) }
            ?: TIME_12_MIN.find(s)?.also { time = hm12(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3]) }
            ?: TIME_12.find(s)?.also { time = hm12(it.groupValues[1].toInt(), 0, it.groupValues[2]) }
            ?: TIME_24.find(s)?.also { m ->
                val h = m.groupValues[1].toInt()
                val mi = m.groupValues[2].toInt()
                time = if (h in 0..23 && mi in 0..59) LocalTime.of(h, mi) else null
            }
        if (tm != null) s = cut(s, tm.range)
        if (time != null) timeUnknown = false

        return BirthTextResult(date, time, timeUnknown, place(s), ambiguous)
    }

    /** SPEC 5d.5 validation: the date exists (always true for a [LocalDate]), >= 1900-01-01 and <= today. */
    fun isAcceptableDate(date: LocalDate, today: LocalDate): Boolean = !date.isBefore(EARLIEST_DATE) && !date.isAfter(today)

    internal fun normalizeSpaces(text: String): String = buildString(text.length) {
        for (c in text) append(
            when {
                InstagramHandle.isWhitespace(c) -> ' '
                c == '’' || c == '‘' || c == 'ʼ' -> '\''
                else -> c
            },
        )
    }

    private fun cut(s: String, r: IntRange): String = s.substring(0, r.first) + "," + s.substring(r.last + 1)

    private fun month(name: String): Int = MONTHS.indexOf(name.lowercase().take(3)) + 1

    private fun year(y: String, today: LocalDate): Int {
        if (y.length == 4) return y.toInt()
        val yy = y.toInt()
        return if (yy > today.year % 100) 1900 + yy else 2000 + yy
    }

    private fun ymd(y: Int, m: Int, d: Int): LocalDate? = try { LocalDate.of(y, m, d) } catch (e: DateTimeException) { null }

    private fun hm12(h: Int, m: Int, ap: String): LocalTime? {
        if (h !in 1..12 || m !in 0..59) return null
        val pm = ap.equals("p", ignoreCase = true)
        return LocalTime.of(h % 12 + if (pm) 12 else 0, m)
    }

    private fun place(rest: String): String? {
        var s = rest
        while (true) {
            val before = s
            s = s.trim { it in EDGE }
            LEADING_WORD.find(s)?.let { s = s.substring(it.range.last + 1) }
            TRAILING_WORD.find(s)?.let { s = s.substring(0, it.range.first) }
            if (s == before) break
        }
        s = COMMAS.replace(s, ", ")
        s = SPACES.replace(s, " ")
        return s.ifEmpty { null }
    }
}
