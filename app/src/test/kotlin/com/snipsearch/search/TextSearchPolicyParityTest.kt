package com.snipsearch.search

import android.net.Uri
import com.snipsearch.ui.overlay.TextSearchPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class TextSearchPolicyParityTest {
    @Test
    fun googlePresetMatchesTextSearchPolicyForTheseQueries() {
        // Internal whitespace runs and the characters !*'() are intentionally outside this parity set.
        for (query in listOf("cat", "C++ & ?=#/%", "café", "🔍 日本", "a-b_c.d~e", "  padded  ")) {
            val built = (SearchQueryBuilder.textSearchUrl(BuiltInProviders.GOOGLE, query) as ProviderResult.Valid).value
            assertEquals(TextSearchPolicy.googleUrl(query), built)
            assertTrue(TextSearchPolicy.isEmbeddedPage(Uri.parse(built)))
        }
    }

    @Test
    fun blankQueryIsRejectedByBothPolicies() {
        assertNull(TextSearchPolicy.googleUrl("   "))
        val result = SearchQueryBuilder.textSearchUrl(BuiltInProviders.GOOGLE, "   ") as ProviderResult.Invalid
        assertEquals(ProviderConfigErrorCode.EMPTY_QUERY, result.errors.single().code)
        assertEquals("query", result.errors.single().field)
    }
}
