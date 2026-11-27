package com.snipsearch.ui.overlay

import android.animation.ValueAnimator
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.snipsearch.R
import kotlin.math.abs
import kotlin.math.max

internal enum class ResultsPaneMode { FULL, REDUCED }

/** The host stays still; translating its child keeps touch coordinates and page layout stable. */
internal class ResultsSheet(
    context: Context,
    private val session: TextSearchSession,
    private val onDismiss: () -> Unit,
) : FrameLayout(context) {
    private val density = resources.displayMetrics.density
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val flingSpeed = max(ViewConfiguration.get(context).scaledMinimumFlingVelocity.toFloat(), 500f * density)
    private val sheet = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val header = FrameLayout(context)
    private val grip = View(context)
    private val body = FrameLayout(context)
    private val failure = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val message = TextView(context).apply { setText(R.string.overlay_search_unavailable); setPadding(dp(16), dp(16), dp(16), dp(8)) }
    private val browser = TextView(context).apply {
        setText(R.string.overlay_open_browser)
        setPadding(dp(16), dp(16), dp(16), dp(16))
        setOnClickListener { openBrowser() }
    }
    private var animation: ValueAnimator? = null
    internal var mode = ResultsPaneMode.REDUCED
        private set
    internal val sheetOffset: Float get() = sheet.translationY
    private var tracker: VelocityTracker? = null
    private var startX = 0f
    private var startY = 0f
    private var startOffset = 0f
    private var startMode = mode
    private var fromTab = false
    private var startedAtTop = false
    private var dragging = false
    private var moved = false
    private var pageOwnsGesture = false
    private var gestureActive = false
    private var dismissed = false
    private var generation = 0

    init {
        clipChildren = true
        clipToPadding = true
        addView(sheet, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        header.contentDescription = context.getString(R.string.overlay_results_drag_tab)
        header.isFocusable = true
        header.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        // Keyboard/accessibility activation expands the pane without introducing visible controls.
        header.setOnClickListener { settle(if (mode == ResultsPaneMode.REDUCED) ResultsPaneMode.FULL else ResultsPaneMode.REDUCED) }
        header.addView(grip, LayoutParams(dp(40), dp(4), Gravity.CENTER))
        sheet.addView(header, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(32)))
        sheet.addView(body, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        failure.addView(message)
        failure.addView(browser)
        updateContent(0xff202124.toInt(), 0xffffffff.toInt(), 0xff9ccbff.toInt())
    }

    private fun dp(value: Int) = (value * density).toInt()

    fun updateContent(background: Int, foreground: Int, link: Int) {
        sheet.setBackgroundColor(background)
        grip.background = GradientDrawable().apply { setColor(foreground); cornerRadius = dp(2).toFloat() }
        message.setTextColor(foreground)
        browser.setTextColor(link)
        val content = if (session.failed || session.webView == null) failure else requireNotNull(session.webView)
        if (content.parent !== body) {
            body.removeAllViews()
            (content.parent as? android.view.ViewGroup)?.removeView(content)
            body.addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        animation?.cancel()
        gestureActive = false
        sheet.translationY = offsetFor(mode)
    }

    private fun offsetFor(target: ResultsPaneMode) = if (target == ResultsPaneMode.FULL) 0f else height * .4f

    internal fun settle(target: ResultsPaneMode, animate: Boolean = true) {
        animation?.cancel()
        mode = target
        val destination = offsetFor(target)
        if (!animate || sheet.translationY == destination) { sheet.translationY = destination; return }
        animation = ValueAnimator.ofFloat(sheet.translationY, destination).apply {
            duration = 220
            addUpdateListener { sheet.translationY = it.animatedValue as Float }
            start()
        }
    }

    override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
        // WebView must not claim swipes that belong to the reduced sheet or top-of-page pull.
        if (gestureActive && (fromTab || startMode == ResultsPaneMode.REDUCED || startedAtTop) && !pageOwnsGesture) return
        super.requestDisallowInterceptTouchEvent(disallowIntercept)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (event.y < sheet.translationY || dismissed) return false
                generation++
                animation?.cancel()
                tracker?.recycle()
                tracker = VelocityTracker.obtain()
                startX = event.x; startY = event.y; startOffset = sheet.translationY; startMode = mode
                fromTab = event.y - sheet.translationY < header.height
                startedAtTop = session.webView?.canScrollVertically(-1) != true
                dragging = false; moved = false; pageOwnsGesture = false; gestureActive = true
            }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> if (abs(event.x - startX) > slop || abs(event.y - startY) > slop) moved = true
        }
        tracker?.addMovement(event)
        val tap = event.actionMasked == MotionEvent.ACTION_UP && gestureActive && !moved && !dragging && !fromTab && startMode == ResultsPaneMode.REDUCED
        val result = super.dispatchTouchEvent(event)
        if (tap) {
            val tapGeneration = generation
            // UP reaches the page at its original coordinates before translation changes.
            post { if (!dismissed && session.isOpen && generation == tapGeneration) settle(ResultsPaneMode.FULL) }
        }
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            gestureActive = false
            tracker?.recycle(); tracker = null
        }
        return result
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) return fromTab
        if (event.actionMasked != MotionEvent.ACTION_MOVE || !gestureActive || pageOwnsGesture) return dragging
        val dx = event.x - startX
        val dy = event.y - startY
        if (abs(dx) <= slop && abs(dy) <= slop) return false
        val canDrag = fromTab || startMode == ResultsPaneMode.REDUCED ||
            (startedAtTop && dy > slop && abs(dy) > abs(dx) && session.webView?.canScrollVertically(-1) != true)
        if (!canDrag) { pageOwnsGesture = true; return false }
        dragging = true
        moveTo(event.y)
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!gestureActive) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (abs(event.y - startY) > slop || abs(event.x - startX) > slop) { dragging = true; moved = true }
                if (dragging) moveTo(event.y)
            }
            MotionEvent.ACTION_UP -> {
                if (dragging) {
                    tracker?.computeCurrentVelocity(1000)
                    finishDrag(event.y - startY, tracker?.yVelocity ?: 0f)
                } else if (fromTab) performTabClick()
            }
            MotionEvent.ACTION_CANCEL -> if (gestureActive) settle(startMode)
        }
        return true
    }

    private fun performTabClick() { header.performClick() }
    private fun moveTo(y: Float) { sheet.translationY = (startOffset + y - startY).coerceIn(0f, height.toFloat()) }

    private fun finishDrag(distance: Float, velocity: Float) {
        val threshold = max(slop * 2f, height * .05f)
        val down = distance > threshold || (distance > slop && velocity > flingSpeed)
        val up = distance < -threshold || (distance < -slop && velocity < -flingSpeed)
        when {
            startMode == ResultsPaneMode.REDUCED && down -> {
                dismissed = true
                session.close()
                onDismiss()
            }
            startMode == ResultsPaneMode.REDUCED && up -> settle(ResultsPaneMode.FULL)
            startMode == ResultsPaneMode.FULL && down -> settle(ResultsPaneMode.REDUCED)
            else -> settle(startMode)
        }
    }

    private fun openBrowser() {
        val target = session.target ?: return
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target)).addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) { Toast.makeText(context, R.string.overlay_no_action_app, Toast.LENGTH_SHORT).show() }
        catch (_: SecurityException) { Toast.makeText(context, R.string.overlay_no_action_app, Toast.LENGTH_SHORT).show() }
    }

    override fun onDetachedFromWindow() {
        animation?.cancel()
        tracker?.recycle(); tracker = null
        gestureActive = false
        generation++
        super.onDetachedFromWindow()
    }
}
