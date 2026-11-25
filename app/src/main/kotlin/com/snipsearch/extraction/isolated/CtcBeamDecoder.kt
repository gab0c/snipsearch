package com.snipsearch.extraction.isolated

import java.nio.FloatBuffer
import java.nio.LongBuffer

/** Single-pass greedy CTC decoder for PP-OCR logits/probabilities. Class zero is the CTC blank. */
internal class CtcBeamDecoder(private val alphabet: List<String>) {
    val classes: Int get() = alphabet.size + 1

    fun decode(probabilities: FloatBuffer, steps: Int): List<DecodedWord> {
        val stepProbs = FloatArray(classes)
        val view = probabilities.duplicate()
        try {
            return decodeSteps(steps) { step ->
                view.position(step * classes)
                view.get(stepProbs)

                // Softmax probabilities sum to 1.0, so any class > 0.5 is the unique argmax.
                if (stepProbs[0] > 0.5f) {
                    0
                } else {
                    var winner = 0
                    var winnerProb = stepProbs[0]
                    for (token in 1 until classes) {
                        val probability = stepProbs[token]
                        if (probability > winnerProb) {
                            winner = token
                            winnerProb = probability
                            if (probability > 0.5f) break
                        }
                    }
                    winner
                }
            }
        } finally {
            stepProbs.fill(0f)
        }
    }

    fun decodeTokens(tokens: LongBuffer, steps: Int): List<DecodedWord> {
        val view = tokens.duplicate()
        return decodeSteps(steps) { step ->
            val raw = view[step]
            if (raw <= 0L || raw > alphabet.size) 0 else raw.toInt()
        }
    }

    private inline fun decodeSteps(steps: Int, tokenAt: (Int) -> Int): List<DecodedWord> {
        val currentWord = StringBuilder()
        val words = mutableListOf<DecodedWord>()
        try {
            var prevToken = 0
            var wordFirstStep = -1
            var wordEndStep = 0
            repeat(steps) { step ->
                val token = tokenAt(step)
                if (token <= 0 || token > alphabet.size) {
                    prevToken = 0
                } else if (token == prevToken) {
                    if (currentWord.isNotEmpty()) {
                        wordEndStep = step + 1
                    }
                } else {
                    val symbol = alphabet[token - 1]
                    if (symbol.isBlank()) {
                        if (currentWord.isNotEmpty()) {
                            words += DecodedWord(currentWord.toString(), wordFirstStep, wordEndStep)
                            currentWord.setLength(0)
                        }
                    } else {
                        if (currentWord.isEmpty()) {
                            wordFirstStep = step
                        }
                        currentWord.append(symbol)
                        wordEndStep = step + 1
                    }
                    prevToken = token
                }
            }
            if (currentWord.isNotEmpty()) {
                words += DecodedWord(currentWord.toString(), wordFirstStep, wordEndStep)
            }
            return words
        } finally {
            currentWord.setLength(0)
        }
    }
}

internal data class DecodedWord(val text: String, val firstStep: Int, val endStep: Int)
