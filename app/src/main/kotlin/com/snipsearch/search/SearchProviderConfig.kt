package com.snipsearch.search

/**
 * Schema-only foundation for search providers. Transport (embedded browser form upload,
 * REST client), settings persistence, credential cipher, and UI wiring are deferred.
 * Nothing in this package performs network I/O or navigation.
 */

/** What a configured provider can do once its transport exists. */
internal enum class ProviderCapability {
    /** Text query via a URL template. */
    TEXT,
    /** Image upload via a deferred transport. */
    VISUAL,
    /** Text prompt sent with an image. */
    MULTIMODAL_PROMPT,
}

/** Wire shape of a deferred REST vision call. */
internal enum class VisionRequestFormat {
    /** JSON chat-completions body with image content. */
    OPENAI_CHAT_COMPLETIONS,
    /** Multipart upload whose JSON response contains a redirect path. */
    MULTIPART_REDIRECT_JSON,
}

/** How a stored secret is placed in the deferred request header. */
internal enum class CredentialScheme {
    /** Prefix the secret with a bearer scheme. */
    BEARER,
    /** Send the secret as the header value unchanged. */
    RAW,
}

/** Opaque reference to a secret held by the (deferred) hardware-keystore-backed store. Never holds secret material. */
internal data class CredentialBinding(
    /** Store key for the secret. */
    val ref: String,
    /** Header that will carry the secret. */
    val headerName: String = "Authorization",
    /** Placement of the secret in that header. */
    val scheme: CredentialScheme = CredentialScheme.BEARER,
)

/** Immutable description of one search provider. */
internal sealed interface SearchProviderConfig {
    /** Stable id, unique among built-ins and the user's list. */
    val id: String

    /** Name shown in settings. */
    val displayName: String

    /** Derived from fields; never stored/serialized. Ordered by ProviderCapability declaration order. */
    val capabilities: Set<ProviderCapability>

    /** GET text search via a URL template with exactly one `{searchTerms}` placeholder as a whole query-parameter value. */
    data class TextOnlyProvider(
        override val id: String,
        override val displayName: String,
        /** URL template containing the search placeholder. */
        val textSearchUrlTemplate: String,
    ) : SearchProviderConfig {
        override val capabilities: Set<ProviderCapability>
            get() = setOf(ProviderCapability.TEXT)
    }

    /** Mode 1 (AGENTS.md §4.1): embedded browser multipart `<form>` POST. Transport deferred. */
    data class BrowserFormVisualProvider(
        override val id: String,
        override val displayName: String,
        /** HTTPS endpoint that accepts the multipart form. */
        val uploadEndpointUrl: String,
        /** Form field that carries the image bytes. */
        val fileFormFieldName: String,
        /** Additional static form fields. */
        val extraFormFields: Map<String, String> = emptyMap(),
        /** Form field that carries an optional text prompt. */
        val multimodalQueryFieldName: String? = null,
        /** Optional text-search template. */
        val textSearchUrlTemplate: String? = null,
    ) : SearchProviderConfig {
        override val capabilities: Set<ProviderCapability>
            get() = buildSet {
                if (textSearchUrlTemplate != null) add(ProviderCapability.TEXT)
                add(ProviderCapability.VISUAL)
                if (multimodalQueryFieldName != null) add(ProviderCapability.MULTIMODAL_PROMPT)
            }

        /** `https://<host>` of [uploadEndpointUrl], or null when that URL is invalid. Base URL for the deferred bootstrap page. */
        val originUrl: String?
            get() = ProviderEndpointPolicy.originOf(uploadEndpointUrl)
    }

    /** Mode 2 (AGENTS.md §4.1): scoped HTTPS REST/JSON vision API. Transport deferred. */
    data class RestVisionProvider(
        override val id: String,
        override val displayName: String,
        /** HTTPS endpoint with no query string. */
        val endpointUrl: String,
        /** Request body shape. */
        val requestFormat: VisionRequestFormat,
        /** Model id for chat-completions requests. */
        val modelName: String? = null,
        /** Optional system text for chat-completions requests. */
        val systemPrompt: String? = null,
        /** Dotted path of a URL inside a multipart JSON response. */
        val jsonRedirectPath: String? = null,
        /** File field for a multipart request. */
        val fileFormFieldName: String? = null,
        /** Optional reference to a stored secret. */
        val credential: CredentialBinding? = null,
    ) : SearchProviderConfig {
        override val capabilities: Set<ProviderCapability>
            get() = if (requestFormat == VisionRequestFormat.OPENAI_CHAT_COMPLETIONS) {
                setOf(ProviderCapability.VISUAL, ProviderCapability.MULTIMODAL_PROMPT)
            } else {
                setOf(ProviderCapability.VISUAL)
            }
    }
}
