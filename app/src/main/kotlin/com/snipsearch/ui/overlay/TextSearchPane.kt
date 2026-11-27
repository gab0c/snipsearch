package com.snipsearch.ui.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.graphics.toArgb
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.snipsearch.R

/** One RAM-only query snapshot per explicit tap. No URL is loaded by warm(). */
internal class TextSearchSession(
    private val context: Context,
    private val online: () -> Boolean = { hasNetwork(context) },
    private val createWebView: () -> WebView = { WebView(context) },
) {
    var webView: WebView? = null; private set
    var target by mutableStateOf<String?>(null); private set
    var isOpen by mutableStateOf(false); private set
    var failed by mutableStateOf(false); private set
    @Volatile private var authorized = false

    @SuppressLint("SetJavaScriptEnabled") // Google results/CAPTCHA require JS; no native bridge is exposed.
    fun warm() {
        if (webView != null || !online()) return
        try { webView = createWebView().apply {
            settings.apply {
                blockNetworkLoads = true; allowFileAccess = false; allowContentAccess = false
                javaScriptCanOpenWindowsAutomatically = false; mediaPlaybackRequiresUserGesture = true
                cacheMode = WebSettings.LOAD_NO_CACHE; domStorageEnabled = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW; javaScriptEnabled = true
                setGeolocationEnabled(false); saveFormData = false
            }
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? =
                    if (authorized && request?.url?.scheme == "https") null else blockedResponse()
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    if (!authorized || request == null) return true
                    val webLink = request.url.scheme == "https" || request.url.scheme == "http"
                    if (request.isForMainFrame && request.hasGesture() && !request.isRedirect && request.method == "GET" && webLink) {
                        BrowserLinkLauncher.open(context, request.url)
                        return true
                    }
                    return request.url.scheme != "https"
                }
                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                    if (request?.isForMainFrame == true && authorized) fail()
                }
            }
        } } catch (_: RuntimeException) { failed = true }
    }
    fun submit(query: String) {
        val url = TextSearchPolicy.googleUrl(query) ?: return
        target = url; isOpen = true; failed = false
        if (!online()) { fail(); return }
        warm(); val web = webView ?: run { fail(); return }
        try { authorized = true; web.settings.blockNetworkLoads = false; web.onResume(); web.loadUrl(url) } catch (_: RuntimeException) { fail() }
    }
    private fun fail() { authorized = false; failed = true; webView?.apply { stopLoading(); settings.blockNetworkLoads = true } }
    fun close() {
        authorized = false; target = null; isOpen = false; failed = false
        webView?.apply { settings.blockNetworkLoads = true; stopLoading(); (parent as? android.view.ViewGroup)?.removeView(this); clearHistory(); clearCache(true); clearFormData(); removeAllViews(); destroy() }
        webView = null
    }
}

private fun blockedResponse() = WebResourceResponse("text/plain", "UTF-8", byteArrayOf().inputStream())
private fun hasNetwork(context: Context): Boolean = try {
    val m = context.getSystemService(ConnectivityManager::class.java); val n = m?.activeNetwork
    n != null && m.getNetworkCapabilities(n)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
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
