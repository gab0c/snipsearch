package com.snipsearch.search

import java.net.URI

/** Builds text-search URLs from a validated template and a user query. */
internal object SearchQueryBuilder {
    /** OpenSearch 1.1 template parameter. */
    const val PLACEHOLDER = "{searchTerms}"

    /** UTF-16 chars after normalization. */
    const val MAX_QUERY_LENGTH = 2048

    /** Maximum length of a built search URL. */
    const val MAX_SEARCH_URL_LENGTH = 8192

    private val hex = "0123456789ABCDEF"

    /** Validates a text-search template for [field]; returns the first error or null. */
    fun checkTemplate(template: String, field: String): ProviderConfigError? {
        if (template.length > ProviderEndpointPolicy.MAX_URL_LENGTH) {
            return error(ProviderConfigErrorCode.URL_TOO_LONG, field, "The URL exceeds the maximum length.")
        }
        var count = 0
        var from = 0
        while (from <= template.length) {
            val at = template.indexOf(PLACEHOLDER, from)
            if (at < 0) break
            count += 1
            from = at + PLACEHOLDER.length
        }
        if (count == 0) {
            return error(ProviderConfigErrorCode.MISSING_PLACEHOLDER, field, "The template is missing the search placeholder.")
        }
        if (count > 1) {
            return error(ProviderConfigErrorCode.MULTIPLE_PLACEHOLDERS, field, "The template contains more than one search placeholder.")
        }
        val without = template.replace(PLACEHOLDER, "")
        if (without.indexOf('{') >= 0 || without.indexOf('}') >= 0) {
            return error(ProviderConfigErrorCode.UNSUPPORTED_PLACEHOLDER, field, "The template contains an unsupported placeholder.")
        }
        val substituted = template.replace(PLACEHOLDER, "x")
        ProviderEndpointPolicy.check(substituted, field, allowQuery = true)?.let { return it }
        val uri = URI(substituted)
        val queryStart = template.indexOf('?')
        val rawQuery = if (uri.rawQuery == null || queryStart < 0) null else template.substring(queryStart + 1)
        val segments = rawQuery?.split('&').orEmpty()
        val wholeValue = segments.count { segment ->
            val eq = segment.indexOf('=')
            eq > 0 && segment.substring(eq + 1) == PLACEHOLDER
        } == 1 && segments.count { it.contains(PLACEHOLDER) } == 1
        if (!wholeValue) {
            return error(
                ProviderConfigErrorCode.PLACEHOLDER_POSITION,
                field,
                "The placeholder must be the entire value of exactly one query parameter.",
            )
        }
        return null
    }

    /** Collapses whitespace and rejects empty, oversized, or ill-formed queries. */
    fun normalizeQuery(raw: String): ProviderResult<String> {
        if (!ProviderEndpointPolicy.isWellFormedUtf16(raw)) {
            return ProviderResult.Invalid(
                listOf(error(ProviderConfigErrorCode.MALFORMED_TEXT, "query", "The query text is not well-formed.")),
            )
        }
        val mapped = buildString(raw.length) {
            for (ch in raw) {
                val type = Character.getType(ch)
                if (
                    Character.isISOControl(ch) ||
                    Character.isWhitespace(ch) ||
                    type == Character.SPACE_SEPARATOR.toInt() ||
                    type == Character.LINE_SEPARATOR.toInt() ||
                    type == Character.PARAGRAPH_SEPARATOR.toInt()
                ) {
                    append(' ')
                } else {
                    append(ch)
                }
            }
        }
        val collapsed = buildString(mapped.length) {
            var pending = false
            for (ch in mapped.trim()) {
                if (ch == ' ') {
                    pending = true
                } else {
                    if (pending && isNotEmpty()) append(' ')
                    pending = false
                    append(ch)
                }
            }
        }
        if (collapsed.isEmpty()) {
            return ProviderResult.Invalid(
                listOf(error(ProviderConfigErrorCode.EMPTY_QUERY, "query", "The query is empty.")),
            )
        }
        if (collapsed.length > MAX_QUERY_LENGTH) {
            return ProviderResult.Invalid(
                listOf(error(ProviderConfigErrorCode.QUERY_TOO_LONG, "query", "The query exceeds the maximum length.")),
            )
        }
        return ProviderResult.Valid(collapsed)
    }

    /** Percent-encodes [value] as a query component. Space becomes `%20`. */
    fun encodeQueryComponent(value: String): String {
        require(ProviderEndpointPolicy.isWellFormedUtf16(value))
        val bytes = value.toByteArray(Charsets.UTF_8)
        return buildString(bytes.size * 3) {
            for (byte in bytes) {
                val b = byte.toInt() and 0xFF
                val ch = b.toChar()
                if ((ch in 'A'..'Z') || (ch in 'a'..'z') || (ch in '0'..'9') || ch == '-' || ch == '.' || ch == '_' || ch == '~') {
                    append(ch)
                } else {
                    append('%')
                    append(hex[b ushr 4])
                    append(hex[b and 0x0F])
                }
            }
        }
    }

    /**
     * Builds a URL string only. Performs no I/O and no navigation; callers must still
     * require explicit user action and apply their own navigation allowlist.
     */
    fun textSearchUrl(provider: SearchProviderConfig, rawQuery: String): ProviderResult<String> {
        val template = when (provider) {
            is SearchProviderConfig.TextOnlyProvider -> provider.textSearchUrlTemplate
            is SearchProviderConfig.BrowserFormVisualProvider -> provider.textSearchUrlTemplate
                ?: return unsupported()
            is SearchProviderConfig.RestVisionProvider -> return unsupported()
        }
        checkTemplate(template, "textSearchUrlTemplate")?.let {
            return ProviderResult.Invalid(listOf(it))
        }
        val normalized = when (val result = normalizeQuery(rawQuery)) {
            is ProviderResult.Valid -> result.value
            is ProviderResult.Invalid -> return result
        }
        val url = template.replace(PLACEHOLDER, encodeQueryComponent(normalized))
        if (url.length > MAX_SEARCH_URL_LENGTH) {
            return ProviderResult.Invalid(
                listOf(error(ProviderConfigErrorCode.QUERY_TOO_LONG, "query", "The query exceeds the maximum length.")),
            )
        }
        ProviderEndpointPolicy.check(url, "query", allowQuery = true, maxLength = MAX_SEARCH_URL_LENGTH)?.let {
            return ProviderResult.Invalid(listOf(it))
        }
        return ProviderResult.Valid(url)
    }

    private fun unsupported() = ProviderResult.Invalid(
        listOf(
            error(
                ProviderConfigErrorCode.UNSUPPORTED_CAPABILITY,
                "capabilities",
                "This provider does not support text search.",
            ),
        ),
    )

    private fun error(code: ProviderConfigErrorCode, field: String, message: String) =
        ProviderConfigError(code, field, message)
}
