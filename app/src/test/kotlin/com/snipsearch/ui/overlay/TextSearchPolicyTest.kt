package com.snipsearch.ui.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
class TextSearchPolicyTest {
    @Test fun blankQueryDoesNotProduceUrl() {
        assertNull(TextSearchPolicy.googleUrl("  "))
    }

    @Test fun googleSearchUrlEncodesSelectedText() {
        assertEquals("https://www.google.com/search?q=hello%20world", TextSearchPolicy.googleUrl("hello world"))
    }
}
