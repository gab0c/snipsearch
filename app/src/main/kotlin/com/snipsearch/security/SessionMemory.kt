package com.snipsearch.security

import android.graphics.Bitmap

/**
 * RAM-only holder for one session's capture buffers.
 *
 * [scrub] drops the screenshot reference before recycling it and zeroes every
 * registered image buffer. Callers must detach those buffers from any [android.view.View]
 * before scrubbing so a recycled bitmap is never drawn.
 */
class SessionMemory {
    var screenshot: Bitmap? = null
        private set

    private val imageBuffers = mutableListOf<ByteArray>()

    fun addImageBuffer(buffer: ByteArray) {
        imageBuffers.add(buffer)
    }

    fun adoptScreenshot(bitmap: Bitmap) {
        val previous = screenshot
        screenshot = bitmap
        if (previous != null && previous !== bitmap) {
            MemoryScrubber.recycle(previous)
        }
    }

    fun scrub() {
        val bitmap = screenshot
        screenshot = null
        try {
            MemoryScrubber.recycle(bitmap)
        } finally {
            for (buffer in imageBuffers) {
                MemoryScrubber.zero(buffer)
            }
            imageBuffers.clear()
        }
    }
}
