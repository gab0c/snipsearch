package com.snipsearch.extraction.ocr

import ai.onnxruntime.OnnxJavaType
import java.nio.FloatBuffer
import java.nio.LongBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class OcrBufferScrubbingTest {
    @Test
    fun scrubsTheExactConsumedOutputCopiesEvenWhenDecodingThrows() {
        val probabilities = FloatBuffer.wrap(floatArrayOf(.8f, .6f))
        val tokens = LongBuffer.wrap(longArrayOf(12, 9))
        try {
            InferenceOutput(OnnxJavaType.FLOAT, probabilities, null).use {
                InferenceOutput(OnnxJavaType.INT64, null, tokens).use {
                    error("decoder failure")
                }
            }
        } catch (_: IllegalStateException) { }
        assertEquals(listOf(0f, 0f), (0 until probabilities.capacity()).map { probabilities[it] })
        assertEquals(listOf(0L, 0L), (0 until tokens.capacity()).map { tokens[it] })
    }

    @Test
    fun reusesScrubbedInputStorageAndScrubsOldStorageOnGrowth() {
        val scratch = DirectFloatScratch()
        val blue = scratch.rowBlue(4).also { it.fill(.8f) }
        val green = scratch.rowGreen(4).also { it.fill(.6f) }
        val red = scratch.rowRed(4).also { it.fill(.4f) }
        val planar = scratch.planarArray(12).also { it.fill(.9f) }
        val buffer = scratch.floatBuffer(12).also { it.put(FloatArray(12) { .7f }) }
        scratch.scrub(12, 4)
        assertEquals(List(4) { 0f }, blue.toList())
        assertEquals(List(4) { 0f }, green.toList())
        assertEquals(List(4) { 0f }, red.toList())
        assertEquals(List(12) { 0f }, planar.toList())
        assertEquals(List(12) { 0f }, (0 until 12).map { buffer[it] })
        assertSame(blue, scratch.rowBlue(2))
        assertSame(green, scratch.rowGreen(2))
        assertSame(red, scratch.rowRed(2))
        assertSame(planar, scratch.planarArray(6))
        assertSame(buffer, scratch.floatBuffer(6))
        blue.fill(.5f)
        green.fill(.5f)
        red.fill(.5f)
        planar.fill(.5f)
        buffer.put(0, .5f)
        scratch.rowBlue(8)
        scratch.rowGreen(8)
        scratch.rowRed(8)
        scratch.planarArray(24)
        scratch.floatBuffer(24)
        assertEquals(List(4) { 0f }, blue.toList())
        assertEquals(List(4) { 0f }, green.toList())
        assertEquals(List(4) { 0f }, red.toList())
        assertEquals(List(12) { 0f }, planar.toList())
        assertEquals(0f, buffer[0])
        scratch.scrub()
    }

    @Test
    fun narrowerInferenceScrubsAllPopulatedDataWithoutRewritingRetainedRowTails() {
        val scratch = DirectFloatScratch()
        val rows = listOf(scratch.rowBlue(1280), scratch.rowGreen(1280), scratch.rowRed(1280))
        val wideInput = scratch.floatBuffer(3 * 48 * 1280)
        val widePlanar = scratch.planarArray(wideInput.capacity())
        rows.forEach { it.fill(.7f) }
        wideInput.put(FloatArray(wideInput.capacity()) { .8f })
        widePlanar.fill(.9f)
        scratch.scrub(wideInput.capacity(), 1280)

        // Emulate an inference failing after partially filling a much shorter line.
        val width = 32
        val usedFloats = 3 * 48 * width
        try {
            rows.forEach { it.fill(.4f, 0, width) }
            scratch.floatBuffer(usedFloats).put(FloatArray(usedFloats) { .5f })
            scratch.planarArray(usedFloats).fill(.6f, 0, usedFloats)
            error("inference failure")
        } catch (_: IllegalStateException) {
        } finally {
            scratch.scrub(usedFloats, width)
        }
        // Assert full retained capacities, not just the active prefixes.
        rows.forEach { assertEquals(List(1280) { 0f }, it.toList()) }
        assertEquals(List(widePlanar.size) { 0f }, widePlanar.toList())
        wideInput.clear()
        assertEquals(List(wideInput.capacity()) { 0f }, (0 until wideInput.capacity()).map { wideInput[it] })
        scratch.scrub()
    }

    @Test
    fun reusesAndScrubsTextDetectorScratchAcrossSizes() {
        val detectorScratch = TextDetectorScratch()
        val probs = detectorScratch.probsArray(16).also { it.fill(.9f) }
        val seen = detectorScratch.seenArray(16).also { it.fill(true) }
        val queue = detectorScratch.queueArray(16).also { it.fill(7) }
        detectorScratch.scrub(16)
        assertEquals(List(16) { 0f }, probs.toList())
        assertEquals(List(16) { false }, seen.toList())
        assertEquals(List(16) { 0 }, queue.toList())
        assertSame(probs, detectorScratch.probsArray(8))
        assertSame(seen, detectorScratch.seenArray(8))
        assertSame(queue, detectorScratch.queueArray(8))
        probs.fill(.4f)
        seen.fill(true)
        queue.fill(3)
        detectorScratch.probsArray(32)
        detectorScratch.seenArray(32)
        detectorScratch.queueArray(32)
        assertEquals(List(16) { 0f }, probs.toList())
        assertEquals(List(16) { false }, seen.toList())
        assertEquals(List(16) { 0 }, queue.toList())
        detectorScratch.scrub()
    }
}
