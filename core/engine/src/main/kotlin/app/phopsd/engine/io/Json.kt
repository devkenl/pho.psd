package app.phopsd.engine.io

/**
 * Minimal, dependency-free JSON model, writer and parser for project manifests and
 * small app files. Deliberately strict: malformed input throws [JsonException] instead of
 * guessing, because a manifest that parses "approximately" could silently drop user data.
 */
sealed interface Json {
    data class Obj(val fields: Map<String, Json>) : Json {
        operator fun get(key: String): Json? = fields[key]
    }
    data class Arr(val items: List<Json>) : Json
    data class Str(val value: String) : Json
    data class Num(val value: Double) : Json
    data class Bool(val value: Boolean) : Json
    data object Null : Json

    companion object {
        fun obj(vararg pairs: Pair<String, Json?>): Obj =
            Obj(LinkedHashMap<String, Json>().apply { for ((k, v) in pairs) if (v != null) put(k, v) })

        fun arr(items: List<Json>): Arr = Arr(items)
        fun str(v: String?): Json = if (v == null) Null else Str(v)
        fun num(v: Number): Num = Num(v.toDouble())
        fun bool(v: Boolean): Bool = Bool(v)

        fun parse(text: String): Json = JsonParser(text).parseDocument()

        fun write(value: Json, pretty: Boolean = true): String =
            StringBuilder().also { JsonWriter(it, pretty).write(value, 0) }.toString()
    }
}

class JsonException(message: String) : Exception(message)

// ------------------------------------------------------------------ typed accessors

fun Json.Obj.string(key: String): String =
    (this[key] as? Json.Str)?.value ?: throw JsonException("Missing or invalid string field “$key”")

fun Json.Obj.stringOrNull(key: String): String? = when (val v = this[key]) {
    null, Json.Null -> null
    is Json.Str -> v.value
    else -> throw JsonException("Field “$key” must be a string")
}

fun Json.Obj.double(key: String): Double {
    val v = (this[key] as? Json.Num)?.value ?: throw JsonException("Missing or invalid number field “$key”")
    if (!v.isFinite()) throw JsonException("Field “$key” must be finite")
    return v
}

fun Json.Obj.doubleOr(key: String, default: Double): Double = if (this[key] == null) default else double(key)

fun Json.Obj.int(key: String): Int {
    val d = double(key)
    if (d != Math.rint(d) || d < Int.MIN_VALUE || d > Int.MAX_VALUE) throw JsonException("Field “$key” must be an integer")
    return d.toInt()
}

fun Json.Obj.long(key: String): Long {
    val d = double(key)
    if (d != Math.rint(d)) throw JsonException("Field “$key” must be an integer")
    return d.toLong()
}

fun Json.Obj.bool(key: String): Boolean =
    (this[key] as? Json.Bool)?.value ?: throw JsonException("Missing or invalid boolean field “$key”")

fun Json.Obj.boolOr(key: String, default: Boolean): Boolean = if (this[key] == null) default else bool(key)

fun Json.Obj.obj(key: String): Json.Obj =
    this[key] as? Json.Obj ?: throw JsonException("Missing or invalid object field “$key”")

fun Json.Obj.objOrNull(key: String): Json.Obj? = when (val v = this[key]) {
    null, Json.Null -> null
    is Json.Obj -> v
    else -> throw JsonException("Field “$key” must be an object")
}

fun Json.Obj.array(key: String): List<Json> =
    (this[key] as? Json.Arr)?.items ?: throw JsonException("Missing or invalid array field “$key”")

// ------------------------------------------------------------------ writer

private class JsonWriter(private val sb: StringBuilder, private val pretty: Boolean) {
    fun write(v: Json, indent: Int) {
        when (v) {
            Json.Null -> sb.append("null")
            is Json.Bool -> sb.append(if (v.value) "true" else "false")
            is Json.Num -> writeNumber(v.value)
            is Json.Str -> writeString(v.value)
            is Json.Arr -> {
                if (v.items.isEmpty()) { sb.append("[]"); return }
                sb.append('[')
                v.items.forEachIndexed { i, item ->
                    if (i > 0) sb.append(',')
                    newline(indent + 1)
                    write(item, indent + 1)
                }
                newline(indent)
                sb.append(']')
            }
            is Json.Obj -> {
                if (v.fields.isEmpty()) { sb.append("{}"); return }
                sb.append('{')
                var first = true
                for ((k, item) in v.fields) {
                    if (!first) sb.append(',')
                    first = false
                    newline(indent + 1)
                    writeString(k)
                    sb.append(if (pretty) ": " else ":")
                    write(item, indent + 1)
                }
                newline(indent)
                sb.append('}')
            }
        }
    }

    private fun newline(indent: Int) {
        if (!pretty) return
        sb.append('\n')
        repeat(indent) { sb.append("  ") }
    }

    private fun writeNumber(d: Double) {
        require(d.isFinite()) { "JSON cannot represent $d" }
        if (d == Math.rint(d) && kotlin.math.abs(d) < 1e15) sb.append(d.toLong()) else sb.append(d.toString())
    }

    private fun writeString(s: String) {
        sb.append('"')
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (ch < ' ') sb.append(String.format("\\u%04x", ch.code)) else sb.append(ch)
            }
        }
        sb.append('"')
    }
}

// ------------------------------------------------------------------ parser

private class JsonParser(private val s: String) {
    private var i = 0
    private var depth = 0

    fun parseDocument(): Json {
        val v = value()
        ws()
        if (i != s.length) fail("Unexpected trailing content")
        return v
    }

    private fun fail(msg: String): Nothing = throw JsonException("$msg at offset $i")

    private fun ws() {
        while (i < s.length && (s[i] == ' ' || s[i] == '\n' || s[i] == '\r' || s[i] == '\t')) i++
    }

    private fun value(): Json {
        ws()
        if (i >= s.length) fail("Unexpected end of input")
        return when (val c = s[i]) {
            '{' -> obj()
            '[' -> arr()
            '"' -> Json.Str(string())
            't' -> literal("true", Json.Bool(true))
            'f' -> literal("false", Json.Bool(false))
            'n' -> literal("null", Json.Null)
            else -> if (c == '-' || c in '0'..'9') number() else fail("Unexpected character '$c'")
        }
    }

    private fun literal(word: String, v: Json): Json {
        if (!s.startsWith(word, i)) fail("Invalid literal")
        i += word.length
        return v
    }

    private fun enter() {
        if (++depth > MAX_DEPTH) fail("Nesting too deep")
    }

    private fun obj(): Json.Obj {
        enter()
        i++ // {
        val map = LinkedHashMap<String, Json>()
        ws()
        if (i < s.length && s[i] == '}') { i++; depth--; return Json.Obj(map) }
        while (true) {
            ws()
            if (i >= s.length || s[i] != '"') fail("Expected object key")
            val k = string()
            ws()
            if (i >= s.length || s[i] != ':') fail("Expected ':'")
            i++
            if (map.containsKey(k)) fail("Duplicate key “$k”")
            map[k] = value()
            ws()
            if (i >= s.length) fail("Unterminated object")
            when (s[i]) {
                ',' -> i++
                '}' -> { i++; depth--; return Json.Obj(map) }
                else -> fail("Expected ',' or '}'")
            }
        }
    }

    private fun arr(): Json.Arr {
        enter()
        i++ // [
        val list = ArrayList<Json>()
        ws()
        if (i < s.length && s[i] == ']') { i++; depth--; return Json.Arr(list) }
        while (true) {
            list.add(value())
            ws()
            if (i >= s.length) fail("Unterminated array")
            when (s[i]) {
                ',' -> i++
                ']' -> { i++; depth--; return Json.Arr(list) }
                else -> fail("Expected ',' or ']'")
            }
        }
    }

    private fun string(): String {
        i++ // opening quote
        val sb = StringBuilder()
        while (true) {
            if (i >= s.length) fail("Unterminated string")
            val c = s[i++]
            when {
                c == '"' -> return sb.toString()
                c == '\\' -> {
                    if (i >= s.length) fail("Unterminated escape")
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
                            if (i + 4 > s.length) fail("Invalid unicode escape")
                            val code = s.substring(i, i + 4).toIntOrNull(16) ?: fail("Invalid unicode escape")
                            sb.append(code.toChar())
                            i += 4
                        }
                        else -> fail("Invalid escape '\\$e'")
                    }
                }
                c < ' ' -> fail("Control character in string")
                else -> sb.append(c)
            }
        }
    }

    private fun number(): Json.Num {
        val start = i
        if (s[i] == '-') i++
        if (i >= s.length) fail("Invalid number")
        if (s[i] == '0') i++ else {
            if (s[i] !in '1'..'9') fail("Invalid number")
            while (i < s.length && s[i].isDigit()) i++
        }
        if (i < s.length && s[i] == '.') {
            i++
            if (i >= s.length || !s[i].isDigit()) fail("Invalid number")
            while (i < s.length && s[i].isDigit()) i++
        }
        if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
            i++
            if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
            if (i >= s.length || !s[i].isDigit()) fail("Invalid number")
            while (i < s.length && s[i].isDigit()) i++
        }
        val d = s.substring(start, i).toDouble()
        if (!d.isFinite()) fail("Number out of range")
        return Json.Num(d)
    }

    companion object {
        const val MAX_DEPTH = 64
    }
}
