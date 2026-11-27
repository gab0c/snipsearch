package com.snipsearch.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class UploadBootstrapHtmlTest {
    @Test
    fun generatesMultipartFormWithCspNonceAndDomClobberSafeSubmit() {
        val request = UploadFormRequest(
            originUrl = "https://lens.google.com",
            uploadEndpointUrl = "https://lens.google.com/v3/upload",
            fileFormFieldName = "encoded_image",
            extraFormFields = mapOf("submit" to "override", "hl" to "en"),
            multimodalQueryFieldName = "q",
            explicitMultimodalQuery = "  find blue sneakers  ",
            allowedNavigationOrigins = setOf("https://www.google.com"),
        ).validate()!!

        val blob = RegisteredUploadBlob(
            submissionId = 1L,
            token = "a".repeat(64),
            expectedOrigin = "https://lens.google.com",
            blobPath = "/${UPLOAD_INTERNAL_BLOB_PATH_SEGMENT}/${"a".repeat(64)}.webp",
            blobUrl = "https://lens.google.com/${UPLOAD_INTERNAL_BLOB_PATH_SEGMENT}/${"a".repeat(64)}.webp",
            format = UploadImageFormat.WEBP_LOSSY,
            byteCount = 42,
        )

        val html1 = UploadBootstrapHtml.generate(request, blob)
        val html2 = UploadBootstrapHtml.generate(request, blob)
        val nonce1 = Regex("""<script nonce="([0-9a-f]{32})">""").find(html1)!!.groupValues[1]
        val nonce2 = Regex("""<script nonce="([0-9a-f]{32})">""").find(html2)!!.groupValues[1]
        assertNotEquals(nonce1, nonce2)

        assertTrue(
            html1.contains(
                "default-src &#39;none&#39;; script-src &#39;nonce-$nonce1&#39;; " +
                    "connect-src https://lens.google.com; " +
                    "form-action https://lens.google.com https://www.google.com; base-uri &#39;none&#39;",
            ),
        )
        assertTrue(html1.contains("""method="POST" enctype="multipart/form-data" accept-charset="UTF-8" action="https://lens.google.com/v3/upload""""))
        assertTrue(html1.contains("""<input id="snipsearch-upload-file" type="file" name="encoded_image">"""))
        assertTrue(html1.contains("""<input type="hidden" name="submit" value="override">"""))
        assertTrue(html1.contains("""<input type="hidden" name="hl" value="en">"""))
        assertTrue(html1.contains("""<input type="hidden" name="q" value="find blue sneakers">"""))
        assertTrue(html1.contains("""HTMLFormElement.prototype.submit.call(f);"""))
        assertTrue(html1.contains("""credentials:"omit",cache:"no-store",mode:"same-origin""""))
    }

    @Test
    fun omitsBlankMultimodalQueryAndEscapesHostileInputsInHtmlAndJs() {
        val hostileName = "\"><script>alert(1)</script><input name='evil' value=\"x&"
        val hostileValue = "${hostileName}\r\n&"
        val request = UploadFormRequest(
            originUrl = "https://search.example.org",
            uploadEndpointUrl = "https://search.example.org/upload?x=a&b=1",
            fileFormFieldName = "file_$hostileName",
            extraFormFields = mapOf("k_$hostileName" to "v_$hostileValue"),
            multimodalQueryFieldName = "q",
            explicitMultimodalQuery = "   ",
        ).validate()!!

        org.junit.Assert.assertNull(
            UploadFormRequest(
                originUrl = "https://search.example.org",
                uploadEndpointUrl = "https://search.example.org/upload",
                fileFormFieldName = "bad\r\nfield",
            ).validate(),
        )

        val blob = RegisteredUploadBlob(
            submissionId = 2L,
            token = "b".repeat(64),
            expectedOrigin = "https://search.example.org",
            blobPath = "/${UPLOAD_INTERNAL_BLOB_PATH_SEGMENT}/${"b".repeat(64)}.png",
            blobUrl = "https://search.example.org/${UPLOAD_INTERNAL_BLOB_PATH_SEGMENT}/${"b".repeat(64)}.png",
            format = UploadImageFormat.PNG,
            byteCount = 12,
        )

        val html = UploadBootstrapHtml.generate(request, blob)
        assertFalse(html.contains("<script>alert(1)"))
        assertFalse(html.contains("name=\"q\""))
        assertTrue(html.contains("""action="https://search.example.org/upload?x=a&amp;b=1""""))
        assertTrue(html.contains("&quot;&gt;&lt;script&gt;alert(1)&lt;/script&gt;"))
        assertTrue(html.contains("&#13;&#10;&amp;"))

        // Config/field values must never enter the inline <script> block.
        val scriptSection = html.substringAfter("<script nonce=")
        assertFalse(scriptSection.contains("alert(1)"))
        assertFalse(scriptSection.contains("evil"))

        assertEquals(
            "a\\\"\\u003c\\/script\\u003e\\u0026\\n\\r\\u2028\\u2029\\u0001",
            UploadBootstrapHtml.escapeJsString("a\"</script>&\n\r\u2028\u2029\u0001"),
        )
    }
}
