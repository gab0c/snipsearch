package com.snipsearch.extraction.isolated

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.os.IBinder
import android.os.SharedMemory
import android.system.OsConstants
import com.snipsearch.extraction.OcrLine
import com.snipsearch.extraction.OcrWord
import com.snipsearch.extraction.PixelRegion
import com.snipsearch.ipc.IOcrIsolatedService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.IOException
import java.nio.ByteOrder
import java.nio.IntBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

internal class OcrClientCoordinator(private val context: Context) {
    // Copy synchronously while the overlay still owns the crop. Only the protected mapping
    // crosses a suspension point, so hiding the overlay can recycle its bitmap immediately.
    suspend fun recognize(crop: Bitmap): List<OcrLine> = CropPixels.copy(crop).use { pixels ->
        withContext(Dispatchers.IO) { recognize(pixels) }
    }

    private fun recognize(pixels: CropPixels): List<OcrLine> {
        val connection = OcrConnection()
        val intent = Intent(context, OcrIsolatedService::class.java)
        val bound = context.bindService(intent, Context.BIND_AUTO_CREATE, Executor { it.run() }, connection)
        try {
            if (!bound || !connection.ready.await(8, TimeUnit.SECONDS)) {
                throw IOException("Could not connect to OCR service")
            }
            val service = connection.service ?: throw IOException("OCR service disconnected")
            context.assets.openFd("ocr/ch_PP-OCRv4_det_infer.onnx").use { detector ->
                context.assets.openFd("ocr/ch_PP-OCRv4_rec_infer.onnx").use { recognizer ->
                    context.assets.openFd("ocr/ppocr_keys_v1.txt").use { dictionary ->
                        service.initializeModels(detector, recognizer, dictionary)
                    }
                }
            }
            return service.recognizeCrop(pixels.memory, pixels.width, pixels.height, pixels.width).mapNotNull { line ->
                val words = line.wordStarts.indices.mapNotNull { index ->
                    val offset = index * 4
                    val bounds = PixelRegion(line.wordBounds[offset], line.wordBounds[offset + 1],
                        line.wordBounds[offset + 2], line.wordBounds[offset + 3])
                    bounds.takeIf { it.width > 0 && it.height > 0 }?.let {
                        OcrWord(line.wordStarts[index] until line.wordEnds[index], it)
                    }
                }
                words.takeIf { it.isNotEmpty() }?.let { OcrLine(line.text, it) }
            }
        } finally {
            context.unbindService(connection)
        }
    }

    // A new connection per request avoids holding a stale proxy after a crash or after the
    // overlay closes. A failed request can be retried without keeping another copy of its pixels.
    private class OcrConnection : ServiceConnection {
        val ready = CountDownLatch(1)

        @Volatile
        var service: IOcrIsolatedService? = null
            private set

        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = IOcrIsolatedService.Stub.asInterface(binder)
            ready.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            ready.countDown()
        }

        override fun onBindingDied(name: ComponentName) = onServiceDisconnected(name)
        override fun onNullBinding(name: ComponentName) = onServiceDisconnected(name)
    }

    private class CropPixels(
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
            fun copy(crop: Bitmap): CropPixels {
                if (crop.config == Bitmap.Config.HARDWARE) {
                    val software = crop.copy(Bitmap.Config.ARGB_8888, true)
                        ?: throw IOException("Could not read screenshot pixels")
                    try {
                        return copy(software)
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
                val row = IntArray(width)
                try {
                    for (y in 0 until height) {
                        crop.getPixels(row, 0, width, 0, y, width, 1)
                        cropPixels.pixels.put(row)
                    }
                    check(memory.setProtect(OsConstants.PROT_READ))
                    return cropPixels
                } catch (failure: Exception) {
                    cropPixels.close()
                    throw failure
                } finally {
                    row.fill(0)
                }
            }
        }
    }
}

internal fun pixelBufferSize(width: Int, height: Int, stride: Int): Int {
    require(width in 1..8192 && height in 1..8192 && stride in width..8192)
    val pixels = stride.toLong() * height
    require(pixels <= 16_777_216)
    return pixels.toInt() * Int.SIZE_BYTES
}
