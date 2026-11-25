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

    @Test
    fun compactPerStepConfidenceAveragesCharacterRunsAndKeepsCallerPositions() {
        val tokens = LongBuffer.wrap(longArrayOf(0L, 1L, 1L, 0L, 3L, 2L, 0L))
        val confidence = FloatBuffer.wrap(floatArrayOf(.1f, .8f, .6f, .1f, .1f, .4f, .1f))
        tokens.position(1)
        confidence.position(2)

        assertEquals(
            listOf(DecodedWord("a", 1, 3, .70000005f), DecodedWord("b", 5, 6, .4f)),
            decoder.decodeTokensWithConfidence(tokens, confidence, 7),
        )
        assertEquals(1, tokens.position())
        assertEquals(2, confidence.position())
    }
    @Test
    fun rejectsUncertainIconLettersAndSymbolsButKeepsConfidentShortText() {
        for (glyph in listOf("O", "X", "<", "一", "🔎")) {
            org.junit.Assert.assertFalse(DecodedWord(glyph, 0, 1, .49f).isConfident())
            org.junit.Assert.assertTrue(DecodedWord(glyph, 0, 1, .995f).isConfident())
        }
        for (text in listOf("OK", "https://example.org/a?q=b", "Dense paragraph", "日本語")) {
            org.junit.Assert.assertFalse(DecodedWord(text, 0, 9, .3f).isConfident())
            org.junit.Assert.assertTrue(DecodedWord(text, 0, 9, .9f).isConfident())
        }
    }

    @Test
    fun blankConfidenceDoesNotRescueLowConfidenceGlyphAndMalformedScoresFailClosed() {
        for (score in listOf(.1f, Float.NaN, Float.POSITIVE_INFINITY, -1f, 2f)) {
            val words = decoder.decodeTokensWithConfidence(
                LongBuffer.wrap(longArrayOf(0, 1, 0)),
                FloatBuffer.wrap(floatArrayOf(.999f, score, .999f)), 3,
            )
            org.junit.Assert.assertFalse(words.single().isConfident())
        }
    }

    @Test
    fun rejectsModerateConfidenceShortSymbolRunsWithoutRejectingLabelsOrUrls() {
        org.junit.Assert.assertFalse(DecodedWord("<XO", 0, 9, .69f).isConfident())
        org.junit.Assert.assertTrue(DecodedWord("OK", 0, 9, .73f).isConfident())
        org.junit.Assert.assertTrue(DecodedWord("IOK", 0, 9, .73f).isConfident())
        org.junit.Assert.assertTrue(DecodedWord("日本語", 0, 9, .73f).isConfident())
        org.junit.Assert.assertTrue(DecodedWord("1.0", 0, 9, .95f).isConfident())
        org.junit.Assert.assertTrue(DecodedWord("https://example.org", 0, 9, .73f).isConfident())
    }

    @Test
    fun rejectsNarrowDetachedLeadingIconWithoutDroppingNormalSingleLetterText() {
        val icon = DecodedWord("Q", 1, 2, .999f)
        val label = DecodedWord("Search", 7, 15, .999f)
        assertEquals(listOf(label), filterRecognizedWords(listOf(icon, label), .1))
        val pronoun = DecodedWord("I", 1, 2, .999f)
        val verb = DecodedWord("read", 3, 8, .999f)
        assertEquals(listOf(pronoun, verb), filterRecognizedWords(listOf(pronoun, verb), .2))
        assertEquals(listOf(pronoun), filterRecognizedWords(listOf(pronoun), .2))
        assertEquals(listOf(icon, label), filterRecognizedWords(listOf(icon, label), .5))
    }

    @Test
    fun keepsWidelySpacedSingleLettersOnLongerTextLines() {
        val words = listOf("A", "I", "OK", "No", "日本語").mapIndexed { i, text ->
            DecodedWord(text, i * 7, i * 7 + 1, .999f)
        }
        assertEquals(words, filterRecognizedWords(words, .1))
    }

}
