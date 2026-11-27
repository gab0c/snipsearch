package com.snipsearch.extraction.ocr

import com.snipsearch.extraction.OcrLine
import com.snipsearch.extraction.OcrWord

internal fun assembleOcrLine(text: String, words: List<OcrWord>): OcrLine? {
    val visibleWords = words.filter { it.bounds.width > 0 && it.bounds.height > 0 }
    return visibleWords.takeIf { it.isNotEmpty() }?.let { OcrLine(text, it) }
}
