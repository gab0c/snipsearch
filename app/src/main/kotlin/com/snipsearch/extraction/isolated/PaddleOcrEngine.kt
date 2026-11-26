package com.snipsearch.extraction.isolated

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.res.AssetFileDescriptor
import android.util.Log
import com.snipsearch.ipc.OcrTextBlockParcelable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

internal class PaddleOcrEngine private constructor(
    private val environment: OrtEnvironment,
    private val detector: OrtSession,
    private val recPoolFuture: CompletableFuture<Array<OrtSession>>,
    private val recInitThread: Thread,
    private val initClosed: AtomicBoolean,
    private val decoder: CtcBeamDecoder,
    private val detScratch: DirectFloatScratch,
    private val detRegionScratch: TextDetectorScratch,
    private val recScratches: Array<DirectFloatScratch>,
    private val recExecutor: ExecutorService,
) : AutoCloseable {
    fun recognize(
        argb: IntBuffer,
        width: Int,
        height: Int,
        stride: Int,
        isCancelled: () -> Boolean = { false },
        onDetected: (regionCount: Int, detectMs: Long) -> Unit = { _, _ -> },
    ): List<OcrTextBlockParcelable> {
        if (isCancelled()) return emptyList()
        val detStartNanos = System.nanoTime()
        val scale = minOf(1.0, DET_MAX_SIDE / maxOf(width, height))
        val detWidth = ((width * scale / 32).roundToInt()).coerceAtLeast(1) * 32
        val detHeight = ((height * scale / 32).roundToInt()).coerceAtLeast(1) * 32
        val scaleX = width.toDouble() / detWidth
        val scaleY = height.toDouble() / detHeight

        val regions = infer(
            session = detector,
            scratch = detScratch,
            width = detWidth,
            height = detHeight,
            fill = { input, scratch ->
                fillDetectorInput(argb, width, height, stride, detWidth, detHeight, scaleX, scaleY, input, scratch)
            },
        ) { shape, output, _, _ ->
            require(shape.contentEquals(longArrayOf(1, 1, detHeight.toLong(), detWidth.toLong())))
            TextDetector.regions(output.floatBuffer!!, detWidth, detHeight, detRegionScratch)
        }
        val detMs = (System.nanoTime() - detStartNanos) / 1_000_000L
        onDetected(regions.size, detMs)
        if (regions.isEmpty() || isCancelled()) return emptyList()

        val recognizers = awaitRecognizers(isCancelled) ?: return emptyList()
        val results = arrayOfNulls<OcrTextBlockParcelable>(regions.size)
        if (regions.size == 1 || recognizers.size <= 1) {
            val image = CropImage(argb.duplicate(), width, height, stride)
            val session = recognizers[0]
            val scratch = recScratches[0]
            for ((index, region) in regions.withIndex()) {
                if (isCancelled()) break
                results[index] = recognizeLine(session, scratch, image, region, scaleX, scaleY)
            }
        } else {
            // Longest-first scheduling keeps wide lines from forming a serial tail.
            val order = regions.indices
                .sortedByDescending { regions[it].aspectRatio(scaleX, scaleY) }
                .toIntArray()
            val nextTask = AtomicInteger(0)
            val failure = AtomicReference<Throwable?>(null)
            val workerCount = minOf(recognizers.size, regions.size)
            val done = CountDownLatch(workerCount)
            for (workerId in 0 until workerCount) {
                recExecutor.execute {
                    val workerImage = CropImage(argb.duplicate(), width, height, stride)
                    val session = recognizers[workerId]
                    val scratch = recScratches[workerId]
                    try {
                        while (!isCancelled() && failure.get() == null) {
                            val orderIdx = nextTask.getAndIncrement()
                            if (orderIdx >= order.size) break
                            val regionIdx = order[orderIdx]
                            results[regionIdx] = recognizeLine(
                                session = session,
                                scratch = scratch,
                                image = workerImage,
                                region = regions[regionIdx],
                                scaleX = scaleX,
                                scaleY = scaleY,
                            )
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

    private fun awaitRecognizers(isCancelled: () -> Boolean): Array<OrtSession>? {
        while (true) {
            if (isCancelled()) return null
            try {
                return recPoolFuture.get(25, TimeUnit.MILLISECONDS)
            } catch (_: java.util.concurrent.TimeoutException) {
                // Keep checking cancellation while cold recognizer initialization finishes.
            } catch (e: ExecutionException) {
                throw e.cause ?: e
            }
        }
    }

    private fun fillDetectorInput(
        argb: IntBuffer,
        width: Int,
        height: Int,
        stride: Int,
        detWidth: Int,
        detHeight: Int,
        scaleX: Double,
        scaleY: Double,
        input: FloatBuffer,
        scratch: DirectFloatScratch,
    ) {
        val plane = detWidth * detHeight
        val x0Table = IntArray(detWidth)
        val x1Table = IntArray(detWidth)
        val wxTable = FloatArray(detWidth)
        val rowA = IntArray(width)
        val rowB = IntArray(width)
        val rowBlue = scratch.rowBlue(detWidth)
        val rowGreen = scratch.rowGreen(detWidth)
        val rowRed = scratch.rowRed(detWidth)
        var rowAIndex = -1
        var rowBIndex = -1
        val argbView = argb.duplicate()
        val scale0 = DET_SCALE[0]
        val scale1 = DET_SCALE[1]
        val scale2 = DET_SCALE[2]
        val bias0 = DET_BIAS[0]
        val bias1 = DET_BIAS[1]
        val bias2 = DET_BIAS[2]
        try {
            for (x in 0 until detWidth) {
                val sx = ((x + 0.5) * scaleX - 0.5).coerceIn(0.0, width - 1.0)
                val x0 = floor(sx).toInt()
                x0Table[x] = x0
                x1Table[x] = minOf(width - 1, x0 + 1)
                wxTable[x] = (sx - x0).toFloat()
            }

            fun rowFor(targetY: Int, otherY: Int): IntArray {
                if (targetY == rowAIndex) return rowA
                if (targetY == rowBIndex) return rowB
                return if (rowAIndex != otherY) {
                    argbView.position(targetY * stride)
                    argbView.get(rowA, 0, width)
                    rowAIndex = targetY
                    rowA
                } else {
                    argbView.position(targetY * stride)
                    argbView.get(rowB, 0, width)
                    rowBIndex = targetY
                    rowB
                }
            }

            for (y in 0 until detHeight) {
                val sy = ((y + 0.5) * scaleY - 0.5).coerceIn(0.0, height - 1.0)
                val y0 = floor(sy).toInt()
                val y1 = minOf(height - 1, y0 + 1)
                val wy = (sy - y0).toFloat()
                val oneMinusWy = 1f - wy
                val topRow = rowFor(y0, y1)
                val bottomRow = rowFor(y1, y0)
                for (x in 0 until detWidth) {
                    val x0 = x0Table[x]
                    val x1 = x1Table[x]
                    val wx = wxTable[x]
                    val oneMinusWx = 1f - wx
                    val tl = topRow[x0]
                    val tr = topRow[x1]
                    val bl = bottomRow[x0]
                    val br = bottomRow[x1]

                    val topB = (tl and 0xff) * oneMinusWx + (tr and 0xff) * wx
                    val botB = (bl and 0xff) * oneMinusWx + (br and 0xff) * wx
                    rowBlue[x] = (topB * oneMinusWy + botB * wy) * scale0 + bias0

                    val topG = ((tl ushr 8) and 0xff) * oneMinusWx + ((tr ushr 8) and 0xff) * wx
                    val botG = ((bl ushr 8) and 0xff) * oneMinusWx + ((br ushr 8) and 0xff) * wx
                    rowGreen[x] = (topG * oneMinusWy + botG * wy) * scale1 + bias1

                    val topR = ((tl ushr 16) and 0xff) * oneMinusWx + ((tr ushr 16) and 0xff) * wx
                    val botR = ((bl ushr 16) and 0xff) * oneMinusWx + ((br ushr 16) and 0xff) * wx
                    rowRed[x] = (topR * oneMinusWy + botR * wy) * scale2 + bias2
                }
                val rowOffset = y * detWidth
                input.position(rowOffset)
                input.put(rowBlue, 0, detWidth)
                input.position(plane + rowOffset)
                input.put(rowGreen, 0, detWidth)
                input.position(2 * plane + rowOffset)
                input.put(rowRed, 0, detWidth)
            }
        } finally {
            rowA.fill(0)
            rowB.fill(0)
            x0Table.fill(0)
            x1Table.fill(0)
            wxTable.fill(0f)
        }
    }

    private fun recognizeLine(
        session: OrtSession,
        scratch: DirectFloatScratch,
        image: CropImage,
        region: TextRegion,
        scaleX: Double,
        scaleY: Double,
    ): OcrTextBlockParcelable? {
        val height = 48
        val contentWidth = ceil(height * region.aspectRatio(scaleX, scaleY)).toInt().coerceIn(16, REC_MAX_WIDTH)
        // PP-OCRv6_tiny_rec has exact horizontal stride 8 (3x stride-2 downsamples).
        val width = (((contentWidth + 7) / 8) * 8).coerceAtLeast(16)
        return infer(
            session = session,
            scratch = scratch,
            width = width,
            height = height,
            fill = { input, rowScratch ->
                if (abs(region.dy) < 1e-6) {
                    fillHorizontalRecognizerInput(image, region, scaleX, scaleY, contentWidth, width, height, input, rowScratch)
                } else {
                    fillAngledRecognizerInput(image, region, scaleX, scaleY, contentWidth, width, height, input, rowScratch)
                }
            },
        ) { shape, output, tokenOutput, inputBuffer ->
            val steps = shape[1].toInt()
            require(shape.contentEquals(longArrayOf(1, steps.toLong())) && output.type == OnnxJavaType.FLOAT)
            val ids = tokenOutput ?: error("Recognizer token output is missing")
            val tokenIds = ids.longBuffer ?: error("Recognizer token output has no INT64 buffer")
            val maxProbabilities = output.floatBuffer ?: error("Recognizer confidence output has no FLOAT buffer")
            require(ids.type == OnnxJavaType.INT64 && tokenIds.capacity() == steps)
            require(maxProbabilities.capacity() == steps)
            val decodedWords = decoder.decodeTokensWithConfidence(tokenIds, maxProbabilities, steps)
            val words = filterRecognizedWords(
                decodedWords, width.toDouble() / (contentWidth * steps) * region.aspectRatio(scaleX, scaleY),
            )
            if (words.isEmpty()) return@infer null
            val floatCount = 3 * width * height
            val planar = scratch.planarArray(floatCount)
            inputBuffer.position(0)
            inputBuffer.get(planar, 0, floatCount)
            val refinedSpans = WordBoundsRefiner.refine(planar, width, contentWidth, height, steps, words)
            val starts = IntArray(words.size)
            val ends = IntArray(words.size)
            val bounds = IntArray(words.size * 4)
            val text = buildString {
                words.forEachIndexed { index, word ->
                    if (index > 0) append(' ')
                    starts[index] = length
                    append(word.text)
                    ends[index] = length
                    val span = refinedSpans[index]
                    val box = region.bounds(
                        start = span.hStart,
                        end = span.hEnd,
                        scaleX = scaleX,
                        scaleY = scaleY,
                        width = image.width,
                        height = image.height,
                        vStart = span.vStart,
                        vEnd = span.vEnd,
                    )
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

    private fun fillHorizontalRecognizerInput(
        image: CropImage,
        region: TextRegion,
        scaleX: Double,
        scaleY: Double,
        contentWidth: Int,
        width: Int,
        height: Int,
        input: FloatBuffer,
        scratch: DirectFloatScratch,
    ) {
        val plane = width * height
        val x0Table = IntArray(contentWidth)
        val x1Table = IntArray(contentWidth)
        val wxTable = FloatArray(contentWidth)
        var minSrcX = image.width - 1
        var maxSrcX = 0
        for (x in 0 until contentWidth) {
            val sx = (region.x((x + 0.5) / contentWidth, 0.5) * scaleX - 0.5).coerceIn(0.0, image.width - 1.0)
            val x0 = floor(sx).toInt()
            val x1 = minOf(image.width - 1, x0 + 1)
            x0Table[x] = x0
            x1Table[x] = x1
            wxTable[x] = (sx - x0).toFloat()
            if (x0 < minSrcX) minSrcX = x0
            if (x1 > maxSrcX) maxSrcX = x1
        }
        val spanWidth = maxSrcX - minSrcX + 1
        val rowA = IntArray(spanWidth)
        val rowB = IntArray(spanWidth)
        val rowBlue = scratch.rowBlue(width).also { if (contentWidth < width) it.fill(0f, contentWidth, width) }
        val rowGreen = scratch.rowGreen(width).also { if (contentWidth < width) it.fill(0f, contentWidth, width) }
        val rowRed = scratch.rowRed(width).also { if (contentWidth < width) it.fill(0f, contentWidth, width) }
        var rowAIndex = -1
        var rowBIndex = -1
        try {
            fun rowFor(targetY: Int, otherY: Int): IntArray {
                if (targetY == rowAIndex) return rowA
                if (targetY == rowBIndex) return rowB
                return if (rowAIndex != otherY) {
                    image.copySpan(targetY, minSrcX, spanWidth, rowA)
                    rowAIndex = targetY
                    rowA
                } else {
                    image.copySpan(targetY, minSrcX, spanWidth, rowB)
                    rowBIndex = targetY
                    rowB
                }
            }

            for (y in 0 until height) {
                val sy = (region.y(0.5, (y + 0.5) / height) * scaleY - 0.5).coerceIn(0.0, image.height - 1.0)
                val y0 = floor(sy).toInt()
                val y1 = minOf(image.height - 1, y0 + 1)
                val wy = (sy - y0).toFloat()
                val oneMinusWy = 1f - wy
                val topRow = rowFor(y0, y1)
                val bottomRow = rowFor(y1, y0)
                for (x in 0 until contentWidth) {
                    val lx0 = x0Table[x] - minSrcX
                    val lx1 = x1Table[x] - minSrcX
                    val wx = wxTable[x]
                    val oneMinusWx = 1f - wx
                    val tl = topRow[lx0]
                    val tr = topRow[lx1]
                    val bl = bottomRow[lx0]
                    val br = bottomRow[lx1]

                    val topB = (tl and 0xff) * oneMinusWx + (tr and 0xff) * wx
                    val botB = (bl and 0xff) * oneMinusWx + (br and 0xff) * wx
                    rowBlue[x] = (topB * oneMinusWy + botB * wy) * REC_SCALE + REC_BIAS

                    val topG = ((tl ushr 8) and 0xff) * oneMinusWx + ((tr ushr 8) and 0xff) * wx
                    val botG = ((bl ushr 8) and 0xff) * oneMinusWx + ((br ushr 8) and 0xff) * wx
                    rowGreen[x] = (topG * oneMinusWy + botG * wy) * REC_SCALE + REC_BIAS

                    val topR = ((tl ushr 16) and 0xff) * oneMinusWx + ((tr ushr 16) and 0xff) * wx
                    val botR = ((bl ushr 16) and 0xff) * oneMinusWx + ((br ushr 16) and 0xff) * wx
                    rowRed[x] = (topR * oneMinusWy + botR * wy) * REC_SCALE + REC_BIAS
                }
                val rowOffset = y * width
                input.position(rowOffset)
                input.put(rowBlue, 0, width)
                input.position(plane + rowOffset)
                input.put(rowGreen, 0, width)
                input.position(2 * plane + rowOffset)
                input.put(rowRed, 0, width)
            }
        } finally {
            rowA.fill(0)
            rowB.fill(0)
            x0Table.fill(0)
            x1Table.fill(0)
            wxTable.fill(0f)
        }
    }

    private fun fillAngledRecognizerInput(
        image: CropImage,
        region: TextRegion,
        scaleX: Double,
        scaleY: Double,
        contentWidth: Int,
        width: Int,
        height: Int,
        input: FloatBuffer,
        scratch: DirectFloatScratch,
    ) {
        val plane = width * height
        val rowBlue = scratch.rowBlue(width).also { if (contentWidth < width) it.fill(0f, contentWidth, width) }
        val rowGreen = scratch.rowGreen(width).also { if (contentWidth < width) it.fill(0f, contentWidth, width) }
        val rowRed = scratch.rowRed(width).also { if (contentWidth < width) it.fill(0f, contentWidth, width) }
        for (y in 0 until height) {
            val vertical = (y + 0.5) / height
            for (x in 0 until contentWidth) {
                val horizontal = (x + 0.5) / contentWidth
                image.sampleRgbToRows(
                    rowBlue, rowGreen, rowRed, x,
                    region.x(horizontal, vertical) * scaleX,
                    region.y(horizontal, vertical) * scaleY,
                )
            }
            val rowOffset = y * width
            input.position(rowOffset)
            input.put(rowBlue, 0, width)
            input.position(plane + rowOffset)
            input.put(rowGreen, 0, width)
            input.position(2 * plane + rowOffset)
            input.put(rowRed, 0, width)
        }
    }

    private fun <T> infer(
        session: OrtSession,
        scratch: DirectFloatScratch,
        width: Int,
        height: Int,
        fill: (FloatBuffer, DirectFloatScratch) -> Unit,
        read: (LongArray, InferenceOutput, InferenceOutput?, FloatBuffer) -> T,
    ): T {
        val floatCount = 3 * width * height
        val input = scratch.floatBuffer(floatCount)
        try {
            fill(input, scratch)
            input.clear()
            input.limit(floatCount)
            val shape = longArrayOf(1, 3, height.toLong(), width.toLong())
            OnnxTensor.createTensor(environment, input, shape).use { tensor ->
                session.run(mapOf(session.inputNames.single() to tensor)).use { result ->
                    val output = result[0] as OnnxTensor
                    val auxiliary = if (result.size() > 1) result[1] as OnnxTensor else null
                    inferenceOutput(output).use { outputData ->
                        auxiliary?.let(::inferenceOutput).use { auxiliaryData ->
                            val planar = input.duplicate()
                            planar.clear()
                            planar.limit(floatCount)
                            return read(output.info.shape, outputData, auxiliaryData, planar)
                        }
                    }
                }
            }
        } finally {
            scratch.scrub(floatCount)
        }
    }

    private fun inferenceOutput(tensor: OnnxTensor) = InferenceOutput(
        type = tensor.info.type,
        floatBuffer = if (tensor.info.type == OnnxJavaType.FLOAT) tensor.floatBuffer else null,
        longBuffer = if (tensor.info.type == OnnxJavaType.INT64) tensor.longBuffer else null,
    )

    override fun close() {
        initClosed.set(true)
        runCatching { recInitThread.join() }
        recExecutor.shutdownNow()
        detRegionScratch.scrub()
        detScratch.scrub()
        recScratches.forEach { it.scrub() }
        var firstFailure: Throwable? = null
        if (recPoolFuture.isDone && !recPoolFuture.isCancelled && !recPoolFuture.isCompletedExceptionally) {
            for (rec in recPoolFuture.get()) {
                try {
                    rec.close()
                } catch (t: Throwable) {
                    if (firstFailure == null) firstFailure = t
                }
            }
        }
        try {
            detector.close()
        } catch (t: Throwable) {
            if (firstFailure == null) firstFailure = t
        }
        firstFailure?.let { throw it }
    }

    companion object {
        private const val TAG = "SnipOcrEngine"
        private const val DET_MAX_SIDE = 736.0
        private const val REC_MAX_WIDTH = 1280
        private val DET_MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        private val DET_STD = floatArrayOf(0.229f, 0.224f, 0.225f)
        private val DET_SCALE = FloatArray(3) { c -> 1f / (255f * DET_STD[c]) }
        private val DET_BIAS = FloatArray(3) { c -> -DET_MEAN[c] / DET_STD[c] }
        private const val REC_SCALE = 1f / (255f * 0.5f)
        private const val REC_BIAS = -1f
        private val ZERO_FLOATS = FloatArray(4096)
        private val ZERO_LONGS = LongArray(1024)

        internal fun zeroFloatBuffer(buffer: FloatBuffer, count: Int = buffer.capacity()) {
            val target = minOf(count, buffer.capacity())
            buffer.clear()
            var offset = 0
            while (offset < target) {
                val step = minOf(ZERO_FLOATS.size, target - offset)
                buffer.put(ZERO_FLOATS, 0, step)
                offset += step
            }
            buffer.clear()
        }

        internal fun zeroLongBuffer(buffer: java.nio.LongBuffer, count: Int = buffer.capacity()) {
            val target = minOf(count, buffer.capacity())
            buffer.clear()
            var offset = 0
            while (offset < target) {
                val step = minOf(ZERO_LONGS.size, target - offset)
                buffer.put(ZERO_LONGS, 0, step)
                offset += step
            }
            buffer.clear()
        }

        fun open(
            detector: AssetFileDescriptor,
            recognizer: AssetFileDescriptor,
            dictionary: AssetFileDescriptor,
        ): PaddleOcrEngine {
            val alphabet = dictionary.createInputStream().bufferedReader(Charsets.UTF_8).use { it.readLines() }
            require(alphabet.isNotEmpty())
            val environment = OrtEnvironment.getEnvironment()
            environment.setTelemetry(false)
            val workers = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
            val detBytes = detector.modelBytes()
            val recBytes = recognizer.modelBytes()
            val recExecutor = Executors.newFixedThreadPool(workers) { runnable ->
                Thread(runnable, "snip-ocr-rec").apply { isDaemon = true }
            }
            val initClosed = AtomicBoolean(false)
            val recPoolFuture = CompletableFuture<Array<OrtSession>>()
            var detSession: OrtSession? = null
            try {
                val (openedDet, detXnnpack) = try {
                    openSession(environment, detBytes, intraThreads = workers)
                } finally {
                    detBytes.fill(0)
                }
                detSession = openedDet

                val recSessions = mutableListOf<OrtSession>()
                val recInitThread = Thread({
                    try {
                        var recXnnpack = true
                        repeat(workers) {
                            if (initClosed.get()) {
                                recSessions.forEach { runCatching { it.close() } }
                                recSessions.clear()
                                recPoolFuture.cancel(false)
                                return@Thread
                            }
                            val (openedRec, active) = openSession(environment, recBytes, intraThreads = 1)
                            recSessions += openedRec
                            recXnnpack = recXnnpack && active
                        }
                        if (initClosed.get()) {
                            recSessions.forEach { runCatching { it.close() } }
                            recSessions.clear()
                            recPoolFuture.cancel(false)
                        } else {
                            runCatching {
                                Log.i(TAG, "opened workers=$workers xnnpack=det:$detXnnpack/rec:$recXnnpack")
                            }
                            recPoolFuture.complete(recSessions.toTypedArray())
                        }
                    } catch (failure: Throwable) {
                        recSessions.forEach { runCatching { it.close() } }
                        recSessions.clear()
                        recPoolFuture.completeExceptionally(failure)
                    } finally {
                        recBytes.fill(0)
                    }
                }, "snip-ocr-rec-init").apply {
                    isDaemon = true
                    start()
                }

                return PaddleOcrEngine(
                    environment = environment,
                    detector = openedDet,
                    recPoolFuture = recPoolFuture,
                    recInitThread = recInitThread,
                    initClosed = initClosed,
                    decoder = CtcBeamDecoder(alphabet + " "),
                    detScratch = DirectFloatScratch(),
                    detRegionScratch = TextDetectorScratch(),
                    recScratches = Array(workers) { DirectFloatScratch() },
                    recExecutor = recExecutor,
                )
            } catch (failure: Throwable) {
                initClosed.set(true)
                recBytes.fill(0)
                recExecutor.shutdownNow()
                detSession?.let { runCatching { it.close() } }
                throw failure
            }
        }

        private fun openSession(
            environment: OrtEnvironment,
            modelBytes: ByteArray,
            intraThreads: Int,
        ): Pair<OrtSession, Boolean> {
            val xnnpackAttempt = createSessionOptions(intraThreads, useXnnpack = true)
            if (xnnpackAttempt.second) {
                try {
                    return xnnpackAttempt.first.use { options ->
                        environment.createSession(modelBytes, options) to true
                    }
                } catch (_: Exception) {
                    // Fall back cleanly to default CPU EP if XNNPACK rejects any graph node.
                }
            } else {
                xnnpackAttempt.first.close()
            }
            return createSessionOptions(intraThreads, useXnnpack = false).first.use { options ->
                environment.createSession(modelBytes, options) to false
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
                options.addConfigEntry("session.use_env_allocators", "1")
                options.addConfigEntry("optimization.enable_gelu_approximation", "1")
                options.addConfigEntry("mlas.enable_gemm_fastmath_arm64_bfloat16", "1")
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

internal data class InferenceOutput(
    val type: OnnxJavaType,
    val floatBuffer: FloatBuffer?,
    val longBuffer: java.nio.LongBuffer?,
) : AutoCloseable {
    override fun close() {
        floatBuffer?.let { PaddleOcrEngine.zeroFloatBuffer(it) }
        longBuffer?.let { PaddleOcrEngine.zeroLongBuffer(it) }
    }
}

internal class DirectFloatScratch {
    private var floatBuffer: FloatBuffer? = null
    private var planar: FloatArray = FloatArray(0)
    private var rowBlue: FloatArray = FloatArray(0)
    private var rowGreen: FloatArray = FloatArray(0)
    private var rowRed: FloatArray = FloatArray(0)

    fun floatBuffer(floatCount: Int): FloatBuffer {
        val current = floatBuffer
        if (current != null && current.capacity() >= floatCount) {
            current.clear()
            current.limit(floatCount)
            return current
        }
        zeroFloatBuffer(current)
        val allocated = ByteBuffer.allocateDirect(floatCount * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        floatBuffer = allocated
        return allocated
    }

    fun planarArray(floatCount: Int): FloatArray {
        if (planar.size < floatCount) {
            planar.fill(0f)
            planar = FloatArray(floatCount)
        }
        return planar
    }

    fun rowBlue(width: Int): FloatArray {
        if (rowBlue.size < width) {
            rowBlue.fill(0f)
            rowBlue = FloatArray(width)
        }
        return rowBlue
    }

    fun rowGreen(width: Int): FloatArray {
        if (rowGreen.size < width) {
            rowGreen.fill(0f)
            rowGreen = FloatArray(width)
        }
        return rowGreen
    }

    fun rowRed(width: Int): FloatArray {
        if (rowRed.size < width) {
            rowRed.fill(0f)
            rowRed = FloatArray(width)
        }
        return rowRed
    }

    fun scrub(usedFloats: Int? = null, usedRowWidth: Int? = null) {
        scrubRow(rowBlue, usedRowWidth)
        scrubRow(rowGreen, usedRowWidth)
        scrubRow(rowRed, usedRowWidth)
        scrubRow(planar, usedFloats)
        zeroFloatBuffer(floatBuffer, usedFloats)
    }

    private fun scrubRow(row: FloatArray, usedRowWidth: Int?) {
        if (row.isNotEmpty()) {
            val limit = minOf(usedRowWidth ?: row.size, row.size)
            row.fill(0f, 0, limit)
        }
    }

    private fun zeroFloatBuffer(buffer: FloatBuffer?, usedFloats: Int? = null) {
        buffer ?: return
        PaddleOcrEngine.zeroFloatBuffer(buffer, usedFloats ?: buffer.capacity())
    }
}

private class CropImage(
    private val argb: IntBuffer,
    val width: Int,
    val height: Int,
    private val stride: Int,
) {
    fun copySpan(y: Int, xStart: Int, count: Int, dest: IntArray) {
        argb.position(y * stride + xStart)
        argb.get(dest, 0, count)
    }

    fun sampleRgbToRows(
        rowBlue: FloatArray,
        rowGreen: FloatArray,
        rowRed: FloatArray,
        xIndex: Int,
        x: Double,
        y: Double,
    ) {
        val sx = (x - 0.5).coerceIn(0.0, width - 1.0)
        val sy = (y - 0.5).coerceIn(0.0, height - 1.0)
        val x0 = floor(sx).toInt()
        val y0 = floor(sy).toInt()
        val x1 = minOf(width - 1, x0 + 1)
        val y1 = minOf(height - 1, y0 + 1)
        val wx = (sx - x0).toFloat()
        val wy = (sy - y0).toFloat()
        val oneMinusWx = 1f - wx
        val oneMinusWy = 1f - wy
        val topLeft = argb[y0 * stride + x0]
        val topRight = argb[y0 * stride + x1]
        val bottomLeft = argb[y1 * stride + x0]
        val bottomRight = argb[y1 * stride + x1]

        val topB = (topLeft and 0xff) * oneMinusWx + (topRight and 0xff) * wx
        val botB = (bottomLeft and 0xff) * oneMinusWx + (bottomRight and 0xff) * wx
        rowBlue[xIndex] = (topB * oneMinusWy + botB * wy) * (1f / (255f * 0.5f)) - 1f

        val topG = ((topLeft ushr 8) and 0xff) * oneMinusWx + ((topRight ushr 8) and 0xff) * wx
        val botG = ((bottomLeft ushr 8) and 0xff) * oneMinusWx + ((bottomRight ushr 8) and 0xff) * wx
        rowGreen[xIndex] = (topG * oneMinusWy + botG * wy) * (1f / (255f * 0.5f)) - 1f

        val topR = ((topLeft ushr 16) and 0xff) * oneMinusWx + ((topRight ushr 16) and 0xff) * wx
        val botR = ((bottomLeft ushr 16) and 0xff) * oneMinusWx + ((bottomRight ushr 16) and 0xff) * wx
        rowRed[xIndex] = (topR * oneMinusWy + botR * wy) * (1f / (255f * 0.5f)) - 1f
    }
}
