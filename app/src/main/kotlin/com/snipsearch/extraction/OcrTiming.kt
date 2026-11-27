package com.snipsearch.extraction

import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Content-free latency logger for assistant OCR responsiveness measurement.
 * Logs only monotonic IDs, elapsed millisecond deltas, counts, and dimensions.
 */
internal class OcrTiming private constructor(
    val invocationId: Long,
    private val startedAtMs: Long,
    private val clock: () -> Long,
    private val logger: (String) -> Unit,
) {
    private val assistLogged = AtomicBoolean(false)
    private val engineReadyLogged = AtomicBoolean(false)
    private val firstSelectableLogged = AtomicBoolean(false)
    private val firstOcrSelectableLogged = AtomicBoolean(false)

    fun elapsedMs(): Long = (clock() - startedAtMs).coerceAtLeast(0L)

    fun nowMs(): Long = clock()

    fun logInvoke(invocationType: Int) {
        logger("id=$invocationId event=invoke type=$invocationType")
    }

    fun logCaptureArrived(width: Int, height: Int) {
        logger("id=$invocationId event=capture_arrived +${elapsedMs()}ms size=${width}x${height}")
    }

    fun logAssistArrived(structureCount: Int) {
        if (assistLogged.compareAndSet(false, true)) {
            logger("id=$invocationId event=assist_arrived +${elapsedMs()}ms structures=$structureCount")
        }
    }

    fun logEngineReady(initMs: Long, reused: Boolean) {
        if (engineReadyLogged.compareAndSet(false, true)) {
            logger("id=$invocationId event=ocr_engine_ready +${elapsedMs()}ms initMs=${initMs}ms reused=$reused")
        }
    }

    fun logPixelsCopied(copyMs: Long, width: Int, height: Int) {
        logger("id=$invocationId event=ocr_pixels_copied +${elapsedMs()}ms copyMs=${copyMs}ms size=${width}x${height}")
    }

    fun logDetectDone(detMs: Long, regions: Int) {
        logger("id=$invocationId event=ocr_detect_done +${elapsedMs()}ms detMs=${detMs}ms regions=$regions")
    }

    fun logOcrComplete(totalOcrMs: Long, lines: Int, words: Int, reused: Boolean) {
        logger("id=$invocationId event=ocr_complete +${elapsedMs()}ms totalOcrMs=${totalOcrMs}ms lines=$lines words=$words reused=$reused")
    }

    fun logFirstSelectableFrame(source: String, words: Int) {
        if (firstSelectableLogged.compareAndSet(false, true)) {
            logger("id=$invocationId event=first_selectable_frame +${elapsedMs()}ms source=$source words=$words")
        }
    }

    fun logFirstOcrSelectableFrame(source: String, words: Int) {
        if (firstOcrSelectableLogged.compareAndSet(false, true)) {
            logger("id=$invocationId event=first_ocr_selectable_frame +${elapsedMs()}ms source=$source words=$words")
        }
    }

    companion object {
        const val TAG = "SnipOcrTiming"
        private val nextId = AtomicLong(0L)

        fun start(
            invocationType: Int,
            clock: () -> Long = { SystemClock.elapsedRealtime() },
            logger: (String) -> Unit = { Log.i(TAG, it) },
        ): OcrTiming {
            val timing = OcrTiming(
                invocationId = nextId.incrementAndGet(),
                startedAtMs = clock(),
                clock = clock,
                logger = logger,
            )
            timing.logInvoke(invocationType)
            return timing
        }
    }
}
