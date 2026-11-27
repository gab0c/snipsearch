package com.snipsearch.ui.overlay

import android.webkit.WebView

/** Robolectric inspection only: production UI cannot obtain a raw session WebView. */
internal val TextSearchSession.webView: WebView?
    get() {
        var result: WebView? = null
        inspectWebView { result = it }
        return result
    }
