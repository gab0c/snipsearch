package com.snipsearch.extraction.isolated

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.FloatBuffer
import java.nio.LongBuffer

class CtcBeamDecoderTest {
    private val decoder = CtcBeamDecoder(listOf("a", "b", " "))

    @Test
    fun usesBlankToSeparateRepeatedLettersAndReturnsWordPositions() {
        val path = intArrayOf(0, 1, 1, 0, 1, 3, 0, 2, 2, 0)
        val scores = FloatBuffer.wrap(FloatArray(path.size * 4) { index ->
            if (path[index / 4] == index % 4) 0.97f else 0.01f
        })
        scores.position(3)

        assertEquals(listOf(DecodedWord("aa", 1, 5), DecodedWord("b", 7, 9)), decoder.decode(scores, path.size))
        assertEquals(3, scores.position())
    }

    @Test
    fun blankOnlyProducesNoWords() {
        assertEquals(emptyList<DecodedWord>(), decoder.decode(FloatBuffer.wrap(floatArrayOf(1f, 0f, 0f, 0f)), 1))
    }

    @Test
    fun handlesLowConfidenceTieBreakAndConsecutiveSpacesWithoutEarlyExit() {
        // Every class < 0.5 so full scan runs across all classes; consecutive blanks/spaces collapse cleanly.
        val classes = 4
        val data = floatArrayOf(
            0.22f, 0.34f, 0.22f, 0.22f, // token 1 ("a")
            0.20f, 0.20f, 0.20f, 0.40f, // token 3 (" ")
            0.20f, 0.20f, 0.20f, 0.40f, // repeated space
            0.20f, 0.20f, 0.40f, 0.20f, // token 2 ("b")
            0.55f, 0.15f, 0.15f, 0.15f, // blank > 0.5 early exit
            0.20f, 0.20f, 0.40f, 0.20f, // token 2 ("b")
        )
        val scores = FloatBuffer.wrap(data)

        assertEquals(
            listOf(DecodedWord("a", 0, 1), DecodedWord("bb", 3, 6)),
            decoder.decode(scores, 6),
        )
    }

    @Test
    fun decodesArgMaxTokenIdsWithBlanksSpacesOutOfRangeIdsAndPreservesPosition() {
        val tokens = LongBuffer.wrap(longArrayOf(0L, 1L, 1L, 0L, 1L, 3L, 3L, 0L, 2L, 99L, 2L, -1L))
        tokens.position(4)

        assertEquals(
            listOf(DecodedWord("aa", 1, 5), DecodedWord("bb", 8, 11)),
            decoder.decodeTokens(tokens, 12),
        )
        assertEquals(4, tokens.position())
    }

    @Test
    fun argMaxBlankAndOutOfRangeOnlyProducesNoWords() {
        val tokens = LongBuffer.wrap(longArrayOf(0L, -3L, 4L, 0L))
        assertEquals(emptyList<DecodedWord>(), decoder.decodeTokens(tokens, 4))
    }
}
