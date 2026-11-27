package com.snipsearch.ui.overlay

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.snipsearch.R
import com.snipsearch.extraction.DetectedEntity
import kotlin.math.roundToInt

/** Prefer below the selection, fall back above, then clamp to the safe viewport. */
internal fun selectionToolbarPosition(anchor: Offset?, canvas: IntSize, toolbar: IntSize, gap: Float,
    inset: Float, topInset: Float, bottomInset: Float): IntOffset {
    if (anchor == null) return IntOffset(inset.roundToInt(), (topInset + inset).roundToInt())
    val minY = topInset + inset
    val maxY = (canvas.height - bottomInset - toolbar.height - inset).coerceAtLeast(minY)
    val rightX = anchor.x + gap
    val leftX = anchor.x - gap - toolbar.width
    val x = if (rightX + toolbar.width <= canvas.width - inset) rightX else leftX
    val y = if (anchor.y + gap + toolbar.height <= canvas.height - bottomInset - inset) anchor.y + gap
        else anchor.y - gap - toolbar.height
    return IntOffset(x.coerceIn(inset, (canvas.width - toolbar.width - inset).coerceAtLeast(inset)).roundToInt(), y.coerceIn(minY, maxY).roundToInt())
}

@Composable
internal fun SelectionIconButton(searchIcon: Boolean, label: String, onClick: () -> Unit) {
    val color = MaterialTheme.colorScheme.onSurface
    DescribedIconButton(label, onClick) {
        Canvas(Modifier.size(24.dp)) {
            val stroke = 2.dp.toPx()
            if (searchIcon) {
                drawCircle(color, size.width * 0.29f, Offset(size.width * 0.4f, size.height * 0.4f), style = Stroke(stroke))
                drawLine(color, Offset(size.width * 0.62f, size.height * 0.62f), Offset(size.width * 0.9f, size.height * 0.9f), stroke)
            } else {
                drawRect(color, Offset(size.width * 0.32f, size.height * 0.25f), Size(size.width * 0.55f, size.height * 0.65f), style = Stroke(stroke))
                drawLine(color, Offset(size.width * 0.12f, size.height * 0.7f), Offset(size.width * 0.12f, size.height * 0.08f), stroke)
                drawLine(color, Offset(size.width * 0.12f, size.height * 0.08f), Offset(size.width * 0.65f, size.height * 0.08f), stroke)
            }
        }
    }
}

/** The tooltip consumes a hold; only a normal tap invokes the action. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DescribedIconButton(label: String, onClick: () -> Unit, content: @Composable () -> Unit) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
    ) {
        IconButton(onClick = onClick, modifier = Modifier.semantics { contentDescription = label }, content = content)
    }
}

internal val primaryActionsWidth = 105.dp

/** Primary controls are one indivisible item: entity actions can only follow or wrap below. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SelectionActionsBubble(
    entities: List<DetectedEntity>,
    onSearch: () -> Unit,
    onCopy: () -> Unit,
    onExternalAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = 8.dp,
    ) {
        FlowRow {
            Row(Modifier.width(primaryActionsWidth), verticalAlignment = Alignment.CenterVertically) {
                SelectionIconButton(true, stringResource(R.string.overlay_search_text), onSearch)
                VerticalDivider(
                    Modifier.padding(horizontal = 4.dp).height(24.dp).width(1.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
                SelectionIconButton(false, stringResource(R.string.overlay_copy_text), onCopy)
            }
            entities.forEach { entity -> EntityActionIcon(entity, onExternalAction) }
        }
    }
}
