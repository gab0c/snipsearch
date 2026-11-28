package com.snipsearch.ui.overlay

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.snipsearch.extraction.PixelRegion

@Composable
internal fun GestureLassoCanvas(
    viewport: CropViewport,
    onSelect: (PixelRegion) -> Unit,
    modifier: Modifier = Modifier,
) {
    val trail = rememberSelectionGlowTrail(viewport)
    val select by rememberUpdatedState(onSelect)
    Canvas(
        modifier.pointerInput(viewport) {
            awaitEachGesture {
                val down = awaitFirstDown()
                if (!viewport.frame.contains(down.position)) return@awaitEachGesture
                down.consume()
                val start = down.position
                var minX = start.x
                var maxX = start.x
                var minY = start.y
                var maxY = start.y
                val padding = CROP_LASSO_PADDING_DP.dp.toPx()
                val minimum = CROP_LASSO_MIN_DP.dp.toPx()
                var released = false

                trail.start(start, down.uptimeMillis)
                try {
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        if (change.isConsumed) break
                        val point = Offset(
                            change.position.x.coerceIn(viewport.frame.left, viewport.frame.right),
                            change.position.y.coerceIn(viewport.frame.top, viewport.frame.bottom),
                        )
                        minX = minOf(minX, point.x)
                        maxX = maxOf(maxX, point.x)
                        minY = minOf(minY, point.y)
                        maxY = maxOf(maxY, point.y)
                        if (change.pressed) {
                            trail.extend(point, change.uptimeMillis, density)
                        } else if (change.previousPressed) {
                            released = true
                        }
                        change.consume()
                        if (!change.pressed) break
                    }
                    if (released) {
                        select(viewport.selection(Rect(minX, minY, maxX, maxY), padding, minimum))
                    }
                } finally {
                    trail.clear()
                }
            }
        },
    ) {
        trail.draw(this)
    }
}
