package com.snipsearch.assistant

import android.content.Intent
import android.os.Bundle
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
class InvocationRouterTest {
    @Test
    fun gestureHomeAndTileOpenTheOverlay() {
        val openTypes = intArrayOf(
            InvocationRouter.INVOCATION_TYPE_GESTURE,
            InvocationRouter.INVOCATION_TYPE_HOME_BUTTON_LONG_PRESS,
            InvocationRouter.INVOCATION_TYPE_QUICK_SETTINGS_TILE,
        )
        for (type in openTypes) {
            val decision = InvocationRouter.decide(type, InvocationRouter.PowerButtonAction.IGNORE, null)
            assertEquals(type.toString(), InvocationRouter.Kind.OPEN_OVERLAY, decision.kind)
            assertTrue(InvocationRouter.opensOverlay(type, InvocationRouter.PowerButtonAction.IGNORE, null))
        }
    }

    @Test
    fun powerButtonDefaultsToTheOverlayUntilSettingsExists() {
        val decision = InvocationRouter.decide(
            InvocationRouter.INVOCATION_TYPE_POWER_BUTTON_LONG_PRESS,
            PowerButtonPolicy().action(),
            PowerButtonPolicy().delegatedPackage(),
        )
        assertEquals(InvocationRouter.PowerButtonAction.OPEN_SNIPSEARCH, PowerButtonPolicy().action())
        assertNull(PowerButtonPolicy().delegatedPackage())
        assertEquals(InvocationRouter.Kind.OPEN_OVERLAY, decision.kind)
    }

    @Test
    fun powerButtonIgnoreAndDelegateFollowThePolicy() {
        val ignore = InvocationRouter.decide(
            InvocationRouter.INVOCATION_TYPE_POWER_BUTTON_LONG_PRESS,
            InvocationRouter.PowerButtonAction.IGNORE,
            "com.example.voice",
        )
        assertEquals(InvocationRouter.Kind.SCRUB_AND_HIDE, ignore.kind)
        assertNull(ignore.delegation)

        val delegate = InvocationRouter.decide(
            InvocationRouter.INVOCATION_TYPE_POWER_BUTTON_LONG_PRESS,
            InvocationRouter.PowerButtonAction.DELEGATE,
            "com.example.voice",
        )
        assertEquals(InvocationRouter.Kind.DELEGATE, delegate.kind)
        val intent = requireNotNull(delegate.delegation)
        assertEquals(Intent.ACTION_VOICE_COMMAND, intent.action)
        assertEquals("com.example.voice", intent.`package`)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun powerButtonDelegateWithoutAPackageScrubsAndHides() {
        for (packageName in listOf(null, "", "  ")) {
            val decision = InvocationRouter.decide(
                InvocationRouter.INVOCATION_TYPE_POWER_BUTTON_LONG_PRESS,
                InvocationRouter.PowerButtonAction.DELEGATE,
                packageName,
            )
            assertEquals(packageName, InvocationRouter.Kind.SCRUB_AND_HIDE, decision.kind)
            assertNull(decision.delegation)
        }
    }

    @Test
    fun anythingOutsideTheAllowlistScrubsAndHides() {
        val rejected = intArrayOf(
            InvocationRouter.MISSING_INVOCATION_TYPE,
            0,
            2,
            3,
            4,
            InvocationRouter.INVOCATION_TYPE_ASSIST_BUTTON,
            InvocationRouter.INVOCATION_TYPE_NAV_HANDLE_LONG_PRESS,
            9,
            99,
            101,
            Int.MIN_VALUE,
            Int.MAX_VALUE,
        )
        for (type in rejected) {
            val decision = InvocationRouter.decide(
                type,
                InvocationRouter.PowerButtonAction.OPEN_SNIPSEARCH,
                "com.example.voice",
            )
            assertEquals(type.toString(), InvocationRouter.Kind.SCRUB_AND_HIDE, decision.kind)
            assertFalse(InvocationRouter.opensOverlay(type, InvocationRouter.PowerButtonAction.OPEN_SNIPSEARCH, null))
        }
    }

    @Test
    fun missingBundleAndMissingExtraAreNotAllowlisted() {
        assertEquals(InvocationRouter.MISSING_INVOCATION_TYPE, InvocationRouter.invocationTypeOf(null))
        assertEquals(InvocationRouter.MISSING_INVOCATION_TYPE, InvocationRouter.invocationTypeOf(Bundle()))
        val other = Bundle().apply { putInt("other", InvocationRouter.INVOCATION_TYPE_GESTURE) }
        assertEquals(InvocationRouter.MISSING_INVOCATION_TYPE, InvocationRouter.invocationTypeOf(other))
    }

    @Test
    fun presentExtraIsReadAndAMistypedExtraCannotOpenTheOverlay() {
        val gesture = InvocationRouter.bundleFor(InvocationRouter.INVOCATION_TYPE_GESTURE)
        assertEquals(
            InvocationRouter.INVOCATION_TYPE_GESTURE,
            InvocationRouter.invocationTypeOf(gesture),
        )
        val mistyped = Bundle().apply {
            putString(InvocationRouter.EXTRA_INVOCATION_TYPE, "gesture")
        }
        val type = InvocationRouter.invocationTypeOf(mistyped)
        val decision = InvocationRouter.decide(type, InvocationRouter.PowerButtonAction.OPEN_SNIPSEARCH, null)
        assertEquals(InvocationRouter.Kind.SCRUB_AND_HIDE, decision.kind)
    }

    @Test
    fun assistFallbackStaysOnTheDelegatedPackage() {
        val fallback = InvocationRouter.assistFallbackIntent("com.example.voice")
        assertEquals(Intent.ACTION_ASSIST, fallback.action)
        assertEquals("com.example.voice", fallback.`package`)
        assertNotNull(fallback)
        assertTrue(fallback.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }
}
