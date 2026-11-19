package com.snipsearch.security

import android.graphics.Bitmap

/**
 * Drops screen-capture buffers. Bitmaps are recycled and byte arrays are overwritten
 * with zeroes. Nothing here is written to disk.
 */
object MemoryScrubber {
    fun zero(buffer: ByteArray?) {
        buffer?.fill(0)
    }

    fun recycle(bitmap: Bitmap?) {
        if (bitmap != null && !bitmap.isRecycled) {
            bitmap.recycle()
        }
    }
}
