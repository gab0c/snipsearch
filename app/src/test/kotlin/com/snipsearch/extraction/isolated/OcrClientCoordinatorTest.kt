package com.snipsearch.extraction.isolated

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.AssetFileDescriptor
import android.graphics.Bitmap
import android.graphics.Color
import android.os.DeadObjectException
import android.os.SharedMemory
import com.snipsearch.extraction.OcrTiming
import com.snipsearch.extraction.PixelRegion
import com.snipsearch.ipc.IOcrIsolatedService
import com.snipsearch.ipc.IOcrProgressCallback
import com.snipsearch.ipc.OcrTextBlockParcelable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.ConscryptMode
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class OcrClientCoordinatorTest {
    private val application = RuntimeEnvironment.getApplication()

    @Test
    fun serviceIsPrivateAndRunsWithAnIsolatedUid() {
        val info = application.packageManager.getServiceInfo(
            ComponentName(application, OcrIsolatedService::class.java),
            PackageManager.ComponentInfoFlags.of(0),
        )
        assertFalse(info.exported)
        assertTrue(info.flags and ServiceInfo.FLAG_ISOLATED_PROCESS != 0)
    }

    @Test
    fun warmServiceAndInitializedModelsAreReusedAcrossRequestsUntilReleased() {
        val before = files()
        val context = FakeOcrServiceContext(application)
        val coordinator = OcrClientCoordinator(context)
        val crop = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        try {
            val warmInfo = runBlocking { coordinator.warmUp() }
            assertFalse(warmInfo.reused)
            assertEquals(1, context.bindings)
            assertEquals(1, context.initializations)
            assertEquals(0, context.unbindings)

            var firstReused = false
            val firstPixels = OcrClientCoordinator.CropPixels.copy(crop)!!
            val firstLines = firstPixels.use { pixels ->
                runBlocking {
                    coordinator.recognize(pixels, onReady = { firstReused = it.reused })
                }
            }
            assertTrue(firstReused)
            assertEquals("sample", firstLines.single().text)

            val secondLines = runBlocking { coordinator.recognize(crop) }
            assertEquals("sample", secondLines.single().text)
            assertEquals(1, context.bindings)
            assertEquals(1, context.initializations)
            assertEquals(0, context.unbindings)

            coordinator.release()
            assertEquals(1, context.unbindings)

            // Subsequent recognition after release binds a fresh connection.
            val thirdLines = runBlocking { coordinator.recognize(crop) }
            assertEquals("sample", thirdLines.single().text)
            assertEquals(2, context.bindings)
            assertEquals(2, context.initializations)
            coordinator.destroy()
            assertEquals(2, context.unbindings)
            assertEquals(before, files())
        } finally {
            crop.recycle()
        }
    }

    @Test
    fun cropPixelsBulkZeroesMultiChunkSharedMemoryOnClose() {
        val large = Bitmap.createBitmap(128, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        try {
            val pixels = OcrClientCoordinator.CropPixels.copy(large)!!
            val mapping = pixels.memory.mapReadOnly()
            try {
                val ints = mapping.order(ByteOrder.nativeOrder()).asIntBuffer()
                assertEquals(Color.RED, ints.get(0))
                assertEquals(Color.RED, ints.get(128 * 64 - 1))
                pixels.close()
                for (i in 0 until ints.capacity()) {
                    assertEquals(0, ints.get(i))
                }
            } finally {
                SharedMemory.unmap(mapping)
            }
        } finally {
            large.recycle()
        }
    }

    @Test
    fun binderDeathReleasesTheBindingAndNextRequestUsesANewServiceWithoutAppFiles() {
        val before = files()
        val context = FakeOcrServiceContext(application, dieOnBindingNumber = 1)
        val coordinator = OcrClientCoordinator(context)
        val crop = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        try {
            assertThrows(DeadObjectException::class.java) { runBlocking { coordinator.recognize(crop) } }
            assertEquals(1, context.bindings)
            assertEquals(1, context.unbindings)

            val line = runBlocking { coordinator.recognize(crop) }.single()
            assertEquals("sample", line.text)
            assertEquals(PixelRegion(0, 1, 8, 6), line.words.single().bounds)
            assertEquals(2, context.bindings)
            assertEquals(2, context.initializations)
            coordinator.destroy()
            assertEquals(2, context.unbindings)
            assertEquals(before, files())
        } finally {
            crop.recycle()
        }
    }

    @Test
    fun detectionCallbackEmitsAndCancellationSignalsService() {
        val startedLatch = CountDownLatch(1)
        val releaseRecognize = CountDownLatch(1)
        val context = FakeOcrServiceContext(
            application,
            emitDetectCallback = true,
            onRecognizeStart = { startedLatch.countDown() },
            waitBeforeFinish = releaseRecognize,
        )
        val coordinator = OcrClientCoordinator(context)
        val crop = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        try {
            val detectedCount = AtomicInteger(0)
            val pixels = OcrClientCoordinator.CropPixels.copy(crop)!!
            pixels.use { p ->
                runBlocking {
                    val job = async(Dispatchers.Default) {
                        coordinator.recognize(
                            pixels = p,
                            onDetect = { count, _ -> detectedCount.set(count) },
                        )
                    }
                    assertTrue(startedLatch.await(3, TimeUnit.SECONDS))
                    releaseRecognize.countDown()
                    val result = job.await()
                    assertEquals(1, detectedCount.get())
                    assertEquals("sample", result.single().text)
                }
            }

            // Now test cancelling an in-flight recognition notifies cancelRecognition().
            val secondStarted = CountDownLatch(1)
            val blockForever = CountDownLatch(1)
            val blockingContext = FakeOcrServiceContext(
                application,
                onRecognizeStart = { secondStarted.countDown() },
                waitBeforeFinish = blockForever,
            )
            val cancelCoordinator = OcrClientCoordinator(blockingContext)
            val secondPixels = OcrClientCoordinator.CropPixels.copy(crop)!!
            try {
                runBlocking {
                    val job = launch(Dispatchers.Default) {
                        cancelCoordinator.recognize(secondPixels)
                    }
                    assertTrue(secondStarted.await(3, TimeUnit.SECONDS))
                    job.cancel(CancellationException("crop changed"))
                    job.join()
                    assertTrue(blockingContext.lastCancelledRequestId.get() > 0L)
                }
            } finally {
                blockForever.countDown()
                secondPixels.close()
                cancelCoordinator.destroy()
            }
            coordinator.destroy()
        } finally {
            releaseRecognize.countDown()
            crop.recycle()
        }
    }

    @Test
    fun sessionOcrRunnerCancelsInvalidatesAndEmitsContentFreeTimingLogs() {
        val startedLatch = CountDownLatch(1)
        val releaseRecognize = CountDownLatch(1)
        val context = FakeOcrServiceContext(
            application,
            emitDetectCallback = true,
            onRecognizeStart = { startedLatch.countDown() },
            waitBeforeFinish = releaseRecognize,
        )
        val coordinator = OcrClientCoordinator(context)
        val runner = SessionOcrRunner(coordinator)
        val logs = mutableListOf<String>()
        var clockMs = 100L
        val timing = OcrTiming.start(
            invocationType = 1,
            clock = { clockMs },
            logger = { logs += it },
        )
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        try {
            val scanId = runner.nextScanId()
            clockMs = 120L
            timing.logCaptureArrived(bitmap.width, bitmap.height)
            runner.startScan(scanId, bitmap, timing)
            assertTrue(startedLatch.await(3, TimeUnit.SECONDS))

            // Cancelling invalidates state immediately before caller recycles bitmap.
            runner.cancelActiveScan()
            bitmap.recycle()
            releaseRecognize.countDown()

            assertEquals(OcrScanSnapshot.IDLE, runner.state.value)
            assertTrue(context.lastCancelledRequestId.get() > 0L)

            // Verify timing logs contain zero screen text or sample strings.
            assertTrue(logs.any { it.contains("event=invoke") })
            assertTrue(logs.any { it.contains("event=capture_arrived") })
            assertFalse(logs.any { it.contains("sample") })
        } finally {
            releaseRecognize.countDown()
            runner.onSessionDestroy()
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    @Test
    fun sessionOcrRunnerCompletesFullScanAndReusesWarmService() {
        val context = FakeOcrServiceContext(application, emitDetectCallback = true)
        val coordinator = OcrClientCoordinator(context)
        val runner = SessionOcrRunner(coordinator)
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val logs = mutableListOf<String>()
        val timing = OcrTiming.start(1, logger = { logs += it })
        try {
            runner.warmUp(timing)
            val scanId = runner.nextScanId()
            runner.startScan(scanId, bitmap, timing)
            runBlocking {
                withTimeout(4_000) {
                    while (runner.state.value.status != OcrScanSnapshot.Status.COMPLETE) {
                        delay(10)
                    }
                }
            }
            val snapshot = runner.state.value
            assertEquals(scanId, snapshot.scanId)
            assertEquals("sample", snapshot.lines.single().text)
            assertTrue(logs.any { it.contains("event=ocr_detect_done") })
            assertTrue(logs.any { it.contains("event=ocr_complete") })
            assertFalse(logs.any { it.contains("sample") })
        } finally {
            runner.onSessionDestroy()
            bitmap.recycle()
        }
    }

    private fun files() = application.dataDir.walkTopDown()
        .filter { it.isFile }.map { it.relativeTo(application.dataDir).path to it.length() }.toSet()

    private class FakeOcrServiceContext(
        base: Context,
        private val dieOnBindingNumber: Int = -1,
        private val emitDetectCallback: Boolean = false,
        private val onRecognizeStart: (() -> Unit)? = null,
        private val waitBeforeFinish: CountDownLatch? = null,
    ) : ContextWrapper(base) {
        var bindings = 0
        var initializations = 0
        var unbindings = 0
        val lastCancelledRequestId = AtomicLong(0L)

        override fun bindService(intent: Intent, flags: Int, executor: Executor, conn: ServiceConnection): Boolean {
            val bindingNumber = ++bindings
            val shouldDie = (bindingNumber == dieOnBindingNumber)
            val service = object : IOcrIsolatedService.Stub() {
                override fun initializeModels(
                    detector: AssetFileDescriptor, recognizer: AssetFileDescriptor, dictionary: AssetFileDescriptor,
                ) {
                    initializations++
                    assertTrue(detector.length > 300_000)
                    assertTrue(recognizer.length > 500_000)
                    assertTrue(dictionary.length > 1_000)
                }

                override fun recognizeCrop(
                    requestId: Long,
                    argbPixels: SharedMemory,
                    width: Int,
                    height: Int,
                    stride: Int,
                    callback: IOcrProgressCallback?,
                ): List<OcrTextBlockParcelable> {
                    val mapping = argbPixels.mapReadOnly()
                    try {
                        assertEquals(Color.RED, mapping.order(ByteOrder.nativeOrder()).getInt(0))
                    } finally {
                        SharedMemory.unmap(mapping)
                    }
                    onRecognizeStart?.invoke()
                    if (shouldDie) throw DeadObjectException()
                    if (emitDetectCallback) {
                        callback?.onDetectionComplete(1, 5L)
                    }
                    waitBeforeFinish?.await(3, TimeUnit.SECONDS)
                    if (requestId <= lastCancelledRequestId.get()) return emptyList()
                    return listOf(block("sample"))
                }

                override fun cancelRecognition(requestId: Long) {
                    lastCancelledRequestId.accumulateAndGet(requestId, ::maxOf)
                }
            }
            executor.execute { conn.onServiceConnected(checkNotNull(intent.component), service) }
            return true
        }

        override fun unbindService(conn: ServiceConnection) {
            unbindings++
        }

        private fun block(value: String) = OcrTextBlockParcelable().apply {
            text = value
            wordStarts = intArrayOf(0)
            wordEnds = intArrayOf(value.length)
            wordBounds = intArrayOf(0, 1, 8, 6)
        }
    }
}
