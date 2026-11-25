package com.snipsearch.extraction

import org.junit.Assert.assertEquals
import org.junit.Test

class OcrTextCorrectionTest {
    @Test
    fun nearbyDisplacedHierarchyTextCorrectsAWordWithoutMovingOcrBounds() {
        val bounds = PixelRegion(100, 140, 190, 160)
        val line = OcrLine("Downioads", listOf(OcrWord(0..8, bounds)))
        val assist = AssistTextBlock("Downloads", PixelRegion(52, 132, 114, 154))
        val corrected = OcrTextCorrection.correct(listOf(line), listOf(assist)).single()

        assertEquals("Downloads", corrected.text)
        assertEquals(bounds, corrected.words.single().bounds)
    }

    @Test
    fun repeatedNearbyLabelsLeaveOcrAlone() {
        val first = OcrLine("Downioads", listOf(OcrWord(0..8, PixelRegion(5, 32, 95, 52))))
        val second = OcrLine("Downloads", listOf(OcrWord(0..8, PixelRegion(105, 32, 195, 52))))
        val hint = AssistTextBlock("Downloads", PixelRegion(5, 32, 195, 52))

        assertEquals(listOf(first, second), OcrTextCorrection.correct(listOf(first, second), listOf(hint)))
    }

    @Test
    fun truncatedFilenamesAndDistantMatchesStayAsRecognized() {
        val bounds = PixelRegion(10, 50, 150, 70)
        val line = OcrLine("report…", listOf(OcrWord(0..6, bounds)))
        val name = AssistTextBlock("report-final.pdf", bounds)
        val distant = AssistTextBlock("report…", bounds.translated(0, 400))
        assertEquals(listOf(line), OcrTextCorrection.correct(listOf(line), listOf(name, distant)))

        val prefix = OcrLine("report", listOf(OcrWord(0..5, bounds)))
        assertEquals(listOf(prefix), OcrTextCorrection.correct(listOf(prefix), listOf(AssistTextBlock("reports", bounds))))
    }
}
