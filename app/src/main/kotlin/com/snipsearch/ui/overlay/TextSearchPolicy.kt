package com.snipsearch.ui.overlay

import android.net.Uri

/** Keeps extracted text local until the user invokes the Search action. */
internal object TextSearchPolicy {
    fun googleUrl(query: String): String? = query.trim().takeIf(String::isNotEmpty)?.let {
        "https://www.google.com/search?q=${Uri.encode(it)}"
    }

}
