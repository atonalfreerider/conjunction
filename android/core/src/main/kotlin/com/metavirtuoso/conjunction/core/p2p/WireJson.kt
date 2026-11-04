package com.metavirtuoso.conjunction.core.p2p

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Canonical JSON for the wire (docs/P2P_PROTOCOL.md section 5.1).
 *
 * Writer: members in map insertion order, no whitespace, integers only, strings escaping only
 * `"`, `\` and U+0000..U+001F (\b \f \n \r \t, others \u00xx lowercase) — byte-identical to
 * Python `json.dumps(o, ensure_ascii=False, separators=(",", ":"))`.
 *
 * Reader: strict UTF-8, rejects duplicate member names, numbers with fraction/exponent, integers
 * outside Long, unpaired surrogates and trailing data. Values: LinkedHashMap, List, String,
 * Long, Boolean, null.
 */
object WireJson {

    fun write(value: Any?): String = StringBuilder().also { writeTo(it, value) }.toString()

    fun writeBytes(value: Any?): ByteArray = write(value).toByteArray(StandardCharsets.UTF_8)

    private fun writeTo(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is String -> quote(sb, v)
            is Boolean -> sb.append(if (v) "true" else "false")
            is Int, is Long, is Short, is Byte -> sb.append((v as Number).toLong().toString())
            is Double, is Float -> throw IllegalArgumentException("floating-point numbers are not allowed on the wire")
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, x) in v) {
                    require(k is String) { "non-string key" }
                    if (!first) sb.append(',')
                    first = false
                    quote(sb, k)
                    sb.append(':')
                    writeTo(sb, x)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (x in v) {
                    if (!first) sb.append(',')
                    first = false
                    writeTo(sb, x)
                }
                sb.append(']')
            }
            else -> throw IllegalArgumentException("unsupported JSON value ${v::class.java.name}")
        }
    }

    private fun quote(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\b' -> sb.append("\\b")
                '\u000c' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') {
                    sb.append("\\u00").append(HEX[c.code shr 4]).append(HEX[c.code and 15])
                } else sb.append(c)
            }
        }
        sb.append('"')
    }

    private val HEX = "0123456789abcdef".toCharArray()

    /** Parses UTF-8 bytes; throws [P2pException] (malformed) on anything not allowed. */
    fun parse(bytes: ByteArray): Any? {
        val text = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) {
            malformed("invalid UTF-8")
        }
        return parse(text)
    }

    fun parse(text: String): Any? {
        val p = Parser(text)
        val v = p.value(0)
        p.ws()
        if (p.pos != text.length) malformed("trailing data at ${p.pos}")
        return v
    }

    private class Parser(val s: String) {
        var pos = 0

        fun ws() {
            while (pos < s.length && (s[pos] == ' ' || s[pos] == '\t' || s[pos] == '\n' || s[pos] == '\r')) pos++
        }

        fun peek(): Char = if (pos < s.length) s[pos] else malformed("unexpected end of JSON")

        fun value(depth: Int): Any? {
            if (depth > 32) malformed("JSON nested too deeply")
            ws()
            return when (val c = peek()) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c in '0'..'9') num() else malformed("unexpected '$c' at $pos")
            }
        }

        fun lit(w: String, v: Any?): Any? {
            if (!s.startsWith(w, pos)) malformed("bad literal at $pos")
            pos += w.length
            return v
        }

        fun obj(depth: Int): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            pos++
            ws()
            if (peek() == '}') { pos++; return m }
            while (true) {
                ws()
                if (peek() != '"') malformed("expected member name at $pos")
                val k = str()
                ws()
                if (peek() != ':') malformed("expected ':' at $pos")
                pos++
                if (m.containsKey(k)) malformed("duplicate member '$k'")
                m[k] = value(depth + 1)
                ws()
                when (peek()) {
                    ',' -> pos++
                    '}' -> { pos++; return m }
                    else -> malformed("expected ',' or '}' at $pos")
                }
            }
        }

        fun arr(depth: Int): List<Any?> {
            val l = ArrayList<Any?>()
            pos++
            ws()
            if (peek() == ']') { pos++; return l }
            while (true) {
                l += value(depth + 1)
                ws()
                when (peek()) {
                    ',' -> pos++
                    ']' -> { pos++; return l }
                    else -> malformed("expected ',' or ']' at $pos")
                }
            }
        }

        fun str(): String {
            pos++ // opening quote
            val sb = StringBuilder()
            while (true) {
                val c = peek()
                pos++
                when {
                    c == '"' -> break
                    c == '\\' -> {
                        val e = peek()
                        pos++
                        when (e) {
                            '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000c'); 'n' -> sb.append('\n')
                            'r' -> sb.append('\r'); 't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > s.length) malformed("short \\u escape")
                                val h = s.substring(pos, pos + 4)
                                if (!h.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) malformed("bad \\u escape")
                                sb.append(h.toInt(16).toChar())
                                pos += 4
                            }
                            else -> malformed("bad escape \\$e")
                        }
                    }
                    c < ' ' -> malformed("raw control character in string")
                    else -> sb.append(c)
                }
            }
            val out = sb.toString()
            // Reject unpaired surrogates (possible via \u escapes).
            var i = 0
            while (i < out.length) {
                val ch = out[i]
                if (Character.isHighSurrogate(ch)) {
                    if (i + 1 >= out.length || !Character.isLowSurrogate(out[i + 1])) malformed("unpaired surrogate")
                    i += 2
                    continue
                }
                if (Character.isLowSurrogate(ch)) malformed("unpaired surrogate")
                i++
            }
            return out
        }

        fun num(): Long {
            val start = pos
            if (s[pos] == '-') pos++
            val digitsStart = pos
            while (pos < s.length && s[pos] in '0'..'9') pos++
            if (pos == digitsStart) malformed("bad number at $start")
            if (pos < s.length && (s[pos] == '.' || s[pos] == 'e' || s[pos] == 'E')) malformed("floating-point number at $start")
            val t = s.substring(start, pos)
            if (t.length > (if (t.startsWith("-")) 2 else 1) && s[digitsStart] == '0') malformed("leading zero at $start")
            return t.toLongOrNull() ?: malformed("integer out of range at $start")
        }
    }
}
