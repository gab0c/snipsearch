package com.snipsearch.search

/** JSON value in the strict subset this package reads and writes. */
internal sealed interface JsonValue {
    /** A decoded JSON string. */
    data class JString(val value: String) : JsonValue

    /** A non-negative decimal integer that fits in nine digits. */
    data class JInt(val value: Int) : JsonValue

    /** A JSON boolean. */
    data class JBool(val value: Boolean) : JsonValue

    /** JSON null. */
    data object JNull : JsonValue

    /** A JSON array. */
    data class JArray(val items: List<JsonValue>) : JsonValue

    /** A JSON object. [members] preserves insertion order. */
    data class JObject(val members: Map<String, JsonValue>) : JsonValue
}

/** Parse failure tagged with a [ProviderConfigErrorCode]. */
internal class StrictJsonException(
    /** Reason the input was rejected. */
    val code: ProviderConfigErrorCode,
    message: String,
) : Exception(message)

/** Strict RFC 8259 subset parser and compact writer. */
internal object StrictJson {
    /** Maximum input length in UTF-16 code units. */
    const val MAX_INPUT_CHARS = 65_536

    /** Maximum nesting of objects and arrays. The root container is depth 1. */
    const val MAX_DEPTH = 6

    /** Maximum decoded string length. */
    const val MAX_STRING_CHARS = 4_096

    /** Maximum object members. */
    const val MAX_MEMBERS = 64

    /** Maximum array items. */
    const val MAX_ARRAY_ITEMS = 64

    private const val HEX = "0123456789abcdef"

    /** Parses [text] or throws [StrictJsonException]. */
    fun parse(text: String): JsonValue {
        if (text.length > MAX_INPUT_CHARS) {
            throw StrictJsonException(ProviderConfigErrorCode.INPUT_TOO_LARGE, "The input exceeds the maximum size.")
        }
        return Parser(text).parse()
    }

    /** Writes [value] with no insignificant whitespace. */
    fun write(value: JsonValue): String = buildString { appendValue(this, value) }

    private fun appendValue(out: StringBuilder, value: JsonValue) {
        when (value) {
            is JsonValue.JString -> appendString(out, value.value)
            is JsonValue.JInt -> out.append(value.value.toString())
            is JsonValue.JBool -> out.append(if (value.value) "true" else "false")
            JsonValue.JNull -> out.append("null")
            is JsonValue.JArray -> {
                out.append('[')
                value.items.forEachIndexed { index, item ->
                    if (index > 0) out.append(',')
                    appendValue(out, item)
                }
                out.append(']')
            }
            is JsonValue.JObject -> {
                out.append('{')
                var first = true
                for ((key, member) in value.members) {
                    if (!first) out.append(',')
                    first = false
                    appendString(out, key)
                    out.append(':')
                    appendValue(out, member)
                }
                out.append('}')
            }
        }
    }

    private fun appendString(out: StringBuilder, value: String) {
        out.append('"')
        for (ch in value) {
            when (ch) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\u2028' -> out.append("\\u2028")
                '\u2029' -> out.append("\\u2029")
                else -> if (ch.code < 0x20) {
                    out.append("\\u")
                    out.append(HEX[(ch.code shr 12) and 0xF])
                    out.append(HEX[(ch.code shr 8) and 0xF])
                    out.append(HEX[(ch.code shr 4) and 0xF])
                    out.append(HEX[ch.code and 0xF])
                } else {
                    out.append(ch)
                }
            }
        }
        out.append('"')
    }

    private class Parser(private val text: String) {
        private var i = 0

        fun parse(): JsonValue {
            skipWs()
            if (i >= text.length) fail(ProviderConfigErrorCode.MALFORMED_JSON, "Malformed JSON.")
            val value = parseValue(0)
            skipWs()
            if (i != text.length) fail(ProviderConfigErrorCode.MALFORMED_JSON, "Malformed JSON at offset $i.")
            return value
        }

        private fun parseValue(depth: Int): JsonValue {
            if (i >= text.length) fail(ProviderConfigErrorCode.MALFORMED_JSON, "Malformed JSON at offset $i.")
            return when (val ch = text[i]) {
                '{', '[' -> {
                    val next = depth + 1
                    if (next > MAX_DEPTH) {
                        fail(ProviderConfigErrorCode.NESTING_TOO_DEEP, "The JSON nesting is too deep.")
                    }
                    if (ch == '{') parseObject(next) else parseArray(next)
                }
                '"' -> JsonValue.JString(parseString())
                't' -> literal("true", JsonValue.JBool(true))
                'f' -> literal("false", JsonValue.JBool(false))
                'n' -> literal("null", JsonValue.JNull)
                in '0'..'9' -> parseNumber()
                else -> fail(ProviderConfigErrorCode.MALFORMED_JSON, "Malformed JSON at offset $i.")
            }
        }

        private fun parseObject(depth: Int): JsonValue.JObject {
            expect('{')
            skipWs()
            val members = LinkedHashMap<String, JsonValue>()
            if (peek('}')) {
                i += 1
                return JsonValue.JObject(members)
            }
            while (true) {
                if (i >= text.length || text[i] != '"') {
                    fail(ProviderConfigErrorCode.MALFORMED_JSON, "Malformed JSON at offset $i.")
                }
                val key = parseString()
                skipWs()
                if (!peek(':')) fail(ProviderConfigErrorCode.MALFORMED_JSON, "Malformed JSON at offset $i.")
                i += 1
                skipWs()
                val value = parseValue(depth)
                if (members.containsKey(key)) {
                    fail(ProviderConfigErrorCode.DUPLICATE_KEY, "A duplicate JSON key is present at offset $i.")
                }
                members[key] = value
                if (members.size > MAX_MEMBERS) {
                    fail(ProviderConfigErrorCode.TOO_MANY_FIELDS, "Too many fields are present.")
                }
                skipWs()
                when {
                    peek(',') -> {
                        i += 1
                        skipWs()
                        if (peek('}')) fail(ProviderConfigErrorCode.MALFORMED_JSON, "Malformed JSON at offset $i.")
                    }
                    peek('}') -> {
                        i += 1
                        return JsonValue.JObject(members)
                    }
                    else -> fail(ProviderConfigErrorCode.MALFORMED_JSON, "Malformed JSON at offset $i.")
                }
            }
        }

        private fun parseArray(depth: Int): JsonValue.JArray {
            expect('[')
            skipWs()
            val items = mutableListOf<JsonValue>()
            if (peek(']')) {
                i += 1
                return JsonValue.JArray(items)
            }
            while (true) {
                items += parseValue(depth)
                if (items.size > MAX_ARRAY_ITEMS) {
                    fail(ProviderConfigErrorCode.TOO_MANY_FIELDS, "Too many fields are present.")
                }
                skipWs()
                when {
                    peek(',') -> {
                        i += 1
                        skipWs()
                        if (peek(']')) fail(ProviderConfigErrorCode.MALFORMED_JSON, "Malformed JSON at offset $i.")
                    }
                    peek(']') -> {
                        i += 1
                        return JsonValue.JArray(items)
                    }
                    else -> fail(ProviderConfigErrorCode.MALFORMED_JSON, "Malformed JSON at offset $i.")
                }
            }
        }

        private fun parseNumber(): JsonValue.JInt {
            val start = i
            if (text[i] == '0') {
                i += 1
                if (i < text.length && isAsciiDigit(text[i])) {
                    fail(ProviderConfigErrorCode.MALFORMED_JSON, "Malformed JSON at offset $start.")
                }
                rejectNumberTail()
                return JsonValue.JInt(0)
            }
            var digits = 0
            var number = 0
            while (i < text.length && isAsciiDigit(text[i])) {
                digits += 1
                if (digits > 9) fail(ProviderConfigErrorCode.MALFORMED_JSON, "Malformed JSON at offset $start.")
                number = number * 10 + (text[i] - '0')
                i += 1
            }
            if (digits == 0) fail(ProviderConfigErrorCode.MALFORMED_JSON, "Malformed JSON at offset $start.")
            rejectNumberTail()
            return JsonValue.JInt(number)
        }

        private fun rejectNumberTail() {
            if (i < text.length && text[i] in ".eE+-") {
                fail(ProviderConfigErrorCode.MALFORMED_JSON, "Malformed JSON at offset $i.")
            }
        }

        private fun isAsciiDigit(ch: Char): Boolean = ch in '0'..'9'

        private fun parseString(): String {
            expect('"')
            val decoded = StringBuilder()
            while (i < text.length) {
                val ch = text[i]
                i += 1
                when {
                    ch == '"' -> {
                        val value = decoded.toString()
                        if (!ProviderEndpointPolicy.isWellFormedUtf16(value)) {
                            fail(ProviderConfigErrorCode.MALFORMED_TEXT, "The text is not well-formed.")
                        }
                        if (value.length > MAX_STRING_CHARS) {
                            fail(ProviderConfigErrorCode.VALUE_TOO_LONG, "The field value exceeds the maximum length.")
                        }
                        return value
                    }
                    ch == '\\' -> decoded.append(parseEscape())
                    ch.code < 0x20 -> fail(ProviderConfigErrorCode.MALFORMED_JSON, "Malformed JSON at offset ${i - 1}.")
                    else -> decoded.append(ch)
                }
            }
            fail(ProviderConfigErrorCode.MALFORMED_JSON, "Malformed JSON at offset $i.")
        }

        private fun parseEscape(): Char {
            if (i >= text.length) fail(ProviderConfigErrorCode.MALFORMED_JSON, "Malformed JSON at offset $i.")
            val ch = text[i]
            i += 1
            return when (ch) {
                '"' -> '"'
                '\\' -> '\\'
                '/' -> '/'
                'b' -> '\b'
                'f' -> '\u000C'
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                'u' -> {
                    if (i + 4 > text.length) fail(ProviderConfigErrorCode.MALFORMED_JSON, "Malformed JSON at offset $i.")
                    val hex = text.substring(i, i + 4)
                    if (hex.any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' }) {
                        fail(ProviderConfigErrorCode.MALFORMED_JSON, "Malformed JSON at offset $i.")
                    }
                    i += 4
                    hex.toInt(16).toChar()
                }
                else -> fail(ProviderConfigErrorCode.MALFORMED_JSON, "Malformed JSON at offset ${i - 1}.")
            }
        }

        private fun literal(token: String, value: JsonValue): JsonValue {
            if (!text.startsWith(token, i)) {
                fail(ProviderConfigErrorCode.MALFORMED_JSON, "Malformed JSON at offset $i.")
            }
            i += token.length
            return value
        }

        private fun skipWs() {
            while (i < text.length) {
                when (text[i]) {
                    ' ', '\t', '\n', '\r' -> i += 1
                    else -> return
                }
            }
        }

        private fun peek(ch: Char) = i < text.length && text[i] == ch

        private fun expect(ch: Char) {
            if (!peek(ch)) fail(ProviderConfigErrorCode.MALFORMED_JSON, "Malformed JSON at offset $i.")
            i += 1
        }

        private fun fail(code: ProviderConfigErrorCode, message: String): Nothing {
            throw StrictJsonException(code, message)
        }
    }
}
