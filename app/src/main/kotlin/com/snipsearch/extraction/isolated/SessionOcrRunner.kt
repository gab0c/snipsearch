package com.snipsearch.extraction.isolated

import android.graphics.Bitmap
import com.snipsearch.extraction.OcrLine
import com.snipsearch.extraction.OcrTiming
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal data class OcrScanSnapshot(
    val scanId: Long = 0L,
    val status: Status = Status.IDLE,
    val lines: List<OcrLine> = emptyList(),
    val detectedRegionCount: Int? = null,
    val reusedService: Boolean = false,
) {
    enum class Status {
        IDLE,
        RUNNING,
        COMPLETE,
        FAILED,
    }

    companion object {
        val IDLE = OcrScanSnapshot()
    }
}

/**
 * Coordinates assistant-session warm-up, early RAM-only pixel copying, atomic
 * OCR results, and synchronous cancellation before the session scrubs or
 * recycles its capture [Bitmap].
 */
internal class SessionOcrRunner(
    private val coordinator: OcrClientCoordinator,
    private val workerDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val scope = CoroutineScope(SupervisorJob() + workerDispatcher)
    private val stateLock = Any()
    private val pixelCopyLock = Any()
    private val nextScanIdGenerator = AtomicLong(0L)

    private val _state = MutableStateFlow(OcrScanSnapshot.IDLE)
    val state: StateFlow<OcrScanSnapshot> = _state.asStateFlow()

    private var warmJob: Job? = null
    private var activeScan: ActiveScan? = null

    fun nextScanId(): Long = nextScanIdGenerator.incrementAndGet()

    fun warmUp(timing: OcrTiming? = null) {
        synchronized(stateLock) {
            if (warmJob?.isActive == true) return
            warmJob = scope.launch(ioDispatcher) {
                try {
                    val ready = coordinator.warmUp()
                    timing?.logServiceReady(ready.bindMs, ready.initMs, ready.reused)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    // Explicit OCR scan handles and surfaces service startup errors.
                }
            }
        }
    }

    fun startScan(scanId: Long, bitmap: Bitmap, timing: OcrTiming? = null) {
        val previous: ActiveScan?
        val current = ActiveScan(scanId)
        val job = scope.launch(workerDispatcher, start = CoroutineStart.LAZY) {
            runScan(current, bitmap, timing)
        }
        current.job = job
        synchronized(stateLock) {
            previous = activeScan
            previous?.cancelled?.set(true)
            activeScan = current
            _state.value = OcrScanSnapshot(
                scanId = scanId,
                status = OcrScanSnapshot.Status.RUNNING,
                lines = emptyList(),
            )
        }
        previous?.job?.cancel()
        job.start()
    }

    fun cancelActiveScan() {
        val previous = synchronized(stateLock) {
            val prev = activeScan
            activeScan = null
            prev?.cancelled?.set(true)
            _state.value = OcrScanSnapshot.IDLE
            prev
        }
        previous?.job?.cancel()
        synchronized(pixelCopyLock) {
            // Wait for any in-flight Bitmap pixel copy to finish before caller recycles bitmap.
        }
    }

    fun onSessionHide() {
        cancelActiveScan()
        coordinator.scheduleIdleRelease()
    }

    fun onSessionDestroy() {
        synchronized(stateLock) {
            warmJob?.cancel()
            warmJob = null
        }
        cancelActiveScan()
        coordinator.destroy()
        scope.cancel()
    }

    private suspend fun runScan(scan: ActiveScan, bitmap: Bitmap, timing: OcrTiming?) {
        val scanStartNanos = System.nanoTime()
        val pixels = try {
            synchronized(pixelCopyLock) {
                if (scan.cancelled.get() || bitmap.isRecycled) {
                    null
                } else {
                    val copyStartNanos = System.nanoTime()
                    val copied = OcrClientCoordinator.CropPixels.copy(bitmap) { scan.cancelled.get() }
                    if (copied != null) {
                        val copyMs = (System.nanoTime() - copyStartNanos) / 1_000_000L
                        timing?.logPixelsCopied(copyMs, copied.width, copied.height)
                    }
                    copied
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            publishIfCurrent(scan) {
                it.copy(status = OcrScanSnapshot.Status.FAILED, lines = emptyList())
            }
            return
        }

        if (pixels == null || scan.cancelled.get()) {
            pixels?.close()
            return
        }

        pixels.use { cropPixels ->
            var reusedService = false
            try {
                val finalLines = coordinator.recognize(
                    pixels = cropPixels,
                    onReady = { info ->
                        reusedService = info.reused
                        timing?.logServiceReady(info.bindMs, info.initMs, info.reused)
                    },
                    onDetect = { count, detMs ->
                        timing?.logDetectDone(detMs, count)
                        publishIfCurrent(scan) { it.copy(detectedRegionCount = count) }
                    },
                )
                if (!scan.cancelled.get()) {
                    val totalOcrMs = (System.nanoTime() - scanStartNanos) / 1_000_000L
                    val wordCount = finalLines.sumOf { it.words.size }
                    timing?.logOcrComplete(totalOcrMs, finalLines.size, wordCount, reusedService)
                    publishIfCurrent(scan) {
                        it.copy(
                            status = OcrScanSnapshot.Status.COMPLETE,
                            lines = finalLines,
                            reusedService = reusedService,
                        )
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                if (!scan.cancelled.get()) {
                    publishIfCurrent(scan) {
                        it.copy(status = OcrScanSnapshot.Status.FAILED, lines = emptyList())
                    }
                }
            }
        }
    }

    private inline fun publishIfCurrent(
        scan: ActiveScan,
        transform: (OcrScanSnapshot) -> OcrScanSnapshot,
    ) {
        synchronized(stateLock) {
            if (activeScan !== scan || scan.cancelled.get()) return
            val snapshot = _state.value
            if (snapshot.scanId != scan.scanId) return
            _state.value = transform(snapshot)
        }
    }

    private class ActiveScan(
        val scanId: Long,
        val cancelled: AtomicBoolean = AtomicBoolean(false),
        @Volatile var job: Job? = null,
    )
}
