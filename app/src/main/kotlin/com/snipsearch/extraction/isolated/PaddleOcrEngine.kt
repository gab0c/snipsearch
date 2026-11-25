package com.snipsearch.extraction.isolated

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.res.AssetFileDescriptor
import com.snipsearch.ipc.OcrTextBlockParcelable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

internal class PaddleOcrEngine private constructor(
    private val environment: OrtEnvironment,
    private val detector: OrtSession,
    private val recognizer: OrtSession,
    private val decoder: CtcBeamDecoder,
    private val recWorkers: Int,
    private val recExecutor: ExecutorService,
) : AutoCloseable {
    fun recognize(
        argb: IntBuffer,
        width: Int,
        height: Int,
        stride: Int,
        isCancelled: () -> Boolean = { false },
    ): List<OcrTextBlockParcelable> {
        if (isCancelled()) return emptyList()
        val scale = minOf(1.0, 960.0 / maxOf(width, height))
        val detWidth = ((width * scale / 32).roundToInt()).coerceAtLeast(1) * 32
        val detHeight = ((height * scale / 32).roundToInt()).coerceAtLeast(1) * 32
        val scaleX = width.toDouble() / detWidth
        val scaleY = height.toDouble() / detHeight

        val detImage = CropImage(argb.duplicate(), width, height, stride)
        val regions = infer(
            detector, detWidth, detHeight,
            fill = { input ->
                for (y in 0 until detHeight) {
                    for (x in 0 until detWidth) {
                        detImage.sample(
                            input, y * detWidth + x, detWidth * detHeight,
                            (x + 0.5) * scaleX, (y + 0.5) * scaleY, DET_MEAN, DET_STD,
                        )
                    }
                }
            },
        ) { shape, output ->
            require(shape.contentEquals(longArrayOf(1, 1, detHeight.toLong(), detWidth.toLong())))
            TextDetector.regions(output, detWidth, detHeight)
        }
        if (regions.isEmpty() || isCancelled()) return emptyList()

        val results = arrayOfNulls<OcrTextBlockParcelable>(regions.size)
        if (regions.size == 1 || recWorkers <= 1) {
            for ((index, region) in regions.withIndex()) {
                if (isCancelled()) break
                results[index] = recognizeLine(detImage, region, scaleX, scaleY)
            }
        } else {
            // Longest-first scheduling keeps wide lines from forming a serial tail.
            val order = regions.indices
                .sortedByDescending { regions[it].aspectRatio(scaleX, scaleY) }
                .toIntArray()
            val nextTask = AtomicInteger(0)
            val failure = AtomicReference<Throwable?>(null)
            val workerCount = minOf(recWorkers, regions.size)
            val done = CountDownLatch(workerCount)
            repeat(workerCount) {
                recExecutor.execute {
                    val workerImage = CropImage(argb.duplicate(), width, height, stride)
                    try {
                        while (!isCancelled() && failure.get() == null) {
                            val orderIdx = nextTask.getAndIncrement()
                            if (orderIdx >= order.size) break
                            val regionIdx = order[orderIdx]
                            results[regionIdx] = recognizeLine(workerImage, regions[regionIdx], scaleX, scaleY)
                        }
                    } catch (t: Throwable) {
                        failure.compareAndSet(null, t)
                    } finally {
                        done.countDown()
                    }
                }
            }
            done.await()
            failure.get()?.let { throw it }
        }
        return if (isCancelled()) emptyList() else results.filterNotNull()
    }

    private fun recognizeLine(
        image: CropImage, region: TextRegion, scaleX: Double, scaleY: Double,
    ): OcrTextBlockParcelable? {
        val height = 48
        val contentWidth = ceil(height * region.aspectRatio(scaleX, scaleY)).toInt().coerceIn(16, 960)
        val width = (((contentWidth + 31) / 32) * 32).coerceAtLeast(32)
        return infer(
            recognizer, width, height,
            fill = { input ->
                for (y in 0 until height) {
                    val vertical = (y + 0.5) / height
                    for (x in 0 until contentWidth) {
                        val horizontal = (x + 0.5) / contentWidth
                        image.sample(
                            input, y * width + x, width * height,
                            region.x(horizontal, vertical) * scaleX, region.y(horizontal, vertical) * scaleY,
                            REC_NORMALIZATION, REC_NORMALIZATION,
                        )
                    }
                }
            },
        ) { shape, output ->
            require(shape.size == 3 && shape[0] == 1L && shape[2] == decoder.classes.toLong())
            val steps = shape[1].toInt()
            val words = decoder.decode(output, steps)
            if (words.isEmpty()) return@infer null
            val starts = IntArray(words.size)
            val ends = IntArray(words.size)
            val bounds = IntArray(words.size * 4)
            val positionScale = width.toDouble() / (contentWidth * steps)
            val text = buildString {
                words.forEachIndexed { index, word ->
                    if (index > 0) append(' ')
                    starts[index] = length
                    append(word.text)
                    ends[index] = length
                    val start = (word.firstStep * positionScale).coerceIn(0.0, 1.0)
                    val end = (word.endStep * positionScale).coerceIn(start, 1.0)
                    val box = region.bounds(start, end, scaleX, scaleY, image.width, image.height)
                    bounds[index * 4] = box.left
                    bounds[index * 4 + 1] = box.top
                    bounds[index * 4 + 2] = box.right
                    bounds[index * 4 + 3] = box.bottom
                }
            }
            OcrTextBlockParcelable().apply {
                this.text = text
                wordStarts = starts
                wordEnds = ends
                wordBounds = bounds
            }
        }
    }

    private fun <T> infer(
        session: OrtSession,
        width: Int,
        height: Int,
        fill: (FloatBuffer) -> Unit,
        read: (LongArray, FloatBuffer) -> T,
    ): T {
        val input = ByteBuffer.allocateDirect(3 * width * height * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        try {
            fill(input)
            val shape = longArrayOf(1, 3, height.toLong(), width.toLong())
            OnnxTensor.createTensor(environment, input, shape).use { tensor ->
                session.run(mapOf(session.inputNames.single() to tensor)).use { result ->
                    val output = result[0] as OnnxTensor
                    val values = output.floatBuffer
                    try {
                        return read(output.info.shape, values)
                    } finally {
                        values.clear()
                        while (values.hasRemaining()) values.put(0f)
                    }
                }
            }
        } finally {
            input.clear()
            while (input.hasRemaining()) input.put(0f)
        }
    }

    override fun close() {
        recExecutor.shutdownNow()
        try {
            recognizer.close()
        } finally {
            detector.close()
        }
    }

    companion object {
        private val DET_MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        private val DET_STD = floatArrayOf(0.229f, 0.224f, 0.225f)
        private val REC_NORMALIZATION = floatArrayOf(0.5f, 0.5f, 0.5f)

        fun open(
            detector: AssetFileDescriptor,
            recognizer: AssetFileDescriptor,
            dictionary: AssetFileDescriptor,
        ): PaddleOcrEngine {
            val alphabet = dictionary.createInputStream().bufferedReader(Charsets.UTF_8).use { it.readLines() }
            require(alphabet.size == 6623)
            val environment = OrtEnvironment.getEnvironment()
            environment.setTelemetry(false)
            val workers = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
            val det = openSession(environment, detector.modelBytes(), intraThreads = workers)
            return try {
                val rec = openSession(environment, recognizer.modelBytes(), intraThreads = 1)
                val recExecutor = Executors.newFixedThreadPool(workers) { runnable ->
                    Thread(runnable, "snip-ocr-rec").apply { isDaemon = true }
                }
                PaddleOcrEngine(environment, det, rec, CtcBeamDecoder(alphabet + " "), workers, recExecutor)
            } catch (failure: Exception) {
                det.close()
                throw failure
            }
        }

        private fun openSession(
            environment: OrtEnvironment,
            modelBytes: ByteArray,
            intraThreads: Int,
        ): OrtSession {
            val xnnpackAttempt = createSessionOptions(intraThreads, useXnnpack = true)
            if (xnnpackAttempt.second) {
                try {
                    return xnnpackAttempt.first.use { options ->
                        environment.createSession(modelBytes, options)
                    }
                } catch (_: Exception) {
                    // Fall back cleanly to default CPU EP if XNNPACK rejects any graph node.
                }
            } else {
                xnnpackAttempt.first.close()
            }
            return createSessionOptions(intraThreads, useXnnpack = false).first.use { options ->
                environment.createSession(modelBytes, options)
            }
        }

        private fun createSessionOptions(
            intraThreads: Int,
            useXnnpack: Boolean,
        ): Pair<OrtSession.SessionOptions, Boolean> {
            val options = OrtSession.SessionOptions()
            try {
                options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                options.setCPUArenaAllocator(true)
                options.setInterOpNumThreads(1)
                options.addConfigEntry("session.intra_op.allow_spinning", "0")
                options.setIntraOpNumThreads(intraThreads)
                val xnnpackActive = if (useXnnpack) {
                    try {
                        options.addXnnpack(mapOf("intra_op_num_threads" to intraThreads.toString()))
                        true
                    } catch (_: Throwable) {
                        false
                    }
                } else {
                    false
                }
                return options to xnnpackActive
            } catch (failure: Throwable) {
                options.close()
                throw failure
            }
        }

        private fun AssetFileDescriptor.modelBytes(): ByteArray {
            require(length in 1..32L * 1024 * 1024)
            return createInputStream().use { it.readNBytes(length.toInt()) }.also {
                require(it.size.toLong() == length)
            }
        }
    }
}

private class CropImage(
    private val argb: IntBuffer,
    val width: Int,
    val height: Int,
    private val stride: Int,
) {
    fun sample(
        tensor: FloatBuffer, offset: Int, plane: Int, x: Double, y: Double,
        mean: FloatArray, deviation: FloatArray,
    ) {
        val sx = (x - 0.5).coerceIn(0.0, width - 1.0)
        val sy = (y - 0.5).coerceIn(0.0, height - 1.0)
        val x0 = floor(sx).toInt()
        val y0 = floor(sy).toInt()
        val x1 = minOf(width - 1, x0 + 1)
        val y1 = minOf(height - 1, y0 + 1)
        val wx = (sx - x0).toFloat()
        val wy = (sy - y0).toFloat()
        val topLeft = argb[y0 * stride + x0]
        val topRight = argb[y0 * stride + x1]
        val bottomLeft = argb[y1 * stride + x0]
        val bottomRight = argb[y1 * stride + x1]
        for (channel in 0..2) {
            // PaddleOCR's OpenCV pipeline feeds BGR channel planes.
            val shift = 8 * channel
            val top = component(topLeft, shift) * (1 - wx) + component(topRight, shift) * wx
            val bottom = component(bottomLeft, shift) * (1 - wx) + component(bottomRight, shift) * wx
            val value = (top * (1 - wy) + bottom * wy) / 255f
            tensor.put(channel * plane + offset, (value - mean[channel]) / deviation[channel])
        }
    }

    private fun component(pixel: Int, shift: Int) = (pixel ushr shift) and 0xff
}
