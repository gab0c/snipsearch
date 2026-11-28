package com.snipsearch.ui.overlay

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

internal const val CROP_LASSO_PADDING_DP = 12f
internal const val CROP_LASSO_MIN_DP = 72f
internal const val TEXT_HANDLE_RADIUS_DP = 24f
internal const val TAP_WORD_TOLERANCE_DP = 8f

private const val JITTER_STEP_DP = 2f
private const val TAP_MAX_RADIUS_DP = 10f
private const val TAP_MAX_PATH_DP = 20f
private const val LASSO_MIN_SIDE_DP = 32f
private const val LASSO_MIN_DIAGONAL_DP = 48f
private const val LASSO_MIN_PERIMETER_RATIO = 0.56f
private const val LASSO_MAX_GAP_TO_DIAGONAL = 0.48f
private const val LASSO_MAX_GAP_TO_PATH = 0.28f
private const val LASSO_MIN_AREA_FILL_RATIO = 0.35f
private const val LASSO_MIN_SWEEP_DEG = 225f
private const val DIAGONAL_MIN_AXIS_DP = 28f
private const val DIAGONAL_MIN_AXIS_RATIO = 0.50f
private const val DIAGONAL_MIN_STRAIGHTNESS = 0.82f
private const val PREVIEW_MIN_RADIUS_DP = 12f
private const val PREVIEW_TEXT_MIN_STRAIGHTNESS = 0.86f
private const val PREVIEW_TEXT_MAX_DEVIATION_RATIO = 0.22f

internal sealed interface GestureIntent {
    data class TextSelection(val range: IntRange, val activeHandle: SelectionHandle) : GestureIntent
    data class ImageCrop(val bounds: Rect) : GestureIntent
    data object ClearSelection : GestureIntent
    data object Ambiguous : GestureIntent
}

internal sealed interface GesturePreview {
    data object None : GesturePreview
    data object Neutral : GesturePreview
    data class TextSelection(val range: IntRange) : GesturePreview
    data class ImageCrop(val bounds: Rect) : GesturePreview
}

internal fun hitTextSelectionHandle(
    words: List<SelectableWord>,
    rects: List<Rect>,
    selection: IntRange?,
    point: Offset,
    handleRadiusPx: Float,
): SelectionHandle? {
    if (selection == null || selection.isEmpty()) return null
    if (selection.first !in words.indices || selection.last !in words.indices) return null
    if (selection.first !in rects.indices || selection.last !in rects.indices) return null
    val startDist = (point - selectionHandleAnchor(words, rects, selection.first, true)).getDistance()
    val endDist = (point - selectionHandleAnchor(words, rects, selection.last, false)).getDistance()
    if (minOf(startDist, endDist) > handleRadiusPx) return null
    return if (startDist <= endDist) SelectionHandle.START else SelectionHandle.END
}

internal fun filterStrokeJitter(points: List<Offset>, minStepPx: Float): List<Offset> {
    if (points.isEmpty()) return emptyList()
    val kept = ArrayList<Offset>(points.size)
    kept.add(points.first())
    val minSq = minStepPx * minStepPx
    for (index in 1 until points.lastIndex) {
        val delta = points[index] - kept.last()
        if (delta.x * delta.x + delta.y * delta.y >= minSq) kept.add(points[index])
    }
    if (points.size > 1) {
        val tail = points.last() - kept.last()
        if (hypot(tail.x.toDouble(), tail.y.toDouble()) >= 0.25f * minStepPx) kept.add(points.last())
    }
    return kept
}


/** Bounded stroke approximation retaining both endpoints and significant turns. */
internal fun appendStrokeSample(points: MutableList<Offset>, point: Offset) {
    points.add(point)
    if (points.size <= 256) return
    var remove = 1
    var smallest = Float.POSITIVE_INFINITY
    for (i in 1 until points.lastIndex) {
        val a = points[i - 1]
        val b = points[i]
        val c = points[i + 1]
        val cost = (b - a).getDistance() + (c - b).getDistance() - (c - a).getDistance()
        if (cost < smallest) { smallest = cost; remove = i }
    }
    points.removeAt(remove)
}

internal fun resolveReleasedGesture(
    released: Boolean,
    rawPoints: List<Offset>,
    wordRects: List<Rect>,
    density: Float,
    allowImageCrop: Boolean = true,
): GestureIntent? {
    if (!released) return null
    return classifyGestureIntent(rawPoints, wordRects, density, allowImageCrop)
}

internal fun classifyGestureIntent(
    rawPoints: List<Offset>,
    wordRects: List<Rect>,
    density: Float,
    allowImageCrop: Boolean = true,
): GestureIntent {
    if (rawPoints.isEmpty() || density <= 0f) return GestureIntent.Ambiguous
    val filtered = filterStrokeJitter(rawPoints, JITTER_STEP_DP * density)
    val origin = rawPoints.first()
    var maxRadius = 0f
    for (point in rawPoints) {
        maxRadius = maxOf(maxRadius, (point - origin).getDistance())
    }
    val totalLength = polylineLength(filtered)
    if (maxRadius <= TAP_MAX_RADIUS_DP * density && totalLength <= TAP_MAX_PATH_DP * density) {
        val hit = nearestWord(wordRects, origin, TAP_WORD_TOLERANCE_DP * density)
        return if (hit >= 0) {
            GestureIntent.TextSelection(hit..hit, SelectionHandle.END)
        } else {
            GestureIntent.ClearSelection
        }
    }
    if (allowImageCrop) {
        detectImageLasso(filtered, density)?.let { return GestureIntent.ImageCrop(it) }
    }
    return classifyOpenTextStroke(filtered, origin, rawPoints.last(), totalLength, wordRects, density)
}

internal fun previewGestureIntent(
    rawPoints: List<Offset>,
    wordRects: List<Rect>,
    density: Float,
    allowImageCrop: Boolean = true,
): GesturePreview {
    if (rawPoints.isEmpty() || density <= 0f) return GesturePreview.None
    val origin = rawPoints.first()
    var maxRadius = 0f
    for (point in rawPoints) maxRadius = maxOf(maxRadius, (point - origin).getDistance())
    if (rawPoints.size < 2 || maxRadius < PREVIEW_MIN_RADIUS_DP * density) return GesturePreview.Neutral
    val filtered = filterStrokeJitter(rawPoints, JITTER_STEP_DP * density)
    if (allowImageCrop) {
        detectImageLasso(filtered, density)?.let { return GesturePreview.ImageCrop(it) }
    }
    val totalLength = polylineLength(filtered)
    val straightness = if (totalLength > 0f) (rawPoints.last() - origin).getDistance() / totalLength else 0f
    if (straightness < PREVIEW_TEXT_MIN_STRAIGHTNESS || maxChordDeviationRatio(filtered) > PREVIEW_TEXT_MAX_DEVIATION_RATIO) {
        return GesturePreview.Neutral
    }
    val intent = classifyOpenTextStroke(filtered, origin, rawPoints.last(), totalLength, wordRects, density)
    return if (intent is GestureIntent.TextSelection) GesturePreview.TextSelection(intent.range) else GesturePreview.Neutral
}

private fun detectImageLasso(filtered: List<Offset>, density: Float): Rect? {
    if (filtered.size < 4) return null
    val loop = extractPrimaryLoop(filtered, density)
    val bounds = boundingRect(loop.points)
    val width = bounds.width
    val height = bounds.height
    val diagonal = hypot(width.toDouble(), height.toDouble()).toFloat()
    val minSide = LASSO_MIN_SIDE_DP * density
    if (width < minSide || height < minSide || diagonal < LASSO_MIN_DIAGONAL_DP * density) return null
    val loopLength = polylineLength(loop.points)
    if (loopLength / (2f * (width + height)) < LASSO_MIN_PERIMETER_RATIO) return null
    val endGap = if (loop.crossed) 0f else (loop.points.last() - loop.points.first()).getDistance()
    if (diagonal <= 0f || loopLength <= 0f) return null
    if (!loop.crossed && endGap >= minOf(width, height) * 0.8f) return null
    if (endGap / diagonal > LASSO_MAX_GAP_TO_DIAGONAL || endGap / loopLength > LASSO_MAX_GAP_TO_PATH) return null
    val signedArea = shoelaceSignedArea(loop.points)
    val enclosedArea = abs(signedArea)
    if (enclosedArea < minSide * minSide * 0.45f) return null
    if (enclosedArea / (width * height) < LASSO_MIN_AREA_FILL_RATIO) return null
    val totalAngle = radialSweepRadians(loop.points, bounds.center, 2f * density)
    if (abs(totalAngle) * (180f / PI.toFloat()) < LASSO_MIN_SWEEP_DEG) return null
    if (signedArea * totalAngle <= 0f) return null
    return bounds
}

private data class PrimaryLoop(val points: List<Offset>, val crossed: Boolean)

private fun extractPrimaryLoop(filtered: List<Offset>, density: Float): PrimaryLoop {
    if (filtered.size < 6) return PrimaryLoop(filtered, false)
    val cumulative = FloatArray(filtered.size)
    for (index in 1 until filtered.size) {
        cumulative[index] = cumulative[index - 1] + (filtered[index] - filtered[index - 1]).getDistance()
    }
    val total = cumulative.last()
    if (total <= 0f) return PrimaryLoop(filtered, false)
    val stride = if (filtered.size > 160) (filtered.size / 80).coerceAtLeast(1) else 1
    val endToStart = (filtered.last() - filtered.first()).getDistance()
    val closeDistance = minOf(16f * density, endToStart * 0.6f)
    var bestCrossArc = -1f
    var bestCross: List<Offset>? = null
    var bestVertexArc = -1f
    var bestVertex: List<Offset>? = null
    var start = 0
    while (start < filtered.size && cumulative[start] <= 0.35f * total) {
        var end = filtered.lastIndex
        while (end >= 0 && cumulative[end] >= 0.65f * total) {
            if (end - start >= 3) {
                val arc = cumulative[end] - cumulative[start]
                if (arc >= 0.55f * total) {
                    if (start + 1 < filtered.size && end + 1 < filtered.size) {
                        val crossing = segmentIntersection(
                            filtered[start], filtered[start + 1], filtered[end], filtered[end + 1],
                        )
                        if (crossing != null) {
                            val loopArc = (filtered[start + 1] - crossing).getDistance() +
                                (cumulative[end] - cumulative[start + 1]).coerceAtLeast(0f) +
                                (crossing - filtered[end]).getDistance()
                            if (loopArc > bestCrossArc) {
                                bestCrossArc = loopArc
                                bestCross = buildList {
                                    add(crossing)
                                    for (index in (start + 1)..end) add(filtered[index])
                                    add(crossing)
                                }
                            }
                        }
                    }
                    if ((filtered[start] - filtered[end]).getDistance() < closeDistance && arc > bestVertexArc) {
                        bestVertexArc = arc
                        bestVertex = filtered.subList(start, end + 1)
                    }
                }
            }
            end -= stride
        }
        start += stride
    }
    bestCross?.let { return PrimaryLoop(it, true) }
    bestVertex?.let { return PrimaryLoop(it, false) }
    return PrimaryLoop(filtered, false)
}

private fun classifyOpenTextStroke(
    filtered: List<Offset>,
    start: Offset,
    end: Offset,
    totalLength: Float,
    wordRects: List<Rect>,
    density: Float,
): GestureIntent {
    if (wordRects.isEmpty() || totalLength <= 0f) return GestureIntent.Ambiguous
    val lines = groupVisualLines(wordRects, density)
    val typicalLineHeight = median(lines.map { it.bounds.height }).coerceAtLeast(12f * density)
    val dx = abs(end.x - start.x)
    val dy = abs(end.y - start.y)
    val netDistance = hypot(dx.toDouble(), dy.toDouble()).toFloat()
    val straightness = netDistance / totalLength
    val minAxis = minOf(dx, dy)
    val maxAxis = maxOf(dx, dy)
    val axisRatio = if (maxAxis > 0f) minAxis / maxAxis else 0f
    if (minAxis >= DIAGONAL_MIN_AXIS_DP * density &&
        axisRatio >= DIAGONAL_MIN_AXIS_RATIO &&
        straightness >= DIAGONAL_MIN_STRAIGHTNESS
    ) {
        return GestureIntent.Ambiguous
    }
    val wordPadX = 8f * density
    val wordPadY = maxOf(6f * density, typicalLineHeight * 0.35f)
    val paddedWords = wordRects.map { it.expanded(wordPadX, wordPadY) }
    val paddedLineRects = lines.map { it.bounds.expanded(wordPadX, wordPadY) }
    val anchorTolerance = maxOf(10f * density, typicalLineHeight * 0.6f)
    val startWord = anchorWord(filtered, start, wordRects, paddedWords, anchorTolerance, fromEnd = false)
    val endWord = anchorWord(filtered, end, wordRects, paddedWords, anchorTolerance, fromEnd = true)
    if (startWord < 0 || endWord < 0) return GestureIntent.Ambiguous
    val samples = resamplePolyline(filtered, 4f * density)
    val wordBandCoverage = fractionInside(samples, paddedLineRects)
    val touched = lines.filter { line ->
        val padded = line.bounds.expanded(wordPadX, wordPadY)
        samples.any { padded.contains(it) } || filteredSegmentsIntersect(filtered, padded)
    }.sortedBy { it.bounds.center.y }
    val linesAreAdjacent = touched.size <= 1 || touched.zipWithNext().all { (above, below) ->
        (below.bounds.top - above.bounds.bottom) <=
            maxOf(2.5f * maxOf(above.bounds.height, below.bounds.height), 44f * density)
    }
    val accepted = if (dx >= dy || straightness < DIAGONAL_MIN_STRAIGHTNESS) {
        wordBandCoverage >= 0.45f && linesAreAdjacent
    } else if (touched.size >= 2) {
        val span = touched.map { it.bounds }.reduce { acc, rect -> acc.union(rect) }.expanded(wordPadX, wordPadY)
        val paragraphSpanCoverage = fractionInside(samples, listOf(span))
        linesAreAdjacent && paragraphSpanCoverage >= 0.55f && wordBandCoverage >= 0.25f
    } else {
        wordBandCoverage >= 0.65f
    }
    if (!accepted) return GestureIntent.Ambiguous
    val range = minOf(startWord, endWord)..maxOf(startWord, endWord)
    val handle = if (endWord < startWord) SelectionHandle.START else SelectionHandle.END
    return GestureIntent.TextSelection(range, handle)
}

private data class VisualLine(val indices: List<Int>, val bounds: Rect)

private fun groupVisualLines(rects: List<Rect>, density: Float): List<VisualLine> {
    if (rects.isEmpty()) return emptyList()
    val groups = mutableListOf<MutableList<Int>>()
    val bounds = mutableListOf<Rect>()
    for (index in rects.indices) {
        val rect = rects[index]
        val current = bounds.lastOrNull()
        if (current != null && belongsToLine(current, rect, density)) {
            groups.last().add(index)
            bounds[bounds.lastIndex] = current.union(rect)
        } else {
            groups.add(mutableListOf(index))
            bounds.add(rect)
        }
    }
    return groups.zip(bounds) { indices, rect -> VisualLine(indices, rect) }
}

private fun belongsToLine(line: Rect, word: Rect, density: Float): Boolean {
    val overlap = minOf(line.bottom, word.bottom) - maxOf(line.top, word.top)
    val stacked = overlap >= 0.45f * minOf(line.height, word.height) ||
        abs(line.center.y - word.center.y) <= 0.55f * maxOf(line.height, word.height)
    if (!stacked) return false
    val gap = maxOf(word.left - line.right, line.left - word.right, 0f)
    val lineHeight = maxOf(line.height, word.height)
    return gap <= maxOf(4f * lineHeight, 96f * density)
}

private fun anchorWord(
    filtered: List<Offset>,
    point: Offset,
    wordRects: List<Rect>,
    paddedWords: List<Rect>,
    tolerance: Float,
    fromEnd: Boolean,
): Int {
    val nearest = nearestWord(wordRects, point, tolerance)
    if (nearest >= 0) return nearest
    if (filtered.size < 2) return -1
    val segments = filtered.indices.zipWithNext()
    val ordered = if (fromEnd) segments.asReversed() else segments
    for ((from, to) in ordered) {
        val direct = if (fromEnd) {
            firstCrossedWord(wordRects, filtered[to], filtered[from])
        } else {
            firstCrossedWord(wordRects, filtered[from], filtered[to])
        }
        if (direct >= 0) return direct
        val padded = if (fromEnd) {
            firstCrossedWord(paddedWords, filtered[to], filtered[from])
        } else {
            firstCrossedWord(paddedWords, filtered[from], filtered[to])
        }
        if (padded >= 0) return padded
    }
    return -1
}

private fun filteredSegmentsIntersect(points: List<Offset>, rect: Rect): Boolean {
    for (index in 0 until points.lastIndex) {
        if (segmentIntersectsRect(points[index], points[index + 1], rect)) return true
    }
    return false
}

private fun segmentIntersectsRect(from: Offset, to: Offset, rect: Rect): Boolean {
    if (rect.contains(from) || rect.contains(to)) return true
    val delta = to - from
    var enter = 0f
    var exit = 1f
    fun axis(start: Float, step: Float, low: Float, high: Float): Boolean {
        if (step == 0f) return start in low..high
        val a = (low - start) / step
        val b = (high - start) / step
        enter = maxOf(enter, minOf(a, b))
        exit = minOf(exit, maxOf(a, b))
        return enter <= exit
    }
    return axis(from.x, delta.x, rect.left, rect.right) && axis(from.y, delta.y, rect.top, rect.bottom)
}

private fun fractionInside(samples: List<Offset>, rects: List<Rect>): Float {
    if (samples.isEmpty()) return 0f
    var inside = 0
    for (sample in samples) {
        if (rects.any { it.contains(sample) }) inside++
    }
    return inside.toFloat() / samples.size
}

private fun resamplePolyline(points: List<Offset>, spacing: Float): List<Offset> {
    if (points.isEmpty()) return emptyList()
    if (points.size == 1 || spacing <= 0f) return points
    val samples = ArrayList<Offset>()
    samples.add(points.first())
    var distanceToNext = spacing
    for (index in 1 until points.size) {
        val start = points[index - 1]
        val delta = points[index] - start
        val length = delta.getDistance()
        if (length == 0f) continue
        while (distanceToNext <= length) {
            samples.add(start + delta * (distanceToNext / length))
            distanceToNext += spacing
        }
        distanceToNext -= length
    }
    if ((samples.last() - points.last()).getDistance() > 0.25f * spacing) samples.add(points.last())
    return samples
}

private fun polylineLength(points: List<Offset>): Float {
    var length = 0f
    for (index in 1 until points.size) length += (points[index] - points[index - 1]).getDistance()
    return length
}

private fun boundingRect(points: List<Offset>): Rect {
    var left = Float.POSITIVE_INFINITY
    var top = Float.POSITIVE_INFINITY
    var right = Float.NEGATIVE_INFINITY
    var bottom = Float.NEGATIVE_INFINITY
    for (point in points) {
        left = minOf(left, point.x)
        top = minOf(top, point.y)
        right = maxOf(right, point.x)
        bottom = maxOf(bottom, point.y)
    }
    return Rect(left, top, right, bottom)
}

private fun shoelaceSignedArea(points: List<Offset>): Float {
    if (points.size < 3) return 0f
    var sum = 0f
    for (index in points.indices) {
        val current = points[index]
        val next = points[(index + 1) % points.size]
        sum += current.x * next.y - next.x * current.y
    }
    return sum * 0.5f
}

/** Net angular sweep around [center]; immune to corner jitter wraparound. */
private fun radialSweepRadians(points: List<Offset>, center: Offset, minRadius: Float): Float {
    var total = 0f
    var previous: Offset? = null
    for (point in points) {
        val radial = point - center
        if (radial.getDistance() < minRadius) continue
        previous?.let { prev ->
            val cross = prev.x * radial.y - prev.y * radial.x
            val dot = prev.x * radial.x + prev.y * radial.y
            total += atan2(cross, dot)
        }
        previous = radial
    }
    return total
}

private fun maxChordDeviationRatio(points: List<Offset>): Float {
    if (points.size < 3) return 0f
    val start = points.first()
    val end = points.last()
    val chordLength = (end - start).getDistance()
    if (chordLength <= 0f) return 0f
    var maxDistance = 0f
    for (point in points) {
        val distance = abs((end.x - start.x) * (point.y - start.y) - (end.y - start.y) * (point.x - start.x)) / chordLength
        if (distance > maxDistance) maxDistance = distance
    }
    return maxDistance / chordLength
}

private fun segmentIntersection(a: Offset, b: Offset, c: Offset, d: Offset): Offset? {
    val ab = b - a
    val cd = d - c
    val denom = ab.x * cd.y - ab.y * cd.x
    if (abs(denom) < 1e-4f) return null
    val ac = c - a
    val t = (ac.x * cd.y - ac.y * cd.x) / denom
    val u = (ac.x * ab.y - ac.y * ab.x) / denom
    if (t < 0f || t > 1f || u < 0f || u > 1f) return null
    return Offset(a.x + ab.x * t, a.y + ab.y * t)
}

private fun median(values: List<Float>): Float {
    if (values.isEmpty()) return 0f
    val sorted = values.sorted()
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2f
}

private fun Rect.expanded(dx: Float, dy: Float) = Rect(left - dx, top - dy, right + dx, bottom + dy)

private fun Rect.union(other: Rect) = Rect(
    minOf(left, other.left),
    minOf(top, other.top),
    maxOf(right, other.right),
    maxOf(bottom, other.bottom),
)
