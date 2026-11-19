package com.snipsearch.assistant

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.voice.VoiceInteractionSession
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.snipsearch.R
import com.snipsearch.security.SessionMemory

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
) : VoiceInteractionSession(context) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val memory = SessionMemory()
    private val controller = SessionController(
        memory = memory,
        beforeScrub = { screenshotView?.setImageDrawable(null) },
        policy = policy,
    )
    private val timeoutRunnable = Runnable { apply(controller.onCaptureTimeout(), showGeneration) }
    private val hideRunnable = Runnable { hide() }

    private var screenshotView: ImageView? = null
    private var titleView: TextView? = null
    private var noticeView: TextView? = null
    private var showGeneration = 0

    init {
        setTheme(android.R.style.Theme_DeviceDefault_NoActionBar)
    }

    override fun onCreateContentView(): View {
        val root = layoutInflater.inflate(R.layout.session_overlay, null)
        screenshotView = root.findViewById(R.id.session_screenshot)
        titleView = root.findViewById(R.id.session_title)
        noticeView = root.findViewById(R.id.session_notice)
        return root
    }

    override fun onPrepareShow(args: Bundle?, showFlags: Int) {
        super.onPrepareShow(args, showFlags)
        val open = InvocationRouter.opensOverlay(
            InvocationRouter.invocationTypeOf(args),
            policy.action(),
            policy.delegatedPackage(),
        )
        setUiEnabled(open)
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        val generation = ++showGeneration
        val invocationType = InvocationRouter.invocationTypeOf(args)
        val screenshotRequested = (showFlags and SHOW_WITH_SCREENSHOT) != 0
        Log.i(TAG, "onShow type=$invocationType screenshotRequested=$screenshotRequested")
        apply(controller.onShow(invocationType, screenshotRequested), generation)
    }

    override fun onHandleScreenshot(screenshot: Bitmap?) {
        Log.i(TAG, "onHandleScreenshot present=${screenshot != null}")
        apply(controller.onScreenshot(screenshot), showGeneration)
    }

    override fun onHandleAssist(state: AssistState) {
        val blocked = AssistStructures.isBlocked(state.assistStructure)
        if (blocked) {
            Log.i(TAG, "assist structure root is blocked")
        }
        apply(controller.onAssistBlocked(blocked), showGeneration)
    }

    override fun onHide() {
        showGeneration += 1
        mainHandler.removeCallbacks(timeoutRunnable)
        mainHandler.removeCallbacks(hideRunnable)
        controller.onHide()
        clearViews()
        super.onHide()
    }

    override fun onDestroy() {
        showGeneration += 1
        mainHandler.removeCallbacksAndMessages(null)
        controller.onDestroy()
        clearViews()
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
            mainHandler.removeCallbacks(hideRunnable)
            hide()
        }
    }

    private fun showWaiting() {
        noticeView?.visibility = View.GONE
        titleView?.visibility = View.VISIBLE
    }

    private fun showOverlay() {
        noticeView?.visibility = View.GONE
        titleView?.visibility = View.GONE
        val screenshot = memory.screenshot
        if (screenshot != null && !screenshot.isRecycled) {
            screenshotView?.setImageBitmap(screenshot)
        }
    }

    private fun showNotice(message: Int) {
        screenshotView?.setImageDrawable(null)
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

    private fun clearViews() {
        screenshotView?.setImageDrawable(null)
        noticeView?.visibility = View.GONE
        titleView?.visibility = View.GONE
    }

    companion object {
        private const val TAG = "SnipSession"

        private const val CAPTURE_FLAGS = SnipVoiceInteractionService.CAPTURE_FLAGS
    }
}
