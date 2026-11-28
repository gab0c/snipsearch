package com.snipsearch.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BuiltInProvidersTest {
    @Test
    fun presetsHaveStableIdsAndTemplates() {
        assertEquals("google", BuiltInProviders.GOOGLE.id)
        assertEquals("Google", BuiltInProviders.GOOGLE.displayName)
        assertEquals("https://www.google.com/search?q={searchTerms}", BuiltInProviders.GOOGLE.textSearchUrlTemplate)
        assertEquals("duckduckgo", BuiltInProviders.DUCKDUCKGO.id)
        assertEquals("DuckDuckGo", BuiltInProviders.DUCKDUCKGO.displayName)
        assertEquals("https://duckduckgo.com/?q={searchTerms}", BuiltInProviders.DUCKDUCKGO.textSearchUrlTemplate)
        assertEquals("brave", BuiltInProviders.BRAVE.id)
        assertEquals("Brave Search", BuiltInProviders.BRAVE.displayName)
        assertEquals("https://search.brave.com/search?q={searchTerms}", BuiltInProviders.BRAVE.textSearchUrlTemplate)
        assertEquals("startpage", BuiltInProviders.STARTPAGE.id)
        assertEquals("Startpage", BuiltInProviders.STARTPAGE.displayName)
        assertEquals("https://www.startpage.com/sp/search?query={searchTerms}", BuiltInProviders.STARTPAGE.textSearchUrlTemplate)
        val ids = BuiltInProviders.all.map { it.config.id }
        assertEquals(ids.toSet(), BuiltInProviders.reservedIds)
        assertEquals(7, BuiltInProviders.reservedIds.size)
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(BuiltInProviders.all[0], BuiltInProviders.byId("google"))
        assertNull(BuiltInProviders.byId("missing"))
    }

    @Test
    fun availabilityAndCapabilitiesMatchThePresetKind() {
        assertTrue(BuiltInProviders.textPresets.all {
            it.availability == ProviderAvailability.TEXT_SEARCH_READY &&
                it.endpointSource == EndpointSource.DOCUMENTED &&
                it.config.capabilities == setOf(ProviderCapability.TEXT)
        })
        assertEquals(
            "Standard Google web search URL; matches TextSearchPolicy.googleUrl.",
            BuiltInProviders.textPresets[0].note,
        )
        assertTrue(BuiltInProviders.roadmapPresets.all {
            it.availability == ProviderAvailability.ROADMAP &&
                it.endpointSource == EndpointSource.UNOFFICIAL &&
                it.config.capabilities.contains(ProviderCapability.VISUAL)
        })
        assertEquals(
            listOf(ProviderCapability.TEXT, ProviderCapability.VISUAL, ProviderCapability.MULTIMODAL_PROMPT),
            BuiltInProviders.GOOGLE_LENS.capabilities.toList(),
        )
        assertEquals(
            listOf(ProviderCapability.TEXT, ProviderCapability.VISUAL),
            BuiltInProviders.YANDEX_IMAGES.capabilities.toList(),
        )
        assertEquals(
            listOf(ProviderCapability.TEXT, ProviderCapability.VISUAL),
            BuiltInProviders.BING_VISUAL.capabilities.toList(),
        )
        assertEquals("https://lens.google.com", BuiltInProviders.GOOGLE_LENS.originUrl)
        assertEquals("encoded_image", BuiltInProviders.GOOGLE_LENS.fileFormFieldName)
        assertEquals("q", BuiltInProviders.GOOGLE_LENS.multimodalQueryFieldName)
        assertEquals("upfile", BuiltInProviders.YANDEX_IMAGES.fileFormFieldName)
        assertEquals("imageBin", BuiltInProviders.BING_VISUAL.fileFormFieldName)
    }

    @Test
    fun configurableTemplatesStayDistinctFromReservedIds() {
        val searx = BuiltInProviders.configurableTemplates.single { it.defaultId == "searxng" }
        assertEquals(setOf(ProviderCapability.TEXT), searx.capabilities)
        assertFalse(searx.capabilities.contains(ProviderCapability.VISUAL))
        assertEquals(ProviderAvailability.TEXT_SEARCH_READY, searx.availability)
        val vision = BuiltInProviders.configurableTemplates.single { it.defaultId == "openai-vision" }
        assertEquals(setOf(ProviderCapability.VISUAL, ProviderCapability.MULTIMODAL_PROMPT), vision.capabilities)
        assertEquals(ProviderAvailability.ROADMAP, vision.availability)
        assertFalse(BuiltInProviders.reservedIds.contains(BuiltInProviders.SEARXNG_DEFAULT_ID))
        assertFalse(BuiltInProviders.reservedIds.contains(BuiltInProviders.OPENAI_VISION_DEFAULT_ID))
    }

    @Test
    fun searxngNormalizesTheBaseAndRejectsUnsafeInstances() {
        val slashed = BuiltInProviders.searxng("https://searx.example.org/") as ProviderResult.Valid
        assertEquals("https://searx.example.org/search?q={searchTerms}", slashed.value.textSearchUrlTemplate)
        val nested = BuiltInProviders.searxng("https://example.org/searx") as ProviderResult.Valid
        assertEquals("https://example.org/searx/search?q={searchTerms}", nested.value.textSearchUrlTemplate)
        val url = SearchQueryBuilder.textSearchUrl(slashed.value, "a b") as ProviderResult.Valid
        assertEquals("https://searx.example.org/search?q=a%20b", url.value)
        assertFactory("https://searx.example.org/?x=1", ProviderConfigErrorCode.QUERY_NOT_ALLOWED, "instanceBaseUrl")
        assertFactory("http://searx.example.org/", ProviderConfigErrorCode.UNSAFE_SCHEME, "instanceBaseUrl")
        assertFactory("https://searx.lan/", ProviderConfigErrorCode.LOCAL_NETWORK_NOT_SUPPORTED, "instanceBaseUrl")
        assertFactory("https://1.2.3.4/", ProviderConfigErrorCode.IP_LITERAL_NOT_ALLOWED, "instanceBaseUrl")
        val reserved = BuiltInProviders.searxng("https://searx.example.org/", id = "google") as ProviderResult.Invalid
        assertEquals(ProviderConfigErrorCode.RESERVED_ID, reserved.errors.single().code)
        assertEquals("id", reserved.errors.single().field)
    }

    @Test
    fun openAiFactoryValidatesTheEndpoint() {
        val created = BuiltInProviders.openAiCompatibleVision(
            "https://api.example.com/v1/chat/completions",
            "gpt-4o",
        ) as ProviderResult.Valid
        assertEquals(VisionRequestFormat.OPENAI_CHAT_COMPLETIONS, created.value.requestFormat)
        assertEquals("gpt-4o", created.value.modelName)
        val local = BuiltInProviders.openAiCompatibleVision("https://model.local/v1", "gpt-4o") as ProviderResult.Invalid
        assertEquals(ProviderConfigErrorCode.LOCAL_NETWORK_NOT_SUPPORTED, local.errors.single().code)
        val user = BuiltInProviders.openAiCompatibleVision("https://user@api.example.com/v1", "gpt-4o") as ProviderResult.Invalid
        assertEquals(ProviderConfigErrorCode.USERINFO_NOT_ALLOWED, user.errors.single().code)
    }

    private fun assertFactory(url: String, code: ProviderConfigErrorCode, field: String) {
        val result = BuiltInProviders.searxng(url) as ProviderResult.Invalid
        assertEquals(code, result.errors.single().code)
        assertEquals(field, result.errors.single().field)
    }
}
