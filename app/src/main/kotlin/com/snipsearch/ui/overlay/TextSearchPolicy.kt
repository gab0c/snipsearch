package com.snipsearch.ui.overlay

import android.net.Uri

/** Keeps extracted text local until the user invokes the Search action. */
internal object TextSearchPolicy {
    private val embeddedHosts = setOf("www.google.com", "google.com", "consent.google.com", "accounts.google.com", "sorry.google.com")

    fun isEmbeddedPage(uri: Uri): Boolean = try {
        // URI rejects malformed authorities/backslashes that Android Uri parses leniently.
        val parsed = java.net.URI(uri.toString())
        parsed.scheme == "https" && parsed.rawUserInfo == null &&
            (parsed.port == -1 || parsed.port == 443) && parsed.host?.lowercase(java.util.Locale.ROOT) in embeddedHosts
    } catch (_: java.net.URISyntaxException) { false }

    fun googleUrl(query: String): String? = query.trim().takeIf(String::isNotEmpty)?.let {
        "https://www.google.com/search?q=${Uri.encode(it)}"
    }

}
