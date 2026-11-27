package com.snipsearch.search

import android.net.Uri
import android.webkit.WebResourceRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class UploadOriginPolicyTest {
    @Test
    fun canonicalizesValidHttpsOriginsAndRejectsMaliciousVariants() {
        assertEquals("https://lens.google.com", UploadOriginPolicy.canonicalizeOrigin("https://lens.google.com"))
        assertEquals("https://lens.google.com", UploadOriginPolicy.canonicalizeOrigin("https://LENS.GOOGLE.COM/"))
        assertEquals("https://lens.google.com", UploadOriginPolicy.canonicalizeOrigin("https://lens.google.com:443"))
        assertEquals("https://lens.google.com", UploadOriginPolicy.canonicalizeOrigin("https://lens.google.com:443/"))

        val rejected = listOf(
            "",
            "   ",
            "http://lens.google.com",
            "https://lens.google.com/v3/upload",
            "https://lens.google.com/?q=1",
            "https://lens.google.com/#frag",
            "https://lens.google.com:8443",
            "https://lens.google.com:80",
            "https://lens.google.com:",
            "https://user@lens.google.com",
            "https://user:pass@lens.google.com",
            "https://localhost",
            "https://127.0.0.1",
            "https://192.168.1.1",
            "https://.lens.google.com",
            "https://lens.google.com.",
            "https://lens..google.com",
            "https://evil.test\\@lens.google.com",
            "https://lens.google.com%2eevil.test",
            "https://lens.google.com\nhttps://evil.test",
            "https://lens.google.com ",
            "file:///sdcard/secret.png",
            "content://com.example/provider",
            "javascript:alert(1)",
            "data:text/html,hi",
            "intent://lens.google.com",
        )
        for (origin in rejected) {
            assertNull("Expected null for $origin", UploadOriginPolicy.canonicalizeOrigin(origin))
        }
    }

    @Test
    fun endpointValidationEnforcesExactOriginAndBlocksInternalBlobMarker() {
        val origin = "https://lens.google.com"
        assertEquals(
            "https://lens.google.com/v3/upload",
            UploadOriginPolicy.canonicalizeEndpoint("https://LENS.GOOGLE.COM:443/v3/upload", origin),
        )
        assertEquals(
            "https://yandex.com/images/touch/search?rpt=imageview",
            UploadOriginPolicy.canonicalizeEndpoint(
                "https://yandex.com/images/touch/search?rpt=imageview",
                "https://yandex.com",
            ),
        )
        assertEquals(
            "https://lens.google.com/",
            UploadOriginPolicy.canonicalizeEndpoint("https://lens.google.com", origin),
        )

        val rejectedEndpoints = listOf(
            "http://lens.google.com/v3/upload",
            "https://www.google.com/v3/upload",
            "https://lens.google.com.evil.test/v3/upload",
            "https://lens.google.com:8443/v3/upload",
            "https://user@lens.google.com/v3/upload",
            "https://lens.google.com/v3/upload#frag",
            "https://lens.google.com/${UPLOAD_INTERNAL_BLOB_PATH_SEGMENT}/abc.webp",
            "https://lens.google.com/v3/${UPLOAD_INTERNAL_BLOB_PATH_SEGMENT.uppercase()}",
            "https://lens.google.com/v3/upload?next=/${UPLOAD_INTERNAL_BLOB_PATH_SEGMENT}/x",
        )
        for (endpoint in rejectedEndpoints) {
            assertNull("Expected null for $endpoint", UploadOriginPolicy.canonicalizeEndpoint(endpoint, origin))
        }
    }

    @Test
    fun internalBlobDetectionCoversParserTricksAndNavigationalBoundaries() {
        assertTrue(UploadOriginPolicy.isInternalBlobUrl(Uri.parse("https://lens.google.com/__snipsearch_internal_blob__/abc.webp")))
        assertTrue(UploadOriginPolicy.isInternalBlobUrl(Uri.parse("https://evil.test/__SNIPSEARCH_INTERNAL_BLOB__/abc.webp")))
        assertTrue(UploadOriginPolicy.isInternalBlobUrl(Uri.parse("http://lens.google.com/__snipsearch_internal_blob__/abc.webp")))
        assertFalse(UploadOriginPolicy.isInternalBlobUrl(Uri.parse("https://lens.google.com/v3/upload")))

        val allowed = setOf("https://lens.google.com", "https://www.google.com")
        assertTrue(UploadOriginPolicy.isAllowedMainFrameNavigation(Uri.parse("https://www.google.com/search?q=x"), allowed))
        assertTrue(UploadOriginPolicy.isAllowedMainFrameNavigation(Uri.parse("https://lens.google.com:443/search"), allowed))
        assertFalse(UploadOriginPolicy.isAllowedMainFrameNavigation(Uri.parse("https://accounts.google.com/"), allowed))
        assertFalse(UploadOriginPolicy.isAllowedMainFrameNavigation(Uri.parse("https://lens.google.com/__snipsearch_internal_blob__/x.webp"), allowed))
        assertFalse(UploadOriginPolicy.isAllowedMainFrameNavigation(Uri.parse("http://www.google.com/"), allowed))

        assertTrue(UploadOriginPolicy.isAllowedHttpsSubresource(Uri.parse("https://gstatic.com/asset.js")))
        assertFalse(UploadOriginPolicy.isAllowedHttpsSubresource(Uri.parse("http://gstatic.com/asset.js")))
        assertFalse(UploadOriginPolicy.isAllowedHttpsSubresource(Uri.parse("file:///etc/passwd")))
        assertFalse(UploadOriginPolicy.isAllowedHttpsSubresource(Uri.parse("https://gstatic.com/__snipsearch_internal_blob__/x.webp")))
    }

    @Test
    fun exactEndpointPostRequiresMainFramePostAndCanonicalEndpointMatch() {
        val canonical = "https://lens.google.com/v3/upload?hl=en"
        assertTrue(
            UploadOriginPolicy.isExactEndpointPost(
                request("https://lens.google.com:443/v3/upload?hl=en", method = "POST", mainFrame = true),
                canonical,
            ),
        )
        assertFalse(
            UploadOriginPolicy.isExactEndpointPost(
                request("https://lens.google.com/v3/upload?hl=en", method = "GET", mainFrame = true),
                canonical,
            ),
        )
        assertFalse(
            UploadOriginPolicy.isExactEndpointPost(
                request("https://lens.google.com/v3/upload?hl=en", method = "POST", mainFrame = false),
                canonical,
            ),
        )
        assertFalse(
            UploadOriginPolicy.isExactEndpointPost(
                request("https://lens.google.com/v3/upload?hl=fr", method = "POST", mainFrame = true),
                canonical,
            ),
        )
        assertFalse(
            UploadOriginPolicy.isExactEndpointPost(
                request("https://www.google.com/v3/upload?hl=en", method = "POST", mainFrame = true),
                canonical,
            ),
        )
    }

    private fun request(
        url: String,
        method: String,
        mainFrame: Boolean,
    ): WebResourceRequest = object : WebResourceRequest {
        override fun getUrl(): Uri = Uri.parse(url)
        override fun isForMainFrame(): Boolean = mainFrame
        override fun isRedirect(): Boolean = false
        override fun hasGesture(): Boolean = false
        override fun getMethod(): String = method
        override fun getRequestHeaders(): Map<String, String> = emptyMap()
    }
}
