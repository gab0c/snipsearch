package com.snipsearch.extraction

import android.text.InputType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class AssistStructureParserTest {
    @Test
    fun nestedCoordinatesIncludeWindowPositionAndAncestorScroll() {
        val root = nodeBounds(100, 200, 4, 8, 500, 600)
        val parent = nodeBounds(root.left - 19, root.top - 30, 20, 50, 150, 160)
        val child = nodeBounds(parent.left - 6, parent.top - 8, 7, 10, 30, 20)
        assertEquals(PixelRegion(106, 230, 136, 250), child)
    }

    @Test
    fun extremeOffScreenNodeCannotWrapIntoViewport() {
        val offScreen = PixelRegion(Int.MIN_VALUE, 0, Int.MIN_VALUE + 1, 5)
        assertNull(offScreen.intersection(PixelRegion(10, 0, 20, 10)))
    }

    @Test
    fun excludesPasswordFieldsButAllowsFileNamesAndNormalText() {
        assertTrue(isPassword(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD))
        assertTrue(isPassword(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD))
        assertFalse(isPassword(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI))
        assertFalse(isPassword(InputType.TYPE_NULL))
    }

    @Test
    fun lineMetadataExcludesOffScreenLinesAndKeepsTheOriginalTextOrigin() {
        val blocks = textLines(
            text = "Outside\nInside",
            bounds = PixelRegion(10, 10, 150, 70),
            crop = PixelRegion(20, 32, 160, 70),
            offsets = intArrayOf(0, 8),
            baselines = intArrayOf(12, 32),
            textSize = 12f,
        )
        assertEquals(listOf(AssistTextBlock("Inside", PixelRegion(10, 30, 150, 45), 12f, baseline = 42)), blocks)
    }

    @Test
    fun malformedLineMetadataFallsBackToVisibleNodeBounds() {
        val blocks = textLines(
            "  Example  ", PixelRegion(4, 6, 40, 30), PixelRegion(10, 10, 50, 50),
            intArrayOf(0, 600), intArrayOf(6, 12), 14f,
        )
        assertEquals(listOf(AssistTextBlock("Example", PixelRegion(4, 6, 40, 30), 14f)), blocks)
    }
}
