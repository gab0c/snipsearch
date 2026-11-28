package com.snipsearch.search

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.net.Uri
import android.view.ViewGroup
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.snipsearch.extraction.PixelRegion
import java.security.SecureRandom

/**
 * Milestone 5 transport foundation for in-memory Chromium multipart `<form>` visual search uploads.
 *
 * Lifecycle and integration boundary:
 * - Standalone transport foundation: UI overlay, `SearchProviderConfig` presets, cookie policy
 *   wiring, and `VoiceInteractionSession` integration are intentionally deferred.
 * - No automatic image upload or extracted-text submission occurs: uploads begin only when the
 *   caller invokes [submit].
 * - Callers must invoke [cancelActiveSubmission] on intermediate cancellation/hide and [close]
 *   on teardown so all in-RAM encoded blobs, active streams, and owned `WebView` instances are
 *   scrubbed and released promptly.
 */
class WebViewFormUploadBridge(
    private val context: Context,
    private val isNetworkAvailable: () -> Boolean = { UploadNetworkCheck.hasInternetCapability(context) },
    private val createWebView: () -> WebView = { WebView(context) },
    private val imageEncoder: UploadImageEncoder = DefaultUploadImageEncoder(),
    private val onExternalNavigation: ((Uri) -> Boolean)? = null,
    private val onStateChanged: ((UploadBridgeState) -> Unit)? = null,
) : AutoCloseable {
    private enum class Phase {
        IDLE,
        BOOTSTRAP_PENDING,
        SUBMITTED,
        FAILED,
    }

    private val lock = Any()
    private val random = SecureRandom()
    private val blobStore = UploadBlobStore(random)

    private var ownedWebView: WebView? = null
    @Volatile
    private var ownerHandler = Handler(Looper.myLooper() ?: Looper.getMainLooper())
    private var webAssigned = false
    private var generation = 0L
    private var closed = false
    private var phase = Phase.IDLE
    private var activeSubmissionId = 0L
    private var activeRequest: ValidatedUploadRequest? = null
    private var bootstrapDocumentRequestConsumed = false

    @Volatile
    private var currentState: UploadBridgeState = UploadBridgeState.Idle

    val state: UploadBridgeState
        get() = currentState

    /**
     * Lazily initializes and hardens the owned [WebView] with network loads blocked.
     *
     * Returns `false` if the bridge is closed, offline (including GrapheneOS Network toggle OFF),
     * or WebView construction fails.
     */
    fun warm(): Boolean {
        val stateUpdate: UploadBridgeState?
        val warmed: Boolean
        synchronized(lock) {
            if (closed || !isNetworkAvailable()) {
                return false
            }
            if (ownedWebView != null) {
                return true
            }
            val result = warmLocked()
            warmed = result.success
            stateUpdate = result.stateUpdate
        }
        stateUpdate?.let(::notifyState)
        return warmed
    }

    /**
     * Re-encodes [bitmap] (or [cropRegion]) in RAM without metadata, registers a single-use
     * exact-origin blob token, and dispatches the Chromium multipart `<form>` POST bootstrap.
     */
    fun submit(
        request: UploadFormRequest,
        bitmap: Bitmap,
        cropRegion: PixelRegion? = null,
    ): UploadSubmissionResult {
        val validated = request.validate() ?: run {
            return failInvalidRequest(UploadFailureReason.INVALID_REQUEST)
        }

        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
            return failInvalidRequest(UploadFailureReason.INVALID_IMAGE)
        }
        if (cropRegion != null && (
                cropRegion.width <= 0 ||
                    cropRegion.height <= 0 ||
                    cropRegion.left < 0 ||
                    cropRegion.top < 0 ||
                    cropRegion.right > bitmap.width ||
                    cropRegion.bottom > bitmap.height
                )
        ) {
            return failInvalidRequest(UploadFailureReason.INVALID_IMAGE)
        }

        val submissionId: Long
        var pendingState: UploadBridgeState? = null
        synchronized(lock) {
            if (closed) {
                return UploadSubmissionResult.Failed(UploadFailureReason.CLOSED)
            }
            if (!isNetworkAvailable()) {
                generation++
                activeSubmissionId = 0L
                activeRequest = null
                phase = Phase.FAILED
                ownedWebView?.let { web ->
                    web.settings.blockNetworkLoads = true
                    web.stopLoading()
                }
                blobStore.scrubAll()
                pendingState = UploadBridgeState.Failed(UploadFailureReason.OFFLINE)
                currentState = pendingState
                submissionId = -1L
            } else {
                generation++
                submissionId = generation
                activeSubmissionId = 0L
                activeRequest = null
                phase = Phase.IDLE
                bootstrapDocumentRequestConsumed = false
                ownedWebView?.let { web ->
                    web.settings.blockNetworkLoads = true
                    web.stopLoading()
                }
                blobStore.scrubAll()
            }
        }
        if (submissionId < 0L) {
            pendingState?.let(::notifyState)
            return UploadSubmissionResult.Failed(UploadFailureReason.OFFLINE)
        }

        val encoded = imageEncoder.encode(
            bitmap = bitmap,
            cropRegion = cropRegion,
            format = validated.imageFormat,
            quality = validated.imageQuality,
        ) ?: return failAfterEncodeAttempt(submissionId, UploadFailureReason.IMAGE_ENCODE_FAILED)

        val prepared = encoded.use { ownedEncoded ->
            synchronized(lock) {
                when {
                    closed -> PrepareOutcome.Failed(UploadFailureReason.CLOSED, null)
                    generation != submissionId ->
                        PrepareOutcome.Failed(UploadFailureReason.SUBMISSION_CANCELLED, null)
                    !isNetworkAvailable() -> {
                        activeSubmissionId = 0L
                        activeRequest = null
                        phase = Phase.FAILED
                        ownedWebView?.let { web ->
                            web.settings.blockNetworkLoads = true
                            web.stopLoading()
                        }
                        val nextState = UploadBridgeState.Failed(UploadFailureReason.OFFLINE)
                        currentState = nextState
                        PrepareOutcome.Failed(UploadFailureReason.OFFLINE, nextState)
                    }
                    else -> {
                        val previousParent = if (webAssigned) {
                            val retired = ownedWebView
                            ownedWebView = null
                            webAssigned = false
                            val parent = retired?.parent as? ViewGroup
                            retired?.let(UploadWebViewHardening::release)
                            parent
                        } else null
                        val warmOutcome = warmLocked()
                        if (warmOutcome.success && previousParent != null) attachTo(previousParent)
                        val web = ownedWebView
                        if (!warmOutcome.success || web == null) {
                            activeSubmissionId = 0L
                            activeRequest = null
                            phase = Phase.FAILED
                            val nextState = warmOutcome.stateUpdate
                                ?: UploadBridgeState.Failed(UploadFailureReason.WEBVIEW_UNAVAILABLE)
                            currentState = nextState
                            PrepareOutcome.Failed(UploadFailureReason.WEBVIEW_UNAVAILABLE, nextState)
                        } else {
                            val bytes = ownedEncoded.detachBytes()
                            if (bytes == null || bytes.isEmpty()) {
                                activeSubmissionId = 0L
                                activeRequest = null
                                phase = Phase.FAILED
                                val nextState =
                                    UploadBridgeState.Failed(UploadFailureReason.IMAGE_ENCODE_FAILED)
                                currentState = nextState
                                PrepareOutcome.Failed(
                                    UploadFailureReason.IMAGE_ENCODE_FAILED,
                                    nextState,
                                )
                            } else {
                                val registeredBlob = blobStore.register(
                                    submissionId = submissionId,
                                    expectedOrigin = validated.canonicalOrigin,
                                    format = validated.imageFormat,
                                    ownedBytes = bytes,
                                )
                                val bootstrapHtml =
                                    UploadBootstrapHtml.generate(validated, registeredBlob, random)
                                webAssigned = true
                                activeSubmissionId = submissionId
                                activeRequest = validated
                                phase = Phase.BOOTSTRAP_PENDING
                                val bootstrapState = UploadBridgeState.BootstrapPending(
                                    submissionId = submissionId,
                                    originUrl = validated.canonicalOrigin,
                                    endpointUrl = validated.canonicalEndpointUrl,
                                )
                                currentState = bootstrapState
                                PrepareOutcome.Ready(
                                    webView = web,
                                    bootstrapHtml = bootstrapHtml,
                                    registeredBlob = registeredBlob,
                                    bootstrapState = bootstrapState,
                                )
                            }
                        }
                    }
                }
            }
        }

        return when (prepared) {
            is PrepareOutcome.Failed -> {
                prepared.stateUpdate?.let(::notifyState)
                UploadSubmissionResult.Failed(prepared.reason)
            }
            is PrepareOutcome.Ready -> {
                notifyState(prepared.bootstrapState)
                val dispatchFailure = synchronized(lock) {
                    when {
                        closed -> return UploadSubmissionResult.Failed(UploadFailureReason.CLOSED)
                        generation != submissionId ||
                            activeSubmissionId != submissionId ||
                            phase != Phase.BOOTSTRAP_PENDING ||
                            ownedWebView !== prepared.webView ->
                            return UploadSubmissionResult.Failed(UploadFailureReason.SUBMISSION_CANCELLED)
                    }
                    try {
                        // Keep validation and WebView dispatch in one critical section so a
                        // concurrent cancel or replacement cannot invalidate this submission
                        // between the ownership check and re-enabling network loads.
                        prepared.webView.stopLoading()
                        prepared.webView.settings.blockNetworkLoads = false
                        prepared.webView.onResume()
                        prepared.webView.loadDataWithBaseURL(
                            UploadOriginPolicy.baseUrlForOrigin(validated.canonicalOrigin),
                            prepared.bootstrapHtml,
                            "text/html",
                            "UTF-8",
                            null,
                        )
                        null
                    } catch (_: SecurityException) {
                        UploadFailureReason.LOAD_ERROR
                    } catch (_: RuntimeException) {
                        UploadFailureReason.LOAD_ERROR
                    }
                }
                if (dispatchFailure != null) {
                    failActiveSubmission(submissionId, dispatchFailure)
                    UploadSubmissionResult.Failed(dispatchFailure)
                } else {
                    UploadSubmissionResult.Dispatched(
                        submissionId = submissionId,
                        originUrl = validated.canonicalOrigin,
                        endpointUrl = validated.canonicalEndpointUrl,
                        encodedByteCount = prepared.registeredBlob.byteCount,
                    )
                }
            }
        }
    }

    /** Attaches the hardened owned [WebView] to [parent] when available and active. */
    fun attachTo(parent: ViewGroup): Boolean {
        val web = synchronized(lock) {
            if (closed || phase == Phase.FAILED) null else ownedWebView
        } ?: return false
        if (web.parent !== parent) {
            (web.parent as? ViewGroup)?.removeView(web)
            parent.addView(
                web,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        }
        return true
    }

    /**
     * Cancels any in-flight upload bootstrap or active navigation, zeroes all pending and
     * streaming image buffers in RAM, blocks network loads, and resets state to [UploadBridgeState.Idle].
     */
    fun cancelActiveSubmission() {
        var nextState: UploadBridgeState? = null
        synchronized(lock) {
            if (closed) return
            generation++
            activeSubmissionId = 0L
            activeRequest = null
            phase = Phase.IDLE
            ownedWebView?.let { web ->
                web.settings.blockNetworkLoads = true
                web.stopLoading()
            }
            blobStore.scrubAll()
            nextState = UploadBridgeState.Idle
            currentState = nextState
        }
        nextState?.let(::notifyState)
    }

    /**
     * Terminal teardown matching session hide/destroy: zeroes all RAM buffers, blocks network
     * loads, clears WebView history/cache/form data, and destroys the owned [WebView].
     */
    override fun close() {
        val webToRelease: WebView?
        synchronized(lock) {
            if (closed) return
            closed = true
            generation++
            activeSubmissionId = 0L
            activeRequest = null
            phase = Phase.FAILED
            webToRelease = ownedWebView
            ownedWebView = null
            currentState = UploadBridgeState.Closed
        }
        blobStore.scrubAll()
        webToRelease?.let(UploadWebViewHardening::release)
        notifyState(UploadBridgeState.Closed)
    }

    internal fun inspectWebView(inspect: (WebView?) -> Unit) {
        val web = synchronized(lock) { ownedWebView }
        inspect(web)
    }

    internal fun hasActiveBlob(): Boolean = blobStore.hasActiveBlob()

    internal fun activeStreamCount(): Int = blobStore.activeStreamCount()

    internal fun handleInterceptRequest(request: WebResourceRequest?): WebResourceResponse? {
        when (val blobOutcome = blobStore.intercept(request)) {
            is BlobInterceptOutcome.Blocked -> return blobOutcome.response
            is BlobInterceptOutcome.Served -> {
                val allowed = synchronized(lock) {
                    !closed &&
                        phase == Phase.BOOTSTRAP_PENDING &&
                        activeSubmissionId == blobOutcome.submissionId
                }
                if (!allowed) {
                    blobOutcome.response.data?.close()
                    return blobStore.blockedBlobResponse()
                }
                return blobOutcome.response
            }
            BlobInterceptOutcome.NotBlobRequest -> Unit
        }

        if (request == null) return blobStore.blockedBlobResponse()

        var submittedState: UploadBridgeState? = null
        val allowed = synchronized(lock) {
            val req = activeRequest
            if (closed || req == null) {
                false
            } else {
                when (phase) {
                    Phase.IDLE, Phase.FAILED -> false
                    Phase.BOOTSTRAP_PENDING -> {
                        if (isBootstrapDocumentRequest(request) && !bootstrapDocumentRequestConsumed) {
                            bootstrapDocumentRequestConsumed = true
                            true
                        } else if (UploadOriginPolicy.isExactEndpointPost(request, req.canonicalEndpointUrl)) {
                            phase = Phase.SUBMITTED
                            blobStore.scrubPendingOnly()
                            val next = UploadBridgeState.Submitted(
                                submissionId = activeSubmissionId,
                                originUrl = req.canonicalOrigin,
                                endpointUrl = req.canonicalEndpointUrl,
                            )
                            currentState = next
                            submittedState = next
                            true
                        } else {
                            false
                        }
                    }
                    Phase.SUBMITTED -> {
                        if (request.isForMainFrame) {
                            UploadOriginPolicy.isAllowedMainFrameNavigation(request.url, req.effectiveAllowedOrigins)
                        } else {
                            UploadOriginPolicy.isAllowedHttpsSubresource(request.url)
                        }
                    }
                }
            }
        }
        submittedState?.let(::notifyState)
        return if (allowed) null else blobStore.blockedBlobResponse()
    }

    internal fun handleOverrideUrlLoading(request: WebResourceRequest?): Boolean {
        if (request == null || UploadOriginPolicy.isInternalBlobUrl(request.url)) {
            return true
        }

        var externalUriToLaunch: Uri? = null
        val shouldOverride = synchronized(lock) {
            val req = activeRequest
            if (closed || req == null) {
                true
            } else {
                when (phase) {
                    Phase.IDLE, Phase.FAILED -> true
                    Phase.BOOTSTRAP_PENDING -> {
                        !UploadOriginPolicy.isExactEndpointPost(request, req.canonicalEndpointUrl)
                    }
                    Phase.SUBMITTED -> {
                        if (request.isForMainFrame) {
                            val isAllowed = UploadOriginPolicy.isAllowedMainFrameNavigation(
                                request.url,
                                req.effectiveAllowedOrigins,
                            )
                            if (isAllowed) {
                                false
                            } else {
                                val scheme = request.url.scheme
                                val isWebLink = scheme == "https" || scheme == "http"
                                if (request.hasGesture() &&
                                    !request.isRedirect &&
                                    request.method == "GET" &&
                                    isWebLink
                                ) {
                                    externalUriToLaunch = request.url
                                }
                                true
                            }
                        } else {
                            !UploadOriginPolicy.isAllowedHttpsSubresource(request.url)
                        }
                    }
                }
            }
        }

        externalUriToLaunch?.let { uri ->
            onExternalNavigation?.invoke(uri)
        }
        return shouldOverride
    }

    internal fun handleReceivedError(
        request: WebResourceRequest?,
        errorCode: Int = WebViewClient.ERROR_UNKNOWN,
    ) {
        if (request?.isForMainFrame != true) return
        val reason = if (!isNetworkAvailable() || errorCode in NETWORK_FAILURE_ERROR_CODES) {
            UploadFailureReason.OFFLINE
        } else {
            UploadFailureReason.LOAD_ERROR
        }

        var nextState: UploadBridgeState? = null
        synchronized(lock) {
            val req = activeRequest ?: return
            if (closed || (phase != Phase.BOOTSTRAP_PENDING && phase != Phase.SUBMITTED)) return
            val matchesSubmittedScope = UploadOriginPolicy.isAllowedMainFrameNavigation(
                request.url,
                req.effectiveAllowedOrigins,
            ) || UploadOriginPolicy.isInternalBlobUrl(request.url)
            if (!matchesSubmittedScope) return

            generation++
            activeSubmissionId = 0L
            activeRequest = null
            phase = Phase.FAILED
            ownedWebView?.let { web ->
                web.settings.blockNetworkLoads = true
                web.stopLoading()
            }
            nextState = UploadBridgeState.Failed(reason)
            currentState = nextState
        }
        if (nextState != null) {
            blobStore.scrubAll()
            notifyState(nextState)
        }
    }

    private fun warmLocked(): WarmResult {
        if (ownedWebView != null) {
            return WarmResult(success = true, stateUpdate = null)
        }
        return try {
            val web = createWebView()
            ownerHandler = Handler(requireNotNull(Looper.myLooper()))
            UploadWebViewHardening.harden(web)
            web.webViewClient = BridgeWebViewClient()
            ownedWebView = web
            WarmResult(success = true, stateUpdate = null)
        } catch (_: RuntimeException) {
            ownedWebView = null
            phase = Phase.FAILED
            val failed = UploadBridgeState.Failed(UploadFailureReason.WEBVIEW_UNAVAILABLE)
            currentState = failed
            WarmResult(success = false, stateUpdate = failed)
        }
    }

    /**
     * Android 17 Chromium asks shouldInterceptRequest about the `data:` document produced by
     * loadDataWithBaseURL before it renders the app-authored bootstrap. Let that one top-level
     * HTML document through without decoding or inspecting its payload; all later data documents
     * and all subframe data loads remain blocked.
     */
    private fun isBootstrapDocumentRequest(request: WebResourceRequest): Boolean {
        if (!request.isForMainFrame || request.isRedirect || request.method != "GET") return false
        val uri = request.url
        if (uri.scheme != "data") return false
        val metadata = uri.schemeSpecificPart.substringBefore(',', missingDelimiterValue = "")
        return metadata.substringBefore(';').equals("text/html", ignoreCase = true)
    }

    private fun failInvalidRequest(reason: UploadFailureReason): UploadSubmissionResult.Failed {
        var nextState: UploadBridgeState? = null
        synchronized(lock) {
            if (closed) return UploadSubmissionResult.Failed(UploadFailureReason.CLOSED)
            generation++
            activeSubmissionId = 0L
            activeRequest = null
            phase = Phase.FAILED
            ownedWebView?.let { web ->
                web.settings.blockNetworkLoads = true
                web.stopLoading()
            }
            nextState = UploadBridgeState.Failed(reason)
            currentState = nextState
        }
        blobStore.scrubAll()
        nextState?.let(::notifyState)
        return UploadSubmissionResult.Failed(reason)
    }

    private fun failAfterEncodeAttempt(
        submissionId: Long,
        reason: UploadFailureReason,
    ): UploadSubmissionResult.Failed {
        var resultReason = reason
        var nextState: UploadBridgeState? = null
        synchronized(lock) {
            if (closed) {
                resultReason = UploadFailureReason.CLOSED
            } else if (generation != submissionId) {
                resultReason = UploadFailureReason.SUBMISSION_CANCELLED
            } else {
                activeSubmissionId = 0L
                activeRequest = null
                phase = Phase.FAILED
                ownedWebView?.let { web ->
                    web.settings.blockNetworkLoads = true
                    web.stopLoading()
                }
                val failed = UploadBridgeState.Failed(reason)
                currentState = failed
                nextState = failed
            }
        }
        nextState?.let(::notifyState)
        return UploadSubmissionResult.Failed(resultReason)
    }

    private fun failActiveSubmission(submissionId: Long, reason: UploadFailureReason) {
        var nextState: UploadBridgeState? = null
        synchronized(lock) {
            if (closed || activeSubmissionId != submissionId) return
            generation++
            activeSubmissionId = 0L
            activeRequest = null
            phase = Phase.FAILED
            ownedWebView?.let { web ->
                web.settings.blockNetworkLoads = true
                web.stopLoading()
            }
            nextState = UploadBridgeState.Failed(reason)
            currentState = nextState
        }
        blobStore.scrubAll()
        nextState?.let(::notifyState)
    }

    private fun notifyState(state: UploadBridgeState) {
        val listener = onStateChanged ?: return
        val version = synchronized(lock) { generation }
        val deliver = Runnable {
            if (synchronized(lock) { currentState === state && generation == version }) listener(state)
        }
        val handler = ownerHandler
        if (Looper.myLooper() === handler.looper) deliver.run() else handler.post(deliver)
    }

    private inner class BridgeWebViewClient : WebViewClient() {
        override fun shouldInterceptRequest(
            view: WebView?,
            request: WebResourceRequest?,
        ): WebResourceResponse? = synchronized(lock) {
            if (view == null || view !== ownedWebView) blobStore.blockedBlobResponse()
            else handleInterceptRequest(request)
        }

        override fun shouldOverrideUrlLoading(
            view: WebView?,
            request: WebResourceRequest?,
        ): Boolean = synchronized(lock) {
            view == null || view !== ownedWebView || handleOverrideUrlLoading(request)
        }

        override fun onReceivedError(
            view: WebView?,
            request: WebResourceRequest?,
            error: WebResourceError?,
        ) {
            synchronized(lock) {
                if (view != null && view === ownedWebView) {
                    handleReceivedError(request, error?.errorCode ?: ERROR_UNKNOWN)
                }
            }
        }
    }

    private data class WarmResult(
        val success: Boolean,
        val stateUpdate: UploadBridgeState?,
    )

    private sealed interface PrepareOutcome {
        data class Failed(
            val reason: UploadFailureReason,
            val stateUpdate: UploadBridgeState?,
        ) : PrepareOutcome

        data class Ready(
            val webView: WebView,
            val bootstrapHtml: String,
            val registeredBlob: RegisteredUploadBlob,
            val bootstrapState: UploadBridgeState,
        ) : PrepareOutcome
    }

    companion object {
        private val NETWORK_FAILURE_ERROR_CODES = setOf(
            WebViewClient.ERROR_CONNECT,
            WebViewClient.ERROR_HOST_LOOKUP,
            WebViewClient.ERROR_IO,
            WebViewClient.ERROR_TIMEOUT,
        )
    }
}
