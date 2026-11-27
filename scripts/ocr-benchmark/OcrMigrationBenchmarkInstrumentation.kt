package com.snipsearch.debug

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle

/**
 * Debug-only Instrumentation runner for [OcrMigrationBenchmarkRunner].
 * Invoked via `adb shell am instrument -w com.snipsearch/com.snipsearch.debug.OcrMigrationBenchmarkInstrumentation`.
 */
class OcrMigrationBenchmarkInstrumentation : Instrumentation() {
    private var fixtureFilter: String? = null

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        fixtureFilter = arguments?.getString("fixture")?.trim()?.takeIf { it.isNotEmpty() }
        start()
    }

    override fun onStart() {
        super.onStart()
        val summary = OcrMigrationBenchmarkRunner(targetContext).run(fixtureFilter)
        val resultBundle = Bundle().apply {
            putBoolean("passed", summary.passed)
            putInt("fixtureCount", summary.fixtureCount)
            putInt("coldPairs", summary.coldPairs)
            putInt("warmPairs", summary.warmPairs)
            if (summary.errorType != null) {
                putString("errorType", summary.errorType)
            }
        }
        val resultCode = if (summary.passed) Activity.RESULT_OK else Activity.RESULT_CANCELED
        finish(resultCode, resultBundle)
    }
}
