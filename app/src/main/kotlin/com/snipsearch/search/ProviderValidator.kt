package com.snipsearch.search

import java.util.Locale

/** Field-level checks for [SearchProviderConfig]. At most one error is returned per field path. */
internal object ProviderValidator {
    /** Maximum display-name length in UTF-16 code units. */
    const val MAX_DISPLAY_NAME = 64

    /** Maximum extra form fields on a browser-form provider. */
    const val MAX_EXTRA_FIELDS = 16

    /** Maximum extra form-field value length. */
    const val MAX_FIELD_VALUE = 512

    /** Maximum model-name length. */
    const val MAX_MODEL_NAME = 128

    /** Maximum system-prompt length. */
    const val MAX_SYSTEM_PROMPT = 4000

    /** Maximum JSON redirect-path length. */
    const val MAX_JSON_PATH = 128

    private val idRegex = Regex("^[a-z0-9](?:[a-z0-9-]{0,30}[a-z0-9])?$")
    private val formName = Regex("^[A-Za-z0-9_.\\[\\]-]{1,64}$")
    private val modelName = Regex("^[A-Za-z0-9._:/@+-]{1,128}$")
    private val jsonPath = Regex("^[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*|\\[[0-9]{1,3}\\])*$")
    private val credentialRef = Regex("^[a-z0-9][a-z0-9._-]{0,63}$")
    private val headerName = Regex("^[A-Za-z0-9-]{1,64}$")
    private val forbiddenHeaders = setOf(
        "host",
        "cookie",
        "cookie2",
        "set-cookie",
        "content-length",
        "content-type",
        "transfer-encoding",
        "connection",
        "keep-alive",
        "upgrade",
        "te",
        "trailer",
        "origin",
        "referer",
        "accept-encoding",
        "user-agent",
        "via",
        "expect",
        "date",
        "dnt",
    )

    /** Returns every field error for [config], in field order. */
    fun validate(config: SearchProviderConfig, allowReservedIds: Boolean = false): List<ProviderConfigError> {
        val errors = mutableListOf<ProviderConfigError>()
        idError(config.id, allowReservedIds)?.let { errors += it }
        displayNameError(config.displayName)?.let { errors += it }
        when (config) {
            is SearchProviderConfig.TextOnlyProvider ->
                SearchQueryBuilder.checkTemplate(config.textSearchUrlTemplate, "textSearchUrlTemplate")?.let { errors += it }
            is SearchProviderConfig.BrowserFormVisualProvider -> validateBrowser(config, errors)
            is SearchProviderConfig.RestVisionProvider -> validateRest(config, errors)
        }
        return errors
    }

    private fun validateBrowser(
        config: SearchProviderConfig.BrowserFormVisualProvider,
        errors: MutableList<ProviderConfigError>,
    ) {
        if (config.uploadEndpointUrl.indexOf('{') >= 0 || config.uploadEndpointUrl.indexOf('}') >= 0) {
            errors += error(ProviderConfigErrorCode.UNSUPPORTED_PLACEHOLDER, "uploadEndpointUrl", "The URL contains an unsupported placeholder.")
        } else {
            ProviderEndpointPolicy.check(config.uploadEndpointUrl, "uploadEndpointUrl", allowQuery = true)?.let { errors += it }
        }
        formFieldName(config.fileFormFieldName, "fileFormFieldName")?.let { errors += it }
        if (config.extraFormFields.size > MAX_EXTRA_FIELDS) {
            errors += error(ProviderConfigErrorCode.TOO_MANY_FIELDS, "extraFormFields", "Too many fields are present.")
        } else {
            for (entry in config.extraFormFields.entries.sortedBy { it.key }) {
                val field = "extraFormFields.${entry.key}"
                val nameError = formFieldName(entry.key, field)
                when {
                    nameError != null -> errors += nameError
                    entry.key == config.fileFormFieldName || entry.key == config.multimodalQueryFieldName ->
                        errors += error(ProviderConfigErrorCode.DUPLICATE_FIELD, field, "The field duplicates another field.")
                    !ProviderEndpointPolicy.isWellFormedUtf16(entry.value) ->
                        errors += error(ProviderConfigErrorCode.MALFORMED_TEXT, field, "The field value is not well-formed.")
                    entry.value.length > MAX_FIELD_VALUE ->
                        errors += error(ProviderConfigErrorCode.VALUE_TOO_LONG, field, "The field value exceeds the maximum length.")
                    entry.value.any { Character.isISOControl(it) } ->
                        errors += error(ProviderConfigErrorCode.INVALID_FIELD_VALUE, field, "The field value is invalid.")
                }
            }
        }
        val multimodal = config.multimodalQueryFieldName
        if (multimodal != null) {
            val nameError = formFieldName(multimodal, "multimodalQueryFieldName")
            when {
                nameError != null -> errors += nameError
                multimodal == config.fileFormFieldName ->
                    errors += error(ProviderConfigErrorCode.DUPLICATE_FIELD, "multimodalQueryFieldName", "The field duplicates another field.")
            }
        }
        val template = config.textSearchUrlTemplate
        if (template != null) {
            SearchQueryBuilder.checkTemplate(template, "textSearchUrlTemplate")?.let { errors += it }
        }
    }

    private fun validateRest(config: SearchProviderConfig.RestVisionProvider, errors: MutableList<ProviderConfigError>) {
        if (config.endpointUrl.indexOf('{') >= 0 || config.endpointUrl.indexOf('}') >= 0) {
            errors += error(ProviderConfigErrorCode.UNSUPPORTED_PLACEHOLDER, "endpointUrl", "The URL contains an unsupported placeholder.")
        } else {
            ProviderEndpointPolicy.check(config.endpointUrl, "endpointUrl", allowQuery = false)?.let { errors += it }
        }
        when (config.requestFormat) {
            VisionRequestFormat.OPENAI_CHAT_COMPLETIONS -> {
                val model = config.modelName
                when {
                    model == null -> errors += error(ProviderConfigErrorCode.MISSING_REQUIRED, "modelName", "A required field is missing.")
                    !modelName.matches(model) -> errors += error(ProviderConfigErrorCode.INVALID_FIELD_VALUE, "modelName", "The field value is invalid.")
                }
            }
            VisionRequestFormat.MULTIPART_REDIRECT_JSON -> {
                if (config.modelName != null) {
                    errors += error(ProviderConfigErrorCode.FIELD_NOT_APPLICABLE, "modelName", "The field does not apply to this provider.")
                }
            }
        }
        when (config.requestFormat) {
            VisionRequestFormat.MULTIPART_REDIRECT_JSON -> {
                if (config.systemPrompt != null) {
                    errors += error(ProviderConfigErrorCode.FIELD_NOT_APPLICABLE, "systemPrompt", "The field does not apply to this provider.")
                }
            }
            VisionRequestFormat.OPENAI_CHAT_COMPLETIONS -> systemPromptError(config.systemPrompt)?.let { errors += it }
        }
        when (config.requestFormat) {
            VisionRequestFormat.OPENAI_CHAT_COMPLETIONS -> {
                if (config.jsonRedirectPath != null) {
                    errors += error(ProviderConfigErrorCode.FIELD_NOT_APPLICABLE, "jsonRedirectPath", "The field does not apply to this provider.")
                }
            }
            VisionRequestFormat.MULTIPART_REDIRECT_JSON -> {
                val path = config.jsonRedirectPath
                when {
                    path == null -> errors += error(ProviderConfigErrorCode.MISSING_REQUIRED, "jsonRedirectPath", "A required field is missing.")
                    path.length > MAX_JSON_PATH || !jsonPath.matches(path) ->
                        errors += error(ProviderConfigErrorCode.INVALID_FIELD_VALUE, "jsonRedirectPath", "The field value is invalid.")
                }
            }
        }
        when (config.requestFormat) {
            VisionRequestFormat.OPENAI_CHAT_COMPLETIONS -> {
                if (config.fileFormFieldName != null) {
                    errors += error(ProviderConfigErrorCode.FIELD_NOT_APPLICABLE, "fileFormFieldName", "The field does not apply to this provider.")
                }
            }
            VisionRequestFormat.MULTIPART_REDIRECT_JSON -> {
                val name = config.fileFormFieldName
                when {
                    name == null -> errors += error(ProviderConfigErrorCode.MISSING_REQUIRED, "fileFormFieldName", "A required field is missing.")
                    else -> formFieldName(name, "fileFormFieldName")?.let { errors += it }
                }
            }
        }
        val credential = config.credential
        if (credential != null) {
            if (!credentialRef.matches(credential.ref)) {
                errors += error(ProviderConfigErrorCode.INVALID_CREDENTIAL_REF, "credential.ref", "The credential reference is invalid.")
            }
            val header = credential.headerName
            if (!headerName.matches(header)) {
                errors += error(ProviderConfigErrorCode.INVALID_FIELD_NAME, "credential.headerName", "The field name is invalid.")
            } else {
                val lower = header.lowercase(Locale.ROOT)
                if (lower in forbiddenHeaders || lower.startsWith("sec-") || lower.startsWith("proxy-")) {
                    errors += error(ProviderConfigErrorCode.FORBIDDEN_HEADER, "credential.headerName", "The header name is forbidden.")
                }
            }
        }
    }

    private fun idError(id: String, allowReservedIds: Boolean): ProviderConfigError? {
        if (!idRegex.matches(id)) {
            return error(ProviderConfigErrorCode.INVALID_ID, "id", "The identifier is invalid.")
        }
        if (!allowReservedIds && id in BuiltInProviders.reservedIds) {
            return error(ProviderConfigErrorCode.RESERVED_ID, "id", "The identifier is reserved.")
        }
        return null
    }

    private fun displayNameError(displayName: String): ProviderConfigError? {
        if (!ProviderEndpointPolicy.isWellFormedUtf16(displayName)) {
            return error(ProviderConfigErrorCode.MALFORMED_TEXT, "displayName", "The display name is not well-formed.")
        }
        val bidi = displayName.any { ch ->
            ch == '\u200E' || ch == '\u200F' || ch in '\u202A'..'\u202E' || ch in '\u2066'..'\u2069'
        }
        if (
            displayName.isBlank() ||
            displayName != displayName.trim() ||
            displayName.length > MAX_DISPLAY_NAME ||
            displayName.any { Character.isISOControl(it) } ||
            bidi
        ) {
            return error(ProviderConfigErrorCode.INVALID_DISPLAY_NAME, "displayName", "The display name is invalid.")
        }
        return null
    }

    private fun systemPromptError(systemPrompt: String?): ProviderConfigError? {
        if (systemPrompt == null) return null
        if (!ProviderEndpointPolicy.isWellFormedUtf16(systemPrompt)) {
            return error(ProviderConfigErrorCode.MALFORMED_TEXT, "systemPrompt", "The field value is not well-formed.")
        }
        if (systemPrompt.isEmpty()) {
            return error(ProviderConfigErrorCode.INVALID_FIELD_VALUE, "systemPrompt", "The field value is invalid.")
        }
        if (systemPrompt.length > MAX_SYSTEM_PROMPT) {
            return error(ProviderConfigErrorCode.VALUE_TOO_LONG, "systemPrompt", "The field value exceeds the maximum length.")
        }
        if (systemPrompt.any { Character.isISOControl(it) && it != '\n' && it != '\t' }) {
            return error(ProviderConfigErrorCode.INVALID_FIELD_VALUE, "systemPrompt", "The field value is invalid.")
        }
        return null
    }

    private fun formFieldName(name: String, field: String): ProviderConfigError? {
        if (!formName.matches(name)) {
            return error(ProviderConfigErrorCode.INVALID_FIELD_NAME, field, "The field name is invalid.")
        }
        if (name.split('[', ']').any { ProviderEndpointPolicy.isSensitiveName(it) }) {
            return error(ProviderConfigErrorCode.PLAINTEXT_CREDENTIAL, field, "A plaintext credential is not allowed.")
        }
        return null
    }

    private fun error(code: ProviderConfigErrorCode, field: String, message: String) =
        ProviderConfigError(code, field, message)
}
