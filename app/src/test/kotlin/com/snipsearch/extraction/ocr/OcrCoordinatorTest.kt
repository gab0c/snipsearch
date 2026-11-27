package com.snipsearch.extraction.ocr

import android.content.Context
import android.graphics.Bitmap
import com.snipsearch.extraction.OcrLine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class OcrCoordinatorTest {
    private val context: Context get() = org.robolectric.RuntimeEnvironment.getApplication()

    @Test
    fun warmUpReusesSessionsAndRetriesInitializationAfterFailure() = runBlocking {
        val attempts = AtomicInteger()
        val coordinator = OcrCoordinator(context, engineFactory = {
            if (attempts.incrementAndGet() == 1) error("init failed")
            FakeEngine()
        })
        try {
            runCatching { coordinator.warmUp() }.onSuccess { error("first init should fail") }
            assertEquals(1, attempts.get())
            assertFalse(coordinator.warmUp().reused)
            assertTrue(coordinator.warmUp().reused)
            assertEquals(2, attempts.get())
        } finally { coordinator.destroy() }
    }

    @Test
    fun idleScheduledDuringColdInitializationEvictsAfterInitAndWarmUpCancelsIdleIntent() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val engines = mutableListOf<FakeEngine>()
        val coordinator = OcrCoordinator(context, idleReleaseDelayMs = 40, engineFactory = {
            entered.countDown()
            release.await(2, TimeUnit.SECONDS)
            FakeEngine().also(engines::add)
        })
        try {
            val warm = async(Dispatchers.Default) { coordinator.warmUp() }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            coordinator.scheduleIdleRelease()
            delay(80)
            release.countDown()
            warm.await()
            delay(80)
            assertEquals(1, engines.single().closed.get())
        } finally { release.countDown(); coordinator.destroy() }

        val reused = mutableListOf<FakeEngine>()
        val second = OcrCoordinator(context, idleReleaseDelayMs = 80, engineFactory = {
            FakeEngine().also(reused::add)
        })
        try {
            second.warmUp()
            second.scheduleIdleRelease()
            delay(20)
            assertTrue(second.warmUp().reused)
            delay(100)
            assertEquals(0, reused.single().closed.get())
        } finally { second.destroy() }
    }

    @Test
    fun destroyDuringInitializationClosesCreatedEngineAndRejectsWarmUp() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        lateinit var created: FakeEngine
        val coordinator = OcrCoordinator(context, engineFactory = {
            entered.countDown()
            release.await(2, TimeUnit.SECONDS)
            FakeEngine().also { created = it }
        })
        val warm = async(Dispatchers.Default) { runCatching { coordinator.warmUp() } }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        coordinator.destroy()
        release.countDown()
        assertTrue(warm.await().isFailure)
        delay(30)
        assertEquals(1, created.closed.get())
    }

    @Test
    fun concurrentRequestsAreSerializedAndInferenceFailureEvictsForRetry() = runBlocking {
        val engines = AtomicInteger()
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val coordinator = OcrCoordinator(context, engineFactory = {
            val engineId = engines.incrementAndGet()
            object : OcrEngine {
                private val calls = AtomicInteger()
                override fun recognize(argb: java.nio.IntBuffer, width: Int, height: Int, stride: Int,
                    isCancelled: () -> Boolean, onDetected: (Int, Long) -> Unit): List<OcrLine> {
                    val running = active.incrementAndGet()
                    maximum.updateAndGet { maxOf(it, running) }
                    try {
                        if (engineId == 1 && calls.incrementAndGet() == 1) error("inference failure")
                        Thread.sleep(40)
                        return emptyList()
                    } finally { active.decrementAndGet() }
                }
                override fun close() = Unit
            }
        })
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val first = OcrCoordinator.CropPixels.copy(bitmap)!!
        val second = OcrCoordinator.CropPixels.copy(bitmap)!!
        try {
            assertTrue(runCatching { coordinator.recognize(first) }.isFailure)
            val tasks = listOf(
                async(Dispatchers.Default) { coordinator.recognize(first) },
                async(Dispatchers.Default) { coordinator.recognize(second) },
            )
            tasks.forEach { it.await() }
            assertEquals(2, engines.get())
            assertEquals(1, maximum.get())
        } finally { first.close(); second.close(); bitmap.recycle(); coordinator.destroy() }
    }

    @Test
    fun idleEvictionClosesEngineAndNextWarmUpReinitializes() = runBlocking {
        val made = mutableListOf<FakeEngine>()
        val coordinator = OcrCoordinator(context, idleReleaseDelayMs = 10, engineFactory = {
            FakeEngine().also(made::add)
        })
        try {
            coordinator.warmUp()
            coordinator.scheduleIdleRelease()
            delay(100)
            assertEquals(1, made.single().closed.get())
            assertFalse(coordinator.warmUp().reused)
            assertEquals(2, made.size)
        } finally { coordinator.destroy() }
    }

    @Test
    fun activeRecognitionOwnsCopiedPixelsAndHideEvictionWaitsForInference() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val made = mutableListOf<FakeEngine>()
        val coordinator = OcrCoordinator(context, idleReleaseDelayMs = 10, engineFactory = {
            FakeEngine(entered, release).also(made::add)
        })
        val bitmap = Bitmap.createBitmap(4, 3, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff123456.toInt()) }
        val pixels = OcrCoordinator.CropPixels.copy(bitmap)!!
        bitmap.recycle()
        try {
            val task = async(Dispatchers.Default) {
                coordinator.recognize(pixels)
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            coordinator.scheduleIdleRelease()
            delay(50)
            assertEquals(0, made.single().closed.get())
            release.countDown()
            task.await()
            delay(50)
            assertEquals(1, made.single().closed.get())
        } finally {
            release.countDown()
            pixels.close()
            coordinator.destroy()
        }
    }

    @Test
    fun pixelCopyIsIndependentAndScrubsOnCloseIncludingCancellation() {
        val bitmap = Bitmap.createBitmap(3, 2, Bitmap.Config.ARGB_8888).apply { eraseColor(0xffabcdef.toInt()) }
        val pixels = OcrCoordinator.CropPixels.copy(bitmap)!!
        bitmap.recycle()
        assertEquals(0xffabcdef.toInt(), pixels.buffer.get(0))
        pixels.close()
        assertEquals(0, pixels.buffer.get(0))
        assertNull(OcrCoordinator.CropPixels.copy(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888), isCancelled = { true }))
    }

    @Test
    fun partialPixelCopyFailureZerosAllocatedBufferAndMutableCopyIsAlwaysRecycled() {
        val bitmap = Bitmap.createBitmap(2, 130, Bitmap.Config.ARGB_8888).apply { eraseColor(0xffabcdef.toInt()) }
        var allocated: OcrCoordinator.CropPixels? = null
        val partial = OcrCoordinator.CropPixels.copy(bitmap, onAllocated = { allocated = it }, afterStrip = { nextY ->
            if (nextY == 128) bitmap.recycle()
        })
        assertNull(partial)
        assertEquals(0, allocated!!.buffer.get(0))

        val immutable = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).copy(Bitmap.Config.ARGB_8888, false)!!
        var software: Bitmap? = null
        withMutableArgbCopy(immutable) { copy ->
            software = copy
            assertTrue(copy.isMutable)
            copy.eraseColor(0xff010203.toInt())
        }
        assertTrue(software!!.isRecycled)
        immutable.recycle()
    }

    @Test
    fun destroyDoesNotBlockCallerOnNativeInferenceAndRejectsStaleCompletion() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val engine = FakeEngine(entered, release)
        val coordinator = OcrCoordinator(context, engineFactory = { engine })
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        val pixels = OcrCoordinator.CropPixels.copy(bitmap)!!
        val task = async(Dispatchers.Default) { runCatching { coordinator.recognize(pixels) } }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        val start = System.nanoTime()
        coordinator.destroy()
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 100)
        release.countDown()
        task.await()
        delay(50)
        assertEquals(1, engine.closed.get())
        pixels.close()
        bitmap.recycle()
    }

    @Test
    fun queuedRecognitionDoesNotCancelLaterHideEviction() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val engine = FakeEngine(entered, release)
        val coordinator = OcrCoordinator(context, idleReleaseDelayMs = 10, engineFactory = { engine })
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        val first = OcrCoordinator.CropPixels.copy(bitmap)!!
        val second = OcrCoordinator.CropPixels.copy(bitmap)!!
        try {
            val running = async(Dispatchers.Default) { coordinator.recognize(first) }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val queued = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { coordinator.recognize(second) }
            coordinator.scheduleIdleRelease()
            delay(30)
            release.countDown()
            running.await()
            queued.await()
            repeat(100) {
                if (engine.closed.get() == 0) delay(10)
            }
            assertEquals(1, engine.closed.get())
        } finally {
            release.countDown()
            first.close()
            second.close()
            bitmap.recycle()
            coordinator.destroy()
        }
    }

    private class FakeEngine(
        private val entered: CountDownLatch? = null,
        private val release: CountDownLatch? = null,
    ) : OcrEngine {
        val closed = AtomicInteger()
        override fun recognize(
            argb: java.nio.IntBuffer,
            width: Int,
            height: Int,
            stride: Int,
            isCancelled: () -> Boolean,
            onDetected: (Int, Long) -> Unit,
        ): List<OcrLine> {
            entered?.countDown()
            release?.await(3, TimeUnit.SECONDS)
            onDetected(1, 2)
            return if (isCancelled()) emptyList() else listOf(OcrLine("fake", emptyList()))
        }
        override fun close() { closed.incrementAndGet() }
    }
}
