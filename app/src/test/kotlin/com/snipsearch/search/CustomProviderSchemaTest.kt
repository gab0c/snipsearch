package com.snipsearch.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomProviderSchemaTest {
    @Test
    fun canonicalSizeAndCapabilities() {
        val providers = (1..3).map { n ->
            SearchProviderConfig.RestVisionProvider("custom-$n", "Vision", "https://example.com/v1",
                VisionRequestFormat.OPENAI_CHAT_COMPLETIONS, modelName = "model", systemPrompt = "\u2028".repeat(4000))
        }
        val single = (CustomProviderSchema.encode(providers[0]) as ProviderResult.Valid).value
        val body = single.substringAfter("\"provider\":").dropLast(1).replace("\\u2028", "\u2028")
        val literal = "{\"schema\":\"com.snipsearch.providers\",\"version\":1,\"providers\":[" +
            (1..3).joinToString(",") { body.replace("custom-1", "custom-$it") } + "]}"
        assertTrue(CustomProviderSchema.decodeList(literal) is ProviderResult.Invalid)
        val accepted = (CustomProviderSchema.encodeList(providers.take(2)) as ProviderResult.Valid).value
        assertTrue(CustomProviderSchema.decodeList(accepted) is ProviderResult.Valid)
        assertEquals(setOf(ProviderCapability.VISUAL), providers[0].copy(
            requestFormat = VisionRequestFormat.MULTIPART_REDIRECT_JSON).capabilities)
    }

    @Test
    fun encodesCanonicalDocuments() {
        assertEquals(TEXT_JSON, (CustomProviderSchema.encode(text()) as ProviderResult.Valid).value)
        val browser = SearchProviderConfig.BrowserFormVisualProvider(
            id = "custom-visual",
            displayName = "Visual",
            uploadEndpointUrl = "https://lens.google.com/v3/upload",
            fileFormFieldName = "encoded_image",
            extraFormFields = mapOf("z" to "1", "a" to "2"),
            multimodalQueryFieldName = "q",
            textSearchUrlTemplate = "https://www.google.com/search?q={searchTerms}",
        )
        assertEquals(BROWSER_JSON, (CustomProviderSchema.encode(browser) as ProviderResult.Valid).value)
        val sensitiveExtras = browser.copy(extraFormFields = mapOf("auth[token]" to "value"))
        assertTrue(CustomProviderSchema.encode(sensitiveExtras) is ProviderResult.Invalid)
        assertTrue(CustomProviderSchema.decode(BROWSER_JSON.replace("\"a\"", "\"auth[token]\"")) is ProviderResult.Invalid)
        val openai = SearchProviderConfig.RestVisionProvider(
            id = "custom-openai",
            displayName = "OpenAI",
            endpointUrl = "https://api.example.com/v1/chat/completions",
            requestFormat = VisionRequestFormat.OPENAI_CHAT_COMPLETIONS,
            modelName = "gpt-4o",
            systemPrompt = "line\n\"quoted\"",
            credential = CredentialBinding("vision-key"),
        )
        val encodedOpenAi = (CustomProviderSchema.encode(openai) as ProviderResult.Valid).value
        assertEquals(OPENAI_JSON, encodedOpenAi)
        assertTrue(encodedOpenAi.contains("\"ref\":\"vision-key\""))
        assertFalse(encodedOpenAi.contains("apiKey"))
        assertFalse(encodedOpenAi.contains("secret"))
        assertFalse(encodedOpenAi.contains("token"))
        assertFalse(encodedOpenAi.contains("password"))
        val multipart = SearchProviderConfig.RestVisionProvider(
            id = "custom-multipart",
            displayName = "Multipart",
            endpointUrl = "https://api.example.com/v1/vision",
            requestFormat = VisionRequestFormat.MULTIPART_REDIRECT_JSON,
            jsonRedirectPath = "results[0].url",
            fileFormFieldName = "image",
        )
        assertEquals(MULTIPART_JSON, (CustomProviderSchema.encode(multipart) as ProviderResult.Valid).value)
    }

    @Test
    fun roundTripsEveryVariantAndReorderedKeys() {
        val samples = listOf(
            text(),
            text().copy(displayName = "Recherche 日本 🔍"),
            SearchProviderConfig.BrowserFormVisualProvider(
                id = "custom-visual",
                displayName = "Visual",
                uploadEndpointUrl = "https://lens.google.com/v3/upload",
                fileFormFieldName = "encoded_image",
                extraFormFields = mapOf("z" to "1", "a" to "2"),
                multimodalQueryFieldName = "q",
                textSearchUrlTemplate = "https://www.google.com/search?q={searchTerms}",
            ),
            SearchProviderConfig.RestVisionProvider(
                id = "custom-openai",
                displayName = "OpenAI",
                endpointUrl = "https://api.example.com/v1/chat/completions",
                requestFormat = VisionRequestFormat.OPENAI_CHAT_COMPLETIONS,
                modelName = "gpt-4o",
                systemPrompt = "line\n\"quoted\"",
                credential = CredentialBinding("vision-key"),
            ),
            SearchProviderConfig.RestVisionProvider(
                id = "custom-multipart",
                displayName = "Multipart",
                endpointUrl = "https://api.example.com/v1/vision",
                requestFormat = VisionRequestFormat.MULTIPART_REDIRECT_JSON,
                jsonRedirectPath = "results[0].url",
                fileFormFieldName = "image",
            ),
        )
        for (sample in samples) {
            val encoded = (CustomProviderSchema.encode(sample) as ProviderResult.Valid).value
            assertEquals(ProviderResult.Valid(sample), CustomProviderSchema.decode(encoded))
            val again = CustomProviderSchema.encode((CustomProviderSchema.decode(encoded) as ProviderResult.Valid).value) as ProviderResult.Valid
            assertEquals(encoded, again.value)
        }
        val reordered = """{"version":1,"provider":{"displayName":"DDG Lite","textSearchUrlTemplate":"https://lite.duckduckgo.com/lite/?q={searchTerms}","id":"custom-ddg-lite","type":"text"},"schema":"com.snipsearch.provider"}"""
        assertEquals(ProviderResult.Valid(text()), CustomProviderSchema.decode(reordered))
    }

    @Test
    fun encodesAndDecodesLists() {
        val configs = listOf(
            text(),
            text().copy(id = "custom-two", displayName = "Two"),
            text().copy(id = "custom-three", displayName = "Three"),
            SearchProviderConfig.RestVisionProvider(
                id = "custom-escaped",
                displayName = "Escaped",
                endpointUrl = "https://api.example.com/v1/chat/completions",
                requestFormat = VisionRequestFormat.OPENAI_CHAT_COMPLETIONS,
                modelName = "model",
                systemPrompt = "line\n\"quoted\"\\\u2028",
            ),
        )
        val encoded = (CustomProviderSchema.encodeList(configs) as ProviderResult.Valid).value
        assertTrue(encoded.contains("line\\n"))
        assertEquals(ProviderResult.Valid(configs), CustomProviderSchema.decodeList(encoded))
        val again = CustomProviderSchema.encodeList((CustomProviderSchema.decodeList(encoded) as ProviderResult.Valid).value) as ProviderResult.Valid
        assertEquals(encoded, again.value)
        val empty = (CustomProviderSchema.encodeList(emptyList()) as ProviderResult.Valid).value
        assertEquals("""{"schema":"com.snipsearch.providers","version":1,"providers":[]}""", empty)
        assertEquals(ProviderResult.Valid(emptyList<SearchProviderConfig>()), CustomProviderSchema.decodeList(empty))
        val tooMany = CustomProviderSchema.encodeList(List(33) { text().copy(id = "custom-$it") }) as ProviderResult.Invalid
        assertEquals(ProviderConfigErrorCode.TOO_MANY_PROVIDERS to "providers", tooMany.errors.single().let { it.code to it.field })
        val duplicate = CustomProviderSchema.encodeList(listOf(text(), text().copy(displayName = "Other"))) as ProviderResult.Invalid
        assertTrue(duplicate.errors.any { it.code == ProviderConfigErrorCode.DUPLICATE_ID && it.field == "providers[1].id" })
        val invalid = CustomProviderSchema.encodeList(listOf(text().copy(textSearchUrlTemplate = "http://example.com/?q={searchTerms}"))) as ProviderResult.Invalid
        assertEquals("providers[0].textSearchUrlTemplate", invalid.errors.single().field)

        val largePrompt = "line\n\"quoted\"\\\u2028".repeat(180)
        val oversized = CustomProviderSchema.encodeList(
            List(CustomProviderSchema.MAX_PROVIDERS) { index ->
                SearchProviderConfig.RestVisionProvider(
                    id = "custom-large-$index",
                    displayName = "Large $index",
                    endpointUrl = "https://api.example.com/v1/chat/completions",
                    requestFormat = VisionRequestFormat.OPENAI_CHAT_COMPLETIONS,
                    modelName = "model",
                    systemPrompt = largePrompt,
                )
            },
        ) as ProviderResult.Invalid
        assertEquals(ProviderConfigErrorCode.INPUT_TOO_LARGE, oversized.errors.single().code)
        assertEquals("$", oversized.errors.single().field)
        assertEquals(
            "The serialized provider configuration exceeds the maximum size.",
            oversized.errors.single().message,
        )
    }

    @Test
    fun rejectsMalformedDocuments() {
        assertDecode("{", ProviderConfigErrorCode.MALFORMED_JSON, "$")
        assertDecode("[]", ProviderConfigErrorCode.TYPE_MISMATCH, "$")
        assertDecode(envelope(schema = "\"com.example.other\""), ProviderConfigErrorCode.UNSUPPORTED_SCHEMA, "schema")
        assertDecode(envelope(schema = "com.example.other"), ProviderConfigErrorCode.MALFORMED_JSON, "$")
        assertDecode(envelope(version = "2"), ProviderConfigErrorCode.UNSUPPORTED_VERSION, "version")
        assertDecode(envelope(version = "\"1\""), ProviderConfigErrorCode.TYPE_MISMATCH, "version")
        assertDecode("""{"schema":"com.snipsearch.provider","version":1}""", ProviderConfigErrorCode.MISSING_REQUIRED, "provider")
        assertDecode(
            """{"schema":"com.snipsearch.provider","version":1,"extra":1,"provider":{"type":"text","id":"custom-ddg-lite","displayName":"DDG Lite","textSearchUrlTemplate":"https://lite.duckduckgo.com/lite/?q={searchTerms}"}}""",
            ProviderConfigErrorCode.UNKNOWN_FIELD,
            "extra",
        )
        assertDecode(provider("\"type\":\"nope\""), ProviderConfigErrorCode.UNKNOWN_PROVIDER_TYPE, "provider.type")
        assertDecode(
            provider("\"type\":\"text\",\"id\":\"custom-ddg-lite\",\"displayName\":\"DDG Lite\",\"textSearchUrlTemplate\":\"https://lite.duckduckgo.com/lite/?q={searchTerms}\",\"endpointUrl\":\"https://example.com/\""),
            ProviderConfigErrorCode.UNKNOWN_FIELD,
            "provider.endpointUrl",
        )
        for (key in listOf("apiKey", "api_key", "token", "password", "bearerToken", "authorization")) {
            val result = CustomProviderSchema.decode(
                provider("\"type\":\"text\",\"id\":\"custom-ddg-lite\",\"displayName\":\"DDG Lite\",\"textSearchUrlTemplate\":\"https://lite.duckduckgo.com/lite/?q={searchTerms}\",\"$key\":\"x\""),
            ) as ProviderResult.Invalid
            assertTrue(result.errors.any { it.code == ProviderConfigErrorCode.PLAINTEXT_CREDENTIAL && it.field == "provider.$key" })
        }
        val secret = CustomProviderSchema.decode(
            provider("\"type\":\"rest_vision\",\"id\":\"custom-openai\",\"displayName\":\"OpenAI\",\"endpointUrl\":\"https://api.example.com/v1/chat/completions\",\"requestFormat\":\"openai_chat_completions\",\"modelName\":\"gpt-4o\",\"credential\":{\"ref\":\"k\",\"value\":\"sk-SECRET\"}"),
        ) as ProviderResult.Invalid
        assertTrue(secret.errors.any { it.code == ProviderConfigErrorCode.PLAINTEXT_CREDENTIAL && it.field == "provider.credential.value" })
        assertTrue(secret.errors.none { it.message.contains("sk-SECRET") || it.field.contains("sk-SECRET") })
        assertDecode(provider("\"type\":\"text\",\"id\":null,\"displayName\":\"DDG Lite\",\"textSearchUrlTemplate\":\"https://example.com/?q={searchTerms}\""), ProviderConfigErrorCode.TYPE_MISMATCH, "provider.id")
        assertDecode(provider("\"type\":\"text\",\"id\":\"custom-ddg-lite\",\"textSearchUrlTemplate\":\"https://lite.duckduckgo.com/lite/?q={searchTerms}\""), ProviderConfigErrorCode.MISSING_REQUIRED, "provider.displayName")
        assertDecode(
            provider("\"type\":\"rest_vision\",\"id\":\"custom-openai\",\"displayName\":\"OpenAI\",\"endpointUrl\":\"https://api.example.com/v1\",\"requestFormat\":\"OPENAI_CHAT_COMPLETIONS\",\"modelName\":\"gpt-4o\""),
            ProviderConfigErrorCode.INVALID_FIELD_VALUE,
            "provider.requestFormat",
        )
        assertDecode(
            provider("\"type\":\"browser_form_visual\",\"id\":\"custom-visual\",\"displayName\":\"Visual\",\"uploadEndpointUrl\":\"https://example.com/upload\",\"fileFormFieldName\":\"image\",\"extraFormFields\":{}"),
            ProviderConfigErrorCode.INVALID_FIELD_VALUE,
            "provider.extraFormFields",
        )
        assertDecode(
            provider("\"type\":\"text\",\"id\":\"google\",\"displayName\":\"Google\",\"textSearchUrlTemplate\":\"https://www.google.com/search?q={searchTerms}\""),
            ProviderConfigErrorCode.RESERVED_ID,
            "provider.id",
        )
        assertDecode(
            provider("\"type\":\"text\",\"id\":\"custom-ddg-lite\",\"displayName\":\"DDG Lite\",\"textSearchUrlTemplate\":\"http://example.com/search?q={searchTerms}\""),
            ProviderConfigErrorCode.UNSAFE_SCHEME,
            "provider.textSearchUrlTemplate",
        )
        assertDecode("{" + " ".repeat(65_537) + "}", ProviderConfigErrorCode.INPUT_TOO_LARGE, "$")
    }

    @Test
    fun encodePrefixesValidationErrors() {
        val invalid = CustomProviderSchema.encode(
            SearchProviderConfig.TextOnlyProvider("Google", "Name", "https://example.com/search?q={searchTerms}"),
        ) as ProviderResult.Invalid
        assertEquals("provider.id", invalid.errors.single().field)
        val reserved = CustomProviderSchema.encode(BuiltInProviders.GOOGLE) as ProviderResult.Invalid
        assertEquals(ProviderConfigErrorCode.RESERVED_ID to "provider.id", reserved.errors.single().let { it.code to it.field })
    }

    private fun text() = SearchProviderConfig.TextOnlyProvider(
        id = "custom-ddg-lite",
        displayName = "DDG Lite",
        textSearchUrlTemplate = "https://lite.duckduckgo.com/lite/?q={searchTerms}",
    )

    private fun envelope(schema: String = "\"com.snipsearch.provider\"", version: String = "1") =
        """{"schema":$schema,"version":$version,"provider":{"type":"text","id":"custom-ddg-lite","displayName":"DDG Lite","textSearchUrlTemplate":"https://lite.duckduckgo.com/lite/?q={searchTerms}"}}"""

    private fun provider(body: String) =
        """{"schema":"com.snipsearch.provider","version":1,"provider":{$body}}"""

    private fun assertDecode(json: String, code: ProviderConfigErrorCode, field: String) {
        val result = CustomProviderSchema.decode(json) as ProviderResult.Invalid
        assertEquals(json.take(80), listOf(code to field), result.errors.map { it.code to it.field })
    }

    private companion object {
        const val TEXT_JSON =
            """{"schema":"com.snipsearch.provider","version":1,"provider":{"type":"text","id":"custom-ddg-lite","displayName":"DDG Lite","textSearchUrlTemplate":"https://lite.duckduckgo.com/lite/?q={searchTerms}"}}"""
        const val BROWSER_JSON =
            """{"schema":"com.snipsearch.provider","version":1,"provider":{"type":"browser_form_visual","id":"custom-visual","displayName":"Visual","uploadEndpointUrl":"https://lens.google.com/v3/upload","fileFormFieldName":"encoded_image","extraFormFields":{"a":"2","z":"1"},"multimodalQueryFieldName":"q","textSearchUrlTemplate":"https://www.google.com/search?q={searchTerms}"}}"""
        const val OPENAI_JSON =
            """{"schema":"com.snipsearch.provider","version":1,"provider":{"type":"rest_vision","id":"custom-openai","displayName":"OpenAI","endpointUrl":"https://api.example.com/v1/chat/completions","requestFormat":"openai_chat_completions","modelName":"gpt-4o","systemPrompt":"line\n\"quoted\"","credential":{"ref":"vision-key","headerName":"Authorization","scheme":"bearer"}}}"""
        const val MULTIPART_JSON =
            """{"schema":"com.snipsearch.provider","version":1,"provider":{"type":"rest_vision","id":"custom-multipart","displayName":"Multipart","endpointUrl":"https://api.example.com/v1/vision","requestFormat":"multipart_redirect_json","jsonRedirectPath":"results[0].url","fileFormFieldName":"image"}}"""
    }
}
