package com.snipsearch.ui.overlay

import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.ContextWrapper
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class WebSearchTest {
    private class RecordingContext(private val failure: RuntimeException? = null) :
        ContextWrapper(RuntimeEnvironment.getApplication()) {
        var launched: Intent? = null
        override fun startActivity(intent: Intent) {
            failure?.let { throw it }
            launched = intent
        }
    }

    @Test
    fun sendsLiteralSelectionWithoutPickingProviderOrBrowser() {
        val context = RecordingContext()
        val query = "private selection & café + https://example.org"
        assertTrue(context.searchWeb(query))
        val intent = requireNotNull(context.launched)
        assertEquals(Intent.ACTION_WEB_SEARCH, intent.action)
        assertEquals(query, intent.getStringExtra(SearchManager.QUERY))
        assertNull(intent.data)
        assertNull(intent.`package`)
        assertNull(intent.component)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun emptySelectionDoesNotLaunch() {
        val context = RecordingContext()
        assertFalse(context.searchWeb("  \n"))
        assertNull(context.launched)
    }

    @Test
    fun missingOrRefusingHandlerLeavesOverlayOpen() {
        for (failure in listOf(ActivityNotFoundException(), SecurityException())) {
            val context = RecordingContext(failure)
            assertFalse(context.searchWeb("selected text"))
            assertNull(context.launched)
        }
    }
}
