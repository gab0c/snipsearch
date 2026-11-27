package com.snipsearch.extraction.ocr

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
    val reusedEngine: Boolean = false,
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
 * OCR results and takes RAM pixel ownership before returning from [startScan],
 * so the session can immediately scrub or recycle its capture [Bitmap].
 */
internal class SessionOcrRunner(
    private val coordinator: OcrCoordinator,
    private val workerDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val pixelCopier: (Bitmap, () -> Boolean) -> OcrCoordinator.CropPixels? = { bitmap, cancelled ->
        OcrCoordinator.CropPixels.copy(bitmap, cancelled)
    },
) {
    private val scope = CoroutineScope(SupervisorJob() + workerDispatcher)
    private val stateLock = Any()
    private val pixelCopyLock = Any()
    private val nextScanIdGenerator = AtomicLong(0L)

    private val _state = MutableStateFlow(OcrScanSnapshot.IDLE)
    val state: StateFlow<OcrScanSnapshot> = _state.asStateFlow()

    private var warmJob: Job? = null
    private var activeScan: ActiveScan? = null
    @Volatile private var destroyed = false

    fun nextScanId(): Long = nextScanIdGenerator.incrementAndGet()

    fun warmUp(timing: OcrTiming? = null) {
        synchronized(stateLock) {
            if (destroyed) return
            coordinator.retainModels()
            if (warmJob?.isActive == true) return
            warmJob = scope.launch(workerDispatcher) {
                try {
                    val ready = coordinator.warmUp(cancelIdleRelease = false)
                    timing?.logEngineReady(ready.initMs, ready.reused)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    // Explicit OCR scan handles initialization errors and fallback.
                } catch (_: LinkageError) {
                    // A missing/incompatible native runtime is recoverable through assist fallback.
                }
            }
        }
    }

    fun startScan(scanId: Long, bitmap: Bitmap, timing: OcrTiming? = null) {
        val current = ActiveScan(scanId)
        val previous = synchronized(stateLock) {
            if (destroyed) return
            coordinator.retainModels()
            val prev = activeScan
            prev?.cancelled?.set(true)
            activeScan = current
            _state.value = OcrScanSnapshot(scanId = scanId, status = OcrScanSnapshot.Status.RUNNING)
            prev
        }
        previous?.job?.cancel()
        // Own screen pixels before yielding; no worker closure retains the source Bitmap.
        val scanStartNanos = System.nanoTime()
        val pixels = try {
            synchronized(pixelCopyLock) {
                if (current.cancelled.get() || destroyed || bitmap.isRecycled) null
                else pixelCopier(bitmap) { current.cancelled.get() }
            }
        } catch (_: Exception) {
            null
        }
        if (pixels == null) {
            publishIfCurrent(current) { it.copy(status = OcrScanSnapshot.Status.FAILED, lines = emptyList()) }
            return
        }
        timing?.logPixelsCopied((System.nanoTime() - scanStartNanos) / 1_000_000L, pixels.width, pixels.height)
        val job = scope.launch(workerDispatcher, start = CoroutineStart.LAZY) {
            runScan(current, pixels, timing, scanStartNanos)
        }
        // Covers cancellation before the lazy job enters its body as well as normal completion.
        job.invokeOnCompletion { pixels.close() }
        current.job = job
        synchronized(stateLock) {
            if (destroyed || activeScan !== current || current.cancelled.get()) {
                job.cancel()
                return
            }
        }
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
            // Wait only for RAM ownership transfer, never for native inference.
        }
    }

    fun onSessionHide() {
        cancelActiveScan()
        coordinator.scheduleIdleRelease()
    }

    fun onSessionDestroy() {
        synchronized(stateLock) {
            if (destroyed) return
            destroyed = true
            warmJob?.cancel()
            warmJob = null
        }
        cancelActiveScan()
        coordinator.destroy()
        scope.cancel()
    }

    private suspend fun runScan(
        scan: ActiveScan,
        pixels: OcrCoordinator.CropPixels,
        timing: OcrTiming?,
        scanStartNanos: Long,
    ) {
        if (scan.cancelled.get()) {
            pixels.close()
            return
        }

        pixels.use { cropPixels ->
            var reusedEngine = false
            try {
                val finalLines = coordinator.recognize(
                    pixels = cropPixels,
                    onReady = { info ->
                        reusedEngine = info.reused
                        timing?.logEngineReady(info.initMs, info.reused)
                    },
                    onDetect = { count, detMs ->
                        timing?.logDetectDone(detMs, count)
                        publishIfCurrent(scan) { it.copy(detectedRegionCount = count) }
                    },
                    isCancelled = { scan.cancelled.get() },
                )
                if (!scan.cancelled.get()) {
                    val totalOcrMs = (System.nanoTime() - scanStartNanos) / 1_000_000L
                    val wordCount = finalLines.sumOf { it.words.size }
                    timing?.logOcrComplete(totalOcrMs, finalLines.size, wordCount, reusedEngine)
                    publishIfCurrent(scan) {
                        it.copy(
                            status = OcrScanSnapshot.Status.COMPLETE,
                            lines = finalLines,
                            reusedEngine = reusedEngine,
                        )
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                publishFailureIfCurrent(scan)
            } catch (_: LinkageError) {
                publishFailureIfCurrent(scan)
            }
        }
    }

    private fun publishFailureIfCurrent(scan: ActiveScan) {
        publishIfCurrent(scan) { it.copy(status = OcrScanSnapshot.Status.FAILED, lines = emptyList()) }
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
