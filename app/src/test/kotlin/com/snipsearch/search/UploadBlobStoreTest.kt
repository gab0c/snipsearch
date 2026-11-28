package com.snipsearch.search

import android.net.Uri
import android.webkit.WebResourceRequest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class UploadBlobStoreTest {
    private val origin = "https://lens.google.com"

    @Test
    fun singleUseBlobServesExactOriginOnceThenBlocksReplayAndZeroesBytesAtEof() {
        val store = UploadBlobStore()
        val payload = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val reg1 = store.register(1L, origin, UploadImageFormat.WEBP_LOSSY, payload)
        val reg2PreviewCheck = UploadBlobStore().register(2L, origin, UploadImageFormat.WEBP_LOSSY, byteArrayOf(9))
        assertEquals(64, reg1.token.length)
        assertTrue(reg1.token.all { it in '0'..'9' || it in 'a'..'f' })
        assertNotEquals(reg1.token, reg2PreviewCheck.token)
        assertTrue(store.hasActiveBlob())

        val validRequest = blobRequest(reg1.blobUrl)
        val served = store.intercept(validRequest) as BlobInterceptOutcome.Served
        assertEquals(1L, served.submissionId)
        assertEquals(200, served.response.statusCode)
        assertEquals("image/webp", served.response.mimeType)
        assertEquals("8", served.response.responseHeaders["Content-Length"])
        assertEquals("same-origin", served.response.responseHeaders["Cross-Origin-Resource-Policy"])
        assertEquals("nosniff", served.response.responseHeaders["X-Content-Type-Options"])
        assertFalse(served.response.responseHeaders.containsKey("Access-Control-Allow-Origin"))
        assertFalse(store.hasActiveBlob())
        assertEquals(1, store.activeStreamCount())

        // Replay must immediately be 403 Blocked, never NotBlobRequest.
        val replay = store.intercept(validRequest) as BlobInterceptOutcome.Blocked
        assertEquals(403, replay.response.statusCode)
        assertEquals(0, replay.response.data.readBytes().size)

        val readBack = served.response.data.readBytes()
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), readBack)
        assertTrue(payload.all { it == 0.toByte() })
        assertEquals(0, store.activeStreamCount())
    }

    @Test
    fun rejectsWrongOriginSchemeMethodFrameQueryTokenAndHeadersWithoutNetworkFallback() {
        val store = UploadBlobStore()
        val payload = byteArrayOf(10, 20, 30, 40)
        val reg = store.register(10L, origin, UploadImageFormat.PNG, payload)

        val invalidRequests = listOf(
            blobRequest("https://evil.test${reg.blobPath}"),
            blobRequest("http://lens.google.com${reg.blobPath}"),
            blobRequest("https://lens.google.com:8443${reg.blobPath}"),
            blobRequest("${reg.blobUrl}?leak=1"),
            blobRequest("${reg.blobUrl}#frag"),
            blobRequest("https://lens.google.com${UPLOAD_INTERNAL_BLOB_PATH_PREFIX}${"0".repeat(64)}.png"),
            blobRequest(reg.blobUrl, method = "POST"),
            blobRequest(reg.blobUrl, method = "HEAD"),
            blobRequest(reg.blobUrl, mainFrame = true),
            blobRequest(reg.blobUrl, headers = mapOf("Origin" to "https://evil.test")),
            blobRequest(reg.blobUrl, headers = mapOf("Origin" to "null")),
            blobRequest(reg.blobUrl, headers = mapOf("Sec-Fetch-Site" to "cross-site")),
            blobRequest(reg.blobUrl, headers = mapOf("Sec-Fetch-Site" to "same-site")),
            blobRequest(reg.blobUrl, headers = mapOf("Sec-Fetch-Dest" to "image")),
            blobRequest(reg.blobUrl, headers = mapOf("Sec-Fetch-Dest" to "document")),
        )
        for (req in invalidRequests) {
            val outcome = store.intercept(req)
            assertTrue("Expected Blocked for ${req.url}", outcome is BlobInterceptOutcome.Blocked)
            val blocked = outcome as BlobInterceptOutcome.Blocked
            assertEquals(403, blocked.response.statusCode)
            assertEquals(0, blocked.response.data.readBytes().size)
        }

        // Token must remain intact for the genuine fetch after invalid probes.
        val valid = store.intercept(
            blobRequest(
                reg.blobUrl,
                headers = mapOf(
                    "Origin" to origin,
                    "Sec-Fetch-Site" to "same-origin",
                    "Sec-Fetch-Dest" to "empty",
                ),
            ),
        )
        assertTrue(valid is BlobInterceptOutcome.Served)
        (valid as BlobInterceptOutcome.Served).response.data.close()
        assertTrue(payload.all { it == 0.toByte() })

        assertTrue(store.intercept(blobRequest("https://lens.google.com/v3/upload")) is BlobInterceptOutcome.NotBlobRequest)
    }

    @Test
    fun scrubAllZeroesUnclaimedMidReadAndClaimedUnreadStreamsAndReplacementZeroesPrior() {
        val store = UploadBlobStore()

        val unclaimed = byteArrayOf(7, 8, 9)
        val reg1 = store.register(1L, origin, UploadImageFormat.WEBP_LOSSY, unclaimed)
        store.scrubAll()
        assertTrue(unclaimed.all { it == 0.toByte() })
        assertTrue(store.intercept(blobRequest(reg1.blobUrl)) is BlobInterceptOutcome.Blocked)

        val midReadBytes = byteArrayOf(11, 22, 33, 44, 55, 66)
        val reg2 = store.register(2L, origin, UploadImageFormat.WEBP_LOSSY, midReadBytes)
        val served2 = store.intercept(blobRequest(reg2.blobUrl)) as BlobInterceptOutcome.Served
        val chunk = ByteArray(2)
        assertEquals(2, served2.response.data.read(chunk))
        assertArrayEquals(byteArrayOf(11, 22), chunk)
        store.scrubAll()
        assertTrue(midReadBytes.all { it == 0.toByte() })
        assertEquals(-1, served2.response.data.read())
        assertEquals(0, store.activeStreamCount())

        val claimedUnread = byteArrayOf(91, 92, 93)
        val reg3 = store.register(3L, origin, UploadImageFormat.WEBP_LOSSY, claimedUnread)
        val served3 = store.intercept(blobRequest(reg3.blobUrl)) as BlobInterceptOutcome.Served
        val replacement = byteArrayOf(100, 101)
        store.register(4L, origin, UploadImageFormat.PNG, replacement)
        assertTrue(claimedUnread.all { it == 0.toByte() })
        assertEquals(-1, served3.response.data.read())
        store.scrubAll()
        assertTrue(replacement.all { it == 0.toByte() })
    }

    @Test
    fun concurrentInterceptAllowsStrictlyOneWinnerAndConcurrentReadScrubNeverThrows() {
        val store = UploadBlobStore()
        val pool = Executors.newFixedThreadPool(8)
        try {
            repeat(25) { round ->
                val payload = ByteArray(256) { ((it + round) and 0x7F).toByte() }
                val reg = store.register(round.toLong(), origin, UploadImageFormat.WEBP_LOSSY, payload)
                val ready = CountDownLatch(8)
                val start = CountDownLatch(1)
                val done = CountDownLatch(8)
                val servedList = Collections.synchronizedList(mutableListOf<BlobInterceptOutcome.Served>())
                val blockedCount = java.util.concurrent.atomic.AtomicInteger(0)

                repeat(8) {
                    pool.execute {
                        ready.countDown()
                        start.await()
                        when (val outcome = store.intercept(blobRequest(reg.blobUrl))) {
                            is BlobInterceptOutcome.Served -> servedList.add(outcome)
                            is BlobInterceptOutcome.Blocked -> blockedCount.incrementAndGet()
                            BlobInterceptOutcome.NotBlobRequest -> Unit
                        }
                        done.countDown()
                    }
                }
                assertTrue(ready.await(5, TimeUnit.SECONDS))
                start.countDown()
                assertTrue(done.await(5, TimeUnit.SECONDS))
                assertEquals(1, servedList.size)
                assertEquals(7, blockedCount.get())

                val stream = servedList.single().response.data
                val readLatch = CountDownLatch(2)
                pool.execute {
                    val buf = ByteArray(16)
                    while (stream.read(buf) >= 0) {
                        // Drain until EOF or scrub interruption.
                    }
                    readLatch.countDown()
                }
                pool.execute {
                    store.scrubAll()
                    readLatch.countDown()
                }
                assertTrue(readLatch.await(5, TimeUnit.SECONDS))
                assertTrue(payload.all { it == 0.toByte() })
            }
        } finally {
            pool.shutdownNow()
        }
    }

    private fun blobRequest(
        url: String,
        method: String = "GET",
        mainFrame: Boolean = false,
        headers: Map<String, String> = emptyMap(),
    ): WebResourceRequest = object : WebResourceRequest {
        override fun getUrl(): Uri = Uri.parse(url)
        override fun isForMainFrame(): Boolean = mainFrame
        override fun isRedirect(): Boolean = false
        override fun hasGesture(): Boolean = false
        override fun getMethod(): String = method
        override fun getRequestHeaders(): Map<String, String> = headers
    }
}
