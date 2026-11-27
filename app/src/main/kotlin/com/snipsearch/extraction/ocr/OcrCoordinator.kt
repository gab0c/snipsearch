package com.snipsearch.extraction.ocr

import android.content.Context
import android.graphics.Bitmap
import com.snipsearch.extraction.OcrLine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.IntBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal data class OcrReadyInfo(val initMs: Long, val reused: Boolean)

internal interface OcrEngine : Closeable {
    fun recognize(
        argb: IntBuffer,
        width: Int,
        height: Int,
        stride: Int,
        isCancelled: () -> Boolean,
        onDetected: (regionCount: Int, detectMs: Long) -> Unit,
    ): List<OcrLine>
}

/** Owns in-process ONNX sessions and serialized access to detector scratch/model state. */
internal class OcrCoordinator(
    context: Context,
    private val idleReleaseDelayMs: Long = DEFAULT_IDLE_RELEASE_DELAY_MS,
    private val engineFactory: (Context) -> OcrEngine = ::openEngine,
) {
    private val context = context.applicationContext
    private val workerDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "snip-ocr-worker").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private val lifecycleScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val stateLock = Any()
    private var engine: OcrEngine? = null
    private var closed = false
    private var idleGeneration = 0L
    private var idleJob: Job? = null

    suspend fun warmUp(cancelIdleRelease: Boolean = true): OcrReadyInfo {
        if (cancelIdleRelease) retainModels()
        return withContext(workerDispatcher) {
            check(!isClosed()) { "OCR coordinator is destroyed" }
            val reused = engine != null
            val start = System.nanoTime()
            if (!reused) {
                val created = engineFactory(context)
                if (isClosed()) {
                    runCatching { created.close() }
                    throw CancellationException("OCR coordinator destroyed during initialization")
                }
                engine = created
            }
            val elapsed = (System.nanoTime() - start) / 1_000_000L
            OcrReadyInfo(initMs = if (reused) 0L else elapsed, reused = reused)
        }
    }

    suspend fun recognize(
        pixels: CropPixels,
        onReady: (OcrReadyInfo) -> Unit = {},
        onDetect: (Int, Long) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false },
    ): List<OcrLine> {
        return withContext(workerDispatcher) {
            check(!isClosed()) { "OCR coordinator is destroyed" }
            val reused = engine != null
            val start = System.nanoTime()
            val activeEngine = engine ?: engineFactory(context).also {
                if (isClosed()) {
                    runCatching { it.close() }
                    throw CancellationException("OCR coordinator destroyed during initialization")
                }
                engine = it
            }
            val initMs = if (reused) 0L else (System.nanoTime() - start) / 1_000_000L
            onReady(OcrReadyInfo(initMs, reused))
            try {
                activeEngine.recognize(pixels.buffer, pixels.width, pixels.height, pixels.width, isCancelled, onDetect)
            } catch (failure: Throwable) {
                if (engine === activeEngine) closeEngineLocked()
                throw failure
            }
        }
    }

    fun scheduleIdleRelease() {
        val generation = synchronized(stateLock) {
            if (closed) return
            idleJob?.cancel()
            ++idleGeneration
        }
        val job = lifecycleScope.launch {
            delay(idleReleaseDelayMs)
            withContext(workerDispatcher) {
                if (!isClosed() && synchronized(stateLock) { idleGeneration == generation }) closeEngineLocked()
            }
        }
        synchronized(stateLock) { if (!closed && idleGeneration == generation) idleJob = job else job.cancel() }
    }

    /** Destruction is queued on the worker so a VoiceInteractionSession callback never waits on native close. */
    fun destroy() {
        synchronized(stateLock) {
            if (closed) return
            closed = true
            idleGeneration++
            idleJob?.cancel()
            idleJob = null
        }
        lifecycleScope.launch {
            withContext(workerDispatcher) { closeEngineLocked() }
            lifecycleScope.cancel()
            workerDispatcher.close()
        }
    }

    private fun closeEngineLocked() {
        val current = engine ?: return
        engine = null
        runCatching { current.close() }
    }

    private fun isClosed(): Boolean = synchronized(stateLock) { closed }

    // Called synchronously when a session requests work, before it can be hidden.
    fun retainModels() {
        synchronized(stateLock) {
            check(!closed) { "OCR coordinator is destroyed" }
            idleJob?.cancel()
            idleJob = null
            idleGeneration++
        }
    }

    internal class CropPixels private constructor(
        val width: Int,
        val height: Int,
        private val storage: ByteBuffer,
    ) : Closeable {
        val buffer: IntBuffer = storage.order(ByteOrder.nativeOrder()).asIntBuffer()
        private val disposed = AtomicBoolean(false)

        override fun close() {
            if (!disposed.compareAndSet(false, true)) return
            buffer.clear()
            while (buffer.hasRemaining()) buffer.put(0)
            buffer.clear()
        }

        companion object {
            private const val STRIP_ROWS = 128
            fun copy(
                bitmap: Bitmap,
                isCancelled: () -> Boolean = { false },
                onAllocated: (CropPixels) -> Unit = {},
                afterStrip: (nextY: Int) -> Unit = {},
            ): CropPixels? {
                if (bitmap.isRecycled || isCancelled()) return null
                if (bitmap.config == Bitmap.Config.HARDWARE) {
                    return withMutableArgbCopy(bitmap) { software -> copy(software, isCancelled, onAllocated, afterStrip) }
                }
                val width = bitmap.width
                val height = bitmap.height
                require(width in 1..8192 && height in 1..8192 && width.toLong() * height <= 16_777_216L)
                val bytes = ByteBuffer.allocateDirect(width * height * Int.SIZE_BYTES).order(ByteOrder.nativeOrder())
                val pixels = CropPixels(width, height, bytes)
                val stripHeight = minOf(STRIP_ROWS, height)
                var strip: IntArray? = null
                try {
                    onAllocated(pixels)
                    strip = IntArray(width * stripHeight)
                    var y = 0
                    while (y < height) {
                        if (bitmap.isRecycled || isCancelled()) {
                            pixels.close()
                            return null
                        }
                        val rows = minOf(stripHeight, height - y)
                        bitmap.getPixels(strip, 0, width, 0, y, width, rows)
                        pixels.buffer.put(strip, 0, width * rows)
                        y += rows
                        afterStrip(y)
                    }
                    if (isCancelled()) { pixels.close(); return null }
                    pixels.buffer.flip()
                    return pixels
                } catch (failure: Throwable) {
                    pixels.close()
                    throw failure
                } finally {
                    strip?.fill(0)
                }
            }
        }
    }

    companion object {
        const val DEFAULT_IDLE_RELEASE_DELAY_MS = 60_000L
        private fun openEngine(context: Context): OcrEngine =
            context.assets.openFd("ocr/PP-OCRv6_tiny_det_infer.onnx").use { det ->
                context.assets.openFd("ocr/PP-OCRv6_tiny_rec_infer.onnx").use { rec ->
                    context.assets.openFd("ocr/ppocrv6_tiny_keys.txt").use { keys ->
                        PaddleOcrEngine.open(det, rec, keys)
                    }
                }
            }
    }
}

internal fun <T> withMutableArgbCopy(bitmap: Bitmap, action: (Bitmap) -> T): T {
    val software = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        ?: throw IOException("Could not copy screenshot pixels")
    return try {
        action(software)
    } finally {
        if (!software.isRecycled) {
            if (software.isMutable) runCatching { software.eraseColor(0) }
            software.recycle()
        }
    }
}

