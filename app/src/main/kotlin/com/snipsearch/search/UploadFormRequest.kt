package com.snipsearch.search

/** Supported in-memory upload image formats re-encoded without EXIF or device metadata. */
enum class UploadImageFormat(
    val mimeType: String,
    val fileExtension: String,
    val uploadFilename: String,
) {
    WEBP_LOSSY(
        mimeType = "image/webp",
        fileExtension = "webp",
        uploadFilename = "crop.webp",
    ),
    WEBP_LOSSLESS(
        mimeType = "image/webp",
        fileExtension = "webp",
        uploadFilename = "crop.webp",
    ),
    PNG(
        mimeType = "image/png",
        fileExtension = "png",
        uploadFilename = "crop.png",
    ),
}

/** Terminal or recoverable failure reasons emitted by [WebViewFormUploadBridge]. */
enum class UploadFailureReason {
    OFFLINE,
    CLOSED,
    INVALID_REQUEST,
    INVALID_IMAGE,
    IMAGE_ENCODE_FAILED,
    WEBVIEW_UNAVAILABLE,
    LOAD_ERROR,
    SUBMISSION_CANCELLED,
}

/** Observable lifecycle state of [WebViewFormUploadBridge]. */
sealed interface UploadBridgeState {
    data object Idle : UploadBridgeState

    data class BootstrapPending(
        val submissionId: Long,
        val originUrl: String,
        val endpointUrl: String,
    ) : UploadBridgeState

    data class Submitted(
        val submissionId: Long,
        val originUrl: String,
        val endpointUrl: String,
    ) : UploadBridgeState

    data class Failed(val reason: UploadFailureReason) : UploadBridgeState

    data object Closed : UploadBridgeState
}

/** Immediate outcome returned by [WebViewFormUploadBridge.submit]. */
sealed interface UploadSubmissionResult {
    data class Dispatched(
        val submissionId: Long,
        val originUrl: String,
        val endpointUrl: String,
        val encodedByteCount: Int,
    ) : UploadSubmissionResult

    data class Failed(val reason: UploadFailureReason) : UploadSubmissionResult
}

/**
 * Explicit visual multipart form upload request accepted by [WebViewFormUploadBridge].
 *
 * Security boundary:
 * - [originUrl] and [uploadEndpointUrl] must share the exact same canonical HTTPS origin.
 * - [explicitMultimodalQuery] must only hold explicit user-entered follow-up text; never populate
 *   it automatically from OCR or view-hierarchy extraction results.
 */
data class UploadFormRequest(
    val originUrl: String,
    val uploadEndpointUrl: String,
    val fileFormFieldName: String,
    val extraFormFields: Map<String, String> = emptyMap(),
    val multimodalQueryFieldName: String? = null,
    val explicitMultimodalQuery: String? = null,
    val allowedNavigationOrigins: Set<String> = emptySet(),
    val imageFormat: UploadImageFormat = UploadImageFormat.WEBP_LOSSY,
    val imageQuality: Int = DEFAULT_IMAGE_QUALITY,
) {
    internal fun validate(): ValidatedUploadRequest? {
        if (imageQuality !in 0..100) return null
        val canonicalOrigin = UploadOriginPolicy.canonicalizeOrigin(originUrl) ?: return null
        val canonicalEndpoint = UploadOriginPolicy.canonicalizeEndpoint(
            endpointUrl = uploadEndpointUrl,
            expectedCanonicalOrigin = canonicalOrigin,
        ) ?: return null
        if (!isValidFieldName(fileFormFieldName)) return null
        if (extraFormFields.size > MAX_EXTRA_FIELDS) return null

        val validatedExtras = LinkedHashMap<String, String>(extraFormFields.size)
        for ((name, value) in extraFormFields) {
            if (!isValidFieldName(name) || name == fileFormFieldName) return null
            if (!isValidFieldValue(value)) return null
            validatedExtras[name] = value
        }

        val queryField = multimodalQueryFieldName
        if (queryField != null) {
            if (!isValidFieldName(queryField)) return null
            if (queryField == fileFormFieldName || queryField in validatedExtras) return null
        }

        val trimmedQuery = explicitMultimodalQuery?.trim()?.takeIf { it.isNotEmpty() }
        if (trimmedQuery != null) {
            if (queryField == null || !isValidFieldValue(trimmedQuery)) return null
        }

        val effectiveOrigins = LinkedHashSet<String>(allowedNavigationOrigins.size + 1)
        effectiveOrigins.add(canonicalOrigin)
        for (rawAllowedOrigin in allowedNavigationOrigins) {
            val canonicalAllowed = UploadOriginPolicy.canonicalizeOrigin(rawAllowedOrigin) ?: return null
            effectiveOrigins.add(canonicalAllowed)
        }

        return ValidatedUploadRequest(
            canonicalOrigin = canonicalOrigin,
            canonicalEndpointUrl = canonicalEndpoint,
            fileFormFieldName = fileFormFieldName,
            extraFormFields = validatedExtras,
            multimodalQueryFieldName = queryField,
            explicitMultimodalQuery = trimmedQuery,
            effectiveAllowedOrigins = effectiveOrigins,
            imageFormat = imageFormat,
            imageQuality = imageQuality,
        )
    }

    private fun isValidFieldName(name: String): Boolean {
        if (name.isBlank() || name.length > MAX_FIELD_NAME_LENGTH) return false
        return name.none { it == '\u0000' || it == '\r' || it == '\n' }
    }

    private fun isValidFieldValue(value: String): Boolean {
        if (value.length > MAX_FIELD_VALUE_LENGTH) return false
        return '\u0000' !in value
    }

    companion object {
        const val DEFAULT_IMAGE_QUALITY = 85
        private const val MAX_EXTRA_FIELDS = 64
        private const val MAX_FIELD_NAME_LENGTH = 128
        private const val MAX_FIELD_VALUE_LENGTH = 4096
    }
}

internal data class ValidatedUploadRequest(
    val canonicalOrigin: String,
    val canonicalEndpointUrl: String,
    val fileFormFieldName: String,
    val extraFormFields: Map<String, String>,
    val multimodalQueryFieldName: String?,
    val explicitMultimodalQuery: String?,
    val effectiveAllowedOrigins: Set<String>,
    val imageFormat: UploadImageFormat,
    val imageQuality: Int,
)
