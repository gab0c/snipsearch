package com.snipsearch.extraction

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrTimingTest {
    @Test
    fun inProcessEngineTimingIsContentFreeAndHasNoServiceBindingFields() {
        var now = 10L
        val events = mutableListOf<String>()
        val timing = OcrTiming.start(1, clock = { now++ }, logger = events::add)
        timing.logEngineReady(initMs = 25, reused = false)
        val event = events.last()
        assertTrue(event.contains("event=ocr_engine_ready"))
        assertTrue(event.contains("initMs=25ms"))
        assertFalse(event.contains("bindMs"))
        assertFalse(event.contains("service"))
        assertFalse(event.contains("screen"))
    }
}
