package com.snipsearch.search

import android.net.Uri
import android.webkit.WebResourceRequest
import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

internal const val UPLOAD_INTERNAL_BLOB_PATH_SEGMENT = "__snipsearch_internal_blob__"
internal const val UPLOAD_INTERNAL_BLOB_PATH_PREFIX = "/$UPLOAD_INTERNAL_BLOB_PATH_SEGMENT/"

/**
 * Strict HTTPS origin, endpoint, and navigation policy for [WebViewFormUploadBridge].
 *
 * Uses dual parsing (`java.net.URI` and `android.net.Uri`) to reject parser-differential
 * authority tricks, non-443 ports, embedded credentials, cleartext schemes, non-DNS hosts,
 * and any path attempting to collide with the RAM-only internal blob route.
 */
internal object UploadOriginPolicy {
    private const val MAX_HOSTNAME_LENGTH = 253
    private const val MAX_LABEL_LENGTH = 63

    fun canonicalizeOrigin(originUrl: String): String? {
        val parsed = parseStrictHttps(originUrl) ?: return null
        if (parsed.rawQuery != null || parsed.rawFragment != null) return null
        val path = parsed.rawPath.orEmpty()
        if (path.isNotEmpty() && path != "/") return null
        return parsed.canonicalOrigin
    }

    fun baseUrlForOrigin(canonicalOrigin: String): String = "$canonicalOrigin/"

    fun canonicalizeEndpoint(endpointUrl: String, expectedCanonicalOrigin: String): String? {
        val parsed = parseStrictHttps(endpointUrl) ?: return null
        if (parsed.canonicalOrigin != expectedCanonicalOrigin) return null
        if (parsed.rawFragment != null) return null
        val rawPath = parsed.rawPath.orEmpty().ifEmpty { "/" }
        if (containsInternalBlobMarker(rawPath) ||
            containsInternalBlobMarker(parsed.decodedPath) ||
            containsInternalBlobMarker(parsed.rawQuery)
        ) {
            return null
        }
        val querySuffix = parsed.rawQuery?.let { "?$it" }.orEmpty()
        return "${parsed.canonicalOrigin}$rawPath$querySuffix"
    }

    fun extractCanonicalOrigin(uri: Uri?): String? {
        if (uri == null) return null
        return parseStrictHttps(uri.toString())?.canonicalOrigin
    }

    fun parseInternalBlobRequestUri(uri: Uri?): ParsedBlobRequestUri? {
        if (uri == null) return null
        val parsed = parseStrictHttps(uri.toString()) ?: return null
        if (parsed.rawQuery != null || parsed.rawFragment != null) return null
        val rawPath = parsed.rawPath ?: return null
        return ParsedBlobRequestUri(
            canonicalOrigin = parsed.canonicalOrigin,
            rawPath = rawPath,
        )
    }

    fun isInternalBlobUrl(uri: Uri?): Boolean {
        if (uri == null) return false
        return containsInternalBlobMarker(uri.encodedPath) ||
            containsInternalBlobMarker(uri.path) ||
            containsInternalBlobMarker(uri.toString())
    }

    fun isAllowedMainFrameNavigation(uri: Uri?, allowedOrigins: Set<String>): Boolean {
        if (isInternalBlobUrl(uri)) return false
        val origin = extractCanonicalOrigin(uri) ?: return false
        return origin in allowedOrigins
    }

    fun isAllowedHttpsSubresource(uri: Uri?): Boolean {
        if (isInternalBlobUrl(uri)) return false
        return extractCanonicalOrigin(uri) != null
    }

    fun isExactEndpointPost(request: WebResourceRequest, canonicalEndpointUrl: String): Boolean {
        if (!request.isForMainFrame || request.method != "POST") return false
        val origin = extractCanonicalOrigin(request.url) ?: return false
        return canonicalizeEndpoint(request.url.toString(), origin) == canonicalEndpointUrl
    }

    internal fun containsInternalBlobMarker(value: String?): Boolean =
        value != null && value.contains(UPLOAD_INTERNAL_BLOB_PATH_SEGMENT, ignoreCase = true)

    private fun parseStrictHttps(raw: String): StrictHttpsUri? {
        if (raw.isEmpty() || hasDisallowedRawCharacters(raw)) return null
        val javaUri = try {
            URI(raw)
        } catch (_: URISyntaxException) {
            return null
        }
        val androidUri = try {
            Uri.parse(raw)
        } catch (_: RuntimeException) {
            return null
        }
        if (javaUri.scheme != "https" || androidUri.scheme != "https") return null
        if (javaUri.rawUserInfo != null || androidUri.userInfo != null) return null
        if (javaUri.port != -1 && javaUri.port != 443) return null
        if (androidUri.port != -1 && androidUri.port != 443) return null

        val javaHost = javaUri.host?.lowercase(Locale.ROOT) ?: return null
        val androidHost = androidUri.host?.lowercase(Locale.ROOT) ?: return null
        if (javaHost != androidHost || !isValidDnsHostname(javaHost)) return null

        val rawAuthority = javaUri.rawAuthority ?: return null
        val androidAuthority = androidUri.encodedAuthority ?: return null
        val expectedPlain = javaHost
        val expectedDefaultPort = "$javaHost:443"
        if (!rawAuthority.equals(expectedPlain, ignoreCase = true) &&
            !rawAuthority.equals(expectedDefaultPort, ignoreCase = true)
        ) {
            return null
        }
        if (!androidAuthority.equals(rawAuthority, ignoreCase = true)) return null
        if ((javaUri.rawFragment != null) != (androidUri.encodedFragment != null)) return null
        if ((javaUri.rawQuery != null) != (androidUri.encodedQuery != null)) return null

        return StrictHttpsUri(
            canonicalOrigin = "https://$javaHost",
            rawPath = javaUri.rawPath,
            decodedPath = javaUri.path,
            rawQuery = javaUri.rawQuery,
            rawFragment = javaUri.rawFragment,
        )
    }

    private fun hasDisallowedRawCharacters(raw: String): Boolean {
        for (ch in raw) {
            if (ch == '\\' || ch.isWhitespace() || ch.code < 0x20 || ch.code == 0x7F) {
                return true
            }
        }
        return false
    }

    private fun isValidDnsHostname(host: String): Boolean {
        if (host.isEmpty() || host.length > MAX_HOSTNAME_LENGTH) return false
        if (host.startsWith('.') || host.endsWith('.')) return false
        val labels = host.split('.')
        if (labels.size < 2) return false
        if (labels.last().all { it in '0'..'9' }) return false
        for (label in labels) {
            if (label.isEmpty() || label.length > MAX_LABEL_LENGTH) return false
            if (!isAlphaNum(label.first()) || !isAlphaNum(label.last())) return false
            for (ch in label) {
                if (!isAlphaNum(ch) && ch != '-') return false
            }
        }
        return true
    }

    private fun isAlphaNum(ch: Char): Boolean = ch in 'a'..'z' || ch in '0'..'9'

    internal data class ParsedBlobRequestUri(
        val canonicalOrigin: String,
        val rawPath: String,
    )

    private data class StrictHttpsUri(
        val canonicalOrigin: String,
        val rawPath: String?,
        val decodedPath: String?,
        val rawQuery: String?,
        val rawFragment: String?,
    )
}
