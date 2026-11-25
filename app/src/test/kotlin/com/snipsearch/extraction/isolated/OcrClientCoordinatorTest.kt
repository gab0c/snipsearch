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
import com.snipsearch.extraction.PixelRegion
import com.snipsearch.ipc.IOcrIsolatedService
import com.snipsearch.ipc.OcrTextBlockParcelable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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
        val crop = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }
        try {
            runBlocking { coordinator.warmUp() }
            assertEquals(1, context.bindings)
            assertEquals(1, context.initializations)
            assertEquals(0, context.unbindings)

            val firstLines = runBlocking { coordinator.recognize(crop) }
            assertEquals("sample", firstLines.single().text)
            assertEquals(1, context.bindings)
            assertEquals(1, context.initializations)
            assertEquals(0, context.unbindings)

            coordinator.release()
            assertEquals(1, context.unbindings)

            // Subsequent recognition after release binds a fresh connection.
            val secondLines = runBlocking { coordinator.recognize(crop) }
            assertEquals("sample", secondLines.single().text)
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
    fun binderDeathReleasesTheBindingAndNextRequestUsesANewServiceWithoutAppFiles() {
        val before = files()
        val context = FakeOcrServiceContext(application, dieOnBindingNumber = 1)
        val coordinator = OcrClientCoordinator(context)
        val crop = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }
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
    fun cancellingRecognitionNotifiesIsolatedService() {
        val startedLatch = CountDownLatch(1)
        val blockRecognize = CountDownLatch(1)
        val context = FakeOcrServiceContext(
            application,
            onRecognizeStart = { startedLatch.countDown() },
            waitBeforeFinish = blockRecognize,
        )
        val coordinator = OcrClientCoordinator(context)
        val crop = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }
        val pixels = OcrClientCoordinator.CropPixels.copy(crop)!!
        try {
            runBlocking {
                val job = launch(Dispatchers.Default) {
                    coordinator.recognize(pixels)
                }
                assertTrue(startedLatch.await(3, TimeUnit.SECONDS))
                job.cancel(CancellationException("crop changed"))
                job.join()
                assertTrue(context.lastCancelledRequestId.get() > 0L)
            }
        } finally {
            blockRecognize.countDown()
            pixels.close()
            coordinator.destroy()
            crop.recycle()
        }
    }

    @Test
    fun sessionOcrRunnerCompletesAndCancelsSynchronouslyBeforeBitmapRecycle() {
        val context = FakeOcrServiceContext(application)
        val runner = SessionOcrRunner(OcrClientCoordinator(context))
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }
        try {
            runner.warmUp()
            val scanId = runner.nextScanId()
            runner.startScan(scanId, bitmap)
            runBlocking {
                withTimeout(4_000) {
                    while (runner.state.value.status != OcrScanSnapshot.Status.COMPLETE) {
                        delay(10)
                    }
                }
            }
            assertEquals(scanId, runner.state.value.scanId)
            assertEquals("sample", runner.state.value.lines.single().text)
        } finally {
            runner.onSessionDestroy()
            bitmap.recycle()
        }

        val startedLatch = CountDownLatch(1)
        val releaseRecognize = CountDownLatch(1)
        val blockingContext = FakeOcrServiceContext(
            application,
            onRecognizeStart = { startedLatch.countDown() },
            waitBeforeFinish = releaseRecognize,
        )
        val cancelRunner = SessionOcrRunner(OcrClientCoordinator(blockingContext))
        val cancelBitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }
        try {
            val scanId = cancelRunner.nextScanId()
            cancelRunner.startScan(scanId, cancelBitmap)
            assertTrue(startedLatch.await(3, TimeUnit.SECONDS))
            cancelRunner.cancelActiveScan()
            cancelBitmap.recycle()
            releaseRecognize.countDown()
            assertEquals(OcrScanSnapshot.IDLE, cancelRunner.state.value)
            assertTrue(blockingContext.lastCancelledRequestId.get() > 0L)
        } finally {
            releaseRecognize.countDown()
            cancelRunner.onSessionDestroy()
            if (!cancelBitmap.isRecycled) cancelBitmap.recycle()
        }
    }

    private fun files() = application.dataDir.walkTopDown()
        .filter { it.isFile }.map { it.relativeTo(application.dataDir).path to it.length() }.toSet()

    private class FakeOcrServiceContext(
        base: Context,
        private val dieOnBindingNumber: Int = -1,
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
                    assertTrue(detector.length > 1_000_000)
                    assertTrue(recognizer.length > 1_000_000)
                    assertTrue(dictionary.length > 20_000)
                }

                override fun recognizeCrop(
                    requestId: Long,
                    argbPixels: SharedMemory,
                    width: Int,
                    height: Int,
                    stride: Int,
                ): List<OcrTextBlockParcelable> {
                    val mapping = argbPixels.mapReadOnly()
                    try {
                        assertEquals(Color.MAGENTA, mapping.order(ByteOrder.nativeOrder()).getInt(0))
                    } finally {
                        SharedMemory.unmap(mapping)
                    }
                    onRecognizeStart?.invoke()
                    if (shouldDie) throw DeadObjectException()
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
