package com.snipsearch.ui.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView

/** Owns the WebView, its security policy, and one explicit RAM-only query snapshot. */
internal class TextSearchSession(
    private val context: Context,
    private val online: () -> Boolean = { hasNetwork(context) },
    private val createWebView: () -> WebView = { WebView(context) },
) {
    private var webView: WebView? = null
    var target by mutableStateOf<String?>(null); private set
    var isOpen by mutableStateOf(false); private set
    var failed by mutableStateOf(false); private set
    @Volatile private var authorized = false

    @SuppressLint("SetJavaScriptEnabled")
    fun warm() {
        if (webView != null || !online()) return
        try {
            val web = createWebView()
            webView = web
            val settings = web.settings
            settings.allowContentAccess = false
            settings.allowFileAccess = false
            settings.blockNetworkLoads = true
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.mediaPlaybackRequiresUserGesture = true
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            settings.domStorageEnabled = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.setGeolocationEnabled(false)
            settings.saveFormData = false
            // Required for Google results/CAPTCHA. Main-frame navigation is restricted to approved
            // Google HTTPS hosts; clicked external links use the browser. No native JS bridge,
            // content/file access, mixed content, or certificate-error bypass is exposed.
            // codeql[java/android/websettings-javascript-enabled]
            settings.javaScriptEnabled = true
            web.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    if (!authorized || request == null || request.url.scheme != "https") return blockedResponse()
                    if (request.isForMainFrame && !TextSearchPolicy.isEmbeddedPage(request.url)) return blockedResponse()
                    return null
                }
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    if (!authorized || request == null) return true
                    val webLink = request.url.scheme == "https" || request.url.scheme == "http"
                    if (request.isForMainFrame && request.hasGesture() && !request.isRedirect && request.method == "GET" && webLink) {
                        BrowserLinkLauncher.open(context, request.url)
                        return true
                    }
                    return if (request.isForMainFrame) !TextSearchPolicy.isEmbeddedPage(request.url) else request.url.scheme != "https"
                }
                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                    if (request?.isForMainFrame == true && authorized) fail()
                }
            }
        } catch (_: RuntimeException) {
            releaseWebView()
            failed = true
        }
    }

    fun submit(query: String) {
        val url = TextSearchPolicy.googleUrl(query) ?: return
        target = url; isOpen = true; failed = false
        if (!online()) { fail(); return }
        warm()
        val web = webView ?: run { fail(); return }
        try {
            authorized = true
            web.settings.blockNetworkLoads = false
            web.onResume()
            web.loadUrl(url)
        } catch (_: RuntimeException) { fail() }
    }

    fun canScrollPageUp(): Boolean = webView?.canScrollVertically(-1) == true

    fun renderInto(parent: ViewGroup, fallback: View) {
        val web = webView
        val content: View = if (failed || web == null) fallback else web
        if (content.parent !== parent) {
            parent.removeAllViews()
            (content.parent as? ViewGroup)?.removeView(content)
            parent.addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
    }

    /** Allows test inspection without exposing raw WebView getters throughout the UI. */
    internal fun inspectWebView(inspect: (WebView?) -> Unit) { inspect(webView) }

    private fun fail() {
        authorized = false
        failed = true
        val web = webView ?: return
        web.settings.blockNetworkLoads = true
        web.stopLoading()
    }

    private fun releaseWebView() {
        val web = webView ?: return
        webView = null
        web.settings.blockNetworkLoads = true
        web.stopLoading()
        (web.parent as? ViewGroup)?.removeView(web)
        web.clearHistory()
        web.clearCache(true)
        web.clearFormData()
        web.removeAllViews()
        web.destroy()
    }

    fun close() {
        authorized = false; target = null; isOpen = false; failed = false
        releaseWebView()
    }
}

private fun blockedResponse() = WebResourceResponse("text/plain", "UTF-8", byteArrayOf().inputStream())
private fun hasNetwork(context: Context): Boolean = try {
    val manager = context.getSystemService(ConnectivityManager::class.java)
    val network = manager?.activeNetwork
    network != null && manager.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
} catch (_: RuntimeException) { false }

@Composable internal fun rememberTextSearchSession(): TextSearchSession {
    val context = LocalContext.current
    val session = remember(context) { TextSearchSession(context) }
    DisposableEffect(session) { onDispose { session.close() } }
    return session
}

@Composable internal fun TextSearchPane(session: TextSearchSession, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val host = remember(session, context) { ResultsSheet(context, session, onDismiss) }
    val colors = MaterialTheme.colorScheme
    AndroidView(factory = { host }, modifier = modifier.fillMaxSize().safeDrawingPadding(),
        update = { it.updateContent(colors.surface.toArgb(), colors.onSurface.toArgb(), colors.primary.toArgb()) })
}
