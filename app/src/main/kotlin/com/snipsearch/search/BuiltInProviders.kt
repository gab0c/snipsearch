package com.snipsearch.search

/** TEXT_SEARCH_READY: SearchQueryBuilder can build its text URL today (app wiring deferred). ROADMAP: schema/capabilities only; transport deferred. */
internal enum class ProviderAvailability {
    /** A text URL can be built from the preset today. */
    TEXT_SEARCH_READY,
    /** Only the schema is available; transport is deferred. */
    ROADMAP,
}

/** Where a preset endpoint description came from. */
internal enum class EndpointSource {
    /** A published provider URL. */
    DOCUMENTED,
    /** An endpoint observed from the provider's own site. */
    UNOFFICIAL,
}

/** A fixed preset plus availability metadata. */
internal data class BuiltInProvider(
    /** Provider configuration. */
    val config: SearchProviderConfig,
    /** Whether text search can be built today. */
    val availability: ProviderAvailability,
    /** Provenance of the endpoint. */
    val endpointSource: EndpointSource,
    /** Short explanation shown with the preset. */
    val note: String,
)

/** A provider the user must configure (instance/endpoint URL); not a fixed preset. */
internal data class ConfigurableProviderTemplate(
    /** Suggested id before the user changes it. */
    val defaultId: String,
    /** Suggested display name. */
    val displayName: String,
    /** Capabilities the finished provider will have. */
    val capabilities: Set<ProviderCapability>,
    /** Whether text search can be built today. */
    val availability: ProviderAvailability,
    /** Provenance of the endpoint pattern. */
    val endpointSource: EndpointSource,
    /** Short explanation shown with the template. */
    val note: String,
)

/** Built-in presets and factories for user-supplied public instances. */
internal object BuiltInProviders {
    /** Standard Google web search. */
    val GOOGLE: SearchProviderConfig.TextOnlyProvider = SearchProviderConfig.TextOnlyProvider(
        id = "google",
        displayName = "Google",
        textSearchUrlTemplate = "https://www.google.com/search?q={searchTerms}",
    )

    /** DuckDuckGo web search. */
    val DUCKDUCKGO: SearchProviderConfig.TextOnlyProvider = SearchProviderConfig.TextOnlyProvider(
        id = "duckduckgo",
        displayName = "DuckDuckGo",
        textSearchUrlTemplate = "https://duckduckgo.com/?q={searchTerms}",
    )

    /** Brave Search web search. */
    val BRAVE: SearchProviderConfig.TextOnlyProvider = SearchProviderConfig.TextOnlyProvider(
        id = "brave",
        displayName = "Brave Search",
        textSearchUrlTemplate = "https://search.brave.com/search?q={searchTerms}",
    )

    /** Startpage web search. */
    val STARTPAGE: SearchProviderConfig.TextOnlyProvider = SearchProviderConfig.TextOnlyProvider(
        id = "startpage",
        displayName = "Startpage",
        textSearchUrlTemplate = "https://www.startpage.com/sp/search?query={searchTerms}",
    )

    /** Google Lens browser-form upload. */
    val GOOGLE_LENS: SearchProviderConfig.BrowserFormVisualProvider = SearchProviderConfig.BrowserFormVisualProvider(
        id = "google-lens",
        displayName = "Google Lens",
        uploadEndpointUrl = "https://lens.google.com/v3/upload",
        fileFormFieldName = "encoded_image",
        multimodalQueryFieldName = "q",
        textSearchUrlTemplate = GOOGLE.textSearchUrlTemplate,
    )

    /** Yandex image search browser-form upload. */
    val YANDEX_IMAGES: SearchProviderConfig.BrowserFormVisualProvider = SearchProviderConfig.BrowserFormVisualProvider(
        id = "yandex-images",
        displayName = "Yandex Images",
        uploadEndpointUrl = "https://yandex.com/images/touch/search?rpt=imageview",
        fileFormFieldName = "upfile",
        textSearchUrlTemplate = "https://yandex.com/search/?text={searchTerms}",
    )

    /** Bing visual search browser-form upload. */
    val BING_VISUAL: SearchProviderConfig.BrowserFormVisualProvider = SearchProviderConfig.BrowserFormVisualProvider(
        id = "bing-visual",
        displayName = "Bing Visual Search",
        uploadEndpointUrl = "https://www.bing.com/images/search?view=detailv2&iss=sbiupload",
        fileFormFieldName = "imageBin",
        textSearchUrlTemplate = "https://www.bing.com/search?q={searchTerms}",
    )

    /** Text presets that can build a URL today. */
    val textPresets: List<BuiltInProvider> = listOf(
        BuiltInProvider(
            GOOGLE,
            ProviderAvailability.TEXT_SEARCH_READY,
            EndpointSource.DOCUMENTED,
            "Standard Google web search URL; matches TextSearchPolicy.googleUrl.",
        ),
        BuiltInProvider(
            DUCKDUCKGO,
            ProviderAvailability.TEXT_SEARCH_READY,
            EndpointSource.DOCUMENTED,
            "Documented DuckDuckGo search URL (?q=).",
        ),
        BuiltInProvider(
            BRAVE,
            ProviderAvailability.TEXT_SEARCH_READY,
            EndpointSource.DOCUMENTED,
            "Documented Brave Search URL (/search?q=).",
        ),
        BuiltInProvider(
            STARTPAGE,
            ProviderAvailability.TEXT_SEARCH_READY,
            EndpointSource.DOCUMENTED,
            "Documented Startpage search URL (/sp/search?query=).",
        ),
    )

    /** Visual presets whose transport is deferred. */
    val roadmapPresets: List<BuiltInProvider> = listOf(
        BuiltInProvider(
            GOOGLE_LENS,
            ProviderAvailability.ROADMAP,
            EndpointSource.UNOFFICIAL,
            ROADMAP_NOTE,
        ),
        BuiltInProvider(
            YANDEX_IMAGES,
            ProviderAvailability.ROADMAP,
            EndpointSource.UNOFFICIAL,
            ROADMAP_NOTE,
        ),
        BuiltInProvider(
            BING_VISUAL,
            ProviderAvailability.ROADMAP,
            EndpointSource.UNOFFICIAL,
            ROADMAP_NOTE,
        ),
    )

    /** Text presets followed by roadmap presets. */
    val all: List<BuiltInProvider> = textPresets + roadmapPresets

    /** Ids that custom providers must not reuse. */
    val reservedIds: Set<String> = setOf(
        "google",
        "duckduckgo",
        "brave",
        "startpage",
        "google-lens",
        "yandex-images",
        "bing-visual",
    )

    /** Looks up a preset by id. */
    fun byId(id: String): BuiltInProvider? = all.find { it.config.id == id }

    /** Default id for a SearXNG instance. */
    const val SEARXNG_DEFAULT_ID = "searxng"

    /** Default id for an OpenAI-compatible vision endpoint. */
    const val OPENAI_VISION_DEFAULT_ID = "openai-vision"

    /** Templates the user finishes by supplying a public HTTPS URL. */
    val configurableTemplates: List<ConfigurableProviderTemplate> = listOf(
        ConfigurableProviderTemplate(
            defaultId = SEARXNG_DEFAULT_ID,
            displayName = "SearXNG",
            capabilities = setOf(ProviderCapability.TEXT),
            availability = ProviderAvailability.TEXT_SEARCH_READY,
            endpointSource = EndpointSource.DOCUMENTED,
            note = "SearXNG search API (GET /search?q=) on a user-chosen public HTTPS instance. Reverse image search is not offered: SearXNG URL-search engines need an image URL or data URI plus instance-specific engine configuration, not a multipart upload.",
        ),
        ConfigurableProviderTemplate(
            defaultId = OPENAI_VISION_DEFAULT_ID,
            displayName = "OpenAI-compatible vision",
            capabilities = setOf(ProviderCapability.VISUAL, ProviderCapability.MULTIMODAL_PROMPT),
            availability = ProviderAvailability.ROADMAP,
            endpointSource = EndpointSource.DOCUMENTED,
            note = "OpenAI-compatible Chat Completions endpoint with image content parts on a public HTTPS host. REST transport is deferred; local-network hosts are rejected because local network permission is not requested.",
        ),
    )

    /**
     * Builds a text-only SearXNG provider for [instanceBaseUrl].
     * A single trailing slash on the base is removed before `/search` is appended.
     */
    fun searxng(
        instanceBaseUrl: String,
        id: String = SEARXNG_DEFAULT_ID,
        displayName: String = "SearXNG",
    ): ProviderResult<SearchProviderConfig.TextOnlyProvider> {
        if (instanceBaseUrl.indexOf('{') >= 0 || instanceBaseUrl.indexOf('}') >= 0) {
            return ProviderResult.Invalid(
                listOf(
                    ProviderConfigError(
                        ProviderConfigErrorCode.UNSUPPORTED_PLACEHOLDER,
                        "instanceBaseUrl",
                        "The URL contains an unsupported placeholder.",
                    ),
                ),
            )
        }
        ProviderEndpointPolicy.check(instanceBaseUrl, "instanceBaseUrl", allowQuery = false)?.let {
            return ProviderResult.Invalid(listOf(it))
        }
        val trimmedBase = if (instanceBaseUrl.endsWith("/")) instanceBaseUrl.dropLast(1) else instanceBaseUrl
        val config = SearchProviderConfig.TextOnlyProvider(
            id = id,
            displayName = displayName,
            textSearchUrlTemplate = "$trimmedBase/search?q={searchTerms}",
        )
        val errors = ProviderValidator.validate(config)
        return if (errors.isEmpty()) ProviderResult.Valid(config) else ProviderResult.Invalid(errors)
    }

    /** Builds an OpenAI-compatible vision provider and validates it. */
    fun openAiCompatibleVision(
        endpointUrl: String,
        modelName: String,
        credential: CredentialBinding? = null,
        systemPrompt: String? = null,
        id: String = OPENAI_VISION_DEFAULT_ID,
        displayName: String = "OpenAI-compatible vision",
    ): ProviderResult<SearchProviderConfig.RestVisionProvider> {
        val config = SearchProviderConfig.RestVisionProvider(
            id = id,
            displayName = displayName,
            endpointUrl = endpointUrl,
            requestFormat = VisionRequestFormat.OPENAI_CHAT_COMPLETIONS,
            modelName = modelName,
            systemPrompt = systemPrompt,
            credential = credential,
        )
        val errors = ProviderValidator.validate(config)
        return if (errors.isEmpty()) ProviderResult.Valid(config) else ProviderResult.Invalid(errors)
    }

    private const val ROADMAP_NOTE =
        "Unofficial browser upload endpoint used by the provider's own web client; not a public API and may change. Embedded browser form upload transport is deferred."
}
