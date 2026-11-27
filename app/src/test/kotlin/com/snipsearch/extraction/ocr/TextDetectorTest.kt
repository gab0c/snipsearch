package com.snipsearch.extraction.ocr

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

    @Test
    fun snapsTinyTiltAndCompactComponentsToHorizontalWhileKeepingAngledStrips() {
        val width = 128
        val height = 128
        val map = FloatBuffer.allocate(width * height)
        // Nearly horizontal line (~1.5 deg tilt due to a slight vertical step across 80px)
        for (x in 10 until 90) {
            val baseY = if (x < 50) 12 else 13
            for (dy in 0 until 5) map.put((baseY + dy) * width + x, .9f)
        }
        // Compact near-square component with asymmetric corner activation
        for (y in 40 until 47) for (x in 20 until 28) map.put(y * width + x, .98f)
        map.put(47 * width + 27, .98f)
        // Genuinely angled strip (~26 deg)
        for (x in 20 until 65) for (dy in 0 until 5) map.put((x / 2 + 70 + dy) * width + x, .9f)

        val regions = TextDetector.regions(map, width, height)
        assertEquals(3, regions.size)
        assertEquals(1.0, regions[0].dx, 1e-6)
        assertEquals(0.0, regions[0].dy, 1e-6)
        assertEquals(1.0, regions[1].dx, 1e-6)
        assertEquals(0.0, regions[1].dy, 1e-6)
        assertTrue(kotlin.math.abs(regions[2].dy) > .2)
    }

    @Test
    fun boundsSupportsVerticalSubRangeForHorizontalAndAngledRegions() {
        val horizontal = TextRegion(10.0, 20.0, 50.0, 40.0, 1.0, 0.0)
        val narrowed = horizontal.bounds(0.25, 0.75, 2.0, 2.0, 200, 200, vStart = 0.25, vEnd = 0.75)
        assertEquals(40, narrowed.left)
        assertEquals(50, narrowed.top)
        assertEquals(80, narrowed.right)
        assertEquals(70, narrowed.bottom)

        val angled = TextRegion(10.0, 20.0, 50.0, 40.0, 0.8, 0.6)
        val full = angled.bounds(0.0, 1.0, 1.0, 1.0, 200, 200)
        val tight = angled.bounds(0.0, 1.0, 1.0, 1.0, 200, 200, vStart = 0.25, vEnd = 0.75)
        assertTrue(tight.height < full.height)
    }

    @Test
    fun snapsNearHorizontalLinesToZeroDyWithoutSnappingAngledTextAndReusesScratch() {
        val scratch = TextDetectorScratch()
        // Slight ~0.9 deg skew across a wide 64x64 map should snap to exact horizontal.
        val skewed = FloatBuffer.allocate(64 * 64)
        for (x in 4 until 60) {
            val yBase = 20 + if (x >= 32) 1 else 0
            for (dy in 0 until 4) skewed.put((yBase + dy) * 64 + x, 0.90f)
        }
        val snapped = TextDetector.regions(skewed, 64, 64, scratch).single()
        assertEquals(1.0, snapped.dx, 0.0)
        assertEquals(0.0, snapped.dy, 0.0)

        // Reuse the larger scratch on a smaller 48x48 angled map (~12 deg) without snapping.
        val angled = FloatBuffer.allocate(48 * 48)
        for (x in 6 until 42) {
            val yBase = 10 + (x / 5)
            for (dy in 0 until 4) angled.put((yBase + dy) * 48 + x, 0.90f)
        }
        val rotated = TextDetector.regions(angled, 48, 48, scratch).single()
        assertTrue(kotlin.math.abs(rotated.dy) > 0.15)
        scratch.scrub()
    }

}
