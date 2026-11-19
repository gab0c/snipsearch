package com.snipsearch.debug

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView

/**
 * Debug-only foreground window with FLAG_SECURE. Not included in the release APK.
 * Used to check that a secure window produces the protected-screen notice.
 */
class SecureWindowProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val label = TextView(this).apply {
            text = "secure-window-probe"
            textSize = 24f
            setPadding(48, 48, 48, 48)
        }
        setContentView(
            label,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ),
        )
    }
}
