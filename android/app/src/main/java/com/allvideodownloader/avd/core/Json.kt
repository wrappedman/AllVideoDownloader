package com.allvideodownloader.avd.core

/**
 * A tiny, dependency-free JSON reader/writer.
 *
 * The Python backend worked with plain dicts/lists, and this port keeps that
 * model exactly (`Map<String, Any?>`, `List<Any?>`), so every algorithm below
 * translates one-to-one. Using the platform's `org.json` would have worked too
 * but would have made the engine untestable off-device — the whole port runs on
 * a plain JVM in `android/dev/CoreSelfTest.kt`, which is how it gets verified.
 */
object Json {

    // ── parsing ───────────────────────────────────────────────────────────
    fun parse(text: String?): Any? {
        val src = text ?: return null
        val p = Parser(src)
        p.skipWs()
        if (p.eof()) return null
        val value = p.value()
        p.skipWs()
        return value
    }

    /** Parse, returning `null` instead of throwing (the Python code catches these). */
    fun parseOrNull(text: String?): Any? = try {
        parse(text)
    } catch (_: Exception) {
        null
    }

    private class Parser(private val s: String) {
        private var i = 0
        fun eof() = i >= s.length
        fun skipWs() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r')) i++
        }

        fun value(): Any? {
            skipWs()
            return when {
                eof() -> null
                s[i] == '{' -> obj()
                s[i] == '[' -> arr()
                s[i] == '"' -> str()
                s.startsWith("true", i) -> { i += 4; true }
                s.startsWith("false", i) -> { i += 5; false }
                s.startsWith("null", i) -> { i += 4; null }
                else -> num()
            }
        }

        private fun obj(): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            expect('{')
            skipWs()
            if (!eof() && s[i] == '}') { i++; return out }
            while (true) {
                skipWs()
                val key = str()
                skipWs()
                expect(':')
                out[key] = value()
                skipWs()
                if (eof()) break
                when (s[i]) {
                    ',' -> { i++; continue }
                    '}' -> { i++; return out }
                    else -> throw IllegalArgumentException("bad object at $i")
                }
            }
            return out
        }

        private fun arr(): List<Any?> {
            val out = ArrayList<Any?>()
            expect('[')
            skipWs()
            if (!eof() && s[i] == ']') { i++; return out }
            while (true) {
                out.add(value())
                skipWs()
                if (eof()) break
                when (s[i]) {
                    ',' -> { i++; continue }
                    ']' -> { i++; return out }
                    else -> throw IllegalArgumentException("bad array at $i")
                }
            }
            return out
        }

        private fun str(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                if (eof()) throw IllegalArgumentException("unterminated string")
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (eof()) throw IllegalArgumentException("bad escape")
                        when (val e = s[i++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) throw IllegalArgumentException("bad \\u")
                                sb.append(s.substring(i, i + 4).toInt(16).toChar())
                                i += 4
                            }
                            else -> throw IllegalArgumentException("bad escape \\$e")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun num(): Any {
            val start = i
            if (!eof() && (s[i] == '-' || s[i] == '+')) i++
            while (!eof() && (s[i].isDigit() || s[i] == '.' || s[i] == 'e' || s[i] == 'E' ||
                        s[i] == '-' || s[i] == '+')) i++
            val raw = s.substring(start, i)
            if (raw.isEmpty()) throw IllegalArgumentException("bad number at $start")
            if (!raw.contains('.') && !raw.contains('e') && !raw.contains('E')) {
                return raw.toLongOrNull() ?: raw.toDouble()
            }
            return raw.toDouble()
        }

        private fun expect(c: Char) {
            if (eof() || s[i] != c) throw IllegalArgumentException("expected '$c' at $i")
            i++
        }
    }

    // ── writing ───────────────────────────────────────────────────────────
    fun write(value: Any?): String = StringBuilder().also { write(value, it) }.toString()

    @Suppress("UNCHECKED_CAST")
    private fun write(value: Any?, sb: StringBuilder) {
        when (value) {
            null -> sb.append("null")
            is String -> quote(value, sb)
            is Boolean -> sb.append(if (value) "true" else "false")
            is Double -> {
                if (value.isNaN() || value.isInfinite()) sb.append("null")
                else if (value == Math.floor(value) && Math.abs(value) < 1e15) sb.append(value.toLong().toString())
                else sb.append(value.toString())
            }
            is Float -> write(value.toDouble(), sb)
            is Int, is Long, is Short, is Byte -> sb.append(value.toString())
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, v) in value as Map<Any?, Any?>) {
                    if (!first) sb.append(',')
                    first = false
                    quote(k?.toString() ?: "", sb)
                    sb.append(':')
                    write(v, sb)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (v in value) {
                    if (!first) sb.append(',')
                    first = false
                    write(v, sb)
                }
                sb.append(']')
            }
            is Array<*> -> write(value.toList(), sb)
            is Set<*> -> write(value.toList(), sb)
            else -> quote(value.toString(), sb)
        }
    }

    private fun quote(s: String, sb: StringBuilder) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c < ' ') sb.append("\\u").append(String.format("%04x", c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }
}

// ── Python-shaped accessors (dict/list ergonomics on top of `Any?`) ─────────

@Suppress("UNCHECKED_CAST")
fun Any?.asMap(): MutableMap<String, Any?>? = when (this) {
    is MutableMap<*, *> -> this as MutableMap<String, Any?>
    is Map<*, *> -> LinkedHashMap(this as Map<String, Any?>)
    else -> null
}

/** Read a nested map as a real map, or an empty one (Python's `x.get("k") or {}`). */
fun Any?.map(): MutableMap<String, Any?> = asMap() ?: LinkedHashMap()

@Suppress("UNCHECKED_CAST")
fun Any?.asList(): MutableList<Any?> = when (this) {
    is MutableList<*> -> this as MutableList<Any?>
    is List<*> -> ArrayList(this as List<Any?>)
    is Array<*> -> ArrayList(this.toList())
    else -> ArrayList()
}

fun Any?.list(): MutableList<Any?> = asList()

fun Any?.str(): String = when (this) {
    null -> ""
    is String -> this
    is Double -> if (this == Math.floor(this) && Math.abs(this) < 1e15) this.toLong().toString() else this.toString()
    else -> this.toString()
}

fun Any?.num(): Double = when (this) {
    null -> 0.0
    is Number -> this.toDouble()
    is String -> this.trim().toDoubleOrNull() ?: 0.0
    is Boolean -> if (this) 1.0 else 0.0
    else -> 0.0
}

fun Any?.int(): Int = num().toInt()

fun Any?.long(): Long = num().toLong()

fun Any?.bool(): Boolean = when (this) {
    null -> false
    is Boolean -> this
    is Number -> this.toDouble() != 0.0
    is String -> this.isNotEmpty() && this != "0" && !this.equals("false", true)
    is Collection<*> -> this.isNotEmpty()
    is Map<*, *> -> this.isNotEmpty()
    else -> true
}

/** `dict.get(key)` — always present-or-null, mirroring Python semantics. */
operator fun Map<String, Any?>?.get(key: String): Any? = this?.get(key as Any?)

fun mapOfNotNull(vararg pairs: Pair<String, Any?>): MutableMap<String, Any?> {
    val out = LinkedHashMap<String, Any?>()
    for ((k, v) in pairs) if (v != null) out[k] = v
    return out
}
