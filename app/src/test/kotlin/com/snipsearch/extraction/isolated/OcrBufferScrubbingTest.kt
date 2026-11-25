package com.snipsearch.extraction.isolated

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
        val array = scratch.planarArray(12).also { it.fill(.8f) }
        val buffer = scratch.floatBuffer(12).also { it.put(FloatArray(12) { .7f }) }
        scratch.scrub(12)
        assertEquals(List(12) { 0f }, array.toList())
        assertEquals(List(12) { 0f }, (0 until 12).map { buffer[it] })
        assertSame(array, scratch.planarArray(6))
        assertSame(buffer, scratch.floatBuffer(6))
        array.fill(.5f)
        buffer.put(0, .5f)
        scratch.planarArray(24)
        scratch.floatBuffer(24)
        assertEquals(List(12) { 0f }, array.toList())
        assertEquals(0f, buffer[0])
        scratch.scrub()
    }
}
