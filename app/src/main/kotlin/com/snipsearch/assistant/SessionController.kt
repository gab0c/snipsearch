package com.snipsearch.assistant

import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import com.snipsearch.security.MemoryScrubber
import com.snipsearch.security.SessionMemory

/**
 * Decides what a session does with an invocation, a screenshot, and assist data,
 * and when its buffers are scrubbed.
 *
 * A refused screenshot (`null`) can arrive before [onShow]. It is remembered for
 * [refusalMemoryMs] and applied when the session is shown. The session does not
 * wait longer than [captureWaitMs] for a capture that never arrives.
 *
 * `onHandleScreenshot(null)` means screen capture is off in system settings. A secure
 * window is a separate signal: [onAssistBlocked] with `true`.
 */
class SessionController(
    val memory: SessionMemory = SessionMemory(),
    private val beforeScrub: () -> Unit = {},
    private val policy: PowerButtonPolicy = PowerButtonPolicy(),
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
    private val refusalMemoryMs: Long = REFUSAL_MEMORY_MS,
    val captureWaitMs: Long = CAPTURE_WAIT_MS,
    val noticeVisibleMs: Long = NOTICE_VISIBLE_MS,
) {
    enum class Ui {
        UNCHANGED,
        WAITING,
        OVERLAY,
        NOTICE_SCREEN_ACCESS_OFF,
        NOTICE_SECURE_WINDOW,
        HIDDEN,
    }

    data class Effect(
        val ui: Ui = Ui.UNCHANGED,
        val hideImmediately: Boolean = false,
        val hideAfterMs: Long? = null,
        val requestCapture: Boolean = false,
        val captureInvocationType: Int = InvocationRouter.MISSING_INVOCATION_TYPE,
        val armCaptureTimeout: Boolean = false,
        val cancelCaptureTimeout: Boolean = false,
        val delegation: Intent? = null,
    )

    private var accepting = true
    private var shown = false
    private var closed = false
    private var terminal = false
    private var secure = false
    private var waitingForCapture = false
    private var captureRequested = false
    private var refusalAt: Long? = null

    val acceptsAssist: Boolean get() = accepting && !closed && !terminal && !secure

    fun onScreenshot(bitmap: Bitmap?): Effect {
        if (closed || !accepting || terminal) {
            MemoryScrubber.recycle(bitmap)
            return Effect()
        }
        if (bitmap == null) {
            refusalAt = clock()
            waitingForCapture = false
            if (shown) {
                return notice(Ui.NOTICE_SCREEN_ACCESS_OFF)
            }
            scrub()
            return Effect(cancelCaptureTimeout = true)
        }
        if (secure) {
            MemoryScrubber.recycle(bitmap)
            return if (shown) notice(Ui.NOTICE_SECURE_WINDOW) else Effect()
        }
        refusalAt = null
        waitingForCapture = false
        beforeScrub()
        memory.adoptScreenshot(bitmap)
        return if (shown) {
            Effect(ui = Ui.OVERLAY, cancelCaptureTimeout = true)
        } else {
            Effect(cancelCaptureTimeout = true)
        }
    }

    fun onAssistBlocked(blocked: Boolean): Effect {
        if (!blocked || closed || !accepting || terminal) {
            return Effect()
        }
        secure = true
        waitingForCapture = false
        return if (shown) {
            notice(Ui.NOTICE_SECURE_WINDOW)
        } else {
            scrub()
            Effect(cancelCaptureTimeout = true)
        }
    }

    fun onShow(invocationType: Int, screenshotAlreadyRequested: Boolean): Effect {
        if (closed) {
            return Effect(ui = Ui.HIDDEN, hideImmediately = true, cancelCaptureTimeout = true)
        }
        if (terminal) {
            return Effect()
        }
        accepting = true
        shown = true
        val decision = InvocationRouter.decide(
            invocationType,
            policy.action(),
            policy.delegatedPackage(),
        )
        return when (decision.kind) {
            InvocationRouter.Kind.SCRUB_AND_HIDE -> hideNow()
            InvocationRouter.Kind.DELEGATE -> hideNow(decision.delegation)
            InvocationRouter.Kind.OPEN_OVERLAY -> openOverlay(invocationType, screenshotAlreadyRequested)
        }
    }

    fun onCaptureTimeout(): Effect {
        if (closed || terminal || !waitingForCapture) {
            return Effect()
        }
        waitingForCapture = false
        return notice(Ui.NOTICE_SCREEN_ACCESS_OFF)
    }

    fun onHide() {
        scrub()
        accepting = false
        shown = false
        terminal = false
        secure = false
        waitingForCapture = false
        captureRequested = false
        refusalAt = null
    }

    fun onDestroy() {
        onHide()
        closed = true
        accepting = false
    }

    private fun openOverlay(invocationType: Int, screenshotAlreadyRequested: Boolean): Effect {
        if (secure) {
            return notice(Ui.NOTICE_SECURE_WINDOW)
        }
        if (freshRefusal()) {
            return notice(Ui.NOTICE_SCREEN_ACCESS_OFF)
        }
        val screenshot = memory.screenshot
        if (screenshot != null && !screenshot.isRecycled) {
            return Effect(ui = Ui.OVERLAY, cancelCaptureTimeout = true)
        }
        val requestCapture = !screenshotAlreadyRequested && !captureRequested
        if (requestCapture) {
            captureRequested = true
        }
        waitingForCapture = true
        return Effect(
            ui = Ui.WAITING,
            requestCapture = requestCapture,
            captureInvocationType = invocationType,
            armCaptureTimeout = true,
        )
    }

    private fun freshRefusal(): Boolean {
        val at = refusalAt ?: return false
        if (clock() - at > refusalMemoryMs) {
            refusalAt = null
            return false
        }
        return true
    }

    private fun notice(ui: Ui): Effect {
        terminal = true
        waitingForCapture = false
        scrub()
        return Effect(
            ui = ui,
            hideAfterMs = noticeVisibleMs,
            cancelCaptureTimeout = true,
        )
    }

    private fun hideNow(delegation: Intent? = null): Effect {
        terminal = true
        waitingForCapture = false
        scrub()
        return Effect(
            ui = Ui.HIDDEN,
            hideImmediately = true,
            cancelCaptureTimeout = true,
            delegation = delegation,
        )
    }

    private fun scrub() {
        beforeScrub()
        memory.scrub()
    }

    companion object {
        const val REFUSAL_MEMORY_MS = 3_000L
        const val CAPTURE_WAIT_MS = 2_500L
        const val NOTICE_VISIBLE_MS = 1_500L
    }
}
