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
}
