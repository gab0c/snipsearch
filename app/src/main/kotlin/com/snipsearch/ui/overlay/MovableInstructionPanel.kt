package com.snipsearch.ui.overlay

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Only the grip consumes drags; the rest of the frozen screen remains selectable. */
@Composable
internal fun MovableInstructionPanel(visible: Boolean, content: @Composable ColumnScope.() -> Unit) {
    var offset by remember { mutableStateOf(IntOffset.Zero) }
    var panelSize by remember { mutableStateOf(IntSize.Zero) }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    Box(Modifier.fillMaxSize().safeDrawingPadding().onSizeChanged { viewportSize = it }.padding(12.dp)) {
        // Bounds include the outer margins, so both edges remain inside the safe drawing area.
        val available = IntSize((viewportSize.width - 24.dp.roundToPixels()).coerceAtLeast(0),
            (viewportSize.height - 24.dp.roundToPixels()).coerceAtLeast(0))
        if (visible) Surface(
            Modifier.align(Alignment.BottomStart).offset { movablePanelPosition(offset, panelSize, available) }
                .onSizeChanged { panelSize = it },
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Column {
                Text("⋮⋮", Modifier.fillMaxWidth().height(32.dp).pointerInput(available, panelSize) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        val current = movablePanelPosition(offset, panelSize, available)
                        offset = movablePanelPosition(IntOffset((current.x + drag.x).roundToInt(),
                            (current.y + drag.y).roundToInt()), panelSize, available)
                    }
                }.padding(horizontal = 12.dp, vertical = 4.dp))
                content()
            }
        }
    }
}

@Composable
private fun androidx.compose.ui.unit.Dp.roundToPixels(): Int = with(androidx.compose.ui.platform.LocalDensity.current) { roundToPx() }

internal fun movablePanelPosition(offset: IntOffset, panel: IntSize, viewport: IntSize): IntOffset {
    val maxX = (viewport.width - panel.width).coerceAtLeast(0)
    val minY = -(viewport.height - panel.height).coerceAtLeast(0)
    return IntOffset(offset.x.coerceIn(0, maxX), offset.y.coerceIn(minY, 0))
}
