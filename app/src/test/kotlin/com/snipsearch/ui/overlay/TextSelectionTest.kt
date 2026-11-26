package com.snipsearch.ui.overlay

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.snipsearch.extraction.AssistTextBlock
import com.snipsearch.extraction.PixelRegion
import org.junit.Assert.assertEquals
import org.junit.Test

class TextSelectionTest {
    @Test
    fun blankStartAnchorsAtFirstWordCrossedInEitherDirection() {
        val words = listOf(Rect(10f, 10f, 20f, 20f), Rect(30f, 10f, 40f, 20f))
        assertEquals(0, firstCrossedWord(words, Offset(0f, 15f), Offset(50f, 15f)))
        assertEquals(1, firstCrossedWord(words, Offset(50f, 15f), Offset(0f, 15f)))
        assertEquals(1, firstCrossedWord(words, Offset(35f, 0f), Offset(35f, 30f)))
        assertEquals(-1, firstCrossedWord(words, Offset(0f, 9f), Offset(50f, 9f)))
    }

    @Test
    fun copiesOnlyTheSelectedRangeAndPreservesSpacingBetweenWords() {
        val bounds = PixelRegion(0, 0, 100, 20)
        val blocks = listOf(
            AssistTextBlock("My  folder", bounds),
            AssistTextBlock("November 19", bounds.translated(0, 22)),
        )
        val words = listOf(
            SelectableWord(0, 0..1, bounds, false),
            SelectableWord(0, 4..9, bounds, false),
            SelectableWord(1, 0..7, bounds, false),
            SelectableWord(1, 9..10, bounds, false),
        )
        assertEquals("My  folder", selectedText(blocks, words, 0..1))
        assertEquals("folder\nNovember", selectedText(blocks, words, 1..2))
        assertEquals("folder", selectedText(blocks, words, 1..1))
        assertEquals("", selectedText(blocks, words, null))
    }

    @Test
    fun tapPrefersTheLabelOnTheSameRowAndIgnoresEmptySpace() {
        val words = listOf(Rect(10f, 10f, 80f, 25f), Rect(10f, 28f, 50f, 42f))
        assertEquals(0, nearestWord(words, Offset(60f, 17f), 8f))
        assertEquals(1, nearestWord(words, Offset(35f, 32f), 8f))
        assertEquals(-1, nearestWord(words, Offset(100f, 16f), 8f))
    }

    @Test
    fun selectionHighlightRectsMergesContiguousSameLineWordsAndSeparatesOthers() {
        val bounds = PixelRegion(0, 0, 10, 10)
        val words = listOf(
            SelectableWord(0, 0..2, bounds, rtl = false),
            SelectableWord(0, 4..7, bounds, rtl = false),
            SelectableWord(0, 9..12, bounds, rtl = false), // distant column on same block
            SelectableWord(1, 0..3, bounds, rtl = true),   // RTL word 0 (right)
            SelectableWord(1, 5..8, bounds, rtl = true),   // RTL word 1 (left)
            SelectableWord(2, 0..3, bounds, rtl = false),  // angled word 0
            SelectableWord(2, 5..8, bounds, rtl = false),  // angled word 1 shifted vertically
        )
        val rects = listOf(
            Rect(10f, 20f, 50f, 40f),
            Rect(58f, 20f, 105f, 40f),
            Rect(220f, 20f, 270f, 40f),
            Rect(160f, 60f, 210f, 80f),
            Rect(95f, 60f, 152f, 80f),
            Rect(10f, 110f, 55f, 130f),
            Rect(62f, 96f, 110f, 116f),
        )

        val merged = selectionHighlightRects(words, rects, 0..6)
        assertEquals(
            listOf(
                Rect(10f, 20f, 105f, 40f),
                Rect(220f, 20f, 270f, 40f),
                Rect(95f, 60f, 210f, 80f),
                Rect(10f, 110f, 55f, 130f),
                Rect(62f, 96f, 110f, 116f),
            ),
            merged,
        )
    }
}
