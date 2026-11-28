package com.snipsearch.search

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import com.snipsearch.extraction.PixelRegion
import com.snipsearch.security.MemoryScrubber
import java.io.OutputStream

/**
 * RAM-only byte buffer [OutputStream] that zeroes superseded backing arrays on growth
 * and scrubs its remaining backing array on [takeBytesAndScrub] or [close].
 */
internal class ScrubbableRamOutputStream(
    initialCapacity: Int = DEFAULT_INITIAL_CAPACITY,
    private val maxBytes: Int = DEFAULT_MAX_ENCODED_BYTES,
) : OutputStream() {
    private var buffer = ByteArray(initialCapacity.coerceIn(MIN_INITIAL_CAPACITY, maxBytes))
    private var count = 0
    private var closed = false

    override fun write(b: Int) {
        checkOpen()
        ensureCapacity(1)
        buffer[count++] = b.toByte()
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        if (off < 0 || len < 0 || off > b.size - len) {
            throw IndexOutOfBoundsException("Invalid slice off=$off len=$len size=${b.size}")
        }
        checkOpen()
        if (len == 0) return
        ensureCapacity(len)
        System.arraycopy(b, off, buffer, count, len)
        count += len
    }

    fun byteCount(): Int = count

    /**
     * Transfers an exact-length encoded byte array to the caller and immediately zeroes
     * any internal buffer retained by this stream.
     */
    fun takeBytesAndScrub(): ByteArray {
        checkOpen()
        closed = true
        val exact = if (count == buffer.size) {
            val detached = buffer
            buffer = EMPTY_BYTES
            detached
        } else {
            val copy = buffer.copyOf(count)
            MemoryScrubber.zero(buffer)
            buffer = EMPTY_BYTES
            copy
        }
        count = 0
        return exact
    }

    override fun close() {
        if (closed) return
        closed = true
        MemoryScrubber.zero(buffer)
        buffer = EMPTY_BYTES
        count = 0
    }

    private fun checkOpen() {
        if (closed) throw IllegalStateException("ScrubbableRamOutputStream is closed")
    }

    private fun ensureCapacity(additional: Int) {
        val requiredLong = count.toLong() + additional.toLong()
        if (requiredLong > maxBytes.toLong()) {
            close()
            throw IllegalStateException("Encoded upload image exceeds max limit of $maxBytes bytes")
        }
        val required = requiredLong.toInt()
        if (required <= buffer.size) return

        val doubled = (buffer.size.toLong() * 2L).coerceAtMost(maxBytes.toLong()).toInt()
        val nextCapacity = maxOf(required, doubled)
        val previous = buffer
        val replacement = ByteArray(nextCapacity)
        System.arraycopy(previous, 0, replacement, 0, count)
        MemoryScrubber.zero(previous)
        buffer = replacement
    }

    companion object {
        private const val MIN_INITIAL_CAPACITY = 256
        const val DEFAULT_INITIAL_CAPACITY = 16 * 1024
        const val DEFAULT_MAX_ENCODED_BYTES = 16 * 1024 * 1024
        private val EMPTY_BYTES = ByteArray(0)
    }
}

/**
 * Owns one encoded in-RAM upload payload until ownership transfers to [UploadBlobStore]
 * or [close] zeroes the array.
 */
class UploadEncodedImage(
    bytes: ByteArray,
    val format: UploadImageFormat,
    val width: Int,
    val height: Int,
) : AutoCloseable {
    private val lock = Any()
    private var ownedBytes: ByteArray? = bytes

    val size: Int
        get() = synchronized(lock) { ownedBytes?.size ?: 0 }

    internal fun detachBytes(): ByteArray? = synchronized(lock) {
        val current = ownedBytes
        ownedBytes = null
        current
    }

    override fun close() {
        val toScrub = synchronized(lock) {
            val current = ownedBytes
            ownedBytes = null
            current
        }
        MemoryScrubber.zero(toScrub)
    }
}

/** Re-encodes a caller bitmap or crop strictly in RAM without EXIF/device metadata. */
fun interface UploadImageEncoder {
    fun encode(
        bitmap: Bitmap,
        cropRegion: PixelRegion?,
        format: UploadImageFormat,
        quality: Int,
    ): UploadEncodedImage?
}

/**
 * Default in-RAM encoder that crops and re-compresses into fresh PNG or WebP bytes.
 *
 * Re-encoding through a clean software bitmap and [Bitmap.compress] strips any EXIF, XMP,
 * ICC/device artifacts while keeping every intermediate pixel and byte buffer exclusively in RAM.
 */
class DefaultUploadImageEncoder(
    private val maxEncodedBytes: Int = ScrubbableRamOutputStream.DEFAULT_MAX_ENCODED_BYTES,
    private val maxDimensionPx: Int = DEFAULT_MAX_DIMENSION_PX,
) : UploadImageEncoder {
    override fun encode(
        bitmap: Bitmap,
        cropRegion: PixelRegion?,
        format: UploadImageFormat,
        quality: Int,
    ): UploadEncodedImage? {
        if (quality !in 0..100 || bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
            return null
        }

        val targetWidth: Int
        val targetHeight: Int
        val srcRect: Rect
        if (cropRegion != null) {
            if (cropRegion.width <= 0 ||
                cropRegion.height <= 0 ||
                cropRegion.left < 0 ||
                cropRegion.top < 0 ||
                cropRegion.right > bitmap.width ||
                cropRegion.bottom > bitmap.height
            ) {
                return null
            }
            targetWidth = cropRegion.width
            targetHeight = cropRegion.height
            srcRect = Rect(cropRegion.left, cropRegion.top, cropRegion.right, cropRegion.bottom)
        } else {
            targetWidth = bitmap.width
            targetHeight = bitmap.height
            srcRect = Rect(0, 0, targetWidth, targetHeight)
        }

        if (targetWidth > maxDimensionPx || targetHeight > maxDimensionPx) return null

        var softwareSource: Bitmap? = null
        var freshSoftwareTarget: Bitmap? = null
        return try {
            val drawSource = if (bitmap.config == Bitmap.Config.HARDWARE) {
                bitmap.copy(Bitmap.Config.ARGB_8888, true)?.also { softwareSource = it } ?: return null
            } else {
                bitmap
            }
            val cleanBitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888).also {
                freshSoftwareTarget = it
            }
            Canvas(cleanBitmap).drawBitmap(
                drawSource,
                srcRect,
                Rect(0, 0, targetWidth, targetHeight),
                Paint(Paint.FILTER_BITMAP_FLAG),
            )
            scrubBitmap(softwareSource)
            softwareSource = null
            val (compressFormat, effectiveQuality) = when (format) {
                UploadImageFormat.PNG -> Bitmap.CompressFormat.PNG to 100
                UploadImageFormat.WEBP_LOSSLESS -> Bitmap.CompressFormat.WEBP_LOSSLESS to 100
                UploadImageFormat.WEBP_LOSSY -> Bitmap.CompressFormat.WEBP_LOSSY to quality
            }
            val output = ScrubbableRamOutputStream(maxBytes = maxEncodedBytes)
            val encodedBytes = output.use { stream ->
                if (!cleanBitmap.compress(compressFormat, effectiveQuality, stream) ||
                    stream.byteCount() <= 0
                ) {
                    return null
                }
                stream.takeBytesAndScrub()
            }
            UploadEncodedImage(
                bytes = encodedBytes,
                format = format,
                width = targetWidth,
                height = targetHeight,
            )
        } catch (_: RuntimeException) {
            null
        } catch (_: OutOfMemoryError) {
            null
        } finally {
            scrubBitmap(freshSoftwareTarget)
            scrubBitmap(softwareSource)
        }
    }

    private fun scrubBitmap(bitmap: Bitmap?) {
        if (bitmap == null || bitmap.isRecycled) return
        if (bitmap.isMutable) {
            try {
                bitmap.eraseColor(Color.TRANSPARENT)
            } catch (_: RuntimeException) {
                // Continue directly to recycling if the bitmap cannot be cleared.
            }
        }
        MemoryScrubber.recycle(bitmap)
    }

    companion object {
        const val DEFAULT_MAX_DIMENSION_PX = 8192
    }
}
