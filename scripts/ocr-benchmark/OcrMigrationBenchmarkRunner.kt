package com.snipsearch.debug

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.Log
import com.snipsearch.extraction.OcrLine
import com.snipsearch.extraction.isolated.OcrClientCoordinator
import com.snipsearch.extraction.isolated.SessionOcrRunner as LegacyRunner
import com.snipsearch.extraction.isolated.OcrScanSnapshot as LegacySnapshot
import com.snipsearch.extraction.ocr.OcrCoordinator
import com.snipsearch.extraction.ocr.OcrScanSnapshot
import com.snipsearch.extraction.ocr.SessionOcrRunner
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.security.MessageDigest
import kotlin.random.Random

/** Debug-only, synthetic, content-free paired OCR migration benchmark core (baseline 4686504 vs local). */
class OcrMigrationBenchmarkRunner(
    private val context: Context,
    private val onStatus: (String) -> Unit = {},
) {
    data class Summary(
        val passed: Boolean,
        val fixtureCount: Int,
        val coldPairs: Int,
        val warmPairs: Int,
        val errorType: String? = null,
    )

    fun run(fixtureFilter: String? = null): Summary {
        val selectedFixtures = if (fixtureFilter != null) {
            FIXTURES.filter { it.id == fixtureFilter }.ifEmpty { FIXTURES }
        } else {
            FIXTURES
        }
        Log.i(TAG, "START baseline=4686504 fixtures=${selectedFixtures.joinToString(",") { it.id }} cold=$COLD_REPETITIONS warm=$WARM_REPETITIONS")
        onStatus("START baseline=4686504 fixtures=${selectedFixtures.size}")

        var coldPairs = 0
        var warmPairs = 0
        return try {
            selectedFixtures.forEach { fixture ->
                val bitmap = makeFixture(fixture)
                try {
                    if (fixture.index == 0) realEngineLifecycleChecks(bitmap)
                    onStatus("Fixture ${fixture.id}: cold x$COLD_REPETITIONS")
                    repeat(COLD_REPETITIONS) { trial ->
                        val order = if ((trial + fixture.index) % 2 == 0) listOf("legacy", "local") else listOf("local", "legacy")
                        val results = mutableMapOf<String, RunResult>()
                        order.forEach { mode ->
                            if (mode == "legacy") {
                                val legacy = OcrClientCoordinator(context, idleUnbindDelayMs = 0L)
                                val legacyRunner = LegacyRunner(legacy)
                                try {
                                    val start = System.nanoTime()
                                    val lines = runLegacyScan(legacyRunner, bitmap)
                                    val ms = (System.nanoTime() - start) / 1_000_000.0
                                    results[mode] = toRunResult(ms, lines)
                                } finally {
                                    legacyRunner.onSessionDestroy()
                                    Thread.sleep(LEGACY_TEARDOWN_DELAY_MS)
                                }
                            } else {
                                val localCoordinator = OcrCoordinator(context)
                                val runner = SessionOcrRunner(localCoordinator)
                                try {
                                    val start = System.nanoTime()
                                    val lines = runLocalScan(runner, bitmap)
                                    val ms = (System.nanoTime() - start) / 1_000_000.0
                                    results[mode] = toRunResult(ms, lines)
                                } finally {
                                    runner.onSessionDestroy()
                                    localCoordinator.destroy()
                                    Thread.sleep(LOCAL_CLOSE_DELAY_MS)
                                }
                            }
                        }
                        logPair(fixture.id, "cold", trial, results)
                        coldPairs++
                    }

                    onStatus("Fixture ${fixture.id}: warm x$WARM_REPETITIONS (+2 warmup)")
                    val legacyWarm = OcrClientCoordinator(context, idleUnbindDelayMs = 60_000L)
                    val legacyWarmRunner = LegacyRunner(legacyWarm)
                    val localWarmCoordinator = OcrCoordinator(context)
                    val localWarmRunner = SessionOcrRunner(localWarmCoordinator)
                    try {
                        repeat(2) {
                            runLegacyScan(legacyWarmRunner, bitmap)
                            runLocalScan(localWarmRunner, bitmap)
                        }
                        repeat(WARM_REPETITIONS) { trial ->
                            val order = if ((trial + fixture.index) % 2 == 0) listOf("legacy", "local") else listOf("local", "legacy")
                            val results = mutableMapOf<String, RunResult>()
                            order.forEach { mode ->
                                val start = System.nanoTime()
                                val lines = if (mode == "legacy") {
                                    runLegacyScan(legacyWarmRunner, bitmap)
                                } else {
                                    runLocalScan(localWarmRunner, bitmap)
                                }
                                val ms = (System.nanoTime() - start) / 1_000_000.0
                                results[mode] = toRunResult(ms, lines)
                            }
                            logPair(fixture.id, "warm", trial, results)
                            warmPairs++
                        }
                    } finally {
                        legacyWarmRunner.onSessionDestroy()
                        Thread.sleep(LEGACY_TEARDOWN_DELAY_MS)
                        localWarmRunner.onSessionDestroy()
                        localWarmCoordinator.destroy()
                        Thread.sleep(LOCAL_CLOSE_DELAY_MS)
                    }
                } finally {
                    bitmap.eraseColor(Color.TRANSPARENT)
                    bitmap.recycle()
                }
            }
            Log.i(TAG, "COMPLETE baseline=4686504 fixtures=${selectedFixtures.size} cold=$COLD_REPETITIONS warm=$WARM_REPETITIONS")
            onStatus("COMPLETE baseline=4686504")
            Summary(passed = true, fixtureCount = selectedFixtures.size, coldPairs = coldPairs, warmPairs = warmPairs)
        } catch (failure: Throwable) {
            val type = failure.javaClass.simpleName.ifEmpty { "Throwable" }
            Log.e(TAG, "FAILED baseline=4686504 type=$type")
            onStatus("FAILED type=$type")
            Summary(passed = false, fixtureCount = selectedFixtures.size, coldPairs = coldPairs, warmPairs = warmPairs, errorType = type)
        }
    }

    private fun realEngineLifecycleChecks(bitmap: Bitmap) = runBlocking {
        val coordinator = OcrCoordinator(context, idleReleaseDelayMs = 100)
        val runner = SessionOcrRunner(coordinator)
        try {
            val expected = runLocalScan(runner, bitmap)
            repeat(5) {
                val source = bitmap.copy(Bitmap.Config.ARGB_8888, true)!!
                val id = runner.nextScanId()
                try { runner.startScan(id, source) } finally { source.eraseColor(0); source.recycle() }
                withTimeout(SCAN_TIMEOUT_MS) {
                    runner.state.first { it.scanId == id && it.detectedRegionCount != null }
                }
                val start = System.nanoTime()
                runner.onSessionHide()
                val hideMs = (System.nanoTime() - start) / 1_000_000.0
                check(runner.state.value == OcrScanSnapshot.IDLE)
                kotlinx.coroutines.delay(20)
                check(runner.state.value == OcrScanSnapshot.IDLE)
                check(runLocalScan(runner, bitmap) == expected)
                Log.i(TAG, "lifecycle cycle=$it hideMs=${round(hideMs)} reopenedEqual=true")
            }
            val hardware = bitmap.copy(Bitmap.Config.HARDWARE, false)!!
            try { check(runLocalScan(runner, hardware) == expected) } finally { hardware.recycle() }
            val crop = Bitmap.createBitmap(bitmap, 50, 50, bitmap.width / 2, bitmap.height / 2)
            val legacy = OcrClientCoordinator(context)
            try {
                val localLines = runLocalScan(runner, crop)
                val legacyLines = legacy.recognize(crop)
                check(localLines == legacyLines)
                check(localLines.flatMap { it.words }.all {
                    it.bounds.left >= 0 && it.bounds.top >= 0 &&
                        it.bounds.right <= crop.width && it.bounds.bottom <= crop.height
                })
            } finally {
                crop.eraseColor(0); crop.recycle(); legacy.destroy()
                Thread.sleep(LEGACY_TEARDOWN_DELAY_MS)
            }
            runner.onSessionHide()
            kotlinx.coroutines.delay(750)
            check(!coordinator.warmUp().reused)
            Log.i(TAG, "lifecycle hardwareEqual=true cropEqual=true idleEvicted=true")
        } finally { runner.onSessionDestroy() }
        kotlinx.coroutines.delay(150)
    }

    private fun runLegacyScan(runner: LegacyRunner, bitmap: Bitmap): List<OcrLine> = runBlocking {
        val scanId = runner.nextScanId()
        runner.startScan(scanId, bitmap)
        val snapshot = withTimeout(SCAN_TIMEOUT_MS) {
            runner.state.first {
                it.scanId == scanId &&
                    (it.status == LegacySnapshot.Status.COMPLETE || it.status == LegacySnapshot.Status.FAILED)
            }
        }
        check(snapshot.status == LegacySnapshot.Status.COMPLETE) { "LegacyOcrScanFailed" }
        snapshot.lines
    }

    private fun runLocalScan(runner: SessionOcrRunner, bitmap: Bitmap): List<OcrLine> = runBlocking {
        val scanId = runner.nextScanId()
        runner.startScan(scanId, bitmap)
        val snapshot = withTimeout(SCAN_TIMEOUT_MS) {
            runner.state.first {
                it.scanId == scanId &&
                    (it.status == OcrScanSnapshot.Status.COMPLETE || it.status == OcrScanSnapshot.Status.FAILED)
            }
        }
        if (snapshot.status == OcrScanSnapshot.Status.FAILED) {
            error("LocalOcrScanFailed")
        }
        snapshot.lines
    }

    private fun toRunResult(ms: Double, lines: List<OcrLine>): RunResult {
        val wordCount = lines.sumOf { it.words.size }
        val canonical = buildString {
            lines.forEach { line ->
                append(line.text).append('\n')
                line.words.forEach { w ->
                    append(w.characters.first).append(':').append(w.characters.last)
                        .append('@').append(w.bounds.left).append(',').append(w.bounds.top)
                        .append(',').append(w.bounds.right).append(',').append(w.bounds.bottom)
                        .append(';')
                }
                append('\n')
            }
        }
        val bytes = canonical.toByteArray(Charsets.UTF_8)
        val digest = try {
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        } finally { bytes.fill(0) }
        return RunResult(ms, lines, wordCount, digest)
    }

    private fun logPair(fixture: String, phase: String, trial: Int, results: Map<String, RunResult>) {
        val a = results["legacy"] ?: return
        val b = results["local"] ?: return
        val textEqual = a.lines.map { it.text } == b.lines.map { it.text }
        val geometryEqual = a.lines.size == b.lines.size &&
            a.lines.indices.all { i -> a.lines[i].words == b.lines[i].words }
        val msg = "pair baseline=4686504 fixture=$fixture phase=$phase trial=$trial " +
            "legacyMs=${round(a.ms)} localMs=${round(b.ms)} " +
            "legacyLines=${a.lines.size} localLines=${b.lines.size} " +
            "legacyWords=${a.words} localWords=${b.words} " +
            "textEqual=$textEqual geometryEqual=$geometryEqual " +
            "legacyDigest=${a.digest} localDigest=${b.digest}"
        Log.i(TAG, msg)
        check(textEqual && geometryEqual) { "OutputMismatch" }
        onStatus("$fixture/$phase#$trial: leg=${round(a.ms)}ms loc=${round(b.ms)}ms eq=$textEqual/$geometryEqual")
    }

    private fun makeFixture(fixture: Fixture): Bitmap {
        val bmp = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.rgb(247, 247, 245))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif", Typeface.NORMAL) }
        val random = Random(fixture.index + 91)
        when (fixture.index) {
            0 -> {
                paint.textSize = 19f
                for (col in 0..2) for (row in 0 until 76) {
                    paint.color = Color.rgb(28, 31, 35)
                    c.drawText("${row + 1}  ${WORDS[(row + col) % WORDS.size]} ${(row * 137 + col * 91) % 99999}", 16f + col * 355f, 31f + row * 30f, paint)
                }
            }
            1 -> {
                paint.textSize = 25f
                for (row in 0 until 56) {
                    paint.color = Color.rgb(125 + row % 28, 127 + row % 27, 126 + row % 25)
                    c.drawText("${WORDS[(row * 3) % WORDS.size]} status ${(row * 47) % 10000}  pending review", 22f, 42f + row * 40f, paint)
                }
                c.save(); c.rotate(-7f, 530f, 1300f)
                paint.color = Color.DKGRAY
                for (i in 0..7) c.drawText("rotated exception trace block $i with codes QX${i}870", 170f, 1280f + i * 42f, paint)
                c.restore()
                paint.style = Paint.Style.STROKE
                for (y in 100..2300 step 160) c.drawRoundRect(750f, y.toFloat(), 1030f, y + 72f, 14f, 14f, paint)
                paint.style = Paint.Style.FILL
            }
            else -> {
                paint.textSize = 17f
                for (row in 0 until 108) {
                    val y = 25f + row * 21f
                    paint.color = if (row % 2 == 0) Color.rgb(224, 232, 237) else Color.WHITE
                    c.drawRect(8f, y - 17f, 1072f, y + 4f, paint)
                    paint.color = Color.rgb(20 + row % 25, 25, 31)
                    c.drawText("${row.toString().padStart(3, '0')}  ${WORDS[row % WORDS.size]}  0x${(row * 3217).toString(16)}  ${random.nextInt(100000, 999999)}  PASS/RETRY", 14f, y, paint)
                }
            }
        }
        return bmp
    }

    private fun round(value: Double) = "%.2f".format(java.util.Locale.US, value)

    private data class RunResult(val ms: Double, val lines: List<OcrLine>, val words: Int, val digest: String)
    private data class Fixture(val index: Int, val id: String)

    companion object {
        private const val TAG = "SnipOcrMigBench"
        private const val WIDTH = 1080
        private const val HEIGHT = 2400
        private const val COLD_REPETITIONS = 3
        private const val WARM_REPETITIONS = 10
        private const val SCAN_TIMEOUT_MS = 60_000L
        private const val LEGACY_TEARDOWN_DELAY_MS = 150L
        private const val LOCAL_CLOSE_DELAY_MS = 100L
        private val FIXTURES = listOf(
            Fixture(0, "columns"),
            Fixture(1, "lowcontrast_rotated"),
            Fixture(2, "technical_table"),
        )
        private val WORDS = listOf(
            "credential_rotation", "scheduler", "network_policy", "allocation",
            "synchronization", "exception_handler", "checksum", "transaction",
            "foreground_service", "isolation_boundary", "interpolation", "retry_window",
            "latency_measurement", "reconciliation",
        )
    }
}
