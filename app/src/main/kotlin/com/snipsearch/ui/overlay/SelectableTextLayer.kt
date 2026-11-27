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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
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

internal fun selectionHandleAnchor(words: List<SelectableWord>, rects: List<Rect>, index: Int, start: Boolean): Offset {
    val rect = rects[index]
    return Offset(if (start != words[index].rtl) rect.left else rect.right, rect.bottom)
}

internal enum class SelectionHandle { START, END }

internal fun activeSelectionHandle(words: List<SelectableWord>, rects: List<Rect>, selection: IntRange?, handle: SelectionHandle): Offset? {
    if (selection == null || selection.isEmpty() || selection.first !in words.indices || selection.last !in words.indices) return null
    val start = handle == SelectionHandle.START
    return selectionHandleAnchor(words, rects, if (start) selection.first else selection.last, start)
}

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
    onActiveHandle: (SelectionHandle) -> Unit = {},
    onSuggestCrop: ((PixelRegion) -> Unit)? = null,
) {
    val rects = words.map { viewport.toCanvas(it.bounds.mapBetween(screen, viewport.imageBounds)) }
    val trail = rememberSelectionGlowTrail(viewport, screen)
    val wordsState by rememberUpdatedState(words)
    val rectsState by rememberUpdatedState(rects)
    val current by rememberUpdatedState(selection)
    val update by rememberUpdatedState(onSelect)
    val updateHandle by rememberUpdatedState(onActiveHandle)
    val suggestCrop by rememberUpdatedState(onSuggestCrop)
    var livePreview by remember(viewport, screen) { mutableStateOf<GesturePreview>(GesturePreview.None) }
    val highlight = SnipColors.SnipBlueGlow

    Canvas(modifier.pointerInput(viewport, screen) {
        awaitEachGesture {
            val down = awaitFirstDown()
            val p = down.position
            val downWords = wordsState
            val downRects = rectsState
            val previous = current
            val handle = hitTextSelectionHandle(
                downWords, downRects, previous, p, TEXT_HANDLE_RADIUS_DP.dp.toPx(),
            )
            if (handle != null && previous != null) {
                updateHandle(handle)
                val fixed = if (handle == SelectionHandle.START) previous.last else previous.first
                down.consume()
                try {
                    do {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        if (change.isConsumed) break
                        if (change.position != change.previousPosition) {
                            val liveWords = wordsState
                            val liveRects = rectsState
                            if (fixed in liveWords.indices && fixed in liveRects.indices) {
                                val end = nearestWord(liveRects, change.position)
                                if (end in liveWords.indices) {
                                    updateHandle(if (end < fixed) SelectionHandle.START else SelectionHandle.END)
                                    update(minOf(fixed, end)..maxOf(fixed, end))
                                }
                            }
                        }
                        change.consume()
                    } while (change.pressed)
                } finally {
                    trail.clear()
                }
            } else if (suggestCrop == null) {
                updateHandle(SelectionHandle.END)
                var fixed = nearestWord(downRects, p, TAP_WORD_TOLERANCE_DP.dp.toPx())
                down.consume()
                trail.start(p, down.uptimeMillis)
                try {
                    update(if (fixed >= 0) fixed..fixed else null)
                    do {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        if (change.isConsumed) break
                        if (change.position != change.previousPosition) {
                            val liveRects = rectsState
                            if (change.pressed) trail.extend(change.position, change.uptimeMillis, density)
                            if (fixed < 0) {
                                fixed = firstCrossedWord(liveRects, change.previousPosition, change.position)
                            }
                            if (fixed >= 0 && fixed in liveRects.indices) {
                                val end = nearestWord(liveRects, change.position)
                                if (end >= 0) {
                                    updateHandle(if (end < fixed) SelectionHandle.START else SelectionHandle.END)
                                    update(minOf(fixed, end)..maxOf(fixed, end))
                                }
                            }
                        }
                        change.consume()
                    } while (change.pressed)
                } finally {
                    trail.clear()
                }
            } else {
                if (!viewport.frame.contains(p) &&
                    nearestWord(downRects, p, TAP_WORD_TOLERANCE_DP.dp.toPx()) < 0
                ) {
                    return@awaitEachGesture
                }
                down.consume()
                val rawPoints = ArrayList<Offset>(64).apply { add(p) }
                trail.start(p, down.uptimeMillis)
                livePreview = GesturePreview.Neutral
                var released = false
                try {
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        if (change.isConsumed) break
                        if (change.position != change.previousPosition) {
                            rawPoints += change.position
                            if (change.pressed) {
                                trail.extend(change.position, change.uptimeMillis, density)
                                livePreview = previewGestureIntent(
                                    rawPoints, rectsState, density, allowImageCrop = suggestCrop != null,
                                )
                            }
                        }
                        change.consume()
                        if (!change.pressed) {
                            released = change.previousPressed
                            break
                        }
                    }
                    when (
                        val intent = resolveReleasedGesture(
                            released, rawPoints, rectsState, density, allowImageCrop = suggestCrop != null,
                        )
                    ) {
                        is GestureIntent.TextSelection -> {
                            val liveWords = wordsState
                            if (intent.range.first in liveWords.indices && intent.range.last in liveWords.indices) {
                                updateHandle(intent.activeHandle)
                                update(intent.range)
                            }
                        }
                        is GestureIntent.ImageCrop -> {
                            val clamped = intent.bounds.clampedTo(viewport.frame)
                            val region = viewport.selection(
                                clamped,
                                CROP_LASSO_PADDING_DP.dp.toPx(),
                                CROP_LASSO_MIN_DP.dp.toPx(),
                            )
                            suggestCrop?.invoke(region)
                        }
                        GestureIntent.ClearSelection -> update(null)
                        GestureIntent.Ambiguous, null -> Unit
                    }
                } finally {
                    trail.clear()
                    livePreview = GesturePreview.None
                }
            }
        }
    }) {
        val corner = CornerRadius(3.dp.toPx())
        when (val preview = livePreview) {
            GesturePreview.None -> {
                if (selection != null && selection.first in rects.indices && selection.last in rects.indices) {
                    for (rect in selectionHighlightRects(words, rects, selection)) {
                        drawRoundRect(highlight.copy(alpha = 0.48f), rect.topLeft, rect.size, corner)
                    }
                    for (endpoint in listOf(
                        selectionHandleAnchor(words, rects, selection.first, true),
                        selectionHandleAnchor(words, rects, selection.last, false),
                    )) {
                        drawCircle(Color.White, 7.dp.toPx(), endpoint)
                        drawCircle(highlight, 5.dp.toPx(), endpoint)
                    }
                }
            }
            GesturePreview.Neutral -> Unit
            is GesturePreview.TextSelection -> {
                if (preview.range.first in rects.indices && preview.range.last in rects.indices) {
                    for (rect in selectionHighlightRects(words, rects, preview.range)) {
                        drawRoundRect(highlight.copy(alpha = 0.30f), rect.topLeft, rect.size, corner)
                    }
                }
            }
            is GesturePreview.ImageCrop -> {
                val outline = preview.bounds.clampedTo(viewport.frame)
                drawRect(
                    Color.Black.copy(alpha = 0.28f),
                    outline.topLeft,
                    outline.size,
                    style = Stroke(4.dp.toPx()),
                )
                drawRect(
                    Color.White.copy(alpha = 0.65f),
                    outline.topLeft,
                    outline.size,
                    style = Stroke(2.dp.toPx()),
                )
            }
        }
        trail.draw(this)
    }
}

private fun Rect.clampedTo(frame: Rect): Rect {
    val leftEdge = left.coerceIn(frame.left, frame.right)
    val topEdge = top.coerceIn(frame.top, frame.bottom)
    val rightEdge = right.coerceIn(frame.left, frame.right)
    val bottomEdge = bottom.coerceIn(frame.top, frame.bottom)
    return Rect(minOf(leftEdge, rightEdge), minOf(topEdge, bottomEdge), maxOf(leftEdge, rightEdge), maxOf(topEdge, bottomEdge))
}

internal fun selectionHighlightRects(
    words: List<SelectableWord>,
    rects: List<Rect>,
    selection: IntRange,
): List<Rect> {
    val result = mutableListOf<Rect>()
    var runBlock = -1
    var runRect: Rect? = null
    for (index in selection) {
        if (index !in words.indices || index !in rects.indices) continue
        val word = words[index]
        val currRect = rects[index]
        val currentRun = runRect
        val canMerge = currentRun != null &&
            word.block == runBlock &&
            abs(currRect.top - currentRun.top) <= maxOf(4f, currentRun.height * 0.2f) &&
            abs(currRect.bottom - currentRun.bottom) <= maxOf(4f, currentRun.height * 0.2f) &&
            maxOf(currRect.left - currentRun.right, currentRun.left - currRect.right, 0f) <=
            maxOf(currentRun.height * 2.0f, 32f)
        if (canMerge) {
            runRect = Rect(
                minOf(currentRun.left, currRect.left),
                minOf(currentRun.top, currRect.top),
                maxOf(currentRun.right, currRect.right),
                maxOf(currentRun.bottom, currRect.bottom),
            )
        } else {
            if (currentRun != null) result += currentRun
            runBlock = word.block
            runRect = currRect
        }
    }
    if (runRect != null) result += runRect
    return result
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
