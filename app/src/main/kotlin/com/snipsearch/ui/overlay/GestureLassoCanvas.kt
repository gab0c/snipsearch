package com.snipsearch.ui.overlay

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
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

                trail.start(start, down.uptimeMillis)
                try {
                    do {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                            ?: break
                        if (change.isConsumed) break
                        val point = Offset(
                            change.position.x.coerceIn(viewport.frame.left, viewport.frame.right),
                            change.position.y.coerceIn(viewport.frame.top, viewport.frame.bottom),
                        )
                        minX = minOf(minX, point.x)
                        maxX = maxOf(maxX, point.x)
                        minY = minOf(minY, point.y)
                        maxY = maxOf(maxY, point.y)
                        change.consume()
                        if (change.pressed) {
                            trail.extend(point, change.uptimeMillis, density)
                        } else {
                            onSelect(viewport.selection(Rect(minX, minY, maxX, maxY), 12.dp.toPx(), 72.dp.toPx()))
                        }
                    } while (change.pressed)
                } finally {
                    trail.clear()
                }
            }
        },
    ) {
        trail.draw(this)
    }
}
