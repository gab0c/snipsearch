package com.snipsearch.search

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import com.snipsearch.security.MemoryScrubber
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.SecureRandom
import java.util.Locale

internal data class RegisteredUploadBlob(
    val submissionId: Long,
    val token: String,
    val expectedOrigin: String,
    val blobPath: String,
    val blobUrl: String,
    val format: UploadImageFormat,
    val byteCount: Int,
)

internal sealed interface BlobInterceptOutcome {
    data object NotBlobRequest : BlobInterceptOutcome
    data class Blocked(val response: WebResourceResponse) : BlobInterceptOutcome
    data class Served(val submissionId: Long, val response: WebResourceResponse) : BlobInterceptOutcome
}

/**
 * Single-use, RAM-only store for encoded cropped upload payloads.
 *
 * Security properties:
 * - Generates 256-bit random hex tokens bound to an exact canonical HTTPS origin and path.
 * - Any request targeting the internal blob path marker returns either a one-time 200 stream
 *   or an immediate synthetic 403 response; it never falls through (`return null`) to network.
 * - Reading a stream to EOF, calling [close], registering a replacement blob, or calling
 *   [scrubAll] immediately zeroes the underlying byte buffer in RAM.
 */
internal class UploadBlobStore(
    private val random: SecureRandom = SecureRandom(),
) {
    private val lock = Any()
    private var activeEntry: ActiveBlob? = null
    private val openStreams = LinkedHashSet<ZeroingBlobInputStream>()

    fun register(
        submissionId: Long,
        expectedOrigin: String,
        format: UploadImageFormat,
        ownedBytes: ByteArray,
    ): RegisteredUploadBlob {
        val token = generateTokenHex()
        val blobPath = "$UPLOAD_INTERNAL_BLOB_PATH_PREFIX$token.${format.fileExtension}"
        val blobUrl = "$expectedOrigin$blobPath"

        val previousEntry: ActiveBlob?
        val streamsToClose: List<ZeroingBlobInputStream>
        synchronized(lock) {
            previousEntry = activeEntry
            activeEntry = ActiveBlob(
                submissionId = submissionId,
                expectedOrigin = expectedOrigin,
                blobPath = blobPath,
                format = format,
                bytes = ownedBytes,
            )
            streamsToClose = openStreams.toList()
            openStreams.clear()
        }
        MemoryScrubber.zero(previousEntry?.bytes)
        for (stream in streamsToClose) {
            stream.close()
        }

        return RegisteredUploadBlob(
            submissionId = submissionId,
            token = token,
            expectedOrigin = expectedOrigin,
            blobPath = blobPath,
            blobUrl = blobUrl,
            format = format,
            byteCount = ownedBytes.size,
        )
    }

    fun intercept(request: WebResourceRequest?): BlobInterceptOutcome {
        if (request == null || !UploadOriginPolicy.isInternalBlobUrl(request.url)) {
            return BlobInterceptOutcome.NotBlobRequest
        }
        if (request.isForMainFrame || request.method != "GET") {
            return BlobInterceptOutcome.Blocked(blockedBlobResponse())
        }
        val parsedUri = UploadOriginPolicy.parseInternalBlobRequestUri(request.url)
            ?: return BlobInterceptOutcome.Blocked(blockedBlobResponse())
        if (!hasSafeBlobRequestHeaders(request.requestHeaders, parsedUri.canonicalOrigin)) {
            return BlobInterceptOutcome.Blocked(blockedBlobResponse())
        }

        val claimed: ActiveBlob
        val stream: ZeroingBlobInputStream
        synchronized(lock) {
            val current = activeEntry
            if (current == null ||
                current.expectedOrigin != parsedUri.canonicalOrigin ||
                current.blobPath != parsedUri.rawPath
            ) {
                return BlobInterceptOutcome.Blocked(blockedBlobResponse())
            }
            activeEntry = null
            claimed = current
            lateinit var createdStream: ZeroingBlobInputStream
            createdStream = ZeroingBlobInputStream(claimed.bytes) { released ->
                synchronized(lock) {
                    openStreams.remove(released)
                }
            }
            stream = createdStream
            openStreams.add(stream)
        }

        return BlobInterceptOutcome.Served(
            submissionId = claimed.submissionId,
            response = okBlobResponse(claimed.format.mimeType, claimed.bytes.size, stream),
        )
    }

    /** Zeroes any pending unclaimed blob while leaving active in-flight streams untouched. */
    fun scrubPendingOnly() {
        val pending = synchronized(lock) {
            val current = activeEntry
            activeEntry = null
            current
        }
        MemoryScrubber.zero(pending?.bytes)
    }

    /** Zeroes both pending blobs and any open streams immediately (used on cancel/close/error). */
    fun scrubAll() {
        val pending: ActiveBlob?
        val streamsToClose: List<ZeroingBlobInputStream>
        synchronized(lock) {
            pending = activeEntry
            activeEntry = null
            streamsToClose = openStreams.toList()
            openStreams.clear()
        }
        MemoryScrubber.zero(pending?.bytes)
        for (stream in streamsToClose) {
            stream.close()
        }
    }

    fun hasActiveBlob(): Boolean = synchronized(lock) { activeEntry != null }

    fun activeStreamCount(): Int = synchronized(lock) { openStreams.size }

    private fun hasSafeBlobRequestHeaders(
        rawHeaders: Map<String, String>?,
        expectedOrigin: String,
    ): Boolean {
        if (rawHeaders.isNullOrEmpty()) return true
        for ((key, value) in rawHeaders) {
            when (key.lowercase(Locale.ROOT)) {
                "origin" -> {
                    val canonicalHeaderOrigin = UploadOriginPolicy.canonicalizeOrigin(value.trim())
                    if (canonicalHeaderOrigin != expectedOrigin) return false
                }
                "sec-fetch-site" -> {
                    val site = value.trim().lowercase(Locale.ROOT)
                    if (site != "same-origin" && site != "none") return false
                }
                "sec-fetch-dest" -> {
                    if (value.trim().lowercase(Locale.ROOT) != "empty") return false
                }
            }
        }
        return true
    }

    private fun generateTokenHex(): String {
        val raw = ByteArray(TOKEN_BYTE_LENGTH)
        return try {
            random.nextBytes(raw)
            buildString(raw.size * 2) {
                for (b in raw) {
                    val unsigned = b.toInt() and 0xFF
                    append(HEX_CHARS[unsigned ushr 4])
                    append(HEX_CHARS[unsigned and 0x0F])
                }
            }
        } finally {
            MemoryScrubber.zero(raw)
        }
    }

    private fun okBlobResponse(
        mimeType: String,
        contentLength: Int,
        stream: InputStream,
    ): WebResourceResponse = WebResourceResponse(
        mimeType,
        null,
        200,
        "OK",
        mapOf(
            "Content-Length" to contentLength.toString(),
            "Cache-Control" to "no-store, no-cache, must-revalidate, max-age=0",
            "Pragma" to "no-cache",
            "Expires" to "0",
            "X-Content-Type-Options" to "nosniff",
            "Cross-Origin-Resource-Policy" to "same-origin",
        ),
        stream,
    )

    internal fun blockedBlobResponse(): WebResourceResponse = WebResourceResponse(
        "text/plain",
        "UTF-8",
        403,
        "Forbidden",
        mapOf(
            "Cache-Control" to "no-store, no-cache, must-revalidate, max-age=0",
            "X-Content-Type-Options" to "nosniff",
        ),
        ByteArrayInputStream(ByteArray(0)),
    )

    private class ActiveBlob(
        val submissionId: Long,
        val expectedOrigin: String,
        val blobPath: String,
        val format: UploadImageFormat,
        val bytes: ByteArray,
    )

    private class ZeroingBlobInputStream(
        initialBytes: ByteArray,
        private val onReleased: (ZeroingBlobInputStream) -> Unit,
    ) : InputStream() {
        private val lock = Any()
        private var data: ByteArray? = initialBytes
        private var position = 0
        private var closed = false

        override fun read(): Int {
            var notifyRelease = false
            val value = synchronized(lock) {
                val buf = data
                if (closed || buf == null || position >= buf.size) {
                    notifyRelease = scrubLocked()
                    -1
                } else {
                    val result = buf[position++].toInt() and 0xFF
                    if (position >= buf.size) {
                        notifyRelease = scrubLocked()
                    }
                    result
                }
            }
            if (notifyRelease) onReleased(this)
            return value
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (off < 0 || len < 0 || off > b.size - len) {
                throw IndexOutOfBoundsException("Invalid slice off=$off len=$len size=${b.size}")
            }
            var notifyRelease = false
            val readCount = synchronized(lock) {
                val buf = data
                if (closed || buf == null) {
                    return@synchronized -1
                }
                if (len == 0) {
                    return@synchronized 0
                }
                if (position >= buf.size) {
                    notifyRelease = scrubLocked()
                    return@synchronized -1
                }
                val count = minOf(len, buf.size - position)
                System.arraycopy(buf, position, b, off, count)
                position += count
                if (position >= buf.size) {
                    notifyRelease = scrubLocked()
                }
                count
            }
            if (notifyRelease) onReleased(this)
            return readCount
        }

        override fun available(): Int = synchronized(lock) {
            val buf = data
            if (closed || buf == null) 0 else (buf.size - position).coerceAtLeast(0)
        }

        override fun close() {
            val notifyRelease = synchronized(lock) {
                scrubLocked()
            }
            if (notifyRelease) onReleased(this)
        }

        private fun scrubLocked(): Boolean {
            if (closed && data == null) return false
            closed = true
            val current = data
            data = null
            MemoryScrubber.zero(current)
            return true
        }
    }

    companion object {
        private const val TOKEN_BYTE_LENGTH = 32
        private val HEX_CHARS = "0123456789abcdef".toCharArray()
    }
}
