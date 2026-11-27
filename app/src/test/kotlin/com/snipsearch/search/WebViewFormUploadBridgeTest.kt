package com.snipsearch.search

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import com.snipsearch.extraction.PixelRegion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.ConscryptMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class WebViewFormUploadBridgeTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val origin = "https://lens.google.com"
    private val endpoint = "https://lens.google.com/v3/upload"

    @Test
    fun warmHardensWebViewLoadsNothingAndBlocksRequestsUntilExplicitSubmit() {
        val bridge = WebViewFormUploadBridge(context, isNetworkAvailable = { true })
        try {
            assertTrue(bridge.warm())
            assertEquals(UploadBridgeState.Idle, bridge.state)
            var captured: WebView? = null
            bridge.inspectWebView { captured = it }
            val web = requireNotNull(captured)
            assertNull(shadowOf(web).lastLoadedUrl)
            assertNull(shadowOf(web).lastLoadDataWithBaseURL)
            assertTrue(web.settings.blockNetworkLoads)
            assertFalse(web.settings.allowFileAccess)
            assertFalse(web.settings.allowContentAccess)
            assertFalse(web.settings.javaScriptCanOpenWindowsAutomatically)
            assertFalse(web.settings.supportMultipleWindows())
            assertTrue(web.settings.mediaPlaybackRequiresUserGesture)
            assertFalse(web.settings.domStorageEnabled)
            assertEquals(WebSettings.LOAD_NO_CACHE, web.settings.cacheMode)
            assertEquals(WebSettings.MIXED_CONTENT_NEVER_ALLOW, web.settings.mixedContentMode)
            assertTrue(web.settings.javaScriptEnabled)

            val client = shadowOf(web).webViewClient
            assertEquals(403, client.shouldInterceptRequest(web, request(endpoint, method = "GET", mainFrame = true))?.statusCode)
            assertTrue(client.shouldOverrideUrlLoading(web, request(endpoint, method = "GET", mainFrame = true)))
        } finally {
            bridge.close()
        }
    }

    @Test
    fun submitDispatchesBootstrapAllowsSingleBlobAndExactPostThenEnforcesNavigationBoundary() {
        val externalLaunches = mutableListOf<Uri>()
        val states = mutableListOf<UploadBridgeState>()
        val bridge = WebViewFormUploadBridge(
            context = context,
            isNetworkAvailable = { true },
            onExternalNavigation = { uri -> externalLaunches += uri; true },
            onStateChanged = { states += it },
        )
        val bitmap = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.GREEN)
        }
        val filesBefore = snapshotDir(context.filesDir)
        val cacheBefore = snapshotDir(context.cacheDir)

        try {
            val req = UploadFormRequest(
                originUrl = origin,
                uploadEndpointUrl = endpoint,
                fileFormFieldName = "encoded_image",
                extraFormFields = mapOf("hl" to "en"),
                multimodalQueryFieldName = "q",
                explicitMultimodalQuery = "red mug",
                allowedNavigationOrigins = setOf("https://www.google.com"),
            )
            val result = bridge.submit(req, bitmap, PixelRegion(2, 2, 14, 14))
            val dispatched = result as UploadSubmissionResult.Dispatched
            assertEquals(origin, dispatched.originUrl)
            assertEquals(endpoint, dispatched.endpointUrl)
            assertTrue(dispatched.encodedByteCount > 0)
            assertTrue(bridge.state is UploadBridgeState.BootstrapPending)
            assertTrue(bridge.hasActiveBlob())

            var capturedWeb: WebView? = null
            bridge.inspectWebView { capturedWeb = it }
            val web = requireNotNull(capturedWeb)
            assertFalse(web.settings.blockNetworkLoads)
            val loaded = requireNotNull(shadowOf(web).lastLoadDataWithBaseURL)
            assertEquals("https://lens.google.com/", loaded.baseUrl)
            assertEquals("text/html", loaded.mimeType)
            assertEquals("UTF-8", loaded.encoding)
            assertNull(loaded.historyUrl)

            val blobUrl = extractBlobUrl(loaded.data)
            val client = shadowOf(web).webViewClient

            // Chromium 145 asks the client about loadDataWithBaseURL's in-memory main document.
            // Allow exactly one top-level HTML data document; it does not authorize other data
            // loads or change the subsequent blob and endpoint checks.
            val bootstrapDocumentUrl = "data:text/html;charset=utf-8;base64,c3ludGhldGlj"
            assertEquals(
                403,
                client.shouldInterceptRequest(
                    web,
                    request(bootstrapDocumentUrl, method = "GET", mainFrame = false),
                )?.statusCode,
            )
            assertNull(
                client.shouldInterceptRequest(
                    web,
                    request(bootstrapDocumentUrl, method = "GET", mainFrame = true),
                ),
            )
            assertEquals(
                403,
                client.shouldInterceptRequest(
                    web,
                    request(bootstrapDocumentUrl, method = "GET", mainFrame = true),
                )?.statusCode,
            )
            assertEquals(
                403,
                client.shouldInterceptRequest(
                    web,
                    request("data:text/plain,not-html", method = "GET", mainFrame = true),
                )?.statusCode,
            )

            // During BootstrapPending, unrelated remote subresources/navigations are blocked.
            assertEquals(403, client.shouldInterceptRequest(web, request("https://evil.test/ping.png", method = "GET", mainFrame = false))?.statusCode)
            assertEquals(403, client.shouldInterceptRequest(web, request("https://lens.google.com/track.js", method = "GET", mainFrame = false))?.statusCode)
            assertTrue(client.shouldOverrideUrlLoading(web, request("https://evil.test/", method = "GET", mainFrame = true)))

            // Single-use RAM blob succeeds once, replay is blocked.
            val blobResp = client.shouldInterceptRequest(
                web,
                request(blobUrl, method = "GET", mainFrame = false, headers = mapOf("Origin" to origin)),
            )
            assertNotNull(blobResp)
            assertEquals(200, blobResp!!.statusCode)
            assertEquals(dispatched.encodedByteCount, blobResp.data.readBytes().size)
            assertEquals(403, client.shouldInterceptRequest(web, request(blobUrl, method = "GET", mainFrame = false))?.statusCode)

            // Exact endpoint main-frame POST transitions to Submitted.
            val postReq = request("https://lens.google.com:443/v3/upload", method = "POST", mainFrame = true)
            assertFalse(client.shouldOverrideUrlLoading(web, postReq))
            assertNull(client.shouldInterceptRequest(web, postReq))
            assertTrue(bridge.state is UploadBridgeState.Submitted)
            assertFalse(bridge.hasActiveBlob())

            // Allowed origins & HTTPS subresources work post-submission; disallowed & internal blob stay blocked.
            val resultsNav = request("https://www.google.com/search?tbs=sbi", method = "GET", mainFrame = true)
            assertFalse(client.shouldOverrideUrlLoading(web, resultsNav))
            assertNull(client.shouldInterceptRequest(web, resultsNav))

            val subresource = request("https://encrypted-tbn0.gstatic.com/thumb.jpg", method = "GET", mainFrame = false)
            assertNull(client.shouldInterceptRequest(web, subresource))
            assertEquals(403, client.shouldInterceptRequest(web, request("http://insecure.test/x.js", method = "GET", mainFrame = false))?.statusCode)
            assertEquals(403, client.shouldInterceptRequest(web, request("file:///data/local/tmp/a", method = "GET", mainFrame = false))?.statusCode)
            assertEquals(403, client.shouldInterceptRequest(web, request(blobUrl, method = "GET", mainFrame = false))?.statusCode)

            // User-tapped external link opens external launcher and does not load inside embedded WebView.
            val externalClick = request("https://example.org/item", method = "GET", mainFrame = true, gesture = true)
            assertTrue(client.shouldOverrideUrlLoading(web, externalClick))
            assertEquals(listOf(Uri.parse("https://example.org/item")), externalLaunches)

            assertFalse(bitmap.isRecycled)
            assertEquals(filesBefore, snapshotDir(context.filesDir))
            assertEquals(cacheBefore, snapshotDir(context.cacheDir))
            assertTrue(states.any { it is UploadBridgeState.BootstrapPending })
            assertTrue(states.any { it is UploadBridgeState.Submitted })
        } finally {
            bridge.close()
            bitmap.recycle()
        }
    }

    @Test
    fun midEncodeCancelAndCloseRacesImmediatelyZeroEncodedBytesAndPreventDispatch() {
        val bitmap = Bitmap.createBitmap(12, 12, Bitmap.Config.ARGB_8888)
        try {
            val backingCancel = byteArrayOf(1, 2, 3, 4, 5)
            lateinit var cancelBridge: WebViewFormUploadBridge
            cancelBridge = WebViewFormUploadBridge(
                context = context,
                isNetworkAvailable = { true },
                imageEncoder = UploadImageEncoder { _, _, format, _ ->
                    cancelBridge.cancelActiveSubmission()
                    UploadEncodedImage(backingCancel, format, 12, 12)
                },
            )
            val cancelResult = cancelBridge.submit(basicRequest(), bitmap)
            assertEquals(UploadSubmissionResult.Failed(UploadFailureReason.SUBMISSION_CANCELLED), cancelResult)
            assertTrue(backingCancel.all { it == 0.toByte() })
            assertFalse(cancelBridge.hasActiveBlob())
            assertEquals(UploadBridgeState.Idle, cancelBridge.state)
            cancelBridge.inspectWebView { assertNull(it) }
            cancelBridge.close()

            val backingClose = byteArrayOf(9, 8, 7, 6)
            lateinit var closeBridge: WebViewFormUploadBridge
            closeBridge = WebViewFormUploadBridge(
                context = context,
                isNetworkAvailable = { true },
                imageEncoder = UploadImageEncoder { _, _, format, _ ->
                    closeBridge.close()
                    UploadEncodedImage(backingClose, format, 12, 12)
                },
            )
            val closeResult = closeBridge.submit(basicRequest(), bitmap)
            assertEquals(UploadSubmissionResult.Failed(UploadFailureReason.CLOSED), closeResult)
            assertTrue(backingClose.all { it == 0.toByte() })
            assertEquals(UploadBridgeState.Closed, closeBridge.state)
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun bootstrapStateCallbackCancellationAndClosePreventStaleDispatch() {
        val bitmap = Bitmap.createBitmap(12, 12, Bitmap.Config.ARGB_8888)
        try {
            lateinit var cancelBridge: WebViewFormUploadBridge
            cancelBridge = WebViewFormUploadBridge(
                context = context,
                isNetworkAvailable = { true },
                onStateChanged = { state ->
                    if (state is UploadBridgeState.BootstrapPending) {
                        cancelBridge.cancelActiveSubmission()
                    }
                },
            )

            assertEquals(
                UploadSubmissionResult.Failed(UploadFailureReason.SUBMISSION_CANCELLED),
                cancelBridge.submit(basicRequest(), bitmap),
            )
            cancelBridge.inspectWebView { web ->
                assertNotNull(web)
                assertTrue(requireNotNull(web).settings.blockNetworkLoads)
                assertNull(shadowOf(requireNotNull(web)).lastLoadDataWithBaseURL)
            }
            assertFalse(cancelBridge.hasActiveBlob())
            assertEquals(UploadBridgeState.Idle, cancelBridge.state)
            cancelBridge.close()

            lateinit var closeBridge: WebViewFormUploadBridge
            var closedWebView: WebView? = null
            closeBridge = WebViewFormUploadBridge(
                context = context,
                isNetworkAvailable = { true },
                createWebView = { WebView(context).also { closedWebView = it } },
                onStateChanged = { state ->
                    if (state is UploadBridgeState.BootstrapPending) closeBridge.close()
                },
            )

            assertEquals(
                UploadSubmissionResult.Failed(UploadFailureReason.CLOSED),
                closeBridge.submit(basicRequest(), bitmap),
            )
            assertEquals(UploadBridgeState.Closed, closeBridge.state)
            assertFalse(closeBridge.hasActiveBlob())
            val releasedWebView = requireNotNull(closedWebView)
            assertTrue(shadowOf(releasedWebView).wasDestroyCalled())
            assertTrue(releasedWebView.settings.blockNetworkLoads)
            assertNull(shadowOf(releasedWebView).lastLoadDataWithBaseURL)
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun bootstrapStateCallbackReplacementPreservesNewSubmissionAndBlob() {
        val bitmap = Bitmap.createBitmap(12, 12, Bitmap.Config.ARGB_8888)
        var replaced = false
        var replacementResult: UploadSubmissionResult? = null
        lateinit var bridge: WebViewFormUploadBridge
        bridge = WebViewFormUploadBridge(
            context = context,
            isNetworkAvailable = { true },
            onStateChanged = { state ->
                if (!replaced && state is UploadBridgeState.BootstrapPending) {
                    replaced = true
                    replacementResult = bridge.submit(basicRequest(), bitmap)
                }
            },
        )
        try {
            assertEquals(
                UploadSubmissionResult.Failed(UploadFailureReason.SUBMISSION_CANCELLED),
                bridge.submit(basicRequest(), bitmap),
            )
            val replacement = replacementResult as UploadSubmissionResult.Dispatched
            assertEquals(UploadBridgeState.BootstrapPending(replacement.submissionId, origin, endpoint), bridge.state)
            assertTrue(bridge.hasActiveBlob())

            bridge.inspectWebView { web ->
                val activeWeb = requireNotNull(web)
                assertFalse(activeWeb.settings.blockNetworkLoads)
                val blobUrl = extractBlobUrl(requireNotNull(shadowOf(activeWeb).lastLoadDataWithBaseURL).data)
                assertEquals(
                    200,
                    shadowOf(activeWeb).webViewClient.shouldInterceptRequest(
                        activeWeb,
                        request(blobUrl, method = "GET", mainFrame = false, headers = mapOf("Origin" to origin)),
                    )?.statusCode,
                )
            }
        } finally {
            bridge.close()
            bitmap.recycle()
        }
    }

    @Test
    fun rapidResubmitCancelAndCloseLifecycleScrubsBlobsAndDestroysWebView() {
        val bridge = WebViewFormUploadBridge(context, isNetworkAvailable = { true })
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        try {
            val first = bridge.submit(basicRequest(), bitmap) as UploadSubmissionResult.Dispatched
            var captured: WebView? = null
            bridge.inspectWebView { captured = it }
            val web = requireNotNull(captured)
            val firstUrl = extractBlobUrl(shadowOf(web).lastLoadDataWithBaseURL!!.data)

            val second = bridge.submit(basicRequest(), bitmap) as UploadSubmissionResult.Dispatched
            assertTrue(second.submissionId > first.submissionId)
            val client = shadowOf(web).webViewClient
            // First token was invalidated and replaced.
            assertEquals(403, client.shouldInterceptRequest(web, request(firstUrl, method = "GET", mainFrame = false))?.statusCode)

            val container = FrameLayout(context)
            assertTrue(bridge.attachTo(container))
            assertSame(web, container.getChildAt(0))

            bridge.cancelActiveSubmission()
            assertEquals(UploadBridgeState.Idle, bridge.state)
            assertFalse(bridge.hasActiveBlob())
            assertTrue(web.settings.blockNetworkLoads)

            bridge.close()
            assertEquals(UploadBridgeState.Closed, bridge.state)
            assertTrue(shadowOf(web).wasDestroyCalled())
            assertTrue(shadowOf(web).wasClearHistoryCalled())
            assertTrue(shadowOf(web).wasClearCacheCalled())
            assertFalse(bridge.attachTo(container))
            assertFalse(bridge.warm())
            assertEquals(UploadSubmissionResult.Failed(UploadFailureReason.CLOSED), bridge.submit(basicRequest(), bitmap))
            bridge.close()
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun offlineUnavailableWebViewInvalidRequestAndMainFrameLoadErrorsDegradeGracefully() {
        var constructed = false
        val offlineBridge = WebViewFormUploadBridge(
            context = context,
            isNetworkAvailable = { false },
            createWebView = { constructed = true; WebView(context) },
        )
        val bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        try {
            assertFalse(offlineBridge.warm())
            assertEquals(UploadSubmissionResult.Failed(UploadFailureReason.OFFLINE), offlineBridge.submit(basicRequest(), bitmap))
            assertFalse(constructed)
            assertEquals(UploadBridgeState.Failed(UploadFailureReason.OFFLINE), offlineBridge.state)
            offlineBridge.close()

            val brokenWebViewBridge = WebViewFormUploadBridge(
                context = context,
                isNetworkAvailable = { true },
                createWebView = { throw IllegalStateException("WebView missing") },
            )
            assertFalse(brokenWebViewBridge.warm())
            assertEquals(
                UploadSubmissionResult.Failed(UploadFailureReason.WEBVIEW_UNAVAILABLE),
                brokenWebViewBridge.submit(basicRequest(), bitmap),
            )
            assertEquals(UploadBridgeState.Failed(UploadFailureReason.WEBVIEW_UNAVAILABLE), brokenWebViewBridge.state)
            brokenWebViewBridge.close()

            val errorBridge = WebViewFormUploadBridge(context, isNetworkAvailable = { true })
            assertTrue(errorBridge.submit(basicRequest(), bitmap) is UploadSubmissionResult.Dispatched)
            var web: WebView? = null
            errorBridge.inspectWebView { web = it }
            val w = requireNotNull(web)
            val blobUrl = extractBlobUrl(shadowOf(w).lastLoadDataWithBaseURL!!.data)
            errorBridge.handleReceivedError(request(endpoint, method = "POST", mainFrame = true), WebViewClient.ERROR_HOST_LOOKUP)
            assertEquals(UploadBridgeState.Failed(UploadFailureReason.OFFLINE), errorBridge.state)
            assertTrue(w.settings.blockNetworkLoads)
            assertFalse(errorBridge.hasActiveBlob())
            assertEquals(403, shadowOf(w).webViewClient.shouldInterceptRequest(w, request(blobUrl, method = "GET", mainFrame = false))?.statusCode)

            // Invalid requests fail cleanly before creating WebView.
            val invalidBridge = WebViewFormUploadBridge(context, isNetworkAvailable = { true })
            assertEquals(
                UploadSubmissionResult.Failed(UploadFailureReason.INVALID_REQUEST),
                invalidBridge.submit(
                    UploadFormRequest(
                        originUrl = origin,
                        uploadEndpointUrl = "https://evil.test/upload",
                        fileFormFieldName = "file",
                    ),
                    bitmap,
                ),
            )
            assertEquals(
                UploadSubmissionResult.Failed(UploadFailureReason.INVALID_REQUEST),
                invalidBridge.submit(
                    UploadFormRequest(
                        originUrl = origin,
                        uploadEndpointUrl = endpoint,
                        fileFormFieldName = "file",
                        explicitMultimodalQuery = "search query",
                    ),
                    bitmap,
                ),
            )
            invalidBridge.close()
            errorBridge.close()
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun searchPackageHasNoNativeHttpClientsOrJsBridges() {
        val cwd = File("").absoluteFile
        val root = listOf(cwd, cwd.resolve("app"), cwd.parentFile)
            .first { it.resolve("src/main/AndroidManifest.xml").isFile }
        val searchDir = root.resolve("src/main/kotlin/com/snipsearch/search")
        val files = searchDir.walkTopDown().filter { it.extension == "kt" }.toList()
        assertTrue(files.isNotEmpty())
        val forbidden = listOf(
            "HttpsURLConnection",
            "HttpURLConnection",
            "Socket(",
            "addJavascriptInterface",
            "JavascriptInterface",
        )
        val hits = files.flatMap { file ->
            val text = file.readText()
            forbidden.filter { token -> text.contains(token) }.map { token -> "${file.name}:$token" }
        }
        assertTrue(hits.joinToString("\n"), hits.isEmpty())
    }

    private fun basicRequest() = UploadFormRequest(
        originUrl = origin,
        uploadEndpointUrl = endpoint,
        fileFormFieldName = "encoded_image",
    )

    private fun extractBlobUrl(html: String): String {
        val match = Regex("""fetch\("([^"]+)"""").find(html)
        assertNotNull("Expected fetch URL in bootstrap HTML", match)
        return match!!.groupValues[1].replace("\\/", "/")
    }

    private fun request(
        url: String,
        method: String,
        mainFrame: Boolean,
        gesture: Boolean = false,
        headers: Map<String, String> = emptyMap(),
    ): WebResourceRequest = object : WebResourceRequest {
        override fun getUrl(): Uri = Uri.parse(url)
        override fun isForMainFrame(): Boolean = mainFrame
        override fun isRedirect(): Boolean = false
        override fun hasGesture(): Boolean = gesture
        override fun getMethod(): String = method
        override fun getRequestHeaders(): Map<String, String> = headers
    }

    private fun snapshotDir(dir: File): List<String> =
        dir.walkTopDown().filter { it.isFile }.map { it.relativeTo(dir).path }.sorted().toList()
}
