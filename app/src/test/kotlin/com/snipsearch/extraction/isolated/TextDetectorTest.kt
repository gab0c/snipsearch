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
    @Test
    fun rejectsWeakIconSizedComponentsButPreservesConfidentSquareCharacters() {
        for ((score, expected) in listOf(.45f to 0, .90f to 0, .98f to 1)) {
            val map = FloatBuffer.allocate(32 * 32)
            for (y in 8 until 16) for (x in 8 until 16) map.put(y * 32 + x, score)
            assertEquals(expected, TextDetector.regions(map, 32, 32).size)
        }
    }

    @Test
    fun preservesDenseSeparateLinesShortLabelsAndAngledText() {
        val map = FloatBuffer.allocate(96 * 96)
        // Dense paragraph/URL-like lines and a short label must stay distinct.
        for (y in 8 until 12) for (x in 4 until 88) map.put(y * 96 + x, .85f)
        for (y in 18 until 22) for (x in 4 until 88) map.put(y * 96 + x, .85f)
        for (y in 30 until 35) for (x in 4 until 14) map.put(y * 96 + x, .85f)
        // An oblique strip checks orientation-independent component fitting.
        for (x in 20 until 65) for (dy in 0 until 5) map.put((x / 2 + 45 + dy) * 96 + x, .9f)
        val regions = TextDetector.regions(map, 96, 96)
        assertEquals(4, regions.size)
        assertTrue(regions.any { kotlin.math.abs(it.dy) > .2 })
    }

    @Test
    fun retainsSmallConfidentTextAtMinimumUsableSize() {
        val map = FloatBuffer.allocate(16 * 16)
        for (y in 4 until 7) for (x in 4 until 7) map.put(y * 16 + x, .98f)
        assertEquals(1, TextDetector.regions(map, 16, 16).size)
    }

}
