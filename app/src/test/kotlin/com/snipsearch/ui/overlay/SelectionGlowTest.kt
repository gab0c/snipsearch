package com.snipsearch.ui.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionGlowTest {
    @Test
    fun theHaloFadesWhenTheFingerPauses() {
        assertEquals(1f, haloOpacity(0L), 0.01f)
        assertEquals(0.5f, haloOpacity(120L), 0.01f)
        assertEquals(0f, haloOpacity(240L), 0.01f)
    }

    @Test
    fun accelerationNarrowsTheHaloWithinItsSizeLimits() {
        assertEquals(48f, haloRadiusDp(-0.01f), 0.01f)
        assertTrue(haloRadiusDp(0.01f) < haloRadiusDp(0f))
        assertEquals(22f, haloRadiusDp(0.2f), 0.01f)
    }
}
