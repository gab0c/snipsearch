package com.snipsearch.search

/** Stable reason a provider configuration or query was rejected. */
internal enum class ProviderConfigErrorCode {
    /** The URL could not be parsed as a hierarchical address. */
    MALFORMED_URL,
    /** The URL contains a character outside ASCII. */
    NON_ASCII_URL,
    /** The URL exceeds the configured length limit. */
    URL_TOO_LONG,
    /** The URL scheme is not https. */
    UNSAFE_SCHEME,
    /** The URL authority includes user info. */
    USERINFO_NOT_ALLOWED,
    /** The URL uses a port other than 443. */
    PORT_NOT_ALLOWED,
    /** The URL includes a fragment. */
    FRAGMENT_NOT_ALLOWED,
    /** The URL has no host. */
    MISSING_HOST,
    /** The host is not a valid DNS name. */
    INVALID_HOST,
    /** The host is an IP address literal. */
    IP_LITERAL_NOT_ALLOWED,
    /** The host names a local-network target. */
    LOCAL_NETWORK_NOT_SUPPORTED,
    /** The URL is not in canonical form. */
    NON_CANONICAL_URL,
    /** A query string is present where this field forbids one. */
    QUERY_NOT_ALLOWED,
    /** A text-search template has no search placeholder. */
    MISSING_PLACEHOLDER,
    /** A text-search template has more than one search placeholder. */
    MULTIPLE_PLACEHOLDERS,
    /** The search placeholder is not a whole query-parameter value. */
    PLACEHOLDER_POSITION,
    /** The template contains a brace that is not the search placeholder. */
    UNSUPPORTED_PLACEHOLDER,
    /** The provider id does not match the id grammar. */
    INVALID_ID,
    /** The provider id is reserved for a built-in provider. */
    RESERVED_ID,
    /** Two providers in one list share an id. */
    DUPLICATE_ID,
    /** The display name fails the display-name rules. */
    INVALID_DISPLAY_NAME,
    /** A form or header field name fails its grammar. */
    INVALID_FIELD_NAME,
    /** A field name collides with another field on the same provider. */
    DUPLICATE_FIELD,
    /** A field value fails its grammar or content rules. */
    INVALID_FIELD_VALUE,
    /** A field value exceeds its length limit. */
    VALUE_TOO_LONG,
    /** A map or list exceeds its item limit. */
    TOO_MANY_FIELDS,
    /** A name or query parameter looks like credential material. */
    PLAINTEXT_CREDENTIAL,
    /** A credential reference does not match the reference grammar. */
    INVALID_CREDENTIAL_REF,
    /** A credential header name is hop-by-hop or forbidden. */
    FORBIDDEN_HEADER,
    /** A required field is absent. */
    MISSING_REQUIRED,
    /** A field is set on a provider type that does not use it. */
    FIELD_NOT_APPLICABLE,
    /** The normalized query is empty. */
    EMPTY_QUERY,
    /** The query exceeds its length limit. */
    QUERY_TOO_LONG,
    /** Text is not well-formed UTF-16. */
    MALFORMED_TEXT,
    /** The provider has no template for the requested capability. */
    UNSUPPORTED_CAPABILITY,
    /** JSON input exceeds the character limit. */
    INPUT_TOO_LARGE,
    /** JSON input is not in the accepted subset. */
    MALFORMED_JSON,
    /** JSON nesting exceeds the depth limit. */
    NESTING_TOO_DEEP,
    /** A JSON object repeats a key. */
    DUPLICATE_KEY,
    /** JSON contains a field this schema does not define. */
    UNKNOWN_FIELD,
    /** A JSON value has the wrong type. */
    TYPE_MISMATCH,
    /** The schema identifier is not recognized. */
    UNSUPPORTED_SCHEMA,
    /** The schema version is not recognized. */
    UNSUPPORTED_VERSION,
    /** The provider type string is not recognized. */
    UNKNOWN_PROVIDER_TYPE,
    /** A provider list exceeds the provider limit. */
    TOO_MANY_PROVIDERS,
}

/**
 * [message] describes the violated rule only; it never echoes the offending value
 * (may be a secret or screen text).
 */
internal data class ProviderConfigError(
    /** Machine-readable reason. */
    val code: ProviderConfigErrorCode,
    /** Dotted field path the rule applies to. */
    val field: String,
    /** Fixed English description of the rule. */
    val message: String,
)

/** Either a validated value or one or more configuration errors. */
internal sealed interface ProviderResult<out T> {
    /** A value that passed validation. */
    data class Valid<out T>(
        /** Accepted value. */
        val value: T,
    ) : ProviderResult<T>

    /** One or more rule violations. The list is never empty. */
    data class Invalid(
        /** Rule violations, in check order. */
        val errors: List<ProviderConfigError>,
    ) : ProviderResult<Nothing> {
        init {
            require(errors.isNotEmpty())
        }
    }
}
