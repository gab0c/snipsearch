package com.snipsearch.extraction.isolated

import com.snipsearch.extraction.PixelRegion
import java.nio.FloatBuffer
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Groups connected regions on the DB probability map, fits oriented text rectangles
 * and expands each rectangle to recover the edges of the text line.
 */
internal object TextDetector {
    private const val PIXEL_THRESH = 0.30f
    private const val REGION_SCORE_THRESH = 0.65
    private const val UNCLIP_RATIO = 1.4

    fun regions(map: FloatBuffer, width: Int, height: Int): List<TextRegion> {
        val totalPixels = width * height
        val probs = FloatArray(totalPixels)
        val seen = BooleanArray(totalPixels)
        val queue = IntArray(totalPixels)
        val regions = mutableListOf<TextRegion>()
        try {
            val view = map.duplicate()
            view.position(0)
            view.get(probs)
            for (start in probs.indices) {
                if (seen[start] || probs[start] < PIXEL_THRESH) continue
                var head = 0
                var tail = 1
                queue[0] = start
                seen[start] = true
                var total = 0.0
                var sumX = 0.0
                var sumY = 0.0
                var sumXX = 0.0
                var sumYY = 0.0
                var sumXY = 0.0
                while (head < tail) {
                    val index = queue[head++]
                    val x = index % width
                    val y = index / width
                    total += probs[index]
                    sumX += x
                    sumY += y
                    sumXX += x.toDouble() * x
                    sumYY += y.toDouble() * y
                    sumXY += x.toDouble() * y
                    for (ny in maxOf(0, y - 1)..minOf(height - 1, y + 1)) {
                        for (nx in maxOf(0, x - 1)..minOf(width - 1, x + 1)) {
                            val neighbor = ny * width + nx
                            if (!seen[neighbor] && probs[neighbor] >= PIXEL_THRESH) {
                                seen[neighbor] = true
                                queue[tail++] = neighbor
                            }
                        }
                    }
                }
                if (tail < 6 || total / tail < REGION_SCORE_THRESH) continue
                val angle = 0.5 * atan2(2 * (sumXY - sumX * sumY / tail),
                    sumXX - sumX * sumX / tail - sumYY + sumY * sumY / tail)
                val dx = cos(angle)
                val dy = sin(angle)
                var left = Double.POSITIVE_INFINITY
                var top = Double.POSITIVE_INFINITY
                var right = Double.NEGATIVE_INFINITY
                var bottom = Double.NEGATIVE_INFINITY
                for (i in 0 until tail) {
                    val x = queue[i] % width + 0.5
                    val y = queue[i] / width + 0.5
                    val u = x * dx + y * dy
                    val v = y * dx - x * dy
                    left = minOf(left, u)
                    right = maxOf(right, u)
                    top = minOf(top, v)
                    bottom = maxOf(bottom, v)
                }
                val w = right - left + 1
                val h = bottom - top + 1
                if (minOf(w, h) < 3) continue
                // UI icons activate strongly in v6. Preserve confident square characters,
                // but require stronger evidence for compact components than for text lines.
                if (maxOf(w, h) / minOf(w, h) <= 1.6 && total / tail < 0.95) continue
                val pad = 0.5 + UNCLIP_RATIO * w * h / (2 * (w + h))
                regions += TextRegion(left - pad, top - pad, right + pad, bottom + pad, dx, dy)
            }
            return regions.sortedWith(compareBy({ it.y(0.5, 0.5) }, { it.x(0.5, 0.5) })).take(96)
        } finally {
            probs.fill(0f)
            queue.fill(0)
            seen.fill(false)
        }
    }
}

internal data class TextRegion(
    val left: Double, val top: Double, val right: Double, val bottom: Double,
    val dx: Double, val dy: Double,
) {
    private fun u(fraction: Double) = left + (right - left) * fraction
    private fun v(fraction: Double) = top + (bottom - top) * fraction
    fun x(horizontal: Double, vertical: Double) = u(horizontal) * dx - v(vertical) * dy
    fun y(horizontal: Double, vertical: Double) = u(horizontal) * dy + v(vertical) * dx

    fun aspectRatio(scaleX: Double, scaleY: Double): Double =
        (right - left) * hypot(dx * scaleX, dy * scaleY) /
            ((bottom - top) * hypot(dy * scaleX, dx * scaleY))

    fun bounds(start: Double, end: Double, scaleX: Double, scaleY: Double, width: Int, height: Int): PixelRegion {
        val xs = doubleArrayOf(x(start, 0.0), x(start, 1.0), x(end, 0.0), x(end, 1.0))
        val ys = doubleArrayOf(y(start, 0.0), y(start, 1.0), y(end, 0.0), y(end, 1.0))
        return PixelRegion(
            floor(xs.min() * scaleX).toInt().coerceIn(0, width),
            floor(ys.min() * scaleY).toInt().coerceIn(0, height),
            ceil(xs.max() * scaleX).toInt().coerceIn(0, width),
            ceil(ys.max() * scaleY).toInt().coerceIn(0, height),
        )
    }
}
