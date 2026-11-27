package com.snipsearch.search

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import com.snipsearch.extraction.PixelRegion
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.ConscryptMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class UploadImageEncoderTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun encodesPngAndWebpInRamWithoutMetadataOrDiskWrites() {
        val source = Bitmap.createBitmap(16, 12, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(42, 128, 200))
        }
        val encoder = DefaultUploadImageEncoder()
        val filesBefore = snapshotDir(context.filesDir)
        val cacheBefore = snapshotDir(context.cacheDir)

        try {
            val png = requireNotNull(encoder.encode(source, PixelRegion(2, 3, 10, 9), UploadImageFormat.PNG, 85))
            png.use { encoded ->
                assertEquals(8, encoded.width)
                assertEquals(6, encoded.height)
                assertTrue(encoded.size > 8)
                val bytes = requireNotNull(encoded.detachBytes())
                assertEquals(0, encoded.size)
                assertEquals(0x89.toByte(), bytes[0])
                assertEquals('P'.code.toByte(), bytes[1])
                assertEquals('N'.code.toByte(), bytes[2])
                assertEquals('G'.code.toByte(), bytes[3])
                assertNoImageMetadataMarkers(bytes)
                bytes.fill(0)
            }

            for (format in listOf(UploadImageFormat.WEBP_LOSSY, UploadImageFormat.WEBP_LOSSLESS)) {
                val webp = requireNotNull(encoder.encode(source, PixelRegion(0, 0, 12, 10), format, 80))
                webp.use { encoded ->
                    assertEquals(12, encoded.width)
                    assertEquals(10, encoded.height)
                    val bytes = requireNotNull(encoded.detachBytes())
                    assertTrue(bytes.size > 12)
                    // Real Android Skia produces RIFF/WEBP; Robolectric's JVM ImageIO fallback writes PNG.
                    val isWebp = String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                        String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP"
                    val isPngFallback = bytes[0] == 0x89.toByte() &&
                        bytes[1] == 'P'.code.toByte() &&
                        bytes[2] == 'N'.code.toByte() &&
                        bytes[3] == 'G'.code.toByte()
                    assertTrue("Expected valid WebP or Robolectric PNG fallback", isWebp || isPngFallback)
                    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    assertNotNull(decoded)
                    try {
                        assertEquals(12, decoded.width)
                        assertEquals(10, decoded.height)
                    } finally {
                        decoded.recycle()
                    }
                    assertNoImageMetadataMarkers(bytes)
                    bytes.fill(0)
                }
            }
            assertFalse(source.isRecycled)
            assertEquals(filesBefore, snapshotDir(context.filesDir))
            assertEquals(cacheBefore, snapshotDir(context.cacheDir))
        } finally {
            source.recycle()
        }
    }

    @Test
    fun rejectsInvalidBitmapsCropRegionsQualityAndOversizedOutputs() {
        val encoder = DefaultUploadImageEncoder()
        val valid = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        try {
            assertNull(encoder.encode(valid, PixelRegion(-1, 0, 5, 5), UploadImageFormat.PNG, 85))
            assertNull(encoder.encode(valid, PixelRegion(0, 0, 11, 5), UploadImageFormat.PNG, 85))
            assertNull(encoder.encode(valid, PixelRegion(5, 0, 5, 5), UploadImageFormat.PNG, 85))
            assertNull(encoder.encode(valid, null, UploadImageFormat.PNG, -1))
            assertNull(encoder.encode(valid, null, UploadImageFormat.PNG, 101))

            val tinyBudgetEncoder = DefaultUploadImageEncoder(maxEncodedBytes = 12)
            assertNull(tinyBudgetEncoder.encode(valid, null, UploadImageFormat.PNG, 100))

            val smallDimEncoder = DefaultUploadImageEncoder(maxDimensionPx = 8)
            assertNull(smallDimEncoder.encode(valid, null, UploadImageFormat.PNG, 100))
        } finally {
            valid.recycle()
        }

        assertNull(encoder.encode(valid, null, UploadImageFormat.PNG, 85))
    }

    @Test
    fun scrubbableRamOutputStreamZeroesSupersededArraysAndScrubsOnTakeAndClose() {
        val stream = ScrubbableRamOutputStream(initialCapacity = 256, maxBytes = 1024)
        val payload = ByteArray(300) { (it + 1).toByte() }
        stream.write(payload, 0, payload.size)
        val exact = stream.takeBytesAndScrub()
        assertArrayEquals(payload, exact)
        assertThrows(IllegalStateException::class.java) { stream.write(1) }

        val closedStream = ScrubbableRamOutputStream(initialCapacity = 256, maxBytes = 512)
        closedStream.write(ByteArray(200) { 0x5A })
        closedStream.close()
        assertEquals(0, closedStream.byteCount())
        assertThrows(IllegalStateException::class.java) { closedStream.takeBytesAndScrub() }

        val limitStream = ScrubbableRamOutputStream(initialCapacity = 256, maxBytes = 300)
        assertThrows(IllegalStateException::class.java) {
            limitStream.write(ByteArray(301) { 0x2A })
        }
    }

    @Test
    fun uploadEncodedImageCloseZeroesOwnedArrayIdempotently() {
        val backing = byteArrayOf(10, 20, 30, 40)
        val image = UploadEncodedImage(backing, UploadImageFormat.WEBP_LOSSY, 2, 2)
        assertEquals(4, image.size)
        image.close()
        assertEquals(0, image.size)
        assertTrue(backing.all { it == 0.toByte() })
        image.close()
        assertNull(image.detachBytes())
    }

    private fun assertNoImageMetadataMarkers(bytes: ByteArray) {
        val latin = String(bytes, Charsets.ISO_8859_1)
        for (marker in listOf("Exif", "EXIF", "eXIf", "XMP", "tEXt", "iTXt", "zTXt")) {
            assertFalse("Unexpected metadata marker $marker", latin.contains(marker))
        }
    }

    private fun snapshotDir(dir: File): List<String> =
        dir.walkTopDown().filter { it.isFile }.map { it.relativeTo(dir).path }.sorted().toList()
}
