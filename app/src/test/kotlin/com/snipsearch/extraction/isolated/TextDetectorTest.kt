package com.snipsearch.extraction.isolated

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.FloatBuffer

class TextDetectorTest {
    @Test
    fun detectsConnectedTextRegionAndPreservesBufferPosition() {
        val width = 32
        val height = 32
        val map = FloatBuffer.allocate(width * height)
        for (y in 8 until 14) {
            for (x in 4 until 20) {
                map.put(y * width + x, 0.9f)
            }
        }
        map.position(7)

        val regions = TextDetector.regions(map, width, height)

        assertEquals(7, map.position())
        assertEquals(1, regions.size)
        val bounds = regions.single().bounds(0.0, 1.0, 1.0, 1.0, width, height)
        assertTrue(bounds.left <= 4 && bounds.right >= 20)
        assertTrue(bounds.top <= 8 && bounds.bottom >= 14)
    }

    @Test
    fun ignoresLowConfidenceAndTinyNoiseRegions() {
        val width = 24
        val height = 24
        val map = FloatBuffer.allocate(width * height)
        map.put(5 * width + 5, 0.95f)
        map.put(5 * width + 6, 0.95f)
        for (x in 2 until 10) {
            map.put(12 * width + x, 0.25f)
        }

        assertEquals(emptyList<TextRegion>(), TextDetector.regions(map, width, height))
    }

    @Test
    fun rejectsMultiRowRegionBelowMeanScoreThreshold() {
        val width = 24
        val height = 24
        val map = FloatBuffer.allocate(width * height)
        for (y in 4 until 8) {
            for (x in 4 until 12) {
                map.put(y * width + x, 0.25f)
            }
        }

        assertEquals(emptyList<TextRegion>(), TextDetector.regions(map, width, height))
    }
}
