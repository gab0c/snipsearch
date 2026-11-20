package com.snipsearch.ui.overlay

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import com.snipsearch.extraction.PixelRegion
import kotlin.math.ceil
import kotlin.math.floor

/** Fits the complete image into the overlay even when the session has different insets. */
internal class CropViewport(val image: IntSize, canvas: IntSize) {
    val scale = minOf(canvas.width.toFloat() / image.width, canvas.height.toFloat() / image.height)
    val offset = Offset(
        (canvas.width - image.width * scale) / 2,
        (canvas.height - image.height * scale) / 2,
    )
    val frame = Rect(offset, offset + Offset(image.width * scale, image.height * scale))
    val imageBounds = PixelRegion(0, 0, image.width, image.height)

    fun toCanvas(region: PixelRegion) = Rect(
        offset.x + region.left * scale,
        offset.y + region.top * scale,
        offset.x + region.right * scale,
        offset.y + region.bottom * scale,
    )

    fun toImage(point: Offset) = (point - offset) / scale

    fun selection(points: Rect, padding: Float, minimum: Float): PixelRegion {
        val start = toImage(points.topLeft)
        val end = toImage(points.bottomRight)
        val x = paddedAxis(start.x, end.x, padding / scale, minimum / scale, image.width)
        val y = paddedAxis(start.y, end.y, padding / scale, minimum / scale, image.height)
        return PixelRegion(x.first, y.first, x.second, y.second)
    }

    private fun paddedAxis(
        start: Float,
        end: Float,
        padding: Float,
        minimum: Float,
        limit: Int,
    ): Pair<Int, Int> {
        val center = (start + end) / 2
        val extent = maxOf(end - start + 2 * padding, minimum).coerceAtMost(limit.toFloat())
        val lower = (center - extent / 2).coerceIn(0f, limit - extent)
        return floor(lower).toInt() to ceil(lower + extent).toInt().coerceAtMost(limit)
    }
}

/** Maps source-image pixels and screen coordinates, including scaled screenshots. */
internal fun PixelRegion.mapBetween(from: PixelRegion, to: PixelRegion): PixelRegion {
    val scaleX = to.width.toDouble() / from.width
    val scaleY = to.height.toDouble() / from.height
    return PixelRegion(
        to.left + floor((left - from.left) * scaleX).toInt(),
        to.top + floor((top - from.top) * scaleY).toInt(),
        to.left + ceil((right - from.left) * scaleX).toInt(),
        to.top + ceil((bottom - from.top) * scaleY).toInt(),
    )
}
