package com.snipsearch.ui.overlay

import android.app.assist.AssistStructure.ViewNode.TEXT_STYLE_BOLD
import android.app.assist.AssistStructure.ViewNode.TEXT_STYLE_ITALIC
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.snipsearch.extraction.AssistTextBlock
import com.snipsearch.extraction.PixelRegion
import com.snipsearch.ui.theme.SnipColors
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

internal data class SelectableWord(
    val block: Int,
    val characters: IntRange,
    val bounds: PixelRegion,
    val rtl: Boolean,
)

private val wordPattern = Regex("\\S+")

/** Positions are estimates from Android's view bounds, line baselines and text size. */
internal fun selectableWords(blocks: List<AssistTextBlock>, screen: PixelRegion): List<SelectableWord> = buildList {
    for ((index, block) in blocks.withIndex()) {
        val region = block.bounds
        if (region.width <= 0 || region.height <= 0) continue
        val paint = TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textSize = block.textSize.takeIf { it.isFinite() && it > 0f }?.coerceAtMost(region.height.toFloat())
                ?: (region.height * 0.55f).coerceIn(8f, 56f)
            val styleBits = TEXT_STYLE_BOLD or TEXT_STYLE_ITALIC
            typeface = when (block.textStyle and styleBits) {
                styleBits -> Typeface.create(Typeface.DEFAULT, Typeface.BOLD_ITALIC)
                TEXT_STYLE_BOLD -> Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                TEXT_STYLE_ITALIC -> Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
                else -> Typeface.DEFAULT
            }
        }
        // A single-line file name may be ellipsized. Keep its first line in place
        // and clip the word bounds instead of introducing a wrapped second line.
        val singleLine = block.baseline != null ||
            ('\n' !in block.text && region.height < paint.fontSpacing * 1.6f)
        val layoutWidth = if (singleLine) {
            maxOf(region.width, ceil(Layout.getDesiredWidth(block.text, paint)).toInt())
        } else {
            region.width
        }
        val layout = StaticLayout.Builder.obtain(block.text, 0, block.text.length, paint, layoutWidth)
            .setIncludePad(false)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .build()
        val left = if (layout.getParagraphDirection(0) < 0) region.right - layoutWidth else region.left
        val top = if (block.baseline != null) {
            block.baseline - layout.getLineBaseline(0)
        } else {
            region.top + (region.height - layout.height).coerceAtLeast(0) / 2
        }
        val path = Path()
        val rect = RectF()
        for (match in wordPattern.findAll(block.text)) {
            if (layout.getLineForOffset(match.range.first) != layout.getLineForOffset(match.range.last)) continue
            path.reset()
            layout.getSelectionPath(match.range.first, match.range.last + 1, path)
            path.computeBounds(rect)
            if (rect.isEmpty) continue
            val bounds = PixelRegion(
                left + floor(rect.left).toInt(), top + floor(rect.top).toInt(),
                left + ceil(rect.right).toInt(), top + ceil(rect.bottom).toInt(),
            ).intersection(region)?.intersection(screen) ?: continue
            add(SelectableWord(index, match.range, bounds, layout.isRtlCharAt(match.range.first)))
        }
    }
}

internal fun selectedText(
    blocks: List<AssistTextBlock>,
    words: List<SelectableWord>,
    selection: IntRange?,
): String {
    if (selection == null) return ""
    if (selection.first !in words.indices || selection.last !in words.indices || selection.isEmpty()) return ""
    return words.subList(selection.first, selection.last + 1).groupBy { it.block }.entries.joinToString("\n") {
        (index, inBlock) ->
        blocks[index].text.substring(inBlock.first().characters.first, inBlock.last().characters.last + 1)
    }
}

@Composable
internal fun SelectableTextLayer(
    words: List<SelectableWord>,
    viewport: CropViewport,
    screen: PixelRegion,
    selection: IntRange?,
    onSelect: (IntRange?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val rects = words.map { viewport.toCanvas(it.bounds.mapBetween(screen, viewport.imageBounds)) }
    val trail = rememberSelectionGlowTrail(words, viewport, screen)
    val current by rememberUpdatedState(selection)
    val update by rememberUpdatedState(onSelect)
    val highlight = SnipColors.SnipBlueGlow
    fun anchor(index: Int, start: Boolean): Offset {
        val rect = rects[index]
        return Offset(if (start != words[index].rtl) rect.left else rect.right, rect.bottom)
    }

    Canvas(modifier.pointerInput(words, viewport, screen) {
        awaitEachGesture {
            val down = awaitFirstDown()
            val p = down.position
            val previous = current
            val handleRadius = 24.dp.toPx()
            val startDistance = previous?.let { (p - anchor(it.first, true)).getDistance() } ?: Float.MAX_VALUE
            val endDistance = previous?.let { (p - anchor(it.last, false)).getDistance() } ?: Float.MAX_VALUE
            val movingHandle = minOf(startDistance, endDistance) <= handleRadius
            var fixed = if (movingHandle && previous != null) {
                if (startDistance <= endDistance) previous.last else previous.first
            } else {
                nearestWord(rects, p, 8.dp.toPx())
            }
            down.consume()
            trail.start(p, down.uptimeMillis)
            try {
                if (!movingHandle) update(if (fixed >= 0) fixed..fixed else null)
                do {
                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                    if (change.isConsumed) break
                    if (change.position != change.previousPosition) {
                        if (change.pressed) trail.extend(change.position, change.uptimeMillis, density)
                        if (fixed < 0) {
                            fixed = firstCrossedWord(rects, change.previousPosition, change.position)
                        }
                        if (fixed >= 0) {
                            val end = nearestWord(rects, change.position)
                            update(minOf(fixed, end)..maxOf(fixed, end))
                        }
                    }
                    change.consume()
                } while (change.pressed)
            } finally {
                trail.clear()
            }
        }
    }) {
        if (selection != null && selection.first in rects.indices && selection.last in rects.indices) {
            for (index in selection) {
                val rect = rects[index]
                drawRect(highlight.copy(alpha = 0.48f), rect.topLeft, rect.size)
            }
            for (handle in listOf(anchor(selection.first, true), anchor(selection.last, false))) {
                drawCircle(Color.White, 7.dp.toPx(), handle)
                drawCircle(highlight, 5.dp.toPx(), handle)
            }
        }
        trail.draw(this)
    }
}

internal fun nearestWord(rects: List<Rect>, point: Offset, tolerance: Float = Float.POSITIVE_INFINITY): Int {
    var index = -1
    var best = Float.POSITIVE_INFINITY
    for ((candidate, rect) in rects.withIndex()) {
        val horizontal = maxOf(rect.left - point.x, point.x - rect.right, 0f)
        val vertical = maxOf(rect.top - point.y, point.y - rect.bottom, 0f)
        if (horizontal > tolerance || vertical > tolerance) continue
        val distance = horizontal + 3 * vertical + 0.001f * abs(rect.center.y - point.y)
        if (distance < best) {
            best = distance
            index = candidate
        }
    }
    return index
}

// Find the earliest intersection along the drag, including words between pointer samples.
internal fun firstCrossedWord(rects: List<Rect>, from: Offset, to: Offset): Int {
    val delta = to - from
    var earliest = Float.POSITIVE_INFINITY
    var hit = -1
    for ((index, rect) in rects.withIndex()) {
        var enter = 0f
        var exit = 1f
        fun crossesAxis(start: Float, step: Float, low: Float, high: Float): Boolean {
            if (step == 0f) return start in low..high
            val a = (low - start) / step
            val b = (high - start) / step
            enter = maxOf(enter, minOf(a, b))
            exit = minOf(exit, maxOf(a, b))
            return enter <= exit
        }
        if (crossesAxis(from.x, delta.x, rect.left, rect.right) &&
            crossesAxis(from.y, delta.y, rect.top, rect.bottom) && enter < earliest
        ) {
            earliest = enter
            hit = index
        }
    }
    return hit
}
