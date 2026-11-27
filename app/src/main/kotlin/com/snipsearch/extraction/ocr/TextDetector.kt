package com.snipsearch.extraction.ocr

import com.snipsearch.extraction.PixelRegion
import java.nio.FloatBuffer
import kotlin.math.abs
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
    private const val HORIZONTAL_SNAP_SIN = 0.045
    private const val COMPACT_ANGLE_RATIO = 1.8

    fun regions(
        map: FloatBuffer,
        width: Int,
        height: Int,
        scratch: TextDetectorScratch? = null,
    ): List<TextRegion> {
        val totalPixels = width * height
        val activeScratch = scratch ?: TextDetectorScratch()
        val probs = activeScratch.probsArray(totalPixels)
        val seen = activeScratch.seenArray(totalPixels)
        val queue = activeScratch.queueArray(totalPixels)
        val regions = mutableListOf<TextRegion>()
        try {
            val view = map.duplicate()
            view.position(0)
            view.get(probs, 0, totalPixels)
            for (start in 0 until totalPixels) {
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
                var minX = width
                var maxX = -1
                var minY = height
                var maxY = -1
                while (head < tail) {
                    val index = queue[head++]
                    val y = index / width
                    val x = index - y * width
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                    total += probs[index]
                    sumX += x
                    sumY += y
                    sumXX += x.toDouble() * x
                    sumYY += y.toDouble() * y
                    sumXY += x.toDouble() * y
                    val yMin = if (y > 0) y - 1 else 0
                    val yMax = if (y + 1 < height) y + 1 else height - 1
                    val xMin = if (x > 0) x - 1 else 0
                    val xMax = if (x + 1 < width) x + 1 else width - 1
                    var ny = yMin
                    while (ny <= yMax) {
                        val rowOffset = ny * width
                        var nx = xMin
                        while (nx <= xMax) {
                            val neighbor = rowOffset + nx
                            if (!seen[neighbor] && probs[neighbor] >= PIXEL_THRESH) {
                                seen[neighbor] = true
                                queue[tail++] = neighbor
                            }
                            nx++
                        }
                        ny++
                    }
                }
                if (tail < 6 || total / tail < REGION_SCORE_THRESH) continue
                val rawAngle = 0.5 * atan2(
                    2 * (sumXY - sumX * sumY / tail),
                    sumXX - sumX * sumX / tail - sumYY + sumY * sumY / tail,
                )
                val rawDy = sin(rawAngle)
                var dx = cos(rawAngle)
                var dy = rawDy
                var left = Double.POSITIVE_INFINITY
                var top = Double.POSITIVE_INFINITY
                var right = Double.NEGATIVE_INFINITY
                var bottom = Double.NEGATIVE_INFINITY
                if (abs(rawDy) < HORIZONTAL_SNAP_SIN) {
                    dx = 1.0
                    dy = 0.0
                    left = minX + 0.5
                    right = maxX + 0.5
                    top = minY + 0.5
                    bottom = maxY + 0.5
                } else {
                    for (i in 0 until tail) {
                        val py = queue[i] / width
                        val px = queue[i] - py * width
                        val x = px + 0.5
                        val y = py + 0.5
                        val u = x * dx + y * dy
                        val v = y * dx - x * dy
                        left = minOf(left, u)
                        right = maxOf(right, u)
                        top = minOf(top, v)
                        bottom = maxOf(bottom, v)
                    }
                    val orientedW = right - left + 1
                    val orientedH = bottom - top + 1
                    if (maxOf(orientedW, orientedH) <= COMPACT_ANGLE_RATIO * minOf(orientedW, orientedH)) {
                        dx = 1.0
                        dy = 0.0
                        left = minX + 0.5
                        right = maxX + 0.5
                        top = minY + 0.5
                        bottom = maxY + 0.5
                    }
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
            activeScratch.scrub(totalPixels)
        }
    }
}

internal class TextDetectorScratch {
    private var probs = FloatArray(0)
    private var seen = BooleanArray(0)
    private var queue = IntArray(0)

    fun probsArray(size: Int): FloatArray {
        if (probs.size < size) {
            probs.fill(0f)
            probs = FloatArray(size)
        }
        return probs
    }

    fun seenArray(size: Int): BooleanArray {
        if (seen.size < size) {
            seen.fill(false)
            seen = BooleanArray(size)
        }
        return seen
    }

    fun queueArray(size: Int): IntArray {
        if (queue.size < size) {
            queue.fill(0)
            queue = IntArray(size)
        }
        return queue
    }

    fun scrub(usedPixels: Int? = null) {
        if (probs.isNotEmpty()) {
            probs.fill(0f, 0, minOf(usedPixels ?: probs.size, probs.size))
        }
        if (seen.isNotEmpty()) {
            seen.fill(false, 0, minOf(usedPixels ?: seen.size, seen.size))
        }
        if (queue.isNotEmpty()) {
            queue.fill(0, 0, minOf(usedPixels ?: queue.size, queue.size))
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

    fun bounds(
        start: Double,
        end: Double,
        scaleX: Double,
        scaleY: Double,
        width: Int,
        height: Int,
        vStart: Double = 0.0,
        vEnd: Double = 1.0,
    ): PixelRegion {
        val xs = doubleArrayOf(x(start, vStart), x(start, vEnd), x(end, vStart), x(end, vEnd))
        val ys = doubleArrayOf(y(start, vStart), y(start, vEnd), y(end, vStart), y(end, vEnd))
        return PixelRegion(
            floor(xs.min() * scaleX).toInt().coerceIn(0, width),
            floor(ys.min() * scaleY).toInt().coerceIn(0, height),
            ceil(xs.max() * scaleX).toInt().coerceIn(0, width),
            ceil(ys.max() * scaleY).toInt().coerceIn(0, height),
        )
    }
}
