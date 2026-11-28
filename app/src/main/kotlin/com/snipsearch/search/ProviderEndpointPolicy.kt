package com.snipsearch.search

import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

/** Rules for public HTTPS endpoints accepted by provider configuration. */
internal object ProviderEndpointPolicy {
    /** Maximum characters in an endpoint URL. */
    const val MAX_URL_LENGTH = 2048

    private val hexHost = Regex("^[0-9A-Fa-fXx.]+$")
    private val decimalHost = Regex("^[0-9.]+$")
    private val hexLiteral = Regex("^0[xX][0-9A-Fa-f]+$")
    private val trailingPort = Regex(":[0-9]+$")
    private val labelRegex = Regex("^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$")
    private val queryName = Regex("^[A-Za-z0-9._~-]+$")
    private val localSuffixes = listOf(
        ".localhost",
        ".local",
        ".lan",
        ".home.arpa",
        ".internal",
        ".localdomain",
        ".intranet",
        ".corp",
        ".home",
        ".private",
    )
    private val sensitiveExact = setOf(
        "key",
        "apikey",
        "token",
        "accesstoken",
        "authtoken",
        "auth",
        "authorization",
        "password",
        "passwd",
        "pass",
        "pwd",
        "secret",
        "clientsecret",
        "bearer",
        "credential",
        "credentials",
        "session",
        "sessionid",
        "cookie",
        "sig",
        "signature",
        "privatekey",
    )

    /**
     * Returns the first failing rule as an error for [field], or null when [url] is an
     * acceptable public HTTPS endpoint.
     */
    fun check(
        url: String,
        field: String,
        allowQuery: Boolean,
        maxLength: Int = MAX_URL_LENGTH,
    ): ProviderConfigError? {
        if (url.length > maxLength) {
            return error(ProviderConfigErrorCode.URL_TOO_LONG, field, "The URL exceeds the maximum length.")
        }
        if (url.any { it > '\u007E' }) {
            return error(
                ProviderConfigErrorCode.NON_ASCII_URL,
                field,
                "The URL must use percent-encoding or punycode for non-ASCII characters.",
            )
        }
        if (url.any { it < '\u0021' }) {
            return error(ProviderConfigErrorCode.MALFORMED_URL, field, "The URL is malformed.")
        }
        if (url.indexOf('\\') >= 0) {
            return error(ProviderConfigErrorCode.MALFORMED_URL, field, "The URL is malformed.")
        }
        val uri = try {
            URI(url)
        } catch (_: URISyntaxException) {
            return error(ProviderConfigErrorCode.MALFORMED_URL, field, "The URL is malformed.")
        }
        if (uri.scheme == null || !uri.scheme.equals("https", ignoreCase = true)) {
            return error(ProviderConfigErrorCode.UNSAFE_SCHEME, field, "The URL scheme must be https.")
        }
        if (uri.scheme != "https") {
            return error(ProviderConfigErrorCode.NON_CANONICAL_URL, field, "The URL is not in canonical form.")
        }
        if (uri.isOpaque) {
            return error(ProviderConfigErrorCode.MALFORMED_URL, field, "The URL is malformed.")
        }
        if (uri.rawAuthority?.contains('@') == true) {
            return error(ProviderConfigErrorCode.USERINFO_NOT_ALLOWED, field, "The URL must not include user info.")
        }
        val rawAuthority = uri.rawAuthority
        if (rawAuthority.isNullOrEmpty()) {
            return error(ProviderConfigErrorCode.MISSING_HOST, field, "The URL is missing a host.")
        }
        if (rawAuthority.endsWith(":")) {
            return error(ProviderConfigErrorCode.MALFORMED_URL, field, "The URL is malformed.")
        }
        val host = uri.host ?: run {
            val hostPart = rawAuthority.replace(trailingPort, "")
            if (hostPart.isEmpty()) {
                return error(ProviderConfigErrorCode.MISSING_HOST, field, "The URL is missing a host.")
            }
            if (hexHost.matches(hostPart)) {
                return error(ProviderConfigErrorCode.IP_LITERAL_NOT_ALLOWED, field, "IP address literals are not allowed.")
            }
            return error(ProviderConfigErrorCode.INVALID_HOST, field, "The host is invalid.")
        }
        if (host.startsWith("[") || host.indexOf(':') >= 0) {
            return error(ProviderConfigErrorCode.IP_LITERAL_NOT_ALLOWED, field, "IP address literals are not allowed.")
        }
        if (host != host.lowercase(Locale.ROOT)) {
            return error(ProviderConfigErrorCode.NON_CANONICAL_URL, field, "The URL is not in canonical form.")
        }
        if (uri.port != -1 && uri.port != 443) {
            return error(ProviderConfigErrorCode.PORT_NOT_ALLOWED, field, "The URL port must be 443.")
        }
        val lastLabel = host.substringAfterLast('.')
        if (decimalHost.matches(host) || hexLiteral.matches(host) || (lastLabel.isNotEmpty() && lastLabel.all { it in '0'..'9' })) {
            return error(ProviderConfigErrorCode.IP_LITERAL_NOT_ALLOWED, field, "IP address literals are not allowed.")
        }
        if (host.endsWith(".")) {
            return error(ProviderConfigErrorCode.NON_CANONICAL_URL, field, "The URL is not in canonical form.")
        }
        if (
            host == "localhost" || host.split('.').size < 2 ||
            localSuffixes.any { host.endsWith(it) || host == it.removePrefix(".") }
        ) {
            return error(
                ProviderConfigErrorCode.LOCAL_NETWORK_NOT_SUPPORTED,
                field,
                "Local-network endpoints are unsupported because local network permission is not requested.",
            )
        }
        if (host.length > 253 || host.split('.').any { !labelRegex.matches(it) }) {
            return error(ProviderConfigErrorCode.INVALID_HOST, field, "The host is invalid.")
        }
        if (uri.rawFragment != null) {
            return error(ProviderConfigErrorCode.FRAGMENT_NOT_ALLOWED, field, "The URL must not include a fragment.")
        }
        val rawPath = uri.rawPath.orEmpty()
        if (rawPath.contains("//") || rawPath.split('/').any { isDotSegment(it) }) {
            return error(ProviderConfigErrorCode.NON_CANONICAL_URL, field, "The URL is not in canonical form.")
        }
        val rawQuery = uri.rawQuery ?: return null
        if (!allowQuery) {
            return error(ProviderConfigErrorCode.QUERY_NOT_ALLOWED, field, "The URL must not include a query.")
        }
        if (rawQuery.isEmpty()) {
            return error(ProviderConfigErrorCode.MALFORMED_URL, field, "The URL is malformed.")
        }
        for (segment in rawQuery.split('&')) {
            if (segment.isEmpty()) {
                return error(ProviderConfigErrorCode.MALFORMED_URL, field, "The URL is malformed.")
            }
            val name = segment.substringBefore('=')
            if (!queryName.matches(name)) {
                return error(ProviderConfigErrorCode.MALFORMED_URL, field, "The URL is malformed.")
            }
            if (isSensitiveName(name)) {
                return error(
                    ProviderConfigErrorCode.PLAINTEXT_CREDENTIAL,
                    field,
                    "The URL includes a plaintext credential parameter.",
                )
            }
        }
        return null
    }

    /** `"https://" + host` for a URL that passes check(..., allowQuery = true), else null. */
    fun originOf(url: String): String? {
        if (check(url, "url", allowQuery = true) != null) return null
        val host = try {
            URI(url).host
        } catch (_: URISyntaxException) {
            null
        } ?: return null
        return "https://$host"
    }

    /** True when [s] has no unpaired UTF-16 surrogates. */
    fun isWellFormedUtf16(s: String): Boolean {
        var index = 0
        while (index < s.length) {
            val ch = s[index]
            if (ch.isHighSurrogate()) {
                if (index + 1 >= s.length || !s[index + 1].isLowSurrogate()) return false
                index += 2
            } else if (ch.isLowSurrogate()) {
                return false
            } else {
                index += 1
            }
        }
        return true
    }

    /** True when [name] looks like a secret/credential parameter or key name. */
    fun isSensitiveName(name: String): Boolean {
        val n = name.lowercase(Locale.ROOT).replace("-", "").replace("_", "").replace(".", "")
        return n in sensitiveExact || n.endsWith("apikey") || n.endsWith("token") || n.endsWith("secret") || n.endsWith("password")
    }

    private fun error(code: ProviderConfigErrorCode, field: String, message: String) =
        ProviderConfigError(code, field, message)

    private fun isDotSegment(segment: String): Boolean {
        if (segment == "." || segment == "..") return true
        return segment.replace(Regex("(?i)%2e"), ".") in setOf(".", "..")
    }
}
