package com.snipsearch.extraction.ocr

import com.snipsearch.extraction.OcrWord
import com.snipsearch.extraction.PixelRegion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OcrLineBuilderTest {
    @Test
    fun keepsTextRangesAndScreenshotCoordinatesAndDropsInvalidBoxes() {
        val first = OcrWord(0 until 5, PixelRegion(10, 20, 40, 38))
        val hidden = OcrWord(6 until 10, PixelRegion(50, 20, 50, 38))
        val third = OcrWord(11 until 16, PixelRegion(44, 20, 80, 38))
        assertEquals(
            com.snipsearch.extraction.OcrLine("alpha hidden gamma", listOf(first, third)),
            assembleOcrLine("alpha hidden gamma", listOf(first, hidden, third)),
        )
    }

    @Test
    fun omitsLinesWhenNoWordHasUsableGeometry() {
        assertNull(assembleOcrLine("uncertain", listOf(OcrWord(0 until 9, PixelRegion(1, 1, 1, 8)))))
    }
}
