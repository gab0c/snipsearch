package com.snipsearch.assistant

import android.content.ActivityNotFoundException
import android.content.ContextWrapper
import android.content.Intent
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class SessionHandoffTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Test fun externalLaunchReachesNormalActivityLayerBeforeAssistantYields() {
        val events = mutableListOf<String>()
        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://example.org"))
        val options = Bundle().apply { putBoolean("example", true) }
        val base = object : ContextWrapper(app) {
            override fun startActivity(actual: Intent, actualOptions: Bundle?) {
                assertSame(intent, actual)
                if (events.isEmpty()) assertSame(options, actualOptions) else assertNull(actualOptions)
                events += "launch"
            }
        }
        val context = SessionActivityContext(base) { events += "yield" }
        context.startActivity(intent, options)
        assertEquals(listOf("launch", "yield"), events)
        context.startActivity(intent)
        assertEquals(listOf("launch", "yield", "launch", "yield"), events)
    }

    @Test fun failedLaunchDoesNotDismissOverlayAndFallbackCanStillSucceed() {
        var launches = 0
        var yields = 0
        val base = object : ContextWrapper(app) {
            override fun startActivity(intent: Intent, options: Bundle?) {
                launches++
                if (launches == 1) throw ActivityNotFoundException()
            }
        }
        val context = SessionActivityContext(base) { yields++ }
        try { context.startActivity(Intent(Intent.ACTION_VIEW)); fail("Expected refused launch") }
        catch (_: ActivityNotFoundException) { }
        assertEquals(0, yields)
        context.startActivity(Intent(Intent.ACTION_VIEW))
        assertEquals(1, yields)
    }

    @Test fun rootObservesUserDownWithoutStealingButtonTap() {
        var interactions = 0
        var clicks = 0
        val activity = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val root = SessionRootLayout(activity).apply { onUserInteraction = { interactions++ } }
        val button = Button(activity).apply { setOnClickListener { clicks++ } }
        root.addView(button, android.widget.FrameLayout.LayoutParams(200, 100))
        activity.setContentView(root)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        root.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 400, 800)
        for ((action, time) in listOf(MotionEvent.ACTION_DOWN to 1000L, MotionEvent.ACTION_UP to 1100L)) {
            val event = MotionEvent.obtain(1000L, time, action, 50f, 50f, 0)
            root.dispatchTouchEvent(event)
            event.recycle()
        }
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(1, interactions)
        assertEquals(1, clicks)
    }

    @Test fun startupAndDuplicateVisibilityDoNotDismissButClipboardEditorDoes() {
        val watch = VisibleActivityWatch<String>()
        watch.start()
        assertFalse(watch.onVisible("tile-capture"))
        assertFalse(watch.onInvisible("tile-capture"))
        assertFalse(watch.onVisible("source-activity"))
        watch.onUserInteraction()
        assertFalse(watch.onVisible("source-activity"))
        assertTrue(watch.onVisible("clipboard-editor"))
        assertFalse(watch.onInvisible("source-activity")) // Yield once.
        assertFalse(watch.onVisible("another-activity"))
    }

    @Test fun replacingSourceActivityYieldsAndHiddenCallbacksCannotAffectNextSession() {
        val watch = VisibleActivityWatch<String>()
        watch.start()
        watch.onVisible("source")
        watch.onUserInteraction()
        assertTrue(watch.onInvisible("source"))
        watch.stop()
        assertFalse(watch.onVisible("late-editor"))
        watch.start()
        assertFalse(watch.onVisible("new-source"))
        watch.onUserInteraction()
        assertFalse(watch.onInvisible("old-source"))
        watch.stop()
        assertFalse(watch.onInvisible("new-source"))
    }

    @Test fun interactionBeforeInitialSnapshotDoesNotMistakeFirstActivityForTakeover() {
        val watch = VisibleActivityWatch<String>()
        watch.start()
        watch.onUserInteraction()
        assertFalse(watch.onVisible("source"))
        watch.onUserInteraction()
        assertTrue(watch.onVisible("editor"))
    }
}
