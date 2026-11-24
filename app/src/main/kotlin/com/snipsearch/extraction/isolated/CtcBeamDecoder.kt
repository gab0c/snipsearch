package com.snipsearch.extraction.isolated

import java.nio.FloatBuffer
import kotlin.math.exp
import kotlin.math.ln

/** Prefix beam search for PP-OCR's CTC probabilities. Class zero is the CTC blank. */
internal class CtcBeamDecoder(private val alphabet: List<String>) {
    val classes: Int get() = alphabet.size + 1

    fun decode(probabilities: FloatBuffer, steps: Int): List<DecodedWord> {
        var beam = mapOf("" to Score(blank = 0.0))
        val tokens = IntArray(10)
        val scores = FloatArray(tokens.size)
        try {
            repeat(steps) { step ->
                val offset = step * classes
                scores.fill(0f)
                for (token in 1 until classes) {
                    val probability = probabilities[offset + token]
                    if (probability <= scores.last()) continue
                    var index = scores.lastIndex
                    while (index > 0 && probability > scores[index - 1]) {
                        tokens[index] = tokens[index - 1]
                        scores[index] = scores[index - 1]
                        index--
                    }
                    tokens[index] = token
                    scores[index] = probability
                }
                val next = mutableMapOf<String, Score>()
                val blank = ln(probabilities[offset].toDouble())
                for ((prefix, score) in beam) {
                    val same = next.getOrPut(prefix, ::Score)
                    same.blank = logAdd(same.blank, score.total + blank)
                    // A repeated emission extends the prefix only if the previous step was blank.
                    val last = prefix.lastOrNull()?.code
                    if (last != null) {
                        val repeat = ln(probabilities[offset + last].toDouble())
                        same.text = logAdd(same.text, score.text + repeat)
                    }
                    for (index in scores.indices) {
                        if (scores[index] <= 0f) break
                        val token = tokens[index]
                        val previous = if (last == token) score.blank else score.total
                        val extended = next.getOrPut(prefix + token.toChar(), ::Score)
                        extended.text = logAdd(extended.text, previous + ln(scores[index].toDouble()))
                    }
                }
                beam = next.entries.sortedByDescending { it.value.total }.take(5).associate { it.key to it.value }
            }
            val best = beam.maxBy { it.value.total }.key
            if (best.isEmpty()) return emptyList()
            return align(best, probabilities, steps)
        } finally {
            scores.fill(0f)
            tokens.fill(0)
        }
    }

    private fun align(prefix: String, probabilities: FloatBuffer, steps: Int): List<DecodedWord> {
        // Align the winning prefix to the most likely path through alternating blank
        // and token states. This locates words even when letters occupy uneven widths.
        val states = 2 * prefix.length + 1
        val path = ByteArray(steps * states)
        var previous = DoubleArray(states) { Double.NEGATIVE_INFINITY }
        var current = previous.clone()
        val first = IntArray(prefix.length) { steps }
        val last = IntArray(prefix.length)
        previous[0] = 0.0
        try {
            repeat(steps) { step ->
                for (state in 0 until states) {
                    val token = if (state % 2 == 0) 0 else prefix[state / 2].code
                    var source = state
                    if (state > 0 && previous[state - 1] > previous[source]) source = state - 1
                    if (state >= 3 && token != 0 && prefix[state / 2 - 1].code != token &&
                        previous[state - 2] > previous[source]) source = state - 2
                    path[step * states + state] = (state - source).toByte()
                    current[state] = previous[source] + ln(probabilities[step * classes + token].toDouble())
                }
                val swap = previous
                previous = current
                current = swap
            }
            var state = if (previous[states - 1] >= previous[states - 2]) states - 1 else states - 2
            if (!previous[state].isFinite()) return emptyList()
            for (step in steps - 1 downTo 0) {
                if (state % 2 == 1) {
                    first[state / 2] = step
                    last[state / 2] = maxOf(last[state / 2], step + 1)
                }
                state -= path[step * states + state]
            }
            return buildList {
                var start = 0
                val text = StringBuilder()
                for (i in prefix.indices) {
                    val symbol = alphabet[prefix[i].code - 1]
                    if (symbol.isBlank()) {
                        if (text.isNotEmpty()) add(DecodedWord(text.toString(), first[start], last[i - 1]))
                        text.clear()
                    } else {
                        if (text.isEmpty()) start = i
                        text.append(symbol)
                    }
                }
                if (text.isNotEmpty()) add(DecodedWord(text.toString(), first[start], last.last()))
            }
        } finally {
            path.fill(0)
            previous.fill(0.0)
            current.fill(0.0)
            first.fill(0)
            last.fill(0)
        }
    }

    private class Score(var blank: Double = Double.NEGATIVE_INFINITY, var text: Double = Double.NEGATIVE_INFINITY) {
        val total get() = logAdd(blank, text)
    }
}

internal data class DecodedWord(val text: String, val firstStep: Int, val endStep: Int)

private fun logAdd(a: Double, b: Double): Double {
    val highest = maxOf(a, b)
    return if (highest.isFinite()) highest + Math.log1p(exp(minOf(a, b) - highest)) else highest
}
