package com.snipsearch.extraction.isolated

import android.app.Service
import android.content.Intent
import android.content.res.AssetFileDescriptor
import android.os.RemoteException
import android.os.SharedMemory
import com.snipsearch.ipc.IOcrIsolatedService
import com.snipsearch.ipc.IOcrProgressCallback
import com.snipsearch.ipc.OcrTextBlockParcelable
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class OcrIsolatedService : Service() {
    private val engineLock = Any()
    private val cancelledUpTo = AtomicLong(0L)
    private var engine: PaddleOcrEngine? = null

    private val binder = object : IOcrIsolatedService.Stub() {
        override fun initializeModels(
            detector: AssetFileDescriptor,
            recognizer: AssetFileDescriptor,
            dictionary: AssetFileDescriptor,
        ) {
            detector.use { det ->
                recognizer.use { rec ->
                    dictionary.use { keys ->
                        runOcr {
                            if (engine == null) engine = PaddleOcrEngine.open(det, rec, keys)
                        }
                    }
                }
            }
        }

        override fun recognizeCrop(
            requestId: Long,
            argbPixels: SharedMemory,
            width: Int,
            height: Int,
            stride: Int,
            callback: IOcrProgressCallback?,
        ): List<OcrTextBlockParcelable> = argbPixels.use { shared ->
            if (requestId <= cancelledUpTo.get()) return emptyList()
            runOcr {
                if (requestId <= cancelledUpTo.get()) return@runOcr emptyList()
                require(shared.size == pixelBufferSize(width, height, stride))
                val ocr = checkNotNull(engine)
                val mapping = shared.mapReadOnly()
                val callbackBroken = AtomicBoolean(false)
                try {
                    val pixels = mapping.order(ByteOrder.nativeOrder()).asIntBuffer()
                    ocr.recognize(
                        argb = pixels,
                        width = width,
                        height = height,
                        stride = stride,
                        isCancelled = { requestId <= cancelledUpTo.get() || callbackBroken.get() },
                        onDetected = { count, detMs ->
                            if (callback != null && !callbackBroken.get() && requestId > cancelledUpTo.get()) {
                                try {
                                    callback.onDetectionComplete(count, detMs)
                                } catch (_: RemoteException) {
                                    callbackBroken.set(true)
                                }
                            }
                        },
                    )
                } finally {
                    SharedMemory.unmap(mapping)
                }
            }
        }

        override fun cancelRecognition(requestId: Long) {
            cancelledUpTo.accumulateAndGet(requestId, ::maxOf)
        }
    }

    private inline fun <T> runOcr(block: () -> T): T = synchronized(engineLock) {
        try {
            block()
        } catch (_: Exception) {
            throw IllegalStateException("OCR failed")
        } catch (_: LinkageError) {
            throw IllegalStateException("OCR runtime unavailable")
        }
    }

    override fun onBind(intent: Intent) = binder

    override fun onDestroy() {
        synchronized(engineLock) {
            engine?.close()
            engine = null
        }
        super.onDestroy()
    }
}
