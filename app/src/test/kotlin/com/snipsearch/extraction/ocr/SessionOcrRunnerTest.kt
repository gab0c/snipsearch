package com.snipsearch.extraction.ocr

import android.graphics.Bitmap
import com.snipsearch.extraction.OcrLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode
import java.nio.IntBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class SessionOcrRunnerTest {
    private val context get() = org.robolectric.RuntimeEnvironment.getApplication()

    @Test
    fun newerGenerationWinsAndSourceCanBeRecycledAsSoonAsStartReturns() = runBlocking {
        val firstEntered = CountDownLatch(1)
        val secondCopied = CountDownLatch(1)
        val finishFirst = CountDownLatch(1)
        val engine = OrderedEngine(firstEntered, finishFirst)
        val coordinator = OcrCoordinator(context, engineFactory = { engine })
        val copies = AtomicInteger()
        val runner = SessionOcrRunner(coordinator, pixelCopier = { bitmap, cancelled ->
            OcrCoordinator.CropPixels.copy(bitmap, cancelled).also {
                if (copies.incrementAndGet() == 2) secondCopied.countDown()
            }
        })
        val firstBitmap = bitmap(0xff112233.toInt())
        val firstId = runner.nextScanId()
        runner.startScan(firstId, firstBitmap)
        firstBitmap.recycle()
        assertTrue(firstEntered.await(2, TimeUnit.SECONDS))

        val secondBitmap = bitmap(0xff445566.toInt())
        val secondId = runner.nextScanId()
        runner.startScan(secondId, secondBitmap)
        secondBitmap.recycle()
        assertTrue(secondCopied.await(2, TimeUnit.SECONDS))
        finishFirst.countDown()
        eventually { runner.state.value.status == OcrScanSnapshot.Status.COMPLETE }
        assertEquals(secondId, runner.state.value.scanId)
        assertEquals("line-2", runner.state.value.lines.single().text)
        runner.onSessionDestroy()
    }

    @Test
    fun hideResetsStateAndCancellationScrubsTheOwnedBufferAfterInferenceReturns() = runBlocking {
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        var input: IntBuffer? = null
        val engine = object : OcrEngine {
            override fun recognize(argb: IntBuffer, width: Int, height: Int, stride: Int,
                isCancelled: () -> Boolean, onDetected: (Int, Long) -> Unit): List<OcrLine> {
                input = argb.duplicate()
                entered.countDown()
                finish.await(3, TimeUnit.SECONDS)
                return emptyList()
            }
            override fun close() = Unit
        }
        val coordinator = OcrCoordinator(context, engineFactory = { engine })
        val runner = SessionOcrRunner(coordinator)
        val bitmap = bitmap(0xffaabbcc.toInt())
        runner.startScan(runner.nextScanId(), bitmap)
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        bitmap.recycle()
        val observedInput = requireNotNull(input)
        assertEquals(0xffaabbcc.toInt(), observedInput.get(0))
        runner.onSessionHide()
        assertEquals(OcrScanSnapshot.IDLE, runner.state.value)
        assertEquals(0xffaabbcc.toInt(), observedInput.get(0))
        finish.countDown()
        eventually { observedInput.get(0) == 0 }
        runner.onSessionDestroy()
    }

    @Test
    fun hideOrDestroyDuringPixelCopyCannotResurrectScanAndScrubsLateCopy() = runBlocking {
        suspend fun exercise(destroy: Boolean) {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            var copied: OcrCoordinator.CropPixels? = null
            val coordinator = OcrCoordinator(context, engineFactory = { error("must not infer") })
            val runner = SessionOcrRunner(coordinator, pixelCopier = { bitmap, cancelled ->
                val pixels = OcrCoordinator.CropPixels.copy(bitmap, cancelled)!!
                copied = pixels
                entered.countDown()
                release.await(2, TimeUnit.SECONDS)
                pixels
            })
            val bitmap = bitmap(0xff778899.toInt())
            val starting = async(Dispatchers.Default) { runner.startScan(runner.nextScanId(), bitmap) }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val hiding = async(Dispatchers.Default) {
                if (destroy) runner.onSessionDestroy() else runner.onSessionHide()
            }
            eventually { runner.state.value == OcrScanSnapshot.IDLE }
            release.countDown()
            hiding.await()
            starting.await()
            assertEquals(OcrScanSnapshot.IDLE, runner.state.value)
            assertEquals(0, copied!!.buffer.get(0))
            bitmap.recycle()
            if (!destroy) runner.onSessionDestroy()
        }
        exercise(destroy = false)
        exercise(destroy = true)
    }

    @Test
    fun inferenceFailurePublishesAssistFallbackStateAndNextScanRetries() = runBlocking {
        val attempts = AtomicInteger()
        val coordinator = OcrCoordinator(context, engineFactory = {
            if (attempts.incrementAndGet() == 1) object : OcrEngine {
                override fun recognize(argb: IntBuffer, width: Int, height: Int, stride: Int,
                    isCancelled: () -> Boolean, onDetected: (Int, Long) -> Unit): List<OcrLine> = error("inference failed")
                override fun close() = Unit
            } else object : OcrEngine {
                override fun recognize(argb: IntBuffer, width: Int, height: Int, stride: Int,
                    isCancelled: () -> Boolean, onDetected: (Int, Long) -> Unit): List<OcrLine> =
                    listOf(OcrLine("retry", emptyList()))
                override fun close() = Unit
            }
        })
        val runner = SessionOcrRunner(coordinator)
        val first = bitmap(0xff010101.toInt())
        runner.startScan(runner.nextScanId(), first)
        eventually { runner.state.value.status == OcrScanSnapshot.Status.FAILED }
        first.recycle()
        val second = bitmap(0xff020202.toInt())
        val retryId = runner.nextScanId()
        runner.startScan(retryId, second)
        eventually { runner.state.value.status == OcrScanSnapshot.Status.COMPLETE }
        second.recycle()
        assertEquals(retryId, runner.state.value.scanId)
        assertEquals("retry", runner.state.value.lines.single().text)
        assertEquals(2, attempts.get())
        runner.onSessionDestroy()
    }

    @Test
    fun unavailableNativeRuntimeFallsBackAndCanRetryWithoutCrashing() = runBlocking {
        val attempts = AtomicInteger()
        val engine = OrderedEngine(CountDownLatch(0), CountDownLatch(0))
        val coordinator = OcrCoordinator(context, engineFactory = {
            if (attempts.incrementAndGet() <= 2) throw UnsatisfiedLinkError("runtime unavailable")
            engine
        })
        val runner = SessionOcrRunner(coordinator)
        val source = bitmap(0xff010203.toInt())
        try {
            runner.warmUp()
            eventually { attempts.get() == 1 }
            runner.startScan(runner.nextScanId(), source)
            eventually { runner.state.value.status == OcrScanSnapshot.Status.FAILED }
            runner.startScan(runner.nextScanId(), source)
            eventually { runner.state.value.status == OcrScanSnapshot.Status.COMPLETE }
            assertEquals(3, attempts.get())
        } finally {
            runner.onSessionDestroy()
            source.recycle()
        }
    }

    @Test
    fun supersededQueuedScanScrubsBeforeEarlierNativeInferenceReturns() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val coordinator = OcrCoordinator(context, engineFactory = {
            object : OcrEngine {
                override fun recognize(argb: IntBuffer, width: Int, height: Int, stride: Int,
                    isCancelled: () -> Boolean, onDetected: (Int, Long) -> Unit): List<OcrLine> {
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                    return emptyList()
                }
                override fun close() = Unit
            }
        })
        val copies = ArrayList<OcrCoordinator.CropPixels>()
        val runner = SessionOcrRunner(coordinator, pixelCopier = { bitmap, cancelled ->
            OcrCoordinator.CropPixels.copy(bitmap, cancelled)!!.also(copies::add)
        })
        val source = bitmap(0xff010203.toInt())
        try {
            runner.startScan(runner.nextScanId(), source)
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            runner.startScan(runner.nextScanId(), source)
            delay(100) // Let this scan queue behind the native reader.
            runner.startScan(runner.nextScanId(), source)
            kotlinx.coroutines.withTimeout(1_000) {
                while (copies[1].buffer.get(0) != 0) delay(5)
            }
            assertEquals(0xff010203.toInt(), copies[0].buffer.get(0))
        } finally {
            release.countDown()
            runner.onSessionDestroy()
            source.recycle()
        }
    }

    private fun bitmap(color: Int) = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    private suspend fun eventually(condition: () -> Boolean) {
        repeat(150) {
            if (condition()) return
            delay(20)
        }
        assertTrue("condition did not become true", condition())
    }

    private class OrderedEngine(
        private val firstEntered: CountDownLatch,
        private val finishFirst: CountDownLatch,
    ) : OcrEngine {
        private val calls = AtomicInteger()
        override fun recognize(argb: IntBuffer, width: Int, height: Int, stride: Int,
            isCancelled: () -> Boolean, onDetected: (Int, Long) -> Unit): List<OcrLine> {
            val call = calls.incrementAndGet()
            if (call == 1) {
                firstEntered.countDown()
                finishFirst.await(3, TimeUnit.SECONDS)
            }
            return listOf(OcrLine("line-$call", emptyList()))
        }
        override fun close() = Unit
    }
}
