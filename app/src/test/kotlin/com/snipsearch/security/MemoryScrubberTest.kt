package com.snipsearch.security

import android.graphics.Bitmap
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class MemoryScrubberTest {
    @Test
    fun zeroOverwritesEveryByte() {
        val buffer = byteArrayOf(1, 0, -1, 42, Byte.MAX_VALUE, Byte.MIN_VALUE)
        MemoryScrubber.zero(buffer)
        assertTrue(buffer.all { it == 0.toByte() })
        MemoryScrubber.zero(null)
    }

    @Test
    fun recycleReleasesTheBitmapAndIsSafeTwice() {
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        MemoryScrubber.recycle(bitmap)
        assertTrue(bitmap.isRecycled)
        MemoryScrubber.recycle(bitmap)
        MemoryScrubber.recycle(null)
    }

    @Test
    fun sessionMemoryScrubDropsTheScreenshotBeforeTheCallerCanObserveIt() {
        val memory = SessionMemory()
        val bitmap = Bitmap.createBitmap(3, 3, Bitmap.Config.ARGB_8888)
        val buffer = byteArrayOf(11, 12, 13)
        memory.adoptScreenshot(bitmap)
        memory.addImageBuffer(buffer)
        memory.scrub()
        assertNull(memory.screenshot)
        assertTrue(bitmap.isRecycled)
        assertTrue(buffer.all { it == 0.toByte() })
        memory.scrub()
        assertNull(memory.screenshot)
    }

    @Test
    fun replacingAScreenshotRecyclesThePreviousBitmap() {
        val memory = SessionMemory()
        val first = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val second = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        memory.adoptScreenshot(first)
        memory.adoptScreenshot(second)
        assertTrue(first.isRecycled)
        memory.scrub()
        assertTrue(second.isRecycled)
        assertNull(memory.screenshot)
    }
}
