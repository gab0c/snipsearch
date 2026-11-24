package com.snipsearch.extraction.isolated

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.FloatBuffer

class CtcBeamDecoderTest {
    private val decoder = CtcBeamDecoder(listOf("a", "b", " "))

    @Test
    fun usesBlankToSeparateRepeatedLettersAndReturnsWordPositions() {
        val path = intArrayOf(0, 1, 1, 0, 1, 3, 0, 2, 2, 0)
        val scores = FloatBuffer.wrap(FloatArray(path.size * 4) { index ->
            if (path[index / 4] == index % 4) 0.97f else 0.01f
        })

        assertEquals(listOf(DecodedWord("aa", 1, 5), DecodedWord("b", 7, 9)), decoder.decode(scores, path.size))
    }

    @Test
    fun blankOnlyProducesNoWords() {
        assertEquals(emptyList<DecodedWord>(), decoder.decode(FloatBuffer.wrap(floatArrayOf(1f, 0f, 0f, 0f)), 1))
    }
}
