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
    @Test fun embeddedPagesRequireExactApprovedHttpsOrigins() {
        for (url in listOf("https://www.google.com/search?q=hello", "https://consent.google.com/m", "https://accounts.google.com/", "https://www.google.com:443/")) {
            assertTrue(url, TextSearchPolicy.isEmbeddedPage(android.net.Uri.parse(url)))
        }
        for (url in listOf("http://www.google.com/", "https://www.google.com.evil.test/", "https://evil.test/", "https://www.google.com:8443/", "https://user@www.google.com/", "content://www.google.com/", "file:///screen.png", "https://evil.test\\@www.google.com/", "https://www.google.com%2eevil.test/", "https://www.google.com/\nnext")) {
            assertFalse(url, TextSearchPolicy.isEmbeddedPage(android.net.Uri.parse(url)))
        }
    }

}
