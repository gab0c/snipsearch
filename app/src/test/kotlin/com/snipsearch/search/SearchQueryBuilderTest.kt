package com.snipsearch.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchQueryBuilderTest {
    @Test
    fun encodesQueryComponents() {
        assertEquals("a%20b", SearchQueryBuilder.encodeQueryComponent("a b"))
        assertEquals("C%2B%2B%20%26%20%3F%3D%23%2F%25", SearchQueryBuilder.encodeQueryComponent("C++ & ?=#/%"))
        assertEquals("caf%C3%A9", SearchQueryBuilder.encodeQueryComponent("café"))
        assertEquals("%F0%9F%94%8D", SearchQueryBuilder.encodeQueryComponent("🔍"))
        assertEquals("%E6%97%A5%E6%9C%AC", SearchQueryBuilder.encodeQueryComponent("日本"))
        assertEquals("%D9%85%D8%B1%D8%AD%D8%A8%D8%A7", SearchQueryBuilder.encodeQueryComponent("مرحبا"))
        assertEquals("-._~", SearchQueryBuilder.encodeQueryComponent("-._~"))
        assertEquals("%21%2A%27%28%29", SearchQueryBuilder.encodeQueryComponent("!*'()"))
        assertEquals("%2B", SearchQueryBuilder.encodeQueryComponent("+"))
    }

    @Test
    fun normalizesWhitespaceAndRejectsBadQueries() {
        assertQuery("x".repeat(2050) + "\uD800", ProviderConfigErrorCode.MALFORMED_TEXT)
        assertEquals("x y", validQuery(" ".repeat(100000) + "x" + " ".repeat(100000) + "y"))
        assertEquals("line1 line2", validQuery("  line1\n\tline2\r\n "))
        assertEquals("x y", validQuery("\u2028x\u00A0y"))
        assertQuery("".let { it }, ProviderConfigErrorCode.EMPTY_QUERY)
        assertQuery("   \n", ProviderConfigErrorCode.EMPTY_QUERY)
        assertQuery("a\uD800b", ProviderConfigErrorCode.MALFORMED_TEXT)
        assertQuery("x".repeat(2049), ProviderConfigErrorCode.QUERY_TOO_LONG)
        assertEquals("x".repeat(2048), validQuery("x".repeat(2048)))
    }

    @Test
    fun buildsPresetTextUrls() {
        assertUrl(BuiltInProviders.GOOGLE, "C++ & ?=#/%", "https://www.google.com/search?q=C%2B%2B%20%26%20%3F%3D%23%2F%25")
        assertUrl(BuiltInProviders.DUCKDUCKGO, "x&tbm=isch", "https://duckduckgo.com/?q=x%26tbm%3Disch")
        assertUrl(BuiltInProviders.BRAVE, "privacy first", "https://search.brave.com/search?q=privacy%20first")
        assertUrl(BuiltInProviders.STARTPAGE, "café", "https://www.startpage.com/sp/search?query=caf%C3%A9")
        assertUrl(BuiltInProviders.GOOGLE_LENS, "cat", "https://www.google.com/search?q=cat")
        assertUrl(BuiltInProviders.YANDEX_IMAGES, "日本", "https://yandex.com/search/?text=%E6%97%A5%E6%9C%AC")
    }

    @Test
    fun keepsReservedCharactersInsideTheQueryParameter() {
        for (provider in listOf(BuiltInProviders.GOOGLE, BuiltInProviders.DUCKDUCKGO)) {
            for (query in listOf("#frag", "a&b=c")) {
                val url = (SearchQueryBuilder.textSearchUrl(provider, query) as ProviderResult.Valid).value
                val value = url.substringAfter("q=")
                assertFalse(url, value.contains("#"))
                assertFalse(url, value.contains("&"))
                assertFalse(url, value.contains("="))
            }
        }
    }

    @Test
    fun rejectsProvidersWithoutATextTemplate() {
        val rest = SearchProviderConfig.RestVisionProvider(
            id = "custom-vision",
            displayName = "Vision",
            endpointUrl = "https://api.example.com/v1",
            requestFormat = VisionRequestFormat.OPENAI_CHAT_COMPLETIONS,
            modelName = "gpt-4o",
        )
        assertUrlError(rest, ProviderConfigErrorCode.UNSUPPORTED_CAPABILITY, "capabilities")
        val visual = SearchProviderConfig.BrowserFormVisualProvider(
            id = "custom-visual",
            displayName = "Visual",
            uploadEndpointUrl = "https://lens.google.com/v3/upload",
            fileFormFieldName = "encoded_image",
            textSearchUrlTemplate = null,
        )
        assertUrlError(visual, ProviderConfigErrorCode.UNSUPPORTED_CAPABILITY, "capabilities")
    }

    @Test
    fun checksTemplates() {
        val oldSentinelStaticTemplate =
            "https://snipsearchqueryplaceholder.example/search?q={searchTerms}&x=snipsearchqueryplaceholder"
        assertEquals(null, SearchQueryBuilder.checkTemplate(oldSentinelStaticTemplate, "template"))
        assertUrl(
            SearchProviderConfig.TextOnlyProvider("custom-sentinel", "Sentinel", oldSentinelStaticTemplate),
            "cat",
            "https://snipsearchqueryplaceholder.example/search?q=cat&x=snipsearchqueryplaceholder",
        )
        assertTemplate("https://example.com/search?q=%s", ProviderConfigErrorCode.MISSING_PLACEHOLDER)
        assertTemplate("https://example.com/search?q={query}", ProviderConfigErrorCode.MISSING_PLACEHOLDER)
        assertTemplate(
            "https://example.com/search?q={searchTerms}&x={searchTerms}",
            ProviderConfigErrorCode.MULTIPLE_PLACEHOLDERS,
        )
        assertTemplate(
            "https://example.com/search?q={searchTerms}&l={lang}",
            ProviderConfigErrorCode.UNSUPPORTED_PLACEHOLDER,
        )
        assertTemplate("https://{searchTerms}.example.com/?q=1", ProviderConfigErrorCode.PLACEHOLDER_POSITION)
        assertTemplate("https://example.com/{searchTerms}?q=1", ProviderConfigErrorCode.PLACEHOLDER_POSITION)
        assertTemplate("https://example.com/search?q=foo{searchTerms}", ProviderConfigErrorCode.PLACEHOLDER_POSITION)
        assertTemplate("https://example.com/search?q={searchTerms}tail", ProviderConfigErrorCode.PLACEHOLDER_POSITION)
        assertTemplate("https://example.com/search?{searchTerms}=1", ProviderConfigErrorCode.PLACEHOLDER_POSITION)
        assertTemplate("http://example.com/search?q={searchTerms}", ProviderConfigErrorCode.UNSAFE_SCHEME)
        assertTemplate("https://example.com/search?q={searchTerms}#x", ProviderConfigErrorCode.FRAGMENT_NOT_ALLOWED)
        assertTemplate("https://example.com/search?api_key=1&q={searchTerms}", ProviderConfigErrorCode.PLAINTEXT_CREDENTIAL)
        val tooLongPrefix = "https://example.com/?q={searchTerms}&x="
        val tooLong = tooLongPrefix + "x".repeat(ProviderEndpointPolicy.MAX_URL_LENGTH + 1 - tooLongPrefix.length)
        assertTemplate(tooLong, ProviderConfigErrorCode.URL_TOO_LONG)
    }

    @Test
    fun queryErrorsDoNotEchoTheQuery() {
        val result = SearchQueryBuilder.textSearchUrl(BuiltInProviders.GOOGLE, "SECRETQUERY\uD800") as ProviderResult.Invalid
        assertEquals(ProviderConfigErrorCode.MALFORMED_TEXT, result.errors.single().code)
        assertFalse(result.errors.single().message.contains("SECRETQUERY"))
        assertFalse(result.errors.single().field.contains("SECRETQUERY"))
    }

    private fun validQuery(raw: String): String = (SearchQueryBuilder.normalizeQuery(raw) as ProviderResult.Valid).value

    private fun assertQuery(raw: String, code: ProviderConfigErrorCode) {
        val result = SearchQueryBuilder.normalizeQuery(raw) as ProviderResult.Invalid
        assertEquals(code, result.errors.single().code)
        assertEquals("query", result.errors.single().field)
    }

    private fun assertUrl(provider: SearchProviderConfig, query: String, expected: String) {
        val result = SearchQueryBuilder.textSearchUrl(provider, query) as ProviderResult.Valid
        assertEquals(expected, result.value)
    }

    private fun assertUrlError(provider: SearchProviderConfig, code: ProviderConfigErrorCode, field: String) {
        val result = SearchQueryBuilder.textSearchUrl(provider, "cat") as ProviderResult.Invalid
        assertEquals(code, result.errors.single().code)
        assertEquals(field, result.errors.single().field)
    }

    private fun assertTemplate(template: String, code: ProviderConfigErrorCode) {
        val error = SearchQueryBuilder.checkTemplate(template, "textSearchUrlTemplate")
        assertEquals(template, code, error?.code)
        assertEquals("textSearchUrlTemplate", error?.field)
        assertTrue(error?.message?.contains(template) != true)
    }
}
