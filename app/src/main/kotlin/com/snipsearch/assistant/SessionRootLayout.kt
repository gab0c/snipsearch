package com.snipsearch.assistant

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.FrameLayout

/** Observe interaction without intercepting touches destined for Compose, WebView, or system UI. */
internal class SessionRootLayout(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {
    var onUserInteraction: (() -> Unit)? = null
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) onUserInteraction?.invoke()
        return super.dispatchTouchEvent(event)
    }
}
