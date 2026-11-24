package com.snipsearch.extraction.isolated

import android.app.Service
import android.content.Intent
import android.content.res.AssetFileDescriptor
import android.os.SharedMemory
import com.snipsearch.ipc.IOcrIsolatedService
import com.snipsearch.ipc.OcrTextBlockParcelable
import java.nio.ByteOrder

class OcrIsolatedService : Service() {
    private val engineLock = Any()
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
            argbPixels: SharedMemory,
            width: Int,
            height: Int,
            stride: Int,
        ): List<OcrTextBlockParcelable> = argbPixels.use { shared ->
            runOcr {
                require(shared.size == pixelBufferSize(width, height, stride))
                val ocr = checkNotNull(engine)
                val mapping = shared.mapReadOnly()
                try {
                    val pixels = mapping.order(ByteOrder.nativeOrder()).asIntBuffer()
                    ocr.recognize(pixels, width, height, stride)
                } finally {
                    SharedMemory.unmap(mapping)
                }
            }
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
