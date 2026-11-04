package com.metavirtuoso.conjunction.core.match

import java.io.File
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Checks MatchFilter, Age and MatchBlurbPrompt against testdata/golden_match_filter.json. */
class MatchFilterTest {

    private val golden: Map<String, Any?> by lazy {
        @Suppress("UNCHECKED_CAST")
        MiniJson.parse(goldenFile().readText()) as Map<String, Any?>
    }

    @Test
    fun everyProfileCombinationMatchesGolden() {
        val profiles = list("profiles").associate { m -> str(m, "id") to profile(m) }
        assertEquals(36, profiles.size)
        val pairs = golden["pairs"] as List<*>
        assertEquals(1296, pairs.size)
        for (row in pairs) {
            row as List<*>
            val a = profiles.getValue(row[0] as String)
            val b = profiles.getValue(row[1] as String)
            val expected = (row[2] as String?)?.let(MatchKind::fromWire)
            assertEquals("${row[0]} x ${row[1]}", expected, MatchFilter.kind(a, b))
            assertEquals("symmetry ${row[0]} x ${row[1]}", MatchFilter.kind(b, a), MatchFilter.kind(a, b))
        }
    }

    @Test
    fun namedCases() {
        for (c in list("named")) {
            val expected = (c["expected"] as String?)?.let(MatchKind::fromWire)
            assertEquals(str(c, "name"), expected, MatchFilter.kind(profile(obj(c, "a")), profile(obj(c, "b"))))
        }
    }

    @Test
    fun defaultLookingFor() {
        @Suppress("UNCHECKED_CAST")
        val defaults = golden["defaultLookingFor"] as Map<String, String>
        for ((status, lookingFor) in defaults) {
            assertEquals(LookingFor.fromWire(lookingFor), MatchFilter.defaultLookingFor(RelationshipStatus.fromWire(status)))
        }
    }

    @Test
    fun ageCases() {
        for (c in list("age")) {
            val birth = LocalDate.parse(str(c, "birthDate"))
            val today = LocalDate.parse(str(c, "today"))
            val years = c["years"] as Double?
            if (years == null) {
                try {
                    Age.yearsOn(birth, today)
                    fail("yearsOn should throw for $birth after $today")
                } catch (expected: IllegalArgumentException) {
                }
            } else {
                assertEquals("$birth on $today", years.toInt(), Age.yearsOn(birth, today))
            }
            assertEquals("$birth adult on $today", c["adult"] as Boolean, Age.isAdult(birth, today))
        }
        for (c in list("latestAdultBirthDate")) {
            val today = LocalDate.parse(str(c, "today"))
            val latest = LocalDate.parse(str(c, "latestAdultBirthDate"))
            assertEquals(latest, Age.latestAdultBirthDate(today))
            assertTrue(Age.isAdult(latest, today))
            assertFalse(Age.isAdult(latest.plusDays(1), today))
        }
    }

    @Test
    fun eligibilityRequiresBothAdults() {
        for (c in list("eligibility")) {
            val expected = (c["expected"] as String?)?.let(MatchKind::fromWire)
            val actual = MatchFilter.kind(
                profile(obj(c, "a")), LocalDate.parse(str(c, "aBirthDate")),
                profile(obj(c, "b")), LocalDate.parse(str(c, "bBirthDate")),
                LocalDate.parse(str(c, "today")),
            )
            assertEquals(c.toString(), expected, actual)
        }
    }

    @Test
    fun blurbPromptAndFallbackWording() {
        for (c in list("blurbs")) {
            val i = obj(c, "input")
            val input = MatchBlurbPrompt.Input(
                kind = MatchKind.fromWire(str(i, "kind")),
                mySun = str(i, "mySun"),
                theirSun = str(i, "theirSun"),
                theirMoon = i["theirMoon"] as String?,
                theirRising = i["theirRising"] as String?,
                myAnimal = str(i, "myAnimal"),
                theirAnimal = str(i, "theirAnimal"),
                total = (i["total"] as Double).toInt(),
                western = (i["western"] as Double).toInt(),
                chinese = (i["chinese"] as Double).toInt(),
                blood = (i["blood"] as Double?)?.toInt(),
                birthdayTwin = i["birthdayTwin"] as Boolean,
                exactTwin = i["exactTwin"] as Boolean,
            )
            val prompt = MatchBlurbPrompt.build(input)
            assertEquals(str(c, "prompt"), prompt)
            assertTrue(prompt.contains("entertainment"))
            assertFalse(prompt.contains("\n\n"))
            assertEquals(str(c, "fallback"), MatchBlurbPrompt.fallback(input))
        }
    }

    @Test
    fun blurbCleaning() {
        for (c in list("clean")) {
            assertEquals(c.toString(), c["expected"] as String?, MatchBlurbPrompt.clean(c["raw"] as String?))
        }
    }

    @Test
    fun wireValuesRoundTripAndRejectUnknown() {
        Gender.entries.forEach { assertEquals(it, Gender.fromWire(it.wire)) }
        Seeking.entries.forEach { assertEquals(it, Seeking.fromWire(it.wire)) }
        RelationshipStatus.entries.forEach { assertEquals(it, RelationshipStatus.fromWire(it.wire)) }
        LookingFor.entries.forEach { assertEquals(it, LookingFor.fromWire(it.wire)) }
        MatchKind.entries.forEach { assertEquals(it, MatchKind.fromWire(it.wire)) }
        try {
            Gender.fromWire("Male")
            fail("wire values are case-sensitive")
        } catch (expected: IllegalArgumentException) {
        }
        assertNull(MatchBlurbPrompt.clean(""))
    }

    // ---- helpers ----

    private fun profile(m: Map<String, Any?>) = MatchProfile(
        gender = Gender.fromWire(str(m, "gender")),
        seeking = Seeking.fromWire(str(m, "seeking")),
        relationshipStatus = RelationshipStatus.fromWire(str(m, "relationshipStatus")),
        lookingFor = LookingFor.fromWire(str(m, "lookingFor")),
    )

    @Suppress("UNCHECKED_CAST")
    private fun list(key: String) = golden[key] as List<Map<String, Any?>>

    @Suppress("UNCHECKED_CAST")
    private fun obj(m: Map<String, Any?>, key: String) = m[key] as Map<String, Any?>

    private fun str(m: Map<String, Any?>, key: String) = m[key] as String

    /**
     * The golden file: from the `conjunction.testdata` system property set by :core's test task, or
     * else by walking up from the working directory (android/core) to the repo's testdata/.
     */
    private fun goldenFile(): File {
        System.getProperty("conjunction.testdata")?.let { File(it, "golden_match_filter.json") }
            ?.takeIf { it.isFile }?.let { return it }
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "testdata/golden_match_filter.json")
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        error("testdata/golden_match_filter.json not found above ${File("").absolutePath}")
    }
}

/**
 * Minimal JSON reader for the golden file (objects → Map, arrays → List, numbers → Double), so this
 * test needs no JSON library on the test classpath.
 */
private object MiniJson {
    fun parse(text: String): Any? {
        val p = Parser(text)
        val value = p.value()
        p.skipWs()
        require(p.i == text.length) { "Trailing data at ${p.i}" }
        return value
    }

    private class Parser(val s: String) {
        var i = 0

        fun skipWs() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun value(): Any? {
            skipWs()
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c.isDigit()) num() else error("Unexpected '$c' at $i")
            }
        }

        private fun literal(word: String, v: Any?): Any? {
            require(s.startsWith(word, i)) { "Bad literal at $i" }
            i += word.length
            return v
        }

        private fun num(): Double {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            return s.substring(start, i).toDouble()
        }

        private fun str(): String {
            i++ // opening quote
            val sb = StringBuilder()
            while (true) {
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        when (val e = s[i++]) {
                            'n' -> sb.append('\n')
                            't' -> sb.append('\t')
                            'r' -> sb.append('\r')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'u' -> {
                                sb.append(s.substring(i, i + 4).toInt(16).toChar())
                                i += 4
                            }
                            else -> sb.append(e) // \" \\ \/
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun arr(): List<Any?> {
            i++
            val out = ArrayList<Any?>()
            skipWs()
            if (s[i] == ']') { i++; return out }
            while (true) {
                out += value()
                skipWs()
                when (s[i++]) {
                    ',' -> continue
                    ']' -> return out
                    else -> error("Expected , or ] at ${i - 1}")
                }
            }
        }

        private fun obj(): Map<String, Any?> {
            i++
            val out = LinkedHashMap<String, Any?>()
            skipWs()
            if (s[i] == '}') { i++; return out }
            while (true) {
                skipWs()
                val key = str()
                skipWs()
                require(s[i++] == ':') { "Expected : at ${i - 1}" }
                out[key] = value()
                skipWs()
                when (s[i++]) {
                    ',' -> continue
                    '}' -> return out
                    else -> error("Expected , or } at ${i - 1}")
                }
            }
        }
    }
}
