package com.snipsearch.debug

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Debug-only activity for benchmarking text extraction latency on ordinary UI text
 * versus pixel-baked canvas text with no AssistStructure nodes.
 */
class OcrTextProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mode = intent?.getStringExtra(EXTRA_MODE) ?: MODE_ORDINARY
        val topInsetPx = dp(72f).toInt()
        val sideInsetPx = dp(24f).toInt()
        val textSizePx = sp(18f)
        val lineAdvancePx = dp(38f)

        val content: View = if (mode == MODE_PIXEL) {
            PixelTextCanvasView(
                context = this,
                lines = SAMPLE_LINES,
                topOffsetPx = topInsetPx.toFloat(),
                leftOffsetPx = sideInsetPx.toFloat(),
                textSizePx = textSizePx,
                lineAdvancePx = lineAdvancePx,
            ).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
        } else {
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#FFF8F9FA"))
                setPadding(sideInsetPx, topInsetPx, sideInsetPx, sideInsetPx)
                SAMPLE_LINES.forEach { line ->
                    addView(
                        TextView(this@OcrTextProbeActivity).apply {
                            text = line
                            setTextColor(Color.parseColor("#FF141619"))
                            setTextSize(TypedValue.COMPLEX_UNIT_PX, textSizePx)
                            typeface = Typeface.SANS_SERIF
                            setPadding(0, 0, 0, dp(10f).toInt())
                        },
                    )
                }
            }
        }
        setContentView(content)
    }

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

    private fun sp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    private class PixelTextCanvasView(
        context: Context,
        private val lines: List<String>,
        private val topOffsetPx: Float,
        private val leftOffsetPx: Float,
        textSizePx: Float,
        private val lineAdvancePx: Float,
    ) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FF141619")
            textSize = textSizePx
            typeface = Typeface.SANS_SERIF
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawColor(Color.parseColor("#FFF8F9FA"))
            var baseline = topOffsetPx - paint.ascent()
            for (line in lines) {
                canvas.drawText(line, leftOffsetPx, baseline, paint)
                baseline += lineAdvancePx
            }
        }
    }

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_ORDINARY = "ordinary"
        const val MODE_PIXEL = "pixel"

        private val SAMPLE_LINES = listOf(
            "Quarterly hardware inventory report",
            "Server rack fourteen maintenance window",
            "Contact support at ops@example.org",
            "Tracking reference ZX94812045",
            "Download archive from status.example.net",
            "Verify checksum before deployment",
            "Local isolated process execution active",
            "GrapheneOS memory tagging enabled",
            "Quick Settings tile screenshot path",
            "Primary selection handles stay anchored",
            "Encrypted credential storage in hardware",
            "Multiline paragraph selection check",
            "Camera calibration target alpha seven",
            "Backup schedule runs every Sunday",
        )
    }
}
