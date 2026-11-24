package com.snipsearch.extraction

import kotlin.math.abs

internal data class OcrWord(val characters: IntRange, val bounds: PixelRegion)

internal data class OcrLine(val text: String, val words: List<OcrWord>) {
    val bounds: PixelRegion
        get() = PixelRegion(
            words.minOf { it.bounds.left }, words.minOf { it.bounds.top },
            words.maxOf { it.bounds.right }, words.maxOf { it.bounds.bottom },
        )
}

internal object OcrTextCorrection {
    private val word = Regex("\\S+")

    fun correct(lines: List<OcrLine>, assist: List<AssistTextBlock>): List<OcrLine> {
        if (lines.isEmpty() || assist.isEmpty()) return lines
        val bounds = lines.map { it.bounds }
        val hints = assist.asSequence()
            .filter { it.text.length in 5..164 }
            .distinctBy { it.text to it.bounds }
            .map { Hint(it.bounds, word.findAll(it.text).map { match -> match.value }.toList()) }
            .toList()
        val uniqueHints = lines.mapIndexed { index, line ->
            hints.filter { matches(line, bounds[index], it) }.singleOrNull()
        }
        return lines.mapIndexed { index, line ->
            val hint = uniqueHints[index] ?: return@mapIndexed line
            // A view can describe a whole row. In that case one hint might be spatially
            // plausible for multiple repeated labels, and it must not replace either.
            if (lines.indices.any { it != index && matches(lines[it], bounds[it], hint) }) {
                return@mapIndexed line
            }
            val words = ArrayList<OcrWord>(line.words.size)
            val corrected = buildString {
                line.words.zip(hint.words).forEachIndexed { wordIndex, (original, replacement) ->
                    if (wordIndex > 0) append(' ')
                    val start = length
                    append(replacement)
                    words += original.copy(characters = start until length)
                }
            }
            OcrLine(corrected, words)
        }
    }

    private fun matches(line: OcrLine, bounds: PixelRegion, hint: Hint): Boolean {
        if (line.words.size != hint.words.size || line.text.length !in 5..160) return false
        val box = hint.bounds
        val horizontalGap = maxOf(box.left - bounds.right, bounds.left - box.right, 0)
        val verticalGap = maxOf(box.top - bounds.bottom, bounds.top - box.bottom, 0)
        if (horizontalGap > bounds.height * 2 || verticalGap > bounds.height / 2) return false
        val centerShift = abs((box.top + box.bottom) - (bounds.top + bounds.bottom)) / 2
        if (centerShift > maxOf(bounds.height, box.height / 2)) return false

        var errors = 0
        val allowed = (line.text.length / 8).coerceIn(1, 4)
        for ((original, replacement) in line.words.zip(hint.words)) {
            val visible = line.text.substring(original.characters)
            if (visible.equals(replacement, ignoreCase = true)) continue
            if (visible.length < 4 || abs(visible.length - replacement.length) > 1) return false
            if ('…' in visible || "..." in visible ||
                replacement.startsWith(visible, ignoreCase = true) ||
                replacement.endsWith(visible, ignoreCase = true)) return false
            errors += editDistance(visible.lowercase(), replacement.lowercase())
            if (errors > allowed) return false
        }
        return true
    }

    private fun editDistance(a: String, b: String): Int {
        val row = IntArray(b.length + 1) { it }
        for (i in a.indices) {
            var diagonal = row[0]
            row[0] = i + 1
            for (j in b.indices) {
                val previous = row[j + 1]
                row[j + 1] = minOf(row[j] + 1, previous + 1, diagonal + if (a[i] == b[j]) 0 else 1)
                diagonal = previous
            }
        }
        return row.last()
    }

    private data class Hint(val bounds: PixelRegion, val words: List<String>)
}
