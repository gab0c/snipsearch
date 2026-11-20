package com.snipsearch.extraction

import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class EntityAndQrDetectorTest {
    @Test
    fun extractsTypedEntitiesInOrderWithoutDuplicatesOrOverlaps() {
        val text = """
            See (https://example.org/archive_(2026)), then email person+tag@example.org.
            Call +1 (415) 555-2671. Coupon code: SAVE-20. Tracking: 1234567890.
            Updated 2026-11-19. https://example.org/archive_(2026)
        """.trimIndent()
        assertEquals(
            listOf(
                DetectedEntity(EntityKind.URL, "https://example.org/archive_(2026)"),
                DetectedEntity(EntityKind.EMAIL, "person+tag@example.org"),
                DetectedEntity(EntityKind.PHONE, "+14155552671"),
                DetectedEntity(EntityKind.COUPON, "SAVE-20"),
                DetectedEntity(EntityKind.TRACKING, "1234567890"),
            ),
            EntityAndQrDetector.extract(text),
        )
    }

    @Test
    fun onlyDispatchesWellFormedHttpAndHttpsUrls() {
        listOf(
            "javascript:alert(1)", "intent://example.org", "file:///private",
            "https://trusted.example@attacker.example/", "https://", "https://example.org/\n",
        ).forEach { assertFalse(it, EntityAndQrDetector.isWebUrl(it)) }
    }

    @Test
    fun decodesNormalAndInvertedQrCodePixels() {
        val value = "https://example.org/qr?id=7"
        val expected = DetectedEntity(EntityKind.QR_CODE, value)
        assertEquals(expected, decode(value, BarcodeFormat.QR_CODE))
        assertEquals(expected, decode(value, BarcodeFormat.QR_CODE, inverted = true))
    }

    @Test
    fun decodesCode128AndIgnoresAnEmptyImage() {
        assertEquals(
            DetectedEntity(EntityKind.BARCODE, "ORDER-4321"),
            decode("ORDER-4321", BarcodeFormat.CODE_128),
        )
        assertNull(EntityAndQrDetector.decode(IntArray(64 * 64) { -1 }, 64, 64))
    }

    private fun decode(value: String, format: BarcodeFormat, inverted: Boolean = false): DetectedEntity? {
        val matrix = MultiFormatWriter().encode(value, format, 240, 120)
        val pixels = IntArray(matrix.width * matrix.height) { index ->
            if (matrix[index % matrix.width, index / matrix.width] xor inverted) 0xff000000.toInt() else -1
        }
        return EntityAndQrDetector.decode(pixels, matrix.width, matrix.height)
    }
}
