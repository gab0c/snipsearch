package com.snipsearch.ui.overlay

import android.webkit.WebResourceRequest
import android.webkit.WebView
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class TextSearchSessionTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun warmHasNoNavigationAndLocksDownWebView() {
        val session = TextSearchSession(context, online = { true })
        session.warm()
        val web = requireNotNull(session.webView)
        assertNull(shadowOf(web).lastLoadedUrl)
        assertTrue(web.settings.blockNetworkLoads)
        assertFalse(web.settings.allowFileAccess)
        assertFalse(web.settings.allowContentAccess)
        assertFalse(web.settings.javaScriptCanOpenWindowsAutomatically)
        assertTrue(web.settings.mediaPlaybackRequiresUserGesture)
        assertFalse(web.settings.domStorageEnabled)
        assertTrue(web.settings.javaScriptEnabled)
        assertEquals(android.webkit.WebSettings.LOAD_NO_CACHE, web.settings.cacheMode)
        assertFalse(session.isOpen)
        assertNull(session.target)
        session.close()
        assertNull(session.webView)
    }

    @Test fun singleSubmitLoadsSnapshotAndCloseReleasesIt() {
        val session = TextSearchSession(context, online = { true })
        session.warm()
        session.submit("hello & goodbye")
        val target = TextSearchPolicy.googleUrl("hello & goodbye")
        assertEquals(target, shadowOf(requireNotNull(session.webView)).lastLoadedUrl)
        assertEquals(target, session.target)
        assertTrue(session.isOpen)
        assertFalse(session.failed)
        val oldWeb = requireNotNull(session.webView)
        session.close()
        assertTrue(shadowOf(oldWeb).wasDestroyCalled())
        assertTrue(shadowOf(oldWeb).wasClearHistoryCalled())
        assertTrue(shadowOf(oldWeb).wasClearCacheCalled())
        assertTrue(oldWeb.settings.blockNetworkLoads)
        assertNull(session.target)
        assertNull(session.webView)
        assertFalse(session.isOpen)
        session.close()
    }

    @Test fun revokedNetworkDoesNotConstructOrLoadWebViewButRetainsExplicitBrowserTarget() {
        var constructed = false
        val session = TextSearchSession(context, online = { false }, createWebView = { constructed = true; WebView(context) })
        session.warm()
        session.submit("offline text")
        assertFalse(constructed)
        assertTrue(session.failed)
        assertTrue(session.isOpen)
        assertEquals(TextSearchPolicy.googleUrl("offline text"), session.target)
        session.close()
    }

    private fun request(url: String) = object : WebResourceRequest {
        override fun getUrl() = android.net.Uri.parse(url)
        override fun isForMainFrame() = true
        override fun isRedirect() = false
        override fun hasGesture() = true
        override fun getMethod() = "GET"
        override fun getRequestHeaders() = emptyMap<String, String>()
    }

    @Test fun requestGateBlocksWarmTrafficAndFileAccessAndRevokesOnLoadError() {
        val session = TextSearchSession(context, online = { true })
        session.warm()
        val web = requireNotNull(session.webView)
        val client = shadowOf(web).webViewClient
        val remote = request("https://www.google.com/search?q=hello")
        assertNotNull(client.shouldInterceptRequest(web, remote))
        session.submit("hello")
        assertNull(client.shouldInterceptRequest(web, remote))
        assertNotNull(client.shouldInterceptRequest(web, request("file:///screen.png")))
        assertTrue(client.shouldOverrideUrlLoading(web, request("intent://example")))
        client.onReceivedError(web, remote, null)
        assertTrue(session.failed)
        assertTrue(web.settings.blockNetworkLoads)
        assertNotNull(client.shouldInterceptRequest(web, remote))
        session.close()
    }

    @Test fun unavailableWebViewProviderDegradesWithoutCrash() {
        val session = TextSearchSession(context, online = { true }, createWebView = { throw IllegalStateException("unavailable") })
        session.warm()
        session.submit("hello")
        assertTrue(session.failed)
        assertNull(session.webView)
        session.close()
    }

    @Test fun selectionToolbarTracksHighlightAndClampsAtScreenEdges() {
        val size = IntSize(400, 800)
        val toolbar = IntSize(100, 48)
        val middle = selectionToolbarPosition(androidx.compose.ui.geometry.Offset(220f, 330f), size, toolbar, 8f, 12f, 24f, 24f)
        assertEquals(228, middle.x)
        assertEquals(338, middle.y)
        val bottom = selectionToolbarPosition(androidx.compose.ui.geometry.Offset(220f, 755f), size, toolbar, 8f, 12f, 24f, 24f)
        assertEquals(699, bottom.y)
        // Adding actions changes the bubble's overall width, not its primary anchor width.
        val extraRow = selectionToolbarPosition(androidx.compose.ui.geometry.Offset(220f, 330f), size, IntSize(100, 96), 8f, 12f, 24f, 24f)
        assertEquals(middle.x, extraRow.x)
        assertEquals(middle.y, extraRow.y)
        val edge = selectionToolbarPosition(androidx.compose.ui.geometry.Offset(390f, 40f), size, toolbar, 8f, 12f, 24f, 24f)
        assertEquals(282, edge.x)
        assertEquals(48, edge.y)
    }

    @Test fun toolbarFollowsLastActiveHandleWithRtlAndMultilineBounds() {
        val words = listOf(
            SelectableWord(0, 0..2, com.snipsearch.extraction.PixelRegion(0, 0, 10, 10), false),
            SelectableWord(0, 3..5, com.snipsearch.extraction.PixelRegion(10, 0, 20, 10), true),
        )
        val rects = listOf(Rect(1f, 2f, 11f, 12f), Rect(21f, 2f, 31f, 12f))
        assertEquals(androidx.compose.ui.geometry.Offset(11f, 12f), selectionHandleAnchor(words, rects, 0, false))
        assertEquals(androidx.compose.ui.geometry.Offset(21f, 12f), selectionHandleAnchor(words, rects, 1, false))
        assertEquals(selectionHandleAnchor(words, rects, 0, true), activeSelectionHandle(words, rects, 0..1, SelectionHandle.START))
        assertEquals(selectionHandleAnchor(words, rects, 1, false), activeSelectionHandle(words, rects, 0..1, SelectionHandle.END))
        assertNull(activeSelectionHandle(words, rects, null, SelectionHandle.START))
        val rtl = words.map { it.copy(rtl = true) }
        assertEquals(androidx.compose.ui.geometry.Offset(11f, 12f), activeSelectionHandle(rtl, rects, 0..1, SelectionHandle.START))
        assertEquals(androidx.compose.ui.geometry.Offset(21f, 12f), activeSelectionHandle(rtl, rects, 0..1, SelectionHandle.END))
        val multiline = listOf(Rect(20f, 10f, 100f, 30f), Rect(5f, 70f, 50f, 90f))
        assertEquals(androidx.compose.ui.geometry.Offset(20f, 30f), activeSelectionHandle(words, multiline, 0..1, SelectionHandle.START))
        assertEquals(androidx.compose.ui.geometry.Offset(5f, 90f), activeSelectionHandle(words, multiline, 0..1, SelectionHandle.END))

    }

    @Test fun heightChangesRetainAuthorizedQueryAndNewSearchIsExplicit() {
        var loadCount = 0
        val session = TextSearchSession(context, online = { true }, createWebView = {
            object : WebView(context) {
                override fun loadUrl(url: String) { loadCount++; super.loadUrl(url) }
            }
        })
        session.submit("first query")
        val web = requireNotNull(session.webView)
        val client = shadowOf(web).webViewClient
        val remote = request("https://www.google.com/search?q=first")
        val target = session.target
        assertTrue(session.isOpen)
        assertFalse(web.settings.blockNetworkLoads)
        assertNull(client.shouldInterceptRequest(web, remote))
        assertTrue(session.isOpen)
        assertSame(web, session.webView)
        assertEquals(target, session.target)
        assertEquals(1, loadCount)
        assertFalse(web.settings.blockNetworkLoads)
        assertNull(client.shouldInterceptRequest(web, remote))
        session.submit("second query")
        assertEquals(2, loadCount)
        assertTrue(shadowOf(web).wasOnResumeCalled())
        assertFalse(web.settings.blockNetworkLoads)
        assertEquals(TextSearchPolicy.googleUrl("second query"), session.target)
        assertEquals(TextSearchPolicy.googleUrl("second query"), shadowOf(web).lastLoadedUrl)
        session.close()
        assertTrue(shadowOf(web).wasDestroyCalled())
        assertNull(session.target)
    }

    @Test fun movablePanelStaysInsideViewport() {
        val viewport = IntSize(400, 800)
        val panel = IntSize(160, 120)
        assertEquals(androidx.compose.ui.unit.IntOffset(240, -680), movablePanelPosition(androidx.compose.ui.unit.IntOffset(900, -900), panel, viewport))
        assertEquals(androidx.compose.ui.unit.IntOffset(40, -80), movablePanelPosition(androidx.compose.ui.unit.IntOffset(240, -680), panel, IntSize(200, 200)))
        assertEquals(androidx.compose.ui.unit.IntOffset(0, 0), movablePanelPosition(androidx.compose.ui.unit.IntOffset(-10, 20), panel, viewport))
    }
}
