package com.snipsearch.ui.overlay

import android.app.Activity
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.webkit.WebView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@LooperMode(LooperMode.Mode.PAUSED)
class ResultsSheetTest {
    private class Page(activity: Activity) : WebView(activity) {
        val touches = mutableListOf<Pair<Int, Float>>()
        var above = false
        var loads = 0
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            // ShadowWebView does not measure its native page; this touch-recording page does.
            setMeasuredDimension(View.MeasureSpec.getSize(widthMeasureSpec), View.MeasureSpec.getSize(heightMeasureSpec))
        }
        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            // The JVM WebView provider has no native frame; supply it for real ViewGroup hit tests.
            org.robolectric.util.ReflectionHelpers.setField(this, "mLeft", left)
            org.robolectric.util.ReflectionHelpers.setField(this, "mTop", top)
            org.robolectric.util.ReflectionHelpers.setField(this, "mRight", right)
            org.robolectric.util.ReflectionHelpers.setField(this, "mBottom", bottom)
        }
        override fun canScrollVertically(direction: Int) = direction < 0 && above
        override fun loadUrl(url: String) { loads++; super.loadUrl(url) }
        override fun onTouchEvent(event: MotionEvent): Boolean {
            touches += event.actionMasked to event.y
            parent?.requestDisallowInterceptTouchEvent(true)
            return true
        }
    }
    private class Fixture(online: Boolean = true) {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val page = Page(activity)
        val session = TextSearchSession(activity, online = { online }, createWebView = { page })
        var dismissals = 0
        val sheet: ResultsSheet
        private var time = 1000L
        init {
            session.submit("hello")
            sheet = ResultsSheet(activity, session) { dismissals++ }
            activity.setContentView(sheet, android.view.ViewGroup.LayoutParams(1000, 1000))
            shadowOf(Looper.getMainLooper()).idle()
            size(1000, 1000)

        }
        fun size(w: Int, h: Int) {
            sheet.layoutParams = sheet.layoutParams.apply { width = w; height = h }
            sheet.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            sheet.layout(0, 0, w, h)
        }
        fun touch(action: Int, y: Float, x: Float = 500f) {
            val event = MotionEvent.obtain(1000L, time, action, x, y, 0)
            time += 120
            sheet.dispatchTouchEvent(event)
            event.recycle()
        }
        fun idle() { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300)) }
    }

    @Test fun reducedTapReachesPageBeforeExpansionAndDoesNotReload() {
        val f = Fixture()
        assertEquals(ResultsPaneMode.REDUCED, f.sheet.mode)
        assertEquals(400f, f.sheet.sheetOffset, .01f)
        f.touch(MotionEvent.ACTION_DOWN, 800f)
        f.touch(MotionEvent.ACTION_UP, 800f)
        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP), f.page.touches.map { it.first })
        assertEquals(f.page.touches.first().second, f.page.touches.last().second, .01f)
        assertEquals(ResultsPaneMode.REDUCED, f.sheet.mode)
        f.idle()
        assertEquals(ResultsPaneMode.FULL, f.sheet.mode)
        assertEquals(0f, f.sheet.sheetOffset, .01f)
        assertEquals(1, f.page.loads)
    }

    @Test fun reducedSwipeInterceptsEvenIfWebViewDisallowsAndDoesNotTapExpand() {
        val f = Fixture()
        f.touch(MotionEvent.ACTION_DOWN, 800f)
        f.touch(MotionEvent.ACTION_MOVE, 650f)
        assertEquals(250f, f.sheet.sheetOffset, .01f)
        f.touch(MotionEvent.ACTION_UP, 600f)
        f.idle()
        assertTrue(f.page.touches.any { it.first == MotionEvent.ACTION_CANCEL })
        assertFalse(f.page.touches.any { it.first == MotionEvent.ACTION_MOVE || it.first == MotionEvent.ACTION_UP })
        assertEquals(ResultsPaneMode.FULL, f.sheet.mode)
        assertEquals(0, f.dismissals)
    }

    @Test fun reducedDownwardSwipeDismissesWholeSessionAndDestroysPage() {
        val f = Fixture()
        f.touch(MotionEvent.ACTION_DOWN, 750f)
        f.touch(MotionEvent.ACTION_MOVE, 850f)
        f.touch(MotionEvent.ACTION_UP, 900f)
        f.idle()
        assertEquals(1, f.dismissals)
        assertFalse(f.session.isOpen)
        assertNull(f.session.target)
        assertNull(f.session.webView)
        assertTrue(shadowOf(f.page).wasDestroyCalled())
        assertEquals(ResultsPaneMode.REDUCED, f.sheet.mode)
    }

    @Test fun fullDownAtPageTopReducesButScrolledPageKeepsGesture() {
        val top = Fixture()
        top.sheet.settle(ResultsPaneMode.FULL, animate = false)
        top.touch(MotionEvent.ACTION_DOWN, 500f)
        top.touch(MotionEvent.ACTION_MOVE, 650f)
        top.touch(MotionEvent.ACTION_UP, 700f)
        top.idle()
        assertEquals(ResultsPaneMode.REDUCED, top.sheet.mode)
        assertEquals(0, top.dismissals)
        assertTrue(top.page.touches.any { it.first == MotionEvent.ACTION_CANCEL })

        val scrolled = Fixture()
        scrolled.sheet.settle(ResultsPaneMode.FULL, animate = false)
        scrolled.page.above = true
        scrolled.touch(MotionEvent.ACTION_DOWN, 500f)
        scrolled.touch(MotionEvent.ACTION_MOVE, 650f)
        scrolled.touch(MotionEvent.ACTION_UP, 700f)
        scrolled.idle()
        assertEquals(ResultsPaneMode.FULL, scrolled.sheet.mode)
        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP), scrolled.page.touches.map { it.first })
    }

    @Test fun fullUpwardSwipeScrollsPageAndTabCanReduceRegardlessOfPageScroll() {
        val f = Fixture()
        f.sheet.settle(ResultsPaneMode.FULL, animate = false)
        f.touch(MotionEvent.ACTION_DOWN, 700f)
        f.touch(MotionEvent.ACTION_MOVE, 500f)
        f.touch(MotionEvent.ACTION_UP, 450f)
        assertEquals(ResultsPaneMode.FULL, f.sheet.mode)
        assertFalse(f.page.touches.any { it.first == MotionEvent.ACTION_CANCEL })
        f.page.above = true
        f.page.touches.clear()
        f.touch(MotionEvent.ACTION_DOWN, 10f)
        f.touch(MotionEvent.ACTION_MOVE, 160f)
        f.touch(MotionEvent.ACTION_UP, 210f)
        f.idle()
        assertEquals(ResultsPaneMode.REDUCED, f.sheet.mode)
        assertTrue(f.page.touches.isEmpty())
    }

    @Test fun cancelledReducedDragSnapsBackAndResizePreservesFraction() {
        val f = Fixture()
        f.touch(MotionEvent.ACTION_DOWN, 800f)
        f.touch(MotionEvent.ACTION_MOVE, 600f)
        f.touch(MotionEvent.ACTION_CANCEL, 600f)
        f.idle()
        assertEquals(ResultsPaneMode.REDUCED, f.sheet.mode)
        assertEquals(400f, f.sheet.sheetOffset, .01f)
        assertEquals(0, f.dismissals)
        f.size(600, 1200)
        assertEquals(480f, f.sheet.sheetOffset, .01f)
        f.sheet.settle(ResultsPaneMode.FULL, animate = false)
        f.size(600, 1000)
        assertEquals(0f, f.sheet.sheetOffset, .01f)
    }

    @Test fun resizeDuringDragCancelsItAndLateUpCannotDismiss() {
        val f = Fixture()
        f.touch(MotionEvent.ACTION_DOWN, 750f)
        f.touch(MotionEvent.ACTION_MOVE, 850f)
        f.size(600, 1200)
        f.touch(MotionEvent.ACTION_UP, 950f)
        f.idle()
        assertEquals(0, f.dismissals)
        assertEquals(ResultsPaneMode.REDUCED, f.sheet.mode)
        assertEquals(480f, f.sheet.sheetOffset, .01f)
    }

    @Test fun offlinePaneHasFallbackAndCanDismissWithoutAWebView() {
        val f = Fixture(online = false)
        assertNull(f.session.webView)
        f.touch(MotionEvent.ACTION_DOWN, 750f)
        f.touch(MotionEvent.ACTION_MOVE, 900f)
        f.touch(MotionEvent.ACTION_UP, 950f)
        assertEquals(1, f.dismissals)
    }
}
