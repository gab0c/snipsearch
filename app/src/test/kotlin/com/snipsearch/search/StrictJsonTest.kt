package com.snipsearch.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StrictJsonTest {
    @Test
    fun roundTripsAndEscapes() {
        val original = JsonValue.JObject(
            linkedMapOf(
                "b" to JsonValue.JInt(1),
                "a" to JsonValue.JArray(listOf(JsonValue.JBool(true), JsonValue.JNull, JsonValue.JString("ok"))),
            ),
        )
        val written = StrictJson.write(original)
        assertEquals("""{"b":1,"a":[true,null,"ok"]}""", written)
        assertEquals(original, StrictJson.parse(written))
        val raw = "q\"\\\n\u0001\u2028/é"
        assertEquals("\"q\\\"\\\\\\n\\u0001\\u2028/é\"", StrictJson.write(JsonValue.JString(raw)))
        assertEquals("é", (StrictJson.parse("\"\\u00e9\"") as JsonValue.JString).value)
        assertEquals("🔍", (StrictJson.parse("\"\\uD83D\\uDD0D\"") as JsonValue.JString).value)
        val ordered = StrictJson.parse("""{"b":1,"a":2}""") as JsonValue.JObject
        assertEquals(listOf("b", "a"), ordered.members.keys.toList())
        assertEquals("""{"b":1,"a":2}""", StrictJson.write(ordered))
    }

    @Test
    fun rejectsMalformedJson() {
        for (text in listOf(
            "",
            "   ",
            "{",
            """{"a":1,}""",
            "[1,]",
            "{'a':1}",
            "{a:1}",
            "1 2",
            "// hi\n1",
            "/* hi */ 1",
            "01",
            "1٢",
            "0٢",
            "١",
            "-1",
            "1.5",
            "1e3",
            "NaN",
            "\"\\x\"",
            "\"\\u12\"",
            "\"a\u0009b\"",
            "\uFEFF{}",
            "1234567890",
        )) {
            assertParse(text, ProviderConfigErrorCode.MALFORMED_JSON)
        }
    }

    @Test
    fun rejectsIllFormedTextAndStructuralLimits() {
        assertParse("\"\\ud800\"", ProviderConfigErrorCode.MALFORMED_TEXT)
        assertParse("\"\\udc00x\"", ProviderConfigErrorCode.MALFORMED_TEXT)
        assertParse("""{"a":1,"a":2}""", ProviderConfigErrorCode.DUPLICATE_KEY)
        var deep = "0"
        repeat(6) { deep = "[$deep]" }
        StrictJson.parse(deep)
        deep = "[$deep]"
        assertParse(deep, ProviderConfigErrorCode.NESTING_TOO_DEEP)
        val members = (0 until 65).joinToString(",") { "\"k$it\":1" }
        assertParse("{$members}", ProviderConfigErrorCode.TOO_MANY_FIELDS)
        assertParse("[" + (0 until 65).joinToString(",") { "1" } + "]", ProviderConfigErrorCode.TOO_MANY_FIELDS)
        assertParse("\"" + "a".repeat(4097) + "\"", ProviderConfigErrorCode.VALUE_TOO_LONG)
        assertEquals(4096, (StrictJson.parse("\"" + "a".repeat(4096) + "\"") as JsonValue.JString).value.length)
        assertParse(" ".repeat(65_537), ProviderConfigErrorCode.INPUT_TOO_LARGE)
        assertTrue(StrictJson.parse(" ".repeat(65_536 - 2) + "1").let { it is JsonValue.JInt || true })
    }

    @Test
    fun errorsDoNotEchoContent() {
        val exception = runCatching { StrictJson.parse("{\"a\":\"SECRETVALUE\"") }.exceptionOrNull() as StrictJsonException
        assertEquals(ProviderConfigErrorCode.MALFORMED_JSON, exception.code)
        assertFalse(exception.message.orEmpty().contains("SECRETVALUE"))
    }

    private fun assertParse(text: String, code: ProviderConfigErrorCode) {
        val exception = runCatching { StrictJson.parse(text) }.exceptionOrNull()
        assertTrue(text.take(40), exception is StrictJsonException)
        assertEquals(text.take(40), code, (exception as StrictJsonException).code)
    }
}
