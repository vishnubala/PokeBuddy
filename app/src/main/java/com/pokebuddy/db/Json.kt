package com.pokebuddy.db

/**
 * A minimal JSON reader/writer, scoped to exactly the shapes [BackupCodec] emits: an object
 * whose values are objects, arrays, strings, whole numbers, booleans or null. No floats, no
 * nesting beyond what the backup uses.
 *
 * Hand-rolled on purpose — the rest of the app avoids a JSON dependency so unit tests run on
 * the plain JVM (org.json isn't there off-device). Unlike the regex approach in the settings
 * codec this is a real tokenizer, because a DB backup carries free-text catch locations with
 * commas, quotes and non-ASCII, and a backup that can't be read back is worse than none.
 */
internal object Json {

    // ---- writing ----

    fun write(value: Any?): String = StringBuilder().also { encode(value, it) }.toString()

    private fun encode(value: Any?, out: StringBuilder) {
        when (value) {
            null -> out.append("null")
            is String -> encodeString(value, out)
            is Boolean -> out.append(value)
            is Int, is Long -> out.append(value.toString())
            is Map<*, *> -> {
                out.append('{')
                value.entries.forEachIndexed { i, (k, v) ->
                    if (i > 0) out.append(',')
                    encodeString(k.toString(), out)
                    out.append(':')
                    encode(v, out)
                }
                out.append('}')
            }
            is List<*> -> {
                out.append('[')
                value.forEachIndexed { i, v ->
                    if (i > 0) out.append(',')
                    encode(v, out)
                }
                out.append(']')
            }
            else -> throw IllegalArgumentException("unsupported JSON value: $value")
        }
    }

    private fun encodeString(s: String, out: StringBuilder) {
        out.append('"')
        for (c in s) when (c) {
            '"' -> out.append("\\\"")
            '\\' -> out.append("\\\\")
            '\n' -> out.append("\\n")
            '\r' -> out.append("\\r")
            '\t' -> out.append("\\t")
            else -> if (c < ' ') out.append("\\u%04x".format(c.code)) else out.append(c)
        }
        out.append('"')
    }

    // ---- reading ----

    class ParseException(msg: String) : Exception(msg)

    fun parse(text: String): Any? = Parser(text).run {
        val v = readValue()
        skipWhitespace()
        if (!atEnd()) fail("trailing content")
        v
    }

    private class Parser(private val s: String) {
        private var i = 0

        fun atEnd() = i >= s.length
        fun fail(msg: String): Nothing = throw ParseException("$msg at index $i")

        fun skipWhitespace() { while (i < s.length && s[i].isWhitespace()) i++ }

        fun readValue(): Any? {
            skipWhitespace()
            if (atEnd()) fail("unexpected end")
            return when (s[i]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't', 'f' -> readBoolean()
                'n' -> readNull()
                else -> readNumber()
            }
        }

        private fun readObject(): Map<String, Any?> {
            expect('{')
            val map = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (peek() == '}') { i++; return map }
            while (true) {
                skipWhitespace()
                val key = readString()
                skipWhitespace()
                expect(':')
                map[key] = readValue()
                skipWhitespace()
                when (next()) {
                    ',' -> continue
                    '}' -> return map
                    else -> fail("expected , or }")
                }
            }
        }

        private fun readArray(): List<Any?> {
            expect('[')
            val list = ArrayList<Any?>()
            skipWhitespace()
            if (peek() == ']') { i++; return list }
            while (true) {
                list.add(readValue())
                skipWhitespace()
                when (next()) {
                    ',' -> continue
                    ']' -> return list
                    else -> fail("expected , or ]")
                }
            }
        }

        private fun readString(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                if (atEnd()) fail("unterminated string")
                when (val c = s[i++]) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (atEnd()) fail("unterminated escape")
                        when (val e = s[i++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('')
                            'u' -> {
                                if (i + 4 > s.length) fail("bad unicode escape")
                                sb.append(s.substring(i, i + 4).toInt(16).toChar())
                                i += 4
                            }
                            else -> fail("bad escape \\$e")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun readBoolean(): Boolean = when {
            s.startsWith("true", i) -> { i += 4; true }
            s.startsWith("false", i) -> { i += 5; false }
            else -> fail("invalid literal")
        }

        private fun readNull(): Any? =
            if (s.startsWith("null", i)) { i += 4; null } else fail("invalid literal")

        /** Whole numbers only — the backup has no fractional fields, so a '.' is a bug worth
         *  surfacing rather than silently coercing. */
        private fun readNumber(): Any {
            val start = i
            if (peek() == '-') i++
            while (i < s.length && s[i].isDigit()) i++
            if (i == start || (i == start + 1 && s[start] == '-')) fail("invalid number")
            val text = s.substring(start, i)
            return text.toIntOrNull() ?: text.toLong()
        }

        private fun peek(): Char = if (atEnd()) fail("unexpected end") else s[i]
        private fun next(): Char = if (atEnd()) fail("unexpected end") else s[i++]
        private fun expect(c: Char) { if (next() != c) fail("expected $c") }
    }
}
