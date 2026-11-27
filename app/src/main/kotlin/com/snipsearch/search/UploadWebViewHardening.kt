package com.snipsearch.search

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView

/**
 * Applies WebView security lockdown and teardown for [WebViewFormUploadBridge].
 *
 * JavaScript necessity and trust boundary:
 * - HTML `<input type="file">` cannot be populated declaratively from RAM. The bootstrap script
 *   must run with JavaScript enabled to fetch the single-use RAM blob URL, construct a `File` via
 *   `DataTransfer`, attach it to the form input, and submit the Chromium multipart POST; visual
 *   search results and inline CAPTCHAs also require JavaScript.
 * - Trust boundary: zero native JS bridges are registered; content/file access, DOM storage,
 *   caching, geolocation, popups, and mixed content are disabled; network loads remain blocked
 *   until explicit caller submission; and main-frame navigation is restricted to explicitly
 *   approved HTTPS origins.
 */
internal object UploadWebViewHardening {
    @Suppress("DEPRECATION")
    @SuppressLint("SetJavaScriptEnabled")
    fun harden(webView: WebView) {
        val settings = webView.settings
        settings.allowContentAccess = false
        settings.allowFileAccess = false
        settings.blockNetworkLoads = true
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.setSupportMultipleWindows(false)
        settings.mediaPlaybackRequiresUserGesture = true
        settings.cacheMode = WebSettings.LOAD_NO_CACHE
        settings.domStorageEnabled = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.setGeolocationEnabled(false)
        settings.saveFormData = false
        // Required for DataTransfer file attachment in the bootstrap and visual results/CAPTCHA.
        settings.javaScriptEnabled = true
    }

    fun release(webView: WebView) {
        webView.settings.blockNetworkLoads = true
        webView.stopLoading()
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.clearHistory()
        webView.clearCache(true)
        webView.clearFormData()
        webView.removeAllViews()
        webView.destroy()
    }
}

internal object UploadNetworkCheck {
    fun hasInternetCapability(context: Context): Boolean = try {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val network = manager?.activeNetwork
        network != null &&
            manager.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    } catch (_: SecurityException) {
        false
    } catch (_: RuntimeException) {
        false
    }
}
