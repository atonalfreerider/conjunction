package com.metavirtuoso.conjunction.core.json

/**
 * Minimal, dependency-free JSON reader/writer for bundled content (fallback reading text) and the
 * golden test vectors. Objects become LinkedHashMap (insertion order kept), arrays List, numbers
 * Double (or Long when integral and written without fraction/exponent), plus String/Boolean/null.
 */
object Json {
    fun parse(text: String): Any? = Parser(text).run {
        val v = value()
        skipWs()
        require(pos == text.length) { "Trailing characters at $pos" }
        v
    }

    @Suppress("UNCHECKED_CAST")
    fun obj(v: Any?): Map<String, Any?> = v as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    fun arr(v: Any?): List<Any?> = v as List<Any?>

    fun num(v: Any?): Double = (v as Number).toDouble()

    /** Serialises maps, lists, strings, numbers, booleans and null; [indent] spaces per level. */
    fun write(value: Any?, indent: Int = 1): String = StringBuilder().also { writeTo(it, value, indent, 0) }.toString()

    private fun writeTo(sb: StringBuilder, v: Any?, indent: Int, level: Int) {
        fun nl(l: Int) { if (indent > 0) { sb.append('\n'); repeat(l * indent) { sb.append(' ') } } }
        when (v) {
            null -> sb.append("null")
            is String -> quote(sb, v)
            is Boolean -> sb.append(v)
            is Int, is Long -> sb.append(v.toString())
            is Double -> sb.append(if (v == Math.rint(v) && kotlin.math.abs(v) < 1e15) v.toLong().toString() else v.toString())
            is Float -> writeTo(sb, v.toDouble(), indent, level)
            is Map<*, *> -> {
                if (v.isEmpty()) { sb.append("{}"); return }
                sb.append('{')
                var first = true
                for ((k, value) in v) {
                    if (!first) sb.append(',')
                    first = false
                    nl(level + 1)
                    quote(sb, k.toString()); sb.append(if (indent > 0) ": " else ":")
                    writeTo(sb, value, indent, level + 1)
                }
                nl(level); sb.append('}')
            }
            is Iterable<*> -> {
                val items = v.toList()
                if (items.isEmpty()) { sb.append("[]"); return }
                val scalars = items.all { it == null || it is Number || it is Boolean || (it is String && it.length < 24) }
                sb.append('[')
                items.forEachIndexed { i, item ->
                    if (i > 0) sb.append(if (scalars) ", " else ",")
                    if (!scalars) nl(level + 1)
                    writeTo(sb, item, indent, level + 1)
                }
                if (!scalars) nl(level)
                sb.append(']')
            }
            is Enum<*> -> quote(sb, v.name)
            else -> quote(sb, v.toString())
        }
    }

    private fun quote(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
        }
        sb.append('"')
    }

    private class Parser(val s: String) {
        var pos = 0

        fun skipWs() { while (pos < s.length && s[pos].isWhitespace()) pos++ }

        fun value(): Any? {
            skipWs()
            require(pos < s.length) { "Unexpected end of JSON" }
            return when (val c = s[pos]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c.isDigit()) number() else error("Unexpected '$c' at $pos")
            }
        }

        fun literal(word: String, v: Any?): Any? {
            require(s.startsWith(word, pos)) { "Bad literal at $pos" }
            pos += word.length
            return v
        }

        fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            pos++
            skipWs()
            if (s[pos] == '}') { pos++; return m }
            while (true) {
                skipWs()
                val k = str()
                skipWs()
                require(s[pos] == ':') { "Expected ':' at $pos" }
                pos++
                m[k] = value()
                skipWs()
                when (s[pos++]) {
                    ',' -> continue
                    '}' -> return m
                    else -> error("Expected ',' or '}' at ${pos - 1}")
                }
            }
        }

        fun arr(): List<Any?> {
            val l = ArrayList<Any?>()
            pos++
            skipWs()
            if (s[pos] == ']') { pos++; return l }
            while (true) {
                l += value()
                skipWs()
                when (s[pos++]) {
                    ',' -> continue
                    ']' -> return l
                    else -> error("Expected ',' or ']' at ${pos - 1}")
                }
            }
        }

        fun str(): String {
            require(s[pos] == '"') { "Expected string at $pos" }
            pos++
            val sb = StringBuilder()
            while (true) {
                val c = s[pos++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        when (val e = s[pos++]) {
                            '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000c'); 'n' -> sb.append('\n')
                            'r' -> sb.append('\r'); 't' -> sb.append('\t')
                            'u' -> { sb.append(s.substring(pos, pos + 4).toInt(16).toChar()); pos += 4 }
                            else -> error("Bad escape \\$e at $pos")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        fun number(): Any {
            val start = pos
            if (s[pos] == '-') pos++
            while (pos < s.length && (s[pos].isDigit() || s[pos] in ".eE+-")) pos++
            val t = s.substring(start, pos)
            return if (t.any { it in ".eE" }) t.toDouble() else t.toLongOrNull() ?: t.toDouble()
        }
    }
}
