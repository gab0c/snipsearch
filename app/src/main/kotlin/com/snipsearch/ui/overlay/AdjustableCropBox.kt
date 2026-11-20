package com.snipsearch.ui.overlay

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.snipsearch.extraction.PixelRegion
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
internal fun AdjustableCropBox(
    region: PixelRegion,
    viewport: CropViewport,
    onChange: (PixelRegion) -> Unit,
    modifier: Modifier = Modifier,
) {
    val rect = viewport.toCanvas(region)
    val currentRegion by rememberUpdatedState(region)
    val update by rememberUpdatedState(onChange)

    Canvas(modifier.pointerInput(viewport) {
        val hitRadius = 32.dp.toPx()
        val minimum = (24.dp.toPx() / viewport.scale).roundToInt().coerceAtLeast(1)
        awaitEachGesture {
            val down = awaitFirstDown()
            val initial = currentRegion
            val corner = hitCropCorner(viewport.toCanvas(initial), down.position, hitRadius)
                ?: return@awaitEachGesture
            var movement = Offset.Zero
            val dragStart = awaitTouchSlopOrCancellation(down.id) { change, overSlop ->
                movement = overSlop
                change.consume()
            } ?: return@awaitEachGesture
            update(corner.resize(initial, movement / viewport.scale, viewport.image, minimum))
            drag(dragStart.id) { change ->
                movement += change.positionChange()
                change.consume()
                update(corner.resize(initial, movement / viewport.scale, viewport.image, minimum))
            }
        }
    }) {
        val scrim = Color.Black.copy(alpha = 0.55f)
        drawRect(scrim, size = Size(size.width, rect.top.coerceAtLeast(0f)))
        drawRect(scrim, Offset(0f, rect.bottom), Size(size.width, (size.height - rect.bottom).coerceAtLeast(0f)))
        drawRect(scrim, Offset(0f, rect.top), Size(rect.left.coerceAtLeast(0f), rect.height))
        drawRect(scrim, Offset(rect.right, rect.top), Size((size.width - rect.right).coerceAtLeast(0f), rect.height))
        drawRect(Color.White, rect.topLeft, rect.size, style = Stroke(4.dp.toPx()))
        for (corner in CropCorner.entries) {
            val center = corner.anchor(rect)
            drawCircle(Color.Black.copy(alpha = 0.7f), 11.dp.toPx(), center)
            drawCircle(Color.White, 9.dp.toPx(), center)
        }
    }
}

internal fun hitCropCorner(rect: Rect, touch: Offset, radius: Float): CropCorner? {
    val corner = CropCorner.entries.minBy { (touch - it.anchor(rect)).getDistanceSquared() }
    val delta = touch - corner.anchor(rect)
    return corner.takeIf { abs(delta.x) <= radius && abs(delta.y) <= radius }
}

internal enum class CropCorner(val left: Boolean, val top: Boolean) {
    TOP_LEFT(true, true), TOP_RIGHT(false, true), BOTTOM_LEFT(true, false), BOTTOM_RIGHT(false, false);

    fun anchor(rect: Rect) = Offset(if (left) rect.left else rect.right, if (top) rect.top else rect.bottom)

    fun resize(region: PixelRegion, delta: Offset, image: IntSize, minimum: Int): PixelRegion {
        val dx = delta.x.roundToInt()
        val dy = delta.y.roundToInt()
        // Preserve a smaller existing extent if a crop was made before the viewport changed size.
        val minWidth = minOf(minimum, region.width)
        val minHeight = minOf(minimum, region.height)
        return PixelRegion(
            if (left) (region.left + dx).coerceIn(0, region.right - minWidth) else region.left,
            if (top) (region.top + dy).coerceIn(0, region.bottom - minHeight) else region.top,
            if (left) region.right else (region.right + dx).coerceIn(region.left + minWidth, image.width),
            if (top) region.bottom else (region.bottom + dy).coerceIn(region.top + minHeight, image.height),
        )
    }
}
