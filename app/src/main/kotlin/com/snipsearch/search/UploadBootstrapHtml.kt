package com.snipsearch.search

import java.security.SecureRandom

/**
 * Generates the ephemeral in-memory HTML form bootstrap loaded via `loadDataWithBaseURL`.
 *
 * Security properties:
 * - Strictly escapes all endpoint URLs, form field names, and field values as HTML attributes;
 *   no caller-supplied or user-supplied strings are interpolated into `<script>`.
 * - Enforces a per-invocation CSP nonce (`default-src 'none'`), exact-origin `connect-src`,
 *   allowed-origin `form-action`, and `base-uri 'none'`.
 * - Calls `HTMLFormElement.prototype.submit.call(form)` so extra form fields named `submit`
 *   cannot clobber form submission.
 */
internal object UploadBootstrapHtml {
    private const val NONCE_BYTES = 16
    private val HEX_CHARS = "0123456789abcdef".toCharArray()

    fun generate(
        request: ValidatedUploadRequest,
        blob: RegisteredUploadBlob,
        random: SecureRandom = SecureRandom(),
    ): String {
        val nonce = generateNonce(random)
        val allowedFormOrigins = request.effectiveAllowedOrigins.joinToString(" ")
        val csp = "default-src 'none'; script-src 'nonce-$nonce'; " +
            "connect-src ${request.canonicalOrigin}; form-action $allowedFormOrigins; base-uri 'none'"

        return buildString(1024) {
            append("<!DOCTYPE html><html lang=\"en\"><head>")
            append("<meta charset=\"utf-8\">")
            append("<meta name=\"referrer\" content=\"same-origin\">")
            append("<meta http-equiv=\"Content-Security-Policy\" content=\"")
            append(escapeHtmlAttribute(csp))
            append("\"></head><body>")
            append("<form id=\"snipsearch-upload-form\" hidden method=\"POST\" ")
            append("enctype=\"multipart/form-data\" accept-charset=\"UTF-8\" action=\"")
            append(escapeHtmlAttribute(request.canonicalEndpointUrl))
            append("\">")
            append("<input id=\"snipsearch-upload-file\" type=\"file\" name=\"")
            append(escapeHtmlAttribute(request.fileFormFieldName))
            append("\">")
            for ((name, value) in request.extraFormFields) {
                appendHiddenInput(name, value)
            }
            val queryField = request.multimodalQueryFieldName
            val explicitQuery = request.explicitMultimodalQuery
            if (queryField != null && explicitQuery != null) {
                appendHiddenInput(queryField, explicitQuery)
            }
            append("</form>")
            append("<script nonce=\"").append(nonce).append("\">")
            append("(function(){")
            append("var f=document.getElementById(\"snipsearch-upload-form\");")
            append("var i=document.getElementById(\"snipsearch-upload-file\");")
            append("if(!f||!i){return;}")
            append("fetch(\"").append(escapeJsString(blob.blobUrl))
            append("\",{method:\"GET\",credentials:\"omit\",cache:\"no-store\",mode:\"same-origin\"})")
            append(".then(function(r){if(!r||!r.ok){throw new Error(\"b\");}return r.blob();})")
            append(".then(function(b){if(!b||b.size<=0){throw new Error(\"e\");}")
            append("var d=new DataTransfer();")
            append("d.items.add(new File([b],\"")
            append(escapeJsString(blob.format.uploadFilename))
            append("\",{type:\"")
            append(escapeJsString(blob.format.mimeType))
            append("\"}));")
            append("i.files=d.files;")
            append("if(!i.files||i.files.length!==1){throw new Error(\"a\");}")
            append("HTMLFormElement.prototype.submit.call(f);")
            append("}).catch(function(){});")
            append("})();")
            append("</script></body></html>")
        }
    }

    internal fun escapeHtmlAttribute(value: String): String = buildString(value.length) {
        for (ch in value) {
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                '\r' -> append("&#13;")
                '\n' -> append("&#10;")
                '\t' -> append("&#9;")
                else -> append(ch)
            }
        }
    }

    internal fun escapeJsString(value: String): String = buildString(value.length) {
        for (ch in value) {
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\'' -> append("\\'")
                '/' -> append("\\/")
                '<' -> append("\\u003c")
                '>' -> append("\\u003e")
                '&' -> append("\\u0026")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\u2028' -> append("\\u2028")
                '\u2029' -> append("\\u2029")
                else -> {
                    if (ch.code < 0x20 || ch.code == 0x7F) {
                        append("\\u")
                        append(ch.code.toString(16).padStart(4, '0'))
                    } else {
                        append(ch)
                    }
                }
            }
        }
    }

    private fun StringBuilder.appendHiddenInput(name: String, value: String) {
        append("<input type=\"hidden\" name=\"")
        append(escapeHtmlAttribute(name))
        append("\" value=\"")
        append(escapeHtmlAttribute(value))
        append("\">")
    }

    private fun generateNonce(random: SecureRandom): String {
        val raw = ByteArray(NONCE_BYTES)
        random.nextBytes(raw)
        return buildString(raw.size * 2) {
            for (b in raw) {
                val unsigned = b.toInt() and 0xFF
                append(HEX_CHARS[unsigned ushr 4])
                append(HEX_CHARS[unsigned and 0x0F])
            }
        }
    }
}
