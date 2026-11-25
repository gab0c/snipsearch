package com.snipsearch.extraction.isolated

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.os.IBinder
import android.os.RemoteException
import android.os.SharedMemory
import android.system.OsConstants
import com.snipsearch.extraction.OcrLine
import com.snipsearch.extraction.OcrWord
import com.snipsearch.extraction.PixelRegion
import com.snipsearch.ipc.IOcrIsolatedService
import com.snipsearch.ipc.OcrTextBlockParcelable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable
import java.io.IOException
import java.nio.ByteOrder
import java.nio.IntBuffer
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class OcrClientCoordinator(
    private val context: Context,
    private val idleUnbindDelayMs: Long = DEFAULT_IDLE_UNBIND_DELAY_MS,
) {
    private val connectionMutex = Mutex()
    private val activeLock = Any()
    private val lifecycleScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val nextRequestId = AtomicLong(0L)
    private val releaseGeneration = AtomicLong(0L)
    private val activeRequestId = AtomicLong(0L)

    @Volatile
    private var activeConnection: OcrConnection? = null

    @Volatile
    private var activeService: IOcrIsolatedService? = null

    private var idleJob: Job? = null

    suspend fun warmUp() {
        ensureReady()
    }

    // Copy synchronously while the overlay still owns the crop. Only the protected mapping
    // crosses a suspension point, so hiding the overlay can recycle its bitmap immediately.
    suspend fun recognize(crop: Bitmap): List<OcrLine> {
        val pixels = CropPixels.copy(crop) ?: throw CancellationException("OCR pixel copy cancelled")
        return pixels.use { recognize(it) }
    }

    suspend fun recognize(pixels: CropPixels): List<OcrLine> {
        val connection = ensureReady()
        val service = connection.service ?: throw IOException("OCR service disconnected")
        val requestId = nextRequestId.incrementAndGet()

        activeRequestId.set(requestId)
        activeService = service

        return try {
            coroutineContext.ensureActive()
            suspendCancellableCoroutine { cont ->
                val callJob = lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val parcelables = service.recognizeCrop(
                            requestId,
                            pixels.memory,
                            pixels.width,
                            pixels.height,
                            pixels.width,
                        )
                        val lines = parcelables.mapNotNull { it.toOcrLineOrNull() }
                        if (cont.isActive) {
                            cont.resume(lines)
                        }
                    } catch (failure: Throwable) {
                        if (failure is RemoteException || failure is IllegalStateException) {
                            evictConnection(connection)
                        }
                        if (cont.isActive) {
                            cont.resumeWithException(failure)
                        }
                    }
                }
                cont.invokeOnCancellation {
                    runCatching { service.cancelRecognition(requestId) }
                    callJob.cancel()
                }
            }
        } finally {
            activeRequestId.compareAndSet(requestId, 0L)
            if (activeService === service) {
                activeService = null
            }
        }
    }

    fun scheduleIdleRelease() {
        synchronized(activeLock) {
            idleJob?.cancel()
            if (activeConnection == null) return
            idleJob = lifecycleScope.launch {
                delay(idleUnbindDelayMs)
                release()
            }
        }
    }

    fun release() {
        releaseGeneration.incrementAndGet()
        val requestId = activeRequestId.getAndSet(0L)
        val service = activeService
        activeService = null
        if (requestId > 0L && service != null) {
            runCatching { service.cancelRecognition(requestId) }
        }
        val conn = synchronized(activeLock) {
            idleJob?.cancel()
            idleJob = null
            val current = activeConnection
            activeConnection = null
            current
        }
        if (conn != null) {
            unbindOnce(conn)
        }
    }

    fun destroy() {
        release()
        lifecycleScope.cancel()
    }

    private suspend fun ensureReady(): OcrConnection = withContext(Dispatchers.IO) {
        connectionMutex.withLock {
            synchronized(activeLock) {
                idleJob?.cancel()
                idleJob = null
                val existing = activeConnection
                if (existing != null && existing.isHealthy()) {
                    return@withLock existing
                }
                if (existing != null) {
                    activeConnection = null
                    unbindOnce(existing)
                }
            }

            val startGen = releaseGeneration.get()
            val conn = OcrConnection { broken -> evictConnection(broken) }
            val intent = Intent(context, OcrIsolatedService::class.java)
            val bound = context.bindService(intent, Context.BIND_AUTO_CREATE, Executor { it.run() }, conn)
            if (!bound) {
                conn.unbound.set(true)
                throw IOException("Could not connect to OCR service")
            }

            try {
                val service = withTimeoutOrNull(BIND_TIMEOUT_MS) { conn.serviceDeferred.await() }
                    ?: throw IOException("Could not connect to OCR service")

                context.assets.openFd("ocr/ch_PP-OCRv4_det_infer.onnx").use { detector ->
                    context.assets.openFd("ocr/ch_PP-OCRv4_rec_infer.onnx").use { recognizer ->
                        context.assets.openFd("ocr/ppocr_keys_v1.txt").use { dictionary ->
                            service.initializeModels(detector, recognizer, dictionary)
                        }
                    }
                }

                if (conn.dead || !service.asBinder().isBinderAlive) {
                    throw IOException("OCR service disconnected")
                }
                if (releaseGeneration.get() != startGen) {
                    throw CancellationException("OCR coordinator released")
                }

                conn.initialized = true
                synchronized(activeLock) {
                    if (releaseGeneration.get() != startGen || conn.dead) {
                        throw CancellationException("OCR coordinator released")
                    }
                    activeConnection = conn
                }
                conn
            } catch (failure: Throwable) {
                unbindOnce(conn)
                throw failure
            }
        }
    }

    private fun evictConnection(connection: OcrConnection) {
        val toUnbind = synchronized(activeLock) {
            if (activeConnection === connection) {
                activeConnection = null
                idleJob?.cancel()
                idleJob = null
            }
            connection
        }
        unbindOnce(toUnbind)
    }

    private fun unbindOnce(connection: OcrConnection) {
        if (connection.unbound.compareAndSet(false, true)) {
            try {
                context.unbindService(connection)
            } catch (_: IllegalArgumentException) {
                // Already unbound or not registered.
            }
        }
    }

    private class OcrConnection(
        private val onBroken: (OcrConnection) -> Unit,
    ) : ServiceConnection {
        val serviceDeferred = CompletableDeferred<IOcrIsolatedService?>()
        val unbound = AtomicBoolean(false)

        @Volatile
        var service: IOcrIsolatedService? = null
            private set

        @Volatile
        var dead = false
            private set

        @Volatile
        var initialized = false

        fun isHealthy(): Boolean {
            val binder = service?.asBinder() ?: return false
            return !dead && !unbound.get() && initialized && binder.isBinderAlive
        }

        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val proxy = IOcrIsolatedService.Stub.asInterface(binder)
            service = proxy
            if (dead || unbound.get()) {
                serviceDeferred.complete(null)
            } else {
                serviceDeferred.complete(proxy)
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            dead = true
            service = null
            serviceDeferred.complete(null)
            onBroken(this)
        }

        override fun onBindingDied(name: ComponentName) = onServiceDisconnected(name)
        override fun onNullBinding(name: ComponentName) = onServiceDisconnected(name)
    }

    internal class CropPixels(
        val width: Int,
        val height: Int,
        val memory: SharedMemory,
        private val mapping: java.nio.ByteBuffer,
    ) : Closeable {
        private val pixels: IntBuffer = mapping.order(ByteOrder.nativeOrder()).asIntBuffer()

        override fun close() {
            // setProtect only affects new mappings. The original mapping lets the owner
            // erase the pixels even though the service can only read them.
            pixels.clear()
            while (pixels.hasRemaining()) pixels.put(0)
            SharedMemory.unmap(mapping)
            memory.close()
        }

        companion object {
            private const val STRIP_ROWS = 128

            fun copy(crop: Bitmap, isCancelled: () -> Boolean = { false }): CropPixels? {
                if (isCancelled() || crop.isRecycled) return null
                if (crop.config == Bitmap.Config.HARDWARE) {
                    val software = crop.copy(Bitmap.Config.ARGB_8888, true)
                        ?: throw IOException("Could not read screenshot pixels")
                    try {
                        return copy(software, isCancelled)
                    } finally {
                        software.eraseColor(0)
                        software.recycle()
                    }
                }
                val width = crop.width
                val height = crop.height
                val memory = SharedMemory.create("snipsearch-ocr", pixelBufferSize(width, height, width))
                val mapping = try {
                    memory.mapReadWrite()
                } catch (failure: Exception) {
                    memory.close()
                    throw failure
                }
                val cropPixels = CropPixels(width, height, memory, mapping)
                val stripHeight = minOf(STRIP_ROWS, height)
                val strip = IntArray(width * stripHeight)
                try {
                    var y = 0
                    while (y < height) {
                        if (isCancelled()) {
                            cropPixels.close()
                            return null
                        }
                        val rows = minOf(stripHeight, height - y)
                        crop.getPixels(strip, 0, width, 0, y, width, rows)
                        cropPixels.pixels.put(strip, 0, width * rows)
                        y += rows
                    }
                    if (isCancelled()) {
                        cropPixels.close()
                        return null
                    }
                    check(memory.setProtect(OsConstants.PROT_READ))
                    return cropPixels
                } catch (failure: Exception) {
                    cropPixels.close()
                    throw failure
                } finally {
                    strip.fill(0)
                }
            }
        }
    }

    companion object {
        const val DEFAULT_IDLE_UNBIND_DELAY_MS = 60_000L
        private const val BIND_TIMEOUT_MS = 8_000L
    }
}

private fun OcrTextBlockParcelable.toOcrLineOrNull(): OcrLine? {
    val words = wordStarts.indices.mapNotNull { index ->
        val offset = index * 4
        val bounds = PixelRegion(
            wordBounds[offset],
            wordBounds[offset + 1],
            wordBounds[offset + 2],
            wordBounds[offset + 3],
        )
        bounds.takeIf { it.width > 0 && it.height > 0 }?.let {
            OcrWord(wordStarts[index] until wordEnds[index], it)
        }
    }
    return words.takeIf { it.isNotEmpty() }?.let { OcrLine(text, it) }
}

internal fun pixelBufferSize(width: Int, height: Int, stride: Int): Int {
    require(width in 1..8192 && height in 1..8192 && stride in width..8192)
    val pixels = stride.toLong() * height
    require(pixels <= 16_777_216)
    return pixels.toInt() * Int.SIZE_BYTES
}
