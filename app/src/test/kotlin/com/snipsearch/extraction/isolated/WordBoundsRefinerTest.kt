package com.snipsearch.extraction.isolated

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class WordBoundsRefinerTest {
    private val height = 48

    @Test
    fun refinesLaggingSingleWordAndTightensVerticalBoundsOnLightAndDarkBackgrounds() {
        for (darkMode in listOf(false, true)) {
            val width = 128
            val contentWidth = 120
            val steps = 16
            val bg = if (darkMode) floatArrayOf(-0.85f, -0.85f, -0.85f) else floatArrayOf(0.95f, 0.95f, 0.95f)
            val fg = if (darkMode) floatArrayOf(0.90f, 0.90f, 0.90f) else floatArrayOf(-0.85f, -0.85f, -0.85f)
            val planar = FloatArray(3 * width * height)
            fillBackground(planar, width, contentWidth) { _, _ -> bg }
            // True word ink spans x=[16..88), y=[12..34)
            paintRect(planar, width, 16, 12, 88, 34, fg)

            // Typical CTC lag: firstStep=3 (x=24, 8px right of ink start) and endStep=12 (x=96, 8px past ink end)
            val spans = WordBoundsRefiner.refine(
                planar = planar,
                width = width,
                contentWidth = contentWidth,
                height = height,
                steps = steps,
                words = listOf(DecodedWord("Display", 3, 12)),
            )

            val span = spans.single()
            val leftPx = span.hStart * contentWidth
            val rightPx = span.hEnd * contentWidth
            val topPx = span.vStart * height
            val bottomPx = span.vEnd * height
            assertTrue("leftPx=$leftPx", abs(leftPx - 16.0) <= 2.0)
            assertTrue("rightPx=$rightPx", abs(rightPx - 88.0) <= 2.0)
            assertTrue("topPx=$topPx", topPx in 8.0..12.0)
            assertTrue("bottomPx=$bottomPx", bottomPx in 34.0..38.0)
            // Vertical center should align closely with ink center (23.0) instead of unclipped crop center (24.0)
            assertTrue(abs((topPx + bottomPx) / 2.0 - 23.0) <= 1.0)
        }
    }

    @Test
    fun splitsAndCentersMultipleWordsAcrossTightAndWideGaps() {
        val width = 224
        val contentWidth = 210
        val steps = 28
        val planar = FloatArray(3 * width * height)
        fillBackground(planar, width, contentWidth) { _, _ -> floatArrayOf(0.92f, 0.92f, 0.92f) }
        val fg = floatArrayOf(-0.88f, -0.88f, -0.88f)
        // Word 1: x=[14..68), Word 2: x=[82..130), Word 3: x=[142..196)
        paintRect(planar, width, 14, 14, 68, 34, fg)
        paintRect(planar, width, 82, 14, 130, 34, fg)
        paintRect(planar, width, 142, 14, 196, 34, fg)

        val words = listOf(
            DecodedWord("Lock", 3, 9),
            DecodedWord("screen", 11, 17),
            DecodedWord("now", 19, 25),
        )
        val spans = WordBoundsRefiner.refine(planar, width, contentWidth, height, steps, words)
        assertEquals(3, spans.size)
        val expected = listOf(14.0 to 68.0, 82.0 to 130.0, 142.0 to 196.0)
        for (i in expected.indices) {
            val l = spans[i].hStart * contentWidth
            val r = spans[i].hEnd * contentWidth
            assertTrue("word $i left=$l", abs(l - expected[i].first) <= 2.0)
            assertTrue("word $i right=$r", abs(r - expected[i].second) <= 2.0)
            assertTrue("word $i center=${(l + r) / 2}", abs((l + r) / 2 - (expected[i].first + expected[i].second) / 2) <= 2.0)
        }
        assertTrue(spans[0].hEnd <= spans[1].hStart)
        assertTrue(spans[1].hEnd <= spans[2].hStart)
    }

    @Test
    fun handlesHorizontalGradientBackgroundWithWhiteText() {
        val width = 160
        val contentWidth = 150
        val steps = 20
        val planar = FloatArray(3 * width * height)
        fillBackground(planar, width, contentWidth) { x, _ ->
            val t = x.toFloat() / contentWidth
            floatArrayOf(-0.6f + 1.4f * t, 0.2f, 0.8f - 1.4f * t)
        }
        paintRect(planar, width, 20, 12, 126, 35, floatArrayOf(1f, 1f, 1f))

        val span = WordBoundsRefiner.refine(
            planar = planar,
            width = width,
            contentWidth = contentWidth,
            height = height,
            steps = steps,
            words = listOf(DecodedWord("Gradient", 4, 16)),
        ).single()

        assertTrue(abs(span.hStart * contentWidth - 20.0) <= 2.0)
        assertTrue(abs(span.hEnd * contentWidth - 126.0) <= 2.0)
        assertTrue(span.vStart * height in 8.0..12.0)
        assertTrue(span.vEnd * height in 35.0..39.0)
    }

    @Test
    fun ignoresAdjacentDividerRulesAtBorderRows() {
        val width = 192
        val contentWidth = 180
        val steps = 24
        val planar = FloatArray(3 * width * height)
        fillBackground(planar, width, contentWidth) { _, _ -> floatArrayOf(0.95f, 0.95f, 0.95f) }
        val fg = floatArrayOf(-0.85f, -0.85f, -0.85f)
        paintRect(planar, width, 24, 14, 148, 32, fg)
        // Full-width divider lines at top border (y=2) and bottom border (y=45)
        paintRect(planar, width, 0, 2, contentWidth, 3, floatArrayOf(-0.6f, -0.6f, -0.6f))
        paintRect(planar, width, 0, 45, contentWidth, 46, floatArrayOf(-0.6f, -0.6f, -0.6f))

        val span = WordBoundsRefiner.refine(
            planar = planar,
            width = width,
            contentWidth = contentWidth,
            height = height,
            steps = steps,
            words = listOf(DecodedWord("Divider", 4, 19)),
        ).single()

        assertTrue(abs(span.hStart * contentWidth - 24.0) <= 2.0)
        assertTrue(abs(span.hEnd * contentWidth - 148.0) <= 2.0)
        assertTrue(span.vStart * height >= 10.0)
        assertTrue(span.vEnd * height <= 36.0)
    }

    @Test
    fun splitsUnderlinedWordsWithoutStretchingIntoInterWordSpace() {
        val width = 192
        val contentWidth = 180
        val steps = 24
        val planar = FloatArray(3 * width * height)
        fillBackground(planar, width, contentWidth) { _, _ -> floatArrayOf(0.95f, 0.95f, 0.95f) }
        val linkColor = floatArrayOf(0.8f, -0.2f, -0.8f)
        // Two words plus a continuous underline spanning both words and the gap between them
        paintRect(planar, width, 16, 12, 82, 32, linkColor)
        paintRect(planar, width, 96, 12, 162, 32, linkColor)
        paintRect(planar, width, 16, 35, 162, 37, linkColor)

        val spans = WordBoundsRefiner.refine(
            planar = planar,
            width = width,
            contentWidth = contentWidth,
            height = height,
            steps = steps,
            words = listOf(DecodedWord("under", 3, 10), DecodedWord("link", 13, 20)),
        )

        assertEquals(2, spans.size)
        val firstRight = spans[0].hEnd * contentWidth
        val secondLeft = spans[1].hStart * contentWidth
        assertTrue("firstRight=$firstRight", abs(firstRight - 82.0) <= 2.0)
        assertTrue("secondLeft=$secondLeft", abs(secondLeft - 96.0) <= 2.0)
        assertTrue("gap collapsed: $firstRight vs $secondLeft", secondLeft - firstRight >= 10.0)
    }

    @Test
    fun fallsBackToCalibratedCtcPriorsOnNoisyTexturedBackground() {
        val width = 128
        val contentWidth = 120
        val steps = 16
        val planar = FloatArray(3 * width * height)
        var seed = 1337
        fillBackground(planar, width, contentWidth) { _, _ ->
            seed = (seed * 1103515245 + 12345) and 0x7fffffff
            val v = -0.6f + ((seed % 1000) / 1000f) * 1.2f
            floatArrayOf(v, v * 0.8f, -v * 0.5f)
        }

        val span = WordBoundsRefiner.refine(
            planar = planar,
            width = width,
            contentWidth = contentWidth,
            height = height,
            steps = steps,
            words = listOf(DecodedWord("Photo", 3, 9)),
        ).single()

        val expectedLeft = (3 - 0.90) * 8.0 / contentWidth
        val expectedRight = (9 + 0.10) * 8.0 / contentWidth
        assertEquals(expectedLeft, span.hStart, 1e-4)
        assertEquals(expectedRight, span.hEnd, 1e-4)
        assertEquals(0.16, span.vStart, 1e-4)
        assertEquals(0.84, span.vEnd, 1e-4)
    }

    @Test
    fun ignoresDetachedEdgeArtifactsAndHandlesSingleNarrowGlyph() {
        val width = 128
        val contentWidth = 120
        val steps = 16
        val planar = FloatArray(3 * width * height)
        fillBackground(planar, width, contentWidth) { _, _ -> floatArrayOf(0.95f, 0.95f, 0.95f) }
        val fg = floatArrayOf(-0.85f, -0.85f, -0.85f)
        // Stray border lines at x=0..1 and x=118..119, separated by wide gaps from narrow glyph "I" at x=48..50
        paintRect(planar, width, 0, 12, 2, 34, fg)
        paintRect(planar, width, 48, 12, 50, 34, fg)
        paintRect(planar, width, 118, 12, 120, 34, fg)

        val span = WordBoundsRefiner.refine(
            planar = planar,
            width = width,
            contentWidth = contentWidth,
            height = height,
            steps = steps,
            words = listOf(DecodedWord("I", 6, 7)),
        ).single()

        val leftPx = span.hStart * contentWidth
        val rightPx = span.hEnd * contentWidth
        assertTrue("leftPx=$leftPx", leftPx in 45.0..48.5)
        assertTrue("rightPx=$rightPx", rightPx in 49.5..52.5)
        assertTrue(rightPx > leftPx)
    }

    @Test
    fun degenerateInputsNeverThrowAndStayClamped() {
        assertEquals(emptyList<RefinedWordSpan>(), WordBoundsRefiner.refine(FloatArray(0), 32, 32, 48, 4, emptyList()))

        val zeroContent = WordBoundsRefiner.refine(
            FloatArray(0),
            width = 32,
            contentWidth = 0,
            height = 48,
            steps = 0,
            words = listOf(DecodedWord("ok", 1, 2)),
        ).single()
        assertEquals(0.0, zeroContent.hStart, 1e-6)
        assertEquals(1.0, zeroContent.hEnd, 1e-6)
    }

    private fun fillBackground(
        planar: FloatArray,
        width: Int,
        contentWidth: Int,
        colorAt: (x: Int, y: Int) -> FloatArray,
    ) {
        val plane = width * height
        for (y in 0 until height) {
            for (x in 0 until contentWidth) {
                val bgr = colorAt(x, y)
                val off = y * width + x
                planar[off] = bgr[0]
                planar[plane + off] = bgr[1]
                planar[2 * plane + off] = bgr[2]
            }
        }
    }

    private fun paintRect(
        planar: FloatArray,
        width: Int,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
        bgr: FloatArray,
    ) {
        val plane = width * height
        for (y in y0 until y1) {
            for (x in x0 until x1) {
                val off = y * width + x
                planar[off] = bgr[0]
                planar[plane + off] = bgr[1]
                planar[2 * plane + off] = bgr[2]
            }
        }
    }
}
