package com.snipsearch.extraction.isolated

import java.nio.FloatBuffer
import java.nio.LongBuffer

/** Single-pass greedy CTC decoder. Class zero is the CTC blank. */
internal class CtcBeamDecoder(private val alphabet: List<String>) {
    val classes: Int get() = alphabet.size + 1

    fun decode(probabilities: FloatBuffer, steps: Int): List<DecodedWord> {
        val stepProbs = FloatArray(classes)
        val view = probabilities.duplicate()
        try {
            return decodeSteps(steps, tokenAt = { step ->
                view.position(step * classes)
                view.get(stepProbs)

                argmax(stepProbs)
            })
        } finally {
            stepProbs.fill(0f)
        }
    }

    fun decodeTokens(tokens: LongBuffer, steps: Int): List<DecodedWord> {
        val view = tokens.duplicate()
        return decodeSteps(steps, tokenAt = { step ->
            val raw = view[step]
            if (raw <= 0L || raw > alphabet.size) 0 else raw.toInt()
        })
    }

    fun decodeTokensWithConfidence(tokens: LongBuffer, confidence: FloatBuffer, steps: Int): List<DecodedWord> {
        val tokenView = tokens.duplicate()
        val confidenceView = confidence.duplicate()
        return decodeSteps(steps, { s -> tokenAt(tokenView[s]) }) { token, at ->
            if (token > 0 && token <= alphabet.size) confidenceView[at].let { if (it.isFinite() && it in 0f..1f) it else 0f } else 0f
        }
    }

    private fun argmax(scores: FloatArray): Int {
        var winner = 0
        for (i in 1 until classes) if (scores[i] > scores[winner]) winner = i
        return winner
    }

    private fun tokenAt(raw: Long): Int = if (raw <= 0L || raw > alphabet.size) 0 else raw.toInt()

    private inline fun decodeSteps(
        steps: Int,
        tokenAt: (Int) -> Int,
        confidenceAt: (Int, Int) -> Float = { _, _ -> 1f },
    ): List<DecodedWord> {
        val currentWord = StringBuilder()
        val words = mutableListOf<DecodedWord>()
        try {
            var prevToken = 0
            var wordFirstStep = -1
            var wordEndStep = 0
            var confidenceSum = 0.0
            var confidenceCount = 0
            repeat(steps) { step ->
                val token = tokenAt(step)
                if (token <= 0 || token > alphabet.size) {
                    prevToken = 0
                } else if (token == prevToken) {
                    if (currentWord.isNotEmpty()) {
                        wordEndStep = step + 1
                        confidenceSum += confidenceAt(token, step)
                        confidenceCount++
                    }
                } else {
                    val symbol = alphabet[token - 1]
                    if (symbol.isBlank()) {
                        if (currentWord.isNotEmpty()) {
                            words += DecodedWord(currentWord.toString(), wordFirstStep, wordEndStep, meanConfidence(confidenceSum, confidenceCount))
                            currentWord.setLength(0)
                            confidenceSum = 0.0
                            confidenceCount = 0
                        }
                    } else {
                        if (currentWord.isEmpty()) {
                            wordFirstStep = step
                        }
                        currentWord.append(symbol)
                        wordEndStep = step + 1
                        confidenceSum += confidenceAt(token, step)
                        confidenceCount++
                    }
                    prevToken = token
                }
            }
            if (currentWord.isNotEmpty()) {
                words += DecodedWord(currentWord.toString(), wordFirstStep, wordEndStep, meanConfidence(confidenceSum, confidenceCount))
            }
            return words
        } finally {
            currentWord.setLength(0)
        }
    }
}

private fun meanConfidence(sum: Double, count: Int) = if (count == 0) 0f else (sum / count).toFloat()

internal data class DecodedWord(val text: String, val firstStep: Int, val endStep: Int, val confidence: Float = 1f)

/** Icons often decode as one glyph or a short mixed symbol run (for example chevron/X/toggle). */
internal fun DecodedWord.isConfident(): Boolean {
    val count = text.codePointCount(0, text.length)
    val iconLike = count == 1 || (count <= 3 && text.codePoints().anyMatch { !Character.isLetterOrDigit(it) })
    return confidence.isFinite() && confidence >= if (iconLike) 0.80f else 0.55f
}

/** A short UI label may swallow a detached leading icon. Never apply this rule to paragraphs. */
internal fun filterRecognizedWords(words: List<DecodedWord>, aspectPerStep: Double): List<DecodedWord> {
    val accepted = words.filter { it.isConfident() }
    if (accepted.size !in 2..3) return accepted
    val first = accepted[0]
    val span = first.endStep - first.firstStep
    val gap = accepted[1].firstStep - first.endStep
    val detachedEdgeGlyph = first.text.codePointCount(0, first.text.length) == 1 &&
        span > 0 && span * aspectPerStep < 0.20 && gap > span * 1.5
    return if (detachedEdgeGlyph) accepted.drop(1) else accepted
}
