package com.snipsearch.extraction

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import java.net.URI
import java.net.URISyntaxException

enum class EntityKind { URL, EMAIL, PHONE, COUPON, TRACKING, QR_CODE, BARCODE }

data class DetectedEntity(val kind: EntityKind, val value: String)

object EntityAndQrDetector {
    private val url = Regex("""(?i)\bhttps?://[^\s<>"']+""")
    private val email = Regex("""(?i)(?<![\w.%+@-])[a-z0-9._%+-]+@[a-z0-9-]+(?:\.[a-z0-9-]+)*\.[a-z]{2,}(?![\w@-])""")
    private val phone = Regex("""(?<![\w-])(?:\+?\d|\(\d)[\d(). -]{5,}\d(?![\w-])""")
    private val date = Regex("""\d{2,4}[-.]\d{1,2}[-.]\d{2,4}""")
    private val code = Regex(
        """(?i)\b(coupon(?: code)?|promo code|tracking(?: number| code)?)\s*[:#]\s*([a-z0-9][a-z0-9-]{3,39})\b""",
    )
    private val hints = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(
            BarcodeFormat.QR_CODE, BarcodeFormat.DATA_MATRIX, BarcodeFormat.AZTEC,
            BarcodeFormat.EAN_13, BarcodeFormat.CODE_128,
        ),
        DecodeHintType.TRY_HARDER to true,
    )

    fun extract(text: String): List<DetectedEntity> {
        val matches = mutableListOf<Pair<IntRange, DetectedEntity>>()

        fun add(range: IntRange, kind: EntityKind, value: String) {
            if (matches.none { (used, _) -> range.first <= used.last && used.first <= range.last }) {
                matches += range to DetectedEntity(kind, value)
            }
        }

        url.findAll(text).forEach { match ->
            val value = trimLinkPunctuation(match.value)
            if (isWebUrl(value)) {
                add(match.range.first until (match.range.first + value.length), EntityKind.URL, value)
            }
        }
        email.findAll(text).forEach { add(it.range, EntityKind.EMAIL, it.value) }
        code.findAll(text).forEach { match ->
            val kind = if (match.groupValues[1].startsWith("tracking", ignoreCase = true)) {
                EntityKind.TRACKING
            } else {
                EntityKind.COUPON
            }
            add(match.range, kind, match.groupValues[2])
        }
        phone.findAll(text).forEach { match ->
            val digits = match.value.filter(Char::isDigit)
            if (digits.length in 7..15 && !date.matches(match.value)) {
                val prefix = if (match.value.startsWith('+')) "+" else ""
                add(match.range, EntityKind.PHONE, prefix + digits)
            }
        }

        return matches.sortedBy { it.first.first }.map { it.second }.distinct()
    }

    /** Call off the UI thread. The caller owns and clears [argb] after decoding. */
    fun decode(argb: IntArray, width: Int, height: Int): DetectedEntity? {
        require(width > 0 && height > 0 && width.toLong() * height == argb.size.toLong())
        val source = RGBLuminanceSource(width, height, argb)
        val luminance = source.matrix
        return try {
            val reader = MultiFormatReader()
            val result = try {
                reader.decode(BinaryBitmap(HybridBinarizer(source)), hints)
            } catch (_: ReaderException) {
                // This source covers the entire matrix, so retry inverted in the same buffer.
                for (index in luminance.indices) luminance[index] = luminance[index].toInt().inv().toByte()
                reader.decode(BinaryBitmap(HybridBinarizer(source)), hints)
            }
            val kind = if (result.barcodeFormat == BarcodeFormat.QR_CODE) EntityKind.QR_CODE else EntityKind.BARCODE
            DetectedEntity(kind, result.text)
        } catch (_: ReaderException) {
            null
        } finally {
            luminance.fill(0)
        }
    }

    fun isWebUrl(value: String): Boolean {
        return try {
            val uri = URI(value)
            (uri.scheme.equals("https", ignoreCase = true) || uri.scheme.equals("http", ignoreCase = true)) &&
                !uri.host.isNullOrEmpty() && uri.rawUserInfo == null
        } catch (_: URISyntaxException) {
            false
        }
    }

    private fun trimLinkPunctuation(value: String): String {
        var end = value.length
        var parentheses = value.count { it == ')' } - value.count { it == '(' }
        var brackets = value.count { it == ']' } - value.count { it == '[' }
        while (end > 0) {
            when (value[end - 1]) {
                '.', ',', ';', ':', '!', '?' -> Unit
                ')' -> if (parentheses > 0) parentheses-- else break
                ']' -> if (brackets > 0) brackets-- else break
                else -> break
            }
            end--
        }
        return value.substring(0, end)
    }
}
