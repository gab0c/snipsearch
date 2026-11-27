package com.snipsearch.assistant

import android.app.assist.AssistStructure
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.voice.VisibleActivityInfo
import android.service.voice.VoiceInteractionSession
import android.util.Log
import android.view.Display
import android.view.View
import android.widget.TextView
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.snipsearch.R
import com.snipsearch.extraction.OcrTiming
import com.snipsearch.extraction.isolated.OcrClientCoordinator
import com.snipsearch.extraction.isolated.SessionOcrRunner
import com.snipsearch.security.SessionMemory
import com.snipsearch.ui.overlay.OverlayScreen
import com.snipsearch.ui.theme.SnipTheme

/**
 * Assistant session window.
 *
 * A show that already asked for a screenshot displays it. A show that arrived
 * without [SHOW_WITH_SCREENSHOT] requests the capture after the window is up.
 * Android 17 still strips that follow-up request unless the app is the foreground
 * UID, which is why the tile goes through [TileCaptureActivity]. A refused
 * screenshot that arrived first is remembered by [SessionController] and is not
 * waited on.
 */
class SnipVoiceInteractionSession(
    context: Context,
    private val policy: PowerButtonPolicy = PowerButtonPolicy(),
) : VoiceInteractionSession(context), SavedStateRegistryOwner {
    override val lifecycle = LifecycleRegistry(this)
    private val stateRegistry = SavedStateRegistryController.create(this)
    override val savedStateRegistry get() = stateRegistry.savedStateRegistry
    private val mainHandler = Handler(Looper.getMainLooper())
    private val memory = SessionMemory()
    private val ocrCoordinator = OcrClientCoordinator(context.applicationContext ?: context)
    private val ocrRunner = SessionOcrRunner(ocrCoordinator)
    private val controller = SessionController(
        memory = memory,
        beforeScrub = ::clearOverlay,
        policy = policy,
    )
    private val timeoutRunnable = Runnable { apply(controller.onCaptureTimeout(), showGeneration) }
    private val hideRunnable = Runnable { hide() }

    private val activityWatch = VisibleActivityWatch<ActivityId>()
    private var visibleActivityCallback: VisibleActivityCallback? = null

    private var composeView: ComposeView? = null
    private val overlayScreenshot = mutableStateOf<Bitmap?>(null)
    private val assistByIndex = sortedMapOf<Int, AssistStructure>()
    private val overlayAssist = mutableStateOf<List<AssistStructure>>(emptyList())
    private val fullScreenScanId = mutableLongStateOf(0L)
    private var titleView: TextView? = null
    private var noticeView: TextView? = null
    private var showGeneration = 0
    private var overlayApproved = false
    private var currentTiming: OcrTiming? = null

    init {
        stateRegistry.performAttach()
        stateRegistry.performRestore(null)
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        setTheme(android.R.style.Theme_DeviceDefault_NoActionBar)
    }

    override fun onCreateContentView(): View {
        val root = layoutInflater.inflate(R.layout.session_overlay, null)
        (root as SessionRootLayout).onUserInteraction = activityWatch::onUserInteraction
        root.setViewTreeLifecycleOwner(this)
        root.setViewTreeSavedStateRegistryOwner(this)
        composeView = root.findViewById(R.id.session_compose)
        titleView = root.findViewById(R.id.session_title)
        noticeView = root.findViewById(R.id.session_notice)
        return root
    }

    override fun onPrepareShow(args: Bundle?, showFlags: Int) {
        super.onPrepareShow(args, showFlags)
        val invocationType = InvocationRouter.invocationTypeOf(args)
        val open = InvocationRouter.opensOverlay(
            invocationType,
            policy.action(),
            policy.delegatedPackage(),
        )
        setUiEnabled(open)
        overlayApproved = open
        if (open) {
            val timing = currentTiming ?: OcrTiming.start(invocationType).also { currentTiming = it }
            ocrRunner.warmUp(timing)
        } else {
            ocrRunner.cancelActiveScan()
            fullScreenScanId.longValue = 0L
            currentTiming = null
        }
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)
        val generation = ++showGeneration
        val invocationType = InvocationRouter.invocationTypeOf(args)
        val open = InvocationRouter.opensOverlay(
            invocationType,
            policy.action(),
            policy.delegatedPackage(),
        )
        overlayApproved = open
        if (open && currentTiming == null) {
            val timing = OcrTiming.start(invocationType)
            currentTiming = timing
            ocrRunner.warmUp(timing)
        }
        val screenshotRequested = (showFlags and SHOW_WITH_SCREENSHOT) != 0
        Log.i(TAG, "onShow type=$invocationType screenshotRequested=$screenshotRequested")
        val effect = controller.onShow(invocationType, screenshotRequested)
        if (effect.hideImmediately || effect.delegation != null) {
            overlayApproved = false
            ocrRunner.cancelActiveScan()
            fullScreenScanId.longValue = 0L
        }
        if (open && !effect.hideImmediately) startActivityWatch(generation)
        apply(effect, generation)
    }

    override fun onHandleScreenshot(screenshot: Bitmap?) {
        Log.i(TAG, "onHandleScreenshot present=${screenshot != null}")
        if (screenshot == null) {
            ocrRunner.cancelActiveScan()
            fullScreenScanId.longValue = 0L
            clearAssist()
        }
        val effect = controller.onScreenshot(screenshot)
        if (screenshot != null && memory.screenshot === screenshot && !screenshot.isRecycled) {
            currentTiming?.logCaptureArrived(screenshot.width, screenshot.height)
            if (overlayApproved) {
                val scanId = ocrRunner.nextScanId()
                fullScreenScanId.longValue = scanId
                ocrRunner.startScan(scanId, screenshot, currentTiming)
            }
        }
        apply(effect, showGeneration)
    }

    override fun onHandleAssist(state: AssistState) {
        val structure = state.assistStructure
        val blocked = AssistStructures.isBlocked(structure)
        if (blocked) {
            Log.i(TAG, "assist structure root is blocked")
            ocrRunner.cancelActiveScan()
            fullScreenScanId.longValue = 0L
            clearAssist()
        }
        apply(controller.onAssistBlocked(blocked), showGeneration)
        if (structure != null && controller.acceptsAssist) {
            assistByIndex[state.index] = structure
            val structures = assistByIndex.values.toList()
            overlayAssist.value = structures
            currentTiming?.logAssistArrived(structures.size)
        }
    }

    override fun onHide() {
        stopActivityWatch()
        showGeneration += 1
        mainHandler.removeCallbacks(timeoutRunnable)
        mainHandler.removeCallbacks(hideRunnable)
        controller.onHide()
        ocrRunner.onSessionHide()
        overlayApproved = false
        currentTiming = null
        fullScreenScanId.longValue = 0L
        clearViews()
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        super.onHide()
    }

    override fun onDestroy() {
        stopActivityWatch()
        showGeneration += 1
        mainHandler.removeCallbacksAndMessages(null)
        controller.onDestroy()
        ocrRunner.onSessionDestroy()
        overlayApproved = false
        currentTiming = null
        fullScreenScanId.longValue = 0L
        clearViews()
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        super.onDestroy()
    }

    private fun apply(effect: SessionController.Effect, generation: Int) {
        if (effect.cancelCaptureTimeout) {
            mainHandler.removeCallbacks(timeoutRunnable)
        }
        if (effect.armCaptureTimeout) {
            mainHandler.removeCallbacks(timeoutRunnable)
            mainHandler.postDelayed(timeoutRunnable, controller.captureWaitMs)
        }
        when (effect.ui) {
            SessionController.Ui.WAITING -> showWaiting()
            SessionController.Ui.OVERLAY -> showOverlay()
            SessionController.Ui.NOTICE_SCREEN_ACCESS_OFF -> {
                showNotice(R.string.notice_screen_access_disabled)
            }
            SessionController.Ui.NOTICE_SECURE_WINDOW -> showNotice(R.string.notice_secure_window)
            SessionController.Ui.HIDDEN,
            SessionController.Ui.UNCHANGED,
            -> Unit
        }
        if (effect.requestCapture) {
            val invocationType = effect.captureInvocationType
            mainHandler.post {
                if (generation != showGeneration) {
                    return@post
                }
                show(InvocationRouter.bundleFor(invocationType), CAPTURE_FLAGS)
            }
        }
        effect.delegation?.let(::launchDelegation)
        if (effect.hideAfterMs != null) {
            mainHandler.removeCallbacks(hideRunnable)
            mainHandler.postDelayed(hideRunnable, effect.hideAfterMs)
        }
        if (effect.hideImmediately) {
            clearAssist()
            mainHandler.removeCallbacks(hideRunnable)
            hide()
        }
    }

    private fun showWaiting() {
        clearOverlay()
        noticeView?.visibility = View.GONE
        titleView?.visibility = View.VISIBLE
    }

    private fun showOverlay() {
        noticeView?.visibility = View.GONE
        titleView?.visibility = View.GONE
        val screenshot = memory.screenshot
        if (screenshot != null && !screenshot.isRecycled) {
            val view = composeView ?: return
            if (fullScreenScanId.longValue == 0L && overlayApproved) {
                val scanId = ocrRunner.nextScanId()
                fullScreenScanId.longValue = scanId
                ocrRunner.startScan(scanId, screenshot, currentTiming)
            }
            overlayScreenshot.value = screenshot
            view.visibility = View.VISIBLE
            view.setContent {
                val launchContext = remember(view.context) { SessionActivityContext(view.context, ::hide) }
                CompositionLocalProvider(LocalContext provides launchContext) {
                    SnipTheme {
                        OverlayScreen(
                            screenshot = overlayScreenshot.value,
                            assistStructures = overlayAssist.value,
                            onScreenshotConsumed = {
                                ocrRunner.cancelActiveScan()
                                fullScreenScanId.longValue = 0L
                                overlayScreenshot.value = null
                                memory.scrub()
                            },
                            onDismiss = ::hide,
                            displayId = view.display?.displayId ?: Display.DEFAULT_DISPLAY,
                            ocrRunner = ocrRunner,
                            fullScreenScanId = fullScreenScanId.longValue,
                            timing = currentTiming,
                        )
                    }
                }
            }
        }
    }

    private fun showNotice(message: Int) {
        clearAssist()
        titleView?.visibility = View.GONE
        noticeView?.setText(message)
        noticeView?.visibility = View.VISIBLE
    }

    private fun launchDelegation(voiceCommand: Intent) {
        val explicit = DelegationTargets.explicit(context.packageManager, voiceCommand)
        if (explicit == null) {
            Log.i(TAG, "No activity resolved for power-button delegation")
            return
        }
        try {
            startAssistantActivity(explicit)
        } catch (_: ActivityNotFoundException) {
            Log.i(TAG, "Delegation activity was not found")
        } catch (_: SecurityException) {
            Log.i(TAG, "Delegation activity refused the launch")
        }
    }

    private fun startActivityWatch(generation: Int) {
        stopActivityWatch()
        activityWatch.start()
        val callback = object : VisibleActivityCallback {
            override fun onVisible(activityInfo: VisibleActivityInfo) {
                if (overlayApproved && generation == showGeneration && activityWatch.onVisible(activityInfo.activityId)) hide()
            }
            override fun onInvisible(activityId: ActivityId) {
                if (overlayApproved && generation == showGeneration && activityWatch.onInvisible(activityId)) hide()
            }
        }
        visibleActivityCallback = callback
        registerVisibleActivityCallback(context.mainExecutor, callback)
    }

    private fun stopActivityWatch() {
        activityWatch.stop()
        visibleActivityCallback?.let { unregisterVisibleActivityCallback(it) }
        visibleActivityCallback = null
    }

    private fun clearViews() {
        clearAssist()
        noticeView?.visibility = View.GONE
        titleView?.visibility = View.GONE
    }

    private fun clearOverlay() {
        ocrRunner.cancelActiveScan()
        fullScreenScanId.longValue = 0L
        composeView?.let {
            it.visibility = View.GONE
            it.disposeComposition()
        }
        overlayScreenshot.value = null
    }

    private fun clearAssist() {
        assistByIndex.clear()
        overlayAssist.value = emptyList()
    }

    companion object {
        private const val TAG = "SnipSession"

        private const val CAPTURE_FLAGS = SnipVoiceInteractionService.CAPTURE_FLAGS
    }
}
