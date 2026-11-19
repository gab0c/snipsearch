package com.snipsearch.assistant

import android.app.assist.AssistStructure
import android.content.Intent
import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class SessionControllerTest {
    private var now = 1_000L

    @Test
    fun hideZeroesImageBuffersAndRecyclesTheScreenshot() {
        val bitmap = bitmap()
        val first = byteArrayOf(1, 2, 3, 4)
        val second = byteArrayOf(9, 8, 7)
        val controller = controller()
        controller.memory.addImageBuffer(first)
        controller.memory.addImageBuffer(second)
        controller.onScreenshot(bitmap)
        controller.onShow(InvocationRouter.INVOCATION_TYPE_GESTURE, screenshotAlreadyRequested = true)

        assertEquals(SessionController.Ui.OVERLAY, controller.onShow(
            InvocationRouter.INVOCATION_TYPE_GESTURE,
            screenshotAlreadyRequested = true,
        ).ui)

        controller.onHide()

        assertTrue(bitmap.isRecycled)
        assertNull(controller.memory.screenshot)
        assertTrue(first.all { it == 0.toByte() })
        assertTrue(second.all { it == 0.toByte() })
    }

    @Test
    fun destroyScrubsEvenWhenHideAlreadyRan() {
        val bitmap = bitmap()
        val bytes = byteArrayOf(5, 5, 5)
        val controller = controller()
        controller.memory.addImageBuffer(bytes)
        controller.onScreenshot(bitmap)
        controller.onShow(InvocationRouter.INVOCATION_TYPE_GESTURE, screenshotAlreadyRequested = true)
        controller.onHide()
        controller.onDestroy()
        assertTrue(bitmap.isRecycled)
        assertTrue(bytes.all { it == 0.toByte() })
    }

    @Test
    fun disallowedInvocationsScrubAndHide() {
        val rejected = intArrayOf(
            InvocationRouter.MISSING_INVOCATION_TYPE,
            InvocationRouter.INVOCATION_TYPE_ASSIST_BUTTON,
            InvocationRouter.INVOCATION_TYPE_NAV_HANDLE_LONG_PRESS,
            0,
            99,
        )
        for (type in rejected) {
            val bitmap = bitmap()
            val bytes = byteArrayOf(1, 1)
            val controller = controller()
            controller.memory.addImageBuffer(bytes)
            controller.onScreenshot(bitmap)
            val effect = controller.onShow(type, screenshotAlreadyRequested = true)
            assertEquals(type.toString(), SessionController.Ui.HIDDEN, effect.ui)
            assertTrue(effect.hideImmediately)
            assertTrue(effect.cancelCaptureTimeout)
            assertFalse(effect.requestCapture)
            assertTrue(bitmap.isRecycled)
            assertTrue(bytes.all { it == 0.toByte() })
        }
    }

    @Test
    fun tileRequestsCaptureOnceAndOnlyAfterTheWindowCanBeVisible() {
        val controller = controller()
        val first = controller.onShow(
            InvocationRouter.INVOCATION_TYPE_QUICK_SETTINGS_TILE,
            screenshotAlreadyRequested = false,
        )
        assertEquals(SessionController.Ui.WAITING, first.ui)
        assertTrue(first.requestCapture)
        assertEquals(InvocationRouter.INVOCATION_TYPE_QUICK_SETTINGS_TILE, first.captureInvocationType)
        assertTrue(first.armCaptureTimeout)
        assertFalse(first.hideImmediately)

        val second = controller.onShow(
            InvocationRouter.INVOCATION_TYPE_QUICK_SETTINGS_TILE,
            screenshotAlreadyRequested = true,
        )
        assertEquals(SessionController.Ui.WAITING, second.ui)
        assertFalse(second.requestCapture)
        assertTrue(second.armCaptureTimeout)
    }

    @Test
    fun gestureWithScreenshotFlagDoesNotRequestAnotherCapture() {
        val effect = controller().onShow(
            InvocationRouter.INVOCATION_TYPE_GESTURE,
            screenshotAlreadyRequested = true,
        )
        assertEquals(SessionController.Ui.WAITING, effect.ui)
        assertFalse(effect.requestCapture)
        assertTrue(effect.armCaptureTimeout)
    }

    @Test
    fun refusedScreenshotBeforeShowIsAppliedAndDoesNotWait() {
        val controller = controller()
        val early = controller.onScreenshot(null)
        assertEquals(SessionController.Ui.UNCHANGED, early.ui)
        assertFalse(early.requestCapture)

        val shown = controller.onShow(
            InvocationRouter.INVOCATION_TYPE_GESTURE,
            screenshotAlreadyRequested = false,
        )
        assertEquals(SessionController.Ui.NOTICE_SCREEN_ACCESS_OFF, shown.ui)
        assertEquals(SessionController.NOTICE_VISIBLE_MS, shown.hideAfterMs)
        assertFalse(shown.requestCapture)
        assertFalse(shown.armCaptureTimeout)
        assertTrue(shown.cancelCaptureTimeout)
        assertNull(controller.memory.screenshot)
    }

    @Test
    fun staleRefusalDoesNotBlockTheNextShow() {
        val controller = controller()
        controller.onScreenshot(null)
        now += SessionController.REFUSAL_MEMORY_MS + 1
        val shown = controller.onShow(
            InvocationRouter.INVOCATION_TYPE_QUICK_SETTINGS_TILE,
            screenshotAlreadyRequested = false,
        )
        assertEquals(SessionController.Ui.WAITING, shown.ui)
        assertTrue(shown.requestCapture)
    }

    @Test
    fun refusalInsideTheMemoryWindowStillNotices() {
        val controller = controller()
        controller.onScreenshot(null)
        now += SessionController.REFUSAL_MEMORY_MS
        val shown = controller.onShow(
            InvocationRouter.INVOCATION_TYPE_GESTURE,
            screenshotAlreadyRequested = true,
        )
        assertEquals(SessionController.Ui.NOTICE_SCREEN_ACCESS_OFF, shown.ui)
        assertFalse(shown.requestCapture)
    }

    @Test
    fun captureTimeoutNoticesAndALaterTimeoutDoesNothing() {
        val controller = controller()
        controller.onShow(InvocationRouter.INVOCATION_TYPE_GESTURE, screenshotAlreadyRequested = true)
        val timedOut = controller.onCaptureTimeout()
        assertEquals(SessionController.Ui.NOTICE_SCREEN_ACCESS_OFF, timedOut.ui)
        assertEquals(SessionController.NOTICE_VISIBLE_MS, timedOut.hideAfterMs)
        val again = controller.onCaptureTimeout()
        assertEquals(SessionController.Ui.UNCHANGED, again.ui)
        assertNull(again.hideAfterMs)
    }

    @Test
    fun screenshotCancelsTheWaitAndTimeoutDoesNotReplaceIt() {
        val controller = controller()
        val bitmap = bitmap()
        controller.onShow(InvocationRouter.INVOCATION_TYPE_HOME_BUTTON_LONG_PRESS, screenshotAlreadyRequested = true)
        val shown = controller.onScreenshot(bitmap)
        assertEquals(SessionController.Ui.OVERLAY, shown.ui)
        assertTrue(shown.cancelCaptureTimeout)
        assertFalse(bitmap.isRecycled)
        assertEquals(SessionController.Ui.UNCHANGED, controller.onCaptureTimeout().ui)
        assertFalse(bitmap.isRecycled)
    }

    @Test
    fun secureWindowScrubsTheBitmapAndNotices() {
        val controller = controller()
        val bitmap = bitmap()
        val bytes = byteArrayOf(4, 3, 2)
        controller.memory.addImageBuffer(bytes)
        controller.onShow(InvocationRouter.INVOCATION_TYPE_GESTURE, screenshotAlreadyRequested = true)
        controller.onScreenshot(bitmap)
        val blocked = controller.onAssistBlocked(blocked = true)
        assertEquals(SessionController.Ui.NOTICE_SECURE_WINDOW, blocked.ui)
        assertEquals(SessionController.NOTICE_VISIBLE_MS, blocked.hideAfterMs)
        assertTrue(bitmap.isRecycled)
        assertNull(controller.memory.screenshot)
        assertTrue(bytes.all { it == 0.toByte() })
        assertEquals(SessionController.Ui.UNCHANGED, controller.onScreenshot(bitmap()).ui)
    }

    @Test
    fun secureSignalBeforeShowNoticesWithoutRequestingCapture() {
        val controller = controller()
        val early = bitmap()
        controller.onScreenshot(early)
        controller.onAssistBlocked(blocked = true)
        assertTrue(early.isRecycled)
        val shown = controller.onShow(
            InvocationRouter.INVOCATION_TYPE_QUICK_SETTINGS_TILE,
            screenshotAlreadyRequested = false,
        )
        assertEquals(SessionController.Ui.NOTICE_SECURE_WINDOW, shown.ui)
        assertFalse(shown.requestCapture)
        assertFalse(shown.armCaptureTimeout)
    }

    @Test
    fun nullScreenshotWhileShownNoticesAndDropsBytes() {
        val controller = controller()
        val bytes = byteArrayOf(7, 7)
        controller.memory.addImageBuffer(bytes)
        controller.onShow(InvocationRouter.INVOCATION_TYPE_GESTURE, screenshotAlreadyRequested = true)
        val effect = controller.onScreenshot(null)
        assertEquals(SessionController.Ui.NOTICE_SCREEN_ACCESS_OFF, effect.ui)
        assertTrue(bytes.all { it == 0.toByte() })
        assertFalse(effect.requestCapture)
    }

    @Test
    fun powerButtonOpenWaitsAndIgnoreOrDelegateScrub() {
        val open = controller().onShow(
            InvocationRouter.INVOCATION_TYPE_POWER_BUTTON_LONG_PRESS,
            screenshotAlreadyRequested = true,
        )
        assertEquals(SessionController.Ui.WAITING, open.ui)

        val bitmap = bitmap()
        val bytes = byteArrayOf(1)
        val ignoring = controller(
            action = InvocationRouter.PowerButtonAction.IGNORE,
            delegatedPackage = "com.example.voice",
        )
        ignoring.memory.addImageBuffer(bytes)
        ignoring.onScreenshot(bitmap)
        val ignored = ignoring.onShow(
            InvocationRouter.INVOCATION_TYPE_POWER_BUTTON_LONG_PRESS,
            screenshotAlreadyRequested = true,
        )
        assertEquals(SessionController.Ui.HIDDEN, ignored.ui)
        assertNull(ignored.delegation)
        assertTrue(bitmap.isRecycled)
        assertTrue(bytes.all { it == 0.toByte() })

        val delegating = controller(
            action = InvocationRouter.PowerButtonAction.DELEGATE,
            delegatedPackage = "com.example.voice",
        )
        val delegated = delegating.onShow(
            InvocationRouter.INVOCATION_TYPE_POWER_BUTTON_LONG_PRESS,
            screenshotAlreadyRequested = false,
        )
        assertEquals(SessionController.Ui.HIDDEN, delegated.ui)
        assertTrue(delegated.hideImmediately)
        assertFalse(delegated.requestCapture)
        assertEquals("com.example.voice", delegated.delegation?.`package`)
        assertEquals(Intent.ACTION_VOICE_COMMAND, delegated.delegation?.action)
    }

    @Test
    fun screenshotArrivingAfterHideIsRecycledAndNotKept() {
        val controller = controller()
        controller.onShow(InvocationRouter.INVOCATION_TYPE_GESTURE, screenshotAlreadyRequested = true)
        controller.onHide()
        val late = bitmap()
        val effect = controller.onScreenshot(late)
        assertEquals(SessionController.Ui.UNCHANGED, effect.ui)
        assertTrue(late.isRecycled)
        assertNull(controller.memory.screenshot)
    }

    @Test
    fun noticeIsTerminalUntilTheSessionIsShownAgain() {
        val controller = controller()
        controller.onScreenshot(null)
        controller.onShow(InvocationRouter.INVOCATION_TYPE_GESTURE, screenshotAlreadyRequested = true)
        val second = controller.onShow(
            InvocationRouter.INVOCATION_TYPE_QUICK_SETTINGS_TILE,
            screenshotAlreadyRequested = false,
        )
        assertEquals(SessionController.Ui.UNCHANGED, second.ui)
        assertFalse(second.requestCapture)

        controller.onHide()
        val retry = controller.onShow(
            InvocationRouter.INVOCATION_TYPE_QUICK_SETTINGS_TILE,
            screenshotAlreadyRequested = false,
        )
        assertEquals(SessionController.Ui.WAITING, retry.ui)
        assertTrue(retry.requestCapture)
    }

    @Test
    fun emptyAssistStructureIsNotASecureWindow() {
        assertFalse(AssistStructures.isBlocked(null))
        assertFalse(AssistStructures.isBlocked(AssistStructure()))
    }

    @Test
    fun scrubIsSafeWhenNothingWasCaptured() {
        val controller = controller()
        controller.onHide()
        controller.onDestroy()
        assertNull(controller.memory.screenshot)
    }

    private fun controller(
        action: InvocationRouter.PowerButtonAction = InvocationRouter.PowerButtonAction.OPEN_SNIPSEARCH,
        delegatedPackage: String? = null,
    ): SessionController {
        return SessionController(
            policy = object : PowerButtonPolicy() {
                override fun action() = action
                override fun delegatedPackage() = delegatedPackage
            },
            clock = { now },
        )
    }

    private fun bitmap(): Bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
}
