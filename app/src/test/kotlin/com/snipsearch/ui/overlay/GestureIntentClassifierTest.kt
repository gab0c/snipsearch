package com.snipsearch.ui.overlay

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import com.snipsearch.extraction.PixelRegion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class GestureIntentClassifierTest {
    @Test
    fun closedWobblyRectanglesAroundWordsAreImageCrops() {
        val words = paragraph(lines = 3, top = 120f)
        val clockwise = wobblyRect(40f, 70f, 360f, 250f, clockwise = true)
        val counter = wobblyRect(40f, 70f, 360f, 250f, clockwise = false)
        assertTrue(classify(clockwise, words) is GestureIntent.ImageCrop)
        assertTrue(classify(counter, words) is GestureIntent.ImageCrop)

        val clean = rectangle(100f, 100f, 300f, 250f)
        val crop = classify(clean, words) as GestureIntent.ImageCrop
        assertEquals(Rect(100f, 100f, 300f, 250f), crop.bounds)
        val viewport = CropViewport(IntSize(1000, 1000), IntSize(1000, 1000))
        assertEquals(
            PixelRegion(88, 88, 312, 262),
            viewport.selection(crop.bounds, CROP_LASSO_PADDING_DP, CROP_LASSO_MIN_DP),
        )
    }

    @Test
    fun nearlyClosedAndOvershotRectanglesOverWordsAreImageCrops() {
        val words = paragraph(lines = 3, top = 140f)
        val gapped = line(Offset(80f, 90f), Offset(340f, 90f)) +
            line(Offset(340f, 90f), Offset(340f, 280f)).drop(1) +
            line(Offset(340f, 280f), Offset(80f, 280f)).drop(1) +
            line(Offset(80f, 280f), Offset(80f, 150f)).drop(1)
        assertTrue(classify(gapped, words) is GestureIntent.ImageCrop)

        val sparse = listOf(
            Offset(80f, 90f), Offset(340f, 90f), Offset(340f, 280f), Offset(80f, 280f), Offset(80f, 90f),
        )
        assertTrue(classify(sparse, words) is GestureIntent.ImageCrop)

        val overshot = listOf(
            Offset(80f, 90f), Offset(340f, 90f), Offset(340f, 280f), Offset(60f, 280f),
            Offset(60f, 70f), Offset(120f, 70f), Offset(120f, 140f),
        )
        assertTrue(classify(overshot, words) is GestureIntent.ImageCrop)
    }

    @Test
    fun closedCirclesOverEmptySpaceAreImageCrops() {
        val circle = circle(200f, 200f, 50f)
        assertTrue(classify(circle, emptyList()) is GestureIntent.ImageCrop)
        assertTrue(classify(circle, emptyList(), density = 2f) is GestureIntent.ImageCrop)
        val preview = previewGestureIntent(circle, emptyList(), 1f)
        assertTrue(preview is GesturePreview.ImageCrop)
    }

    @Test
    fun horizontalDragsSelectInBothDirectionsForLtrAndRtl() {
        val ltr = listOf(Rect(100f, 40f, 150f, 60f), Rect(170f, 40f, 230f, 60f), Rect(250f, 40f, 310f, 60f))
        val leftToRight = classify(line(Offset(40f, 50f), Offset(300f, 50f)), ltr) as GestureIntent.TextSelection
        assertEquals(0..2, leftToRight.range)
        assertEquals(SelectionHandle.END, leftToRight.activeHandle)

        val rightToLeft = classify(line(Offset(300f, 50f), Offset(40f, 50f)), ltr) as GestureIntent.TextSelection
        assertEquals(0..2, rightToLeft.range)
        assertEquals(SelectionHandle.START, rightToLeft.activeHandle)

        val rtlWords = listOf(
            SelectableWord(0, 0..2, PixelRegion(200, 40, 250, 60), rtl = true),
            SelectableWord(0, 4..6, PixelRegion(100, 40, 150, 60), rtl = true),
        )
        val rtlRects = listOf(Rect(200f, 40f, 250f, 60f), Rect(100f, 40f, 150f, 60f))
        val rtlForward = classify(line(Offset(240f, 50f), Offset(110f, 50f)), rtlRects) as GestureIntent.TextSelection
        assertEquals(0..1, rtlForward.range)
        assertEquals(SelectionHandle.END, rtlForward.activeHandle)
        val rtlBackward = classify(line(Offset(110f, 50f), Offset(240f, 50f)), rtlRects) as GestureIntent.TextSelection
        assertEquals(0..1, rtlBackward.range)
        assertEquals(SelectionHandle.START, rtlBackward.activeHandle)

        assertEquals(
            Offset(250f, 60f),
            selectionHandleAnchor(rtlWords, rtlRects, 0, start = true),
        )
        assertEquals(
            Offset(100f, 60f),
            selectionHandleAnchor(rtlWords, rtlRects, 1, start = false),
        )
    }

    @Test
    fun verticalDragsSelectAdjacentLinesAndRejectLargeGaps() {
        val words = listOf(
            Rect(40f, 40f, 100f, 58f), Rect(116f, 40f, 190f, 58f),
            Rect(40f, 80f, 100f, 98f), Rect(116f, 80f, 190f, 98f),
            Rect(40f, 120f, 100f, 138f), Rect(116f, 120f, 190f, 138f),
        )
        // x=105 sits in the gap between words and still within the line band.
        val downward = classify(line(Offset(105f, 30f), Offset(105f, 150f)), words) as GestureIntent.TextSelection
        assertEquals(0..4, downward.range)
        assertEquals(SelectionHandle.END, downward.activeHandle)

        val upward = classify(line(Offset(105f, 150f), Offset(105f, 30f)), words) as GestureIntent.TextSelection
        assertEquals(0..4, upward.range)
        assertEquals(SelectionHandle.START, upward.activeHandle)

        val gapped = words + Rect(40f, 420f, 120f, 438f)
        assertEquals(GestureIntent.Ambiguous, classify(line(Offset(80f, 30f), Offset(80f, 450f)), gapped))
    }

    @Test
    fun lineFollowingZigzagSelectsText() {
        val words = paragraph(lines = 2, top = 100f, wordWidth = 70f)
        val stroke = line(Offset(30f, 110f), Offset(280f, 110f)) +
            line(Offset(280f, 110f), Offset(40f, 160f)).drop(1) +
            line(Offset(40f, 160f), Offset(270f, 160f)).drop(1)
        val intent = classify(stroke, words) as GestureIntent.TextSelection
        assertEquals(0, intent.range.first)
        assertTrue(intent.range.last > intent.range.first)
    }

    @Test
    fun straightDiagonalsStayAmbiguousAndNeutral() {
        val paragraph = paragraph(lines = 4, top = 40f)
        val emptyDiagonal = line(Offset(0f, 0f), Offset(220f, 220f))
        val throughText = line(Offset(20f, 30f), Offset(300f, 310f))
        val obliqueLine = line(Offset(10f, 20f), Offset(240f, 180f))
        val singleLine = listOf(Rect(40f, 90f, 220f, 110f))
        val retrace = line(Offset(10f, 10f), Offset(180f, 180f)) + line(Offset(180f, 180f), Offset(10f, 10f)).drop(1)
        for (stroke in listOf(emptyDiagonal, throughText, obliqueLine, retrace)) {
            val words = if (stroke === emptyDiagonal || stroke === retrace) emptyList() else {
                if (stroke === obliqueLine) singleLine else paragraph
            }
            assertEquals(stroke.toString(), GestureIntent.Ambiguous, classify(stroke, words))
            assertEquals(stroke.toString(), GesturePreview.Neutral, previewGestureIntent(stroke, words, 1f))
        }
    }

    @Test
    fun tapsJitterAndTinyLoopsDoNotBecomeCrops() {
        val words = listOf(Rect(40f, 40f, 90f, 60f))
        val tap = listOf(Offset(60f, 50f))
        assertEquals(GestureIntent.TextSelection(0..0, SelectionHandle.END), classify(tap, words))
        val jitter = listOf(Offset(60f, 50f), Offset(64f, 52f), Offset(58f, 49f), Offset(61f, 51f))
        assertEquals(GestureIntent.TextSelection(0..0, SelectionHandle.END), classify(jitter, words))
        assertEquals(GestureIntent.ClearSelection, classify(listOf(Offset(10f, 10f)), words))
        assertEquals(GestureIntent.ClearSelection, classify(jitter.map { it + Offset(80f, 80f) }, words))
        assertEquals(GestureIntent.Ambiguous, classify(circle(20f, 20f, 8f), emptyList()))

        assertEquals(
            listOf(Offset(0f, 0f), Offset(3f, 0f), Offset(10f, 0f)),
            filterStrokeJitter(listOf(Offset(0f, 0f), Offset(1f, 0f), Offset(3f, 0f), Offset(3.2f, 0f), Offset(10f, 0f)), 2f),
        )
    }

    @Test
    fun previewStartsNeutralThenShowsTextOrCrop() {
        val words = listOf(Rect(40f, 40f, 120f, 60f), Rect(140f, 40f, 220f, 60f))
        assertEquals(GesturePreview.Neutral, previewGestureIntent(listOf(Offset(30f, 50f), Offset(38f, 50f)), words, 1f))
        val elbow = line(Offset(40f, 40f), Offset(200f, 40f)) + line(Offset(200f, 40f), Offset(200f, 180f)).drop(1)
        assertEquals(GesturePreview.Neutral, previewGestureIntent(elbow, words, 1f))
        val drag = line(Offset(30f, 50f), Offset(210f, 50f))
        assertEquals(GesturePreview.TextSelection(0..1), previewGestureIntent(drag, words, 1f))
        val arc = List(24) { index ->
            val angle = 110.0 * PI * index / (23.0 * 180.0)
            Offset(40f + 160f * cos(angle).toFloat(), 20f + 160f * sin(angle).toFloat())
        }
        assertEquals(GesturePreview.Neutral, previewGestureIntent(arc, words, 1f))
        val loop = rectangle(30f, 20f, 240f, 180f)
        assertTrue(previewGestureIntent(loop, words, 1f) is GesturePreview.ImageCrop)
    }

    @Test
    fun cancelledGesturesResolveToNull() {
        val words = listOf(Rect(40f, 40f, 200f, 60f))
        val drag = line(Offset(30f, 50f), Offset(180f, 50f))
        val loop = rectangle(40f, 40f, 200f, 180f)
        assertNull(resolveReleasedGesture(false, drag, words, 1f))
        assertNull(resolveReleasedGesture(false, loop, words, 1f))
        assertTrue(resolveReleasedGesture(true, drag, words, 1f) is GestureIntent.TextSelection)
    }

    @Test
    fun handlesAndCornersOutrankAClosedLoopAndCropCanBeDisabled() {
        val words = listOf(
            SelectableWord(0, 0..2, PixelRegion(10, 10, 40, 30), rtl = false),
            SelectableWord(0, 4..6, PixelRegion(50, 10, 90, 30), rtl = false),
        )
        val rects = listOf(Rect(10f, 10f, 40f, 30f), Rect(50f, 10f, 90f, 30f))
        assertEquals(
            SelectionHandle.START,
            hitTextSelectionHandle(words, rects, 0..1, Offset(10f, 30f), TEXT_HANDLE_RADIUS_DP),
        )
        assertEquals(
            SelectionHandle.END,
            hitTextSelectionHandle(words, rects, 0..1, Offset(90f, 30f), TEXT_HANDLE_RADIUS_DP),
        )
        val narrow = listOf(SelectableWord(0, 0..1, PixelRegion(0, 0, 4, 8), rtl = false))
        val narrowRect = listOf(Rect(0f, 0f, 4f, 8f))
        assertEquals(
            SelectionHandle.START,
            hitTextSelectionHandle(narrow, narrowRect, 0..0, Offset(2f, 8f), TEXT_HANDLE_RADIUS_DP),
        )
        val rtl = listOf(
            SelectableWord(0, 0..2, PixelRegion(80, 10, 120, 30), rtl = true),
            SelectableWord(0, 4..6, PixelRegion(10, 10, 50, 30), rtl = true),
        )
        val rtlRects = listOf(Rect(80f, 10f, 120f, 30f), Rect(10f, 10f, 50f, 30f))
        assertEquals(Offset(120f, 30f), selectionHandleAnchor(rtl, rtlRects, 0, true))
        assertEquals(
            SelectionHandle.START,
            hitTextSelectionHandle(rtl, rtlRects, 0..1, Offset(120f, 30f), TEXT_HANDLE_RADIUS_DP),
        )
        assertEquals(
            SelectionHandle.END,
            hitTextSelectionHandle(rtl, rtlRects, 0..1, Offset(10f, 30f), TEXT_HANDLE_RADIUS_DP),
        )

        val loop = rectangle(20f, 40f, 180f, 180f)
        assertTrue(classify(loop, rects) is GestureIntent.ImageCrop)
        assertNotNull(hitTextSelectionHandle(words, rects, 0..1, Offset(10f, 30f), TEXT_HANDLE_RADIUS_DP))
        assertEquals(CropCorner.TOP_LEFT, hitCropCorner(Rect(0f, 0f, 120f, 120f), Offset(4f, 3f), 24f))
        assertEquals(GestureIntent.Ambiguous, classify(loop, emptyList(), allowImageCrop = false))
    }

    @Test
    fun preOcrEmptyWordsStayAmbiguousUntilAClosedLasso() {
        val stroke = line(Offset(10f, 10f), Offset(180f, 12f))
        assertEquals(GestureIntent.Ambiguous, classify(stroke, emptyList()))
        assertTrue(classify(rectangle(30f, 30f, 180f, 160f), emptyList()) is GestureIntent.ImageCrop)
    }

    private fun classify(
        points: List<Offset>,
        words: List<Rect>,
        density: Float = 1f,
        allowImageCrop: Boolean = true,
    ) = classifyGestureIntent(points, words, density, allowImageCrop)

    private fun paragraph(lines: Int, top: Float, wordWidth: Float = 60f): List<Rect> = buildList {
        for (line in 0 until lines) {
            val y = top + line * 36f
            var x = 50f
            repeat(3) {
                add(Rect(x, y, x + wordWidth, y + 20f))
                x += wordWidth + 16f
            }
        }
    }

    private fun line(from: Offset, to: Offset, step: Float = 8f): List<Offset> {
        val delta = to - from
        val length = delta.getDistance()
        val count = (length / step).toInt().coerceAtLeast(1)
        return List(count + 1) { index ->
            val t = index.toFloat() / count
            Offset(from.x + delta.x * t, from.y + delta.y * t)
        }
    }

    private fun rectangle(left: Float, top: Float, right: Float, bottom: Float): List<Offset> =
        line(Offset(left, top), Offset(right, top)) +
            line(Offset(right, top), Offset(right, bottom)).drop(1) +
            line(Offset(right, bottom), Offset(left, bottom)).drop(1) +
            line(Offset(left, bottom), Offset(left, top)).drop(1)

    private fun wobblyRect(left: Float, top: Float, right: Float, bottom: Float, clockwise: Boolean): List<Offset> {
        val clean = rectangle(left, top, right, bottom)
        val wobble = clean.mapIndexed { index, point ->
            val shift = if (index % 2 == 0) 5f else -4f
            Offset(point.x + if (index % 4 == 0) shift else 0f, point.y + if (index % 4 == 2) shift else 0f)
        }
        return if (clockwise) wobble else wobble.asReversed()
    }

    private fun circle(cx: Float, cy: Float, radius: Float, samples: Int = 36): List<Offset> =
        List(samples + 1) { index ->
            val angle = 2.0 * PI * index / samples
            Offset(cx + radius * cos(angle).toFloat(), cy + radius * sin(angle).toFloat())
        }
}
