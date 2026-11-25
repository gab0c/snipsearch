package com.snipsearch.extraction.isolated

import java.nio.FloatBuffer

/** Single-pass greedy CTC decoder for PP-OCR probabilities. Class zero is the CTC blank. */
internal class CtcBeamDecoder(private val alphabet: List<String>) {
    val classes: Int get() = alphabet.size + 1

    fun decode(probabilities: FloatBuffer, steps: Int): List<DecodedWord> {
        val stepProbs = FloatArray(classes)
        val view = probabilities.duplicate()
        val currentWord = StringBuilder()
        val words = mutableListOf<DecodedWord>()
        try {
            var prevToken = 0
            var wordFirstStep = -1
            var wordEndStep = 0
            repeat(steps) { step ->
                view.position(step * classes)
                view.get(stepProbs)

                // Softmax probabilities sum to 1.0, so any class > 0.5 is the unique argmax.
                val bestToken = if (stepProbs[0] > 0.5f) {
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

                if (bestToken == 0) {
                    prevToken = 0
                } else if (bestToken == prevToken) {
                    if (currentWord.isNotEmpty()) {
                        wordEndStep = step + 1
                    }
                } else {
                    val symbol = alphabet[bestToken - 1]
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
                    prevToken = bestToken
                }
            }
            if (currentWord.isNotEmpty()) {
                words += DecodedWord(currentWord.toString(), wordFirstStep, wordEndStep)
            }
            return words
        } finally {
            stepProbs.fill(0f)
            currentWord.setLength(0)
        }
    }
}

internal data class DecodedWord(val text: String, val firstStep: Int, val endStep: Int)
