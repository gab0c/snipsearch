package com.snipsearch.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderValidatorTest {
    @Test
    fun builtInsPassOnlyWhenReservedIdsAreAllowed() {
        for (preset in BuiltInProviders.all) {
            assertTrue(preset.config.id, ProviderValidator.validate(preset.config, allowReservedIds = true).isEmpty())
            val errors = ProviderValidator.validate(preset.config)
            assertEquals(listOf(ProviderConfigErrorCode.RESERVED_ID to "id"), errors.map { it.code to it.field })
        }
    }

    @Test
    fun checksIds() {
        val valid = text(id = "custom-1")
        assertTrue(ProviderValidator.validate(valid).isEmpty())
        for (id in listOf("", "Google", "-x", "x-", "a".repeat(33), "a_b")) {
            assertField(text(id = id), ProviderConfigErrorCode.INVALID_ID, "id")
        }
    }

    @Test
    fun checksDisplayNames() {
        assertTrue(ProviderValidator.validate(text(displayName = "Moteur de recherche 🔍")).isEmpty())
        for (name in listOf("", " x", "x ", "a".repeat(65), "a\u0007", "evil\u202Egoogle", "a\u2066b")) {
            assertField(text(displayName = name), ProviderConfigErrorCode.INVALID_DISPLAY_NAME, "displayName")
        }
        assertField(text(displayName = "a\uDC00"), ProviderConfigErrorCode.MALFORMED_TEXT, "displayName")
    }

    @Test
    fun checksBrowserFormFields() {
        assertField(browser(file = "auth[token]"), ProviderConfigErrorCode.PLAINTEXT_CREDENTIAL, "fileFormFieldName")
        assertField(browser(file = "api_key"), ProviderConfigErrorCode.PLAINTEXT_CREDENTIAL, "fileFormFieldName")
        assertField(browser(file = "bad field"), ProviderConfigErrorCode.INVALID_FIELD_NAME, "fileFormFieldName")
        assertField(
            browser(extra = mapOf("encoded_image" to "1")),
            ProviderConfigErrorCode.DUPLICATE_FIELD,
            "extraFormFields.encoded_image",
        )
        assertField(
            browser(extra = mapOf("api_key" to "1")),
            ProviderConfigErrorCode.PLAINTEXT_CREDENTIAL,
            "extraFormFields.api_key",
        )
        val many = (1..17).associate { "f$it" to "v" }
        assertField(browser(extra = many), ProviderConfigErrorCode.TOO_MANY_FIELDS, "extraFormFields")
        assertField(
            browser(extra = mapOf("note" to "a".repeat(513))),
            ProviderConfigErrorCode.VALUE_TOO_LONG,
            "extraFormFields.note",
        )
        assertField(
            browser(extra = mapOf("note" to "a\nb")),
            ProviderConfigErrorCode.INVALID_FIELD_VALUE,
            "extraFormFields.note",
        )
        assertField(
            browser(file = "image", multimodal = "image"),
            ProviderConfigErrorCode.DUPLICATE_FIELD,
            "multimodalQueryFieldName",
        )
        assertField(
            browser(upload = "https://example.com/{x}"),
            ProviderConfigErrorCode.UNSUPPORTED_PLACEHOLDER,
            "uploadEndpointUrl",
        )
    }

    @Test
    fun checksOpenAiVisionFields() {
        assertField(rest(model = null), ProviderConfigErrorCode.MISSING_REQUIRED, "modelName")
        assertField(rest(jsonPath = "results[0].url"), ProviderConfigErrorCode.FIELD_NOT_APPLICABLE, "jsonRedirectPath")
        assertField(rest(file = "image"), ProviderConfigErrorCode.FIELD_NOT_APPLICABLE, "fileFormFieldName")
        assertField(
            rest(endpoint = "https://api.example.com/v1?x=1"),
            ProviderConfigErrorCode.QUERY_NOT_ALLOWED,
            "endpointUrl",
        )
        assertField(
            rest(endpoint = "https://router.lan/v1"),
            ProviderConfigErrorCode.LOCAL_NETWORK_NOT_SUPPORTED,
            "endpointUrl",
        )
        assertTrue(ProviderValidator.validate(rest(prompt = "keep\n\tgoing")).isEmpty())
        assertField(rest(prompt = "bad\u0000"), ProviderConfigErrorCode.INVALID_FIELD_VALUE, "systemPrompt")
        assertField(rest(prompt = "a".repeat(4001)), ProviderConfigErrorCode.VALUE_TOO_LONG, "systemPrompt")
    }

    @Test
    fun checksMultipartVisionFields() {
        val missing = ProviderValidator.validate(
            multipart(path = null, file = null),
        )
        assertEquals(
            listOf(
                ProviderConfigErrorCode.MISSING_REQUIRED to "jsonRedirectPath",
                ProviderConfigErrorCode.MISSING_REQUIRED to "fileFormFieldName",
            ),
            missing.map { it.code to it.field },
        )
        assertTrue(ProviderValidator.validate(multipart(path = "results[0].url", file = "image")).isEmpty())
        for (path in listOf("a..b", "1a", "a[1234]")) {
            assertField(multipart(path = path, file = "image"), ProviderConfigErrorCode.INVALID_FIELD_VALUE, "jsonRedirectPath")
        }
        assertField(
            multipart(path = "results[0].url", file = "image", model = "gpt-4o"),
            ProviderConfigErrorCode.FIELD_NOT_APPLICABLE,
            "modelName",
        )
    }

    @Test
    fun checksCredentialBindings() {
        assertField(rest(credential = CredentialBinding("Bad Ref")), ProviderConfigErrorCode.INVALID_CREDENTIAL_REF, "credential.ref")
        for (header in listOf("Cookie", "host", "Sec-Fetch-Mode", "Proxy-Authorization")) {
            assertField(
                rest(credential = CredentialBinding("vision-key", headerName = header)),
                ProviderConfigErrorCode.FORBIDDEN_HEADER,
                "credential.headerName",
            )
        }
        assertTrue(ProviderValidator.validate(rest(credential = CredentialBinding("vision-key", headerName = "X-Goog-Api-Key"))).isEmpty())
        assertTrue(ProviderValidator.validate(rest(credential = CredentialBinding("vision-key"))).isEmpty())
    }

    @Test
    fun reportsErrorsInFieldOrder() {
        val errors = ProviderValidator.validate(
            SearchProviderConfig.TextOnlyProvider(
                id = "Google",
                displayName = " x",
                textSearchUrlTemplate = "https://example.com/search?q=%s",
            ),
        )
        assertEquals(
            listOf(
                ProviderConfigErrorCode.INVALID_ID to "id",
                ProviderConfigErrorCode.INVALID_DISPLAY_NAME to "displayName",
                ProviderConfigErrorCode.MISSING_PLACEHOLDER to "textSearchUrlTemplate",
            ),
            errors.map { it.code to it.field },
        )
    }

    private fun text(
        id: String = "custom-1",
        displayName: String = "Custom",
    ) = SearchProviderConfig.TextOnlyProvider(
        id = id,
        displayName = displayName,
        textSearchUrlTemplate = "https://example.com/search?q={searchTerms}",
    )

    private fun browser(
        upload: String = "https://example.com/upload",
        file: String = "encoded_image",
        extra: Map<String, String> = emptyMap(),
        multimodal: String? = null,
    ) = SearchProviderConfig.BrowserFormVisualProvider(
        id = "custom-visual",
        displayName = "Visual",
        uploadEndpointUrl = upload,
        fileFormFieldName = file,
        extraFormFields = extra,
        multimodalQueryFieldName = multimodal,
    )

    private fun rest(
        endpoint: String = "https://api.example.com/v1",
        model: String? = "gpt-4o",
        prompt: String? = null,
        jsonPath: String? = null,
        file: String? = null,
        credential: CredentialBinding? = null,
    ) = SearchProviderConfig.RestVisionProvider(
        id = "custom-vision",
        displayName = "Vision",
        endpointUrl = endpoint,
        requestFormat = VisionRequestFormat.OPENAI_CHAT_COMPLETIONS,
        modelName = model,
        systemPrompt = prompt,
        jsonRedirectPath = jsonPath,
        fileFormFieldName = file,
        credential = credential,
    )

    private fun multipart(path: String?, file: String?, model: String? = null) = SearchProviderConfig.RestVisionProvider(
        id = "custom-multipart",
        displayName = "Multipart",
        endpointUrl = "https://api.example.com/v1",
        requestFormat = VisionRequestFormat.MULTIPART_REDIRECT_JSON,
        modelName = model,
        jsonRedirectPath = path,
        fileFormFieldName = file,
    )

    private fun assertField(config: SearchProviderConfig, code: ProviderConfigErrorCode, field: String) {
        val errors = ProviderValidator.validate(config)
        assertEquals(config.toString(), listOf(code to field), errors.map { it.code to it.field })
    }
}
