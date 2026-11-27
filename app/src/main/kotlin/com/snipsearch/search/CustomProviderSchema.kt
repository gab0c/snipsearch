package com.snipsearch.search

/**
 * Canonical JSON for custom user-defined providers. Built-in ids are reserved and rejected.
 * Output is canonical and deterministic. Decode accepts any member order.
 * `encode(decode(encode(c)))` is byte-identical to `encode(c)`.
 * Credentials serialize only as an opaque `credential.ref`.
 */
internal object CustomProviderSchema {
    /** Schema id for one provider. */
    const val SCHEMA_ID = "com.snipsearch.provider"

    /** Schema id for a provider list. */
    const val LIST_SCHEMA_ID = "com.snipsearch.providers"

    /** Supported schema version. */
    const val VERSION = 1

    /** Maximum providers in one list. */
    const val MAX_PROVIDERS = 32

    private val textKeys = setOf("type", "id", "displayName", "textSearchUrlTemplate")
    private val browserKeys = setOf(
        "type",
        "id",
        "displayName",
        "uploadEndpointUrl",
        "fileFormFieldName",
        "extraFormFields",
        "multimodalQueryFieldName",
        "textSearchUrlTemplate",
    )
    private val restKeys = setOf(
        "type",
        "id",
        "displayName",
        "endpointUrl",
        "requestFormat",
        "modelName",
        "systemPrompt",
        "jsonRedirectPath",
        "fileFormFieldName",
        "credential",
    )
    private val credentialKeys = setOf("ref", "headerName", "scheme")

    /** Encodes one custom provider, or returns validation errors prefixed with `provider.`. */
    fun encode(config: SearchProviderConfig): ProviderResult<String> {
        val errors = ProviderValidator.validate(config).map { it.copy(field = "provider.${it.field}") }
        if (errors.isNotEmpty()) return ProviderResult.Invalid(errors)
        return encoded(envelope(SCHEMA_ID, "provider", providerObject(config)))
    }

    /** Decodes one custom provider. */
    fun decode(json: String): ProviderResult<SearchProviderConfig> {
        val root = parseObject(json).valueOr { return it }
        schemaAndVersion(root, SCHEMA_ID).invalidOrContinue()?.let { return it }
        val envelopeErrors = mutableListOf<ProviderConfigError>()
        for (key in root.members.keys) {
            if (key != "schema" && key != "version" && key != "provider") {
                envelopeErrors += error(ProviderConfigErrorCode.UNKNOWN_FIELD, key.take(64), "The field is unknown.")
            }
        }
        when (val provider = root.members["provider"]) {
            null -> envelopeErrors += error(ProviderConfigErrorCode.MISSING_REQUIRED, "provider", "A required field is missing.")
            !is JsonValue.JObject -> envelopeErrors += error(ProviderConfigErrorCode.TYPE_MISMATCH, "provider", "The JSON type does not match.")
            else -> {
                if (envelopeErrors.isNotEmpty()) return ProviderResult.Invalid(envelopeErrors)
                return decodeProvider(provider, "provider.")
            }
        }
        return ProviderResult.Invalid(envelopeErrors)
    }

    /** Encodes a list of custom providers, preserving order. */
    fun encodeList(configs: List<SearchProviderConfig>): ProviderResult<String> {
        if (configs.size > MAX_PROVIDERS) {
            return ProviderResult.Invalid(
                listOf(error(ProviderConfigErrorCode.TOO_MANY_PROVIDERS, "providers", "Too many providers are present.")),
            )
        }
        val errors = mutableListOf<ProviderConfigError>()
        val seen = mutableSetOf<String>()
        configs.forEachIndexed { index, config ->
            val prefix = "providers[$index]."
            ProviderValidator.validate(config).mapTo(errors) { it.copy(field = prefix + it.field) }
            if (!seen.add(config.id)) {
                errors += error(ProviderConfigErrorCode.DUPLICATE_ID, prefix + "id", "The identifier is duplicated.")
            }
        }
        if (errors.isNotEmpty()) return ProviderResult.Invalid(errors)
        val items = configs.map { providerObject(it) }
        return encoded(envelope(LIST_SCHEMA_ID, "providers", JsonValue.JArray(items)))
    }

    /** Decodes a provider list. An empty list is valid. */
    fun decodeList(json: String): ProviderResult<List<SearchProviderConfig>> {
        val root = parseObject(json).valueOr { return it }
        schemaAndVersion(root, LIST_SCHEMA_ID).invalidOrContinue()?.let { return it }
        val errors = mutableListOf<ProviderConfigError>()
        for (key in root.members.keys) {
            if (key != "schema" && key != "version" && key != "providers") {
                errors += error(ProviderConfigErrorCode.UNKNOWN_FIELD, key.take(64), "The field is unknown.")
            }
        }
        val providers = root.members["providers"]
        if (providers == null) {
            errors += error(ProviderConfigErrorCode.MISSING_REQUIRED, "providers", "A required field is missing.")
            return ProviderResult.Invalid(errors)
        }
        if (providers !is JsonValue.JArray) {
            errors += error(ProviderConfigErrorCode.TYPE_MISMATCH, "providers", "The JSON type does not match.")
            return ProviderResult.Invalid(errors)
        }
        if (errors.isNotEmpty()) return ProviderResult.Invalid(errors)
        if (providers.items.size > MAX_PROVIDERS) {
            return ProviderResult.Invalid(
                listOf(error(ProviderConfigErrorCode.TOO_MANY_PROVIDERS, "providers", "Too many providers are present.")),
            )
        }
        val decoded = mutableListOf<SearchProviderConfig>()
        val seen = mutableSetOf<String>()
        providers.items.forEachIndexed { index, item ->
            if (item !is JsonValue.JObject) {
                errors += error(ProviderConfigErrorCode.TYPE_MISMATCH, "providers[$index]", "The JSON type does not match.")
                return@forEachIndexed
            }
            when (val result = decodeProvider(item, "providers[$index].")) {
                is ProviderResult.Invalid -> errors += result.errors
                is ProviderResult.Valid -> {
                    if (!seen.add(result.value.id)) {
                        errors += error(ProviderConfigErrorCode.DUPLICATE_ID, "providers[$index].id", "The identifier is duplicated.")
                    } else {
                        decoded += result.value
                    }
                }
            }
        }
        return if (errors.isEmpty()) ProviderResult.Valid(decoded) else ProviderResult.Invalid(errors)
    }

    private fun envelope(schema: String, bodyKey: String, body: JsonValue): JsonValue.JObject =
        JsonValue.JObject(
            linkedMapOf(
                "schema" to JsonValue.JString(schema),
                "version" to JsonValue.JInt(VERSION),
                bodyKey to body,
            ),
        )

    private fun encoded(value: JsonValue): ProviderResult<String> {
        val json = StrictJson.write(value)
        return if (json.length > StrictJson.MAX_INPUT_CHARS) {
            ProviderResult.Invalid(
                listOf(error(ProviderConfigErrorCode.INPUT_TOO_LARGE, "$", "The serialized provider configuration exceeds the maximum size.")),
            )
        } else {
            ProviderResult.Valid(json)
        }
    }

    private fun providerObject(config: SearchProviderConfig): JsonValue.JObject {
        val members = LinkedHashMap<String, JsonValue>()
        when (config) {
            is SearchProviderConfig.TextOnlyProvider -> {
                members["type"] = JsonValue.JString("text")
                members["id"] = JsonValue.JString(config.id)
                members["displayName"] = JsonValue.JString(config.displayName)
                members["textSearchUrlTemplate"] = JsonValue.JString(config.textSearchUrlTemplate)
            }
            is SearchProviderConfig.BrowserFormVisualProvider -> {
                members["type"] = JsonValue.JString("browser_form_visual")
                members["id"] = JsonValue.JString(config.id)
                members["displayName"] = JsonValue.JString(config.displayName)
                members["uploadEndpointUrl"] = JsonValue.JString(config.uploadEndpointUrl)
                members["fileFormFieldName"] = JsonValue.JString(config.fileFormFieldName)
                if (config.extraFormFields.isNotEmpty()) {
                    val extra = LinkedHashMap<String, JsonValue>()
                    for (entry in config.extraFormFields.entries.sortedBy { it.key }) {
                        extra[entry.key] = JsonValue.JString(entry.value)
                    }
                    members["extraFormFields"] = JsonValue.JObject(extra)
                }
                config.multimodalQueryFieldName?.let { members["multimodalQueryFieldName"] = JsonValue.JString(it) }
                config.textSearchUrlTemplate?.let { members["textSearchUrlTemplate"] = JsonValue.JString(it) }
            }
            is SearchProviderConfig.RestVisionProvider -> {
                members["type"] = JsonValue.JString("rest_vision")
                members["id"] = JsonValue.JString(config.id)
                members["displayName"] = JsonValue.JString(config.displayName)
                members["endpointUrl"] = JsonValue.JString(config.endpointUrl)
                members["requestFormat"] = JsonValue.JString(
                    when (config.requestFormat) {
                        VisionRequestFormat.OPENAI_CHAT_COMPLETIONS -> "openai_chat_completions"
                        VisionRequestFormat.MULTIPART_REDIRECT_JSON -> "multipart_redirect_json"
                    },
                )
                config.modelName?.let { members["modelName"] = JsonValue.JString(it) }
                config.systemPrompt?.let { members["systemPrompt"] = JsonValue.JString(it) }
                config.jsonRedirectPath?.let { members["jsonRedirectPath"] = JsonValue.JString(it) }
                config.fileFormFieldName?.let { members["fileFormFieldName"] = JsonValue.JString(it) }
                config.credential?.let { binding ->
                    members["credential"] = JsonValue.JObject(
                        linkedMapOf(
                            "ref" to JsonValue.JString(binding.ref),
                            "headerName" to JsonValue.JString(binding.headerName),
                            "scheme" to JsonValue.JString(
                                when (binding.scheme) {
                                    CredentialScheme.BEARER -> "bearer"
                                    CredentialScheme.RAW -> "raw"
                                },
                            ),
                        ),
                    )
                }
            }
        }
        return JsonValue.JObject(members)
    }

    private fun decodeProvider(obj: JsonValue.JObject, prefix: String): ProviderResult<SearchProviderConfig> {
        val typeValue = obj.members["type"]
        when {
            typeValue == null ->
                return ProviderResult.Invalid(listOf(error(ProviderConfigErrorCode.MISSING_REQUIRED, prefix + "type", "A required field is missing.")))
            typeValue !is JsonValue.JString ->
                return ProviderResult.Invalid(listOf(error(ProviderConfigErrorCode.TYPE_MISMATCH, prefix + "type", "The JSON type does not match.")))
            typeValue.value !in setOf("text", "browser_form_visual", "rest_vision") ->
                return ProviderResult.Invalid(listOf(error(ProviderConfigErrorCode.UNKNOWN_PROVIDER_TYPE, prefix + "type", "The provider type is unknown.")))
        }
        val type = typeValue.value
        val allowed = when (type) {
            "text" -> textKeys
            "browser_form_visual" -> browserKeys
            else -> restKeys
        }
        val errors = mutableListOf<ProviderConfigError>()
        for (key in obj.members.keys) {
            if (key !in allowed) {
                val code = if (ProviderEndpointPolicy.isSensitiveName(key)) {
                    ProviderConfigErrorCode.PLAINTEXT_CREDENTIAL
                } else {
                    ProviderConfigErrorCode.UNKNOWN_FIELD
                }
                val message = if (code == ProviderConfigErrorCode.PLAINTEXT_CREDENTIAL) {
                    "A plaintext credential is not allowed."
                } else {
                    "The field is unknown."
                }
                errors += error(code, prefix + key.take(64), message)
            }
        }
        fun takeString(name: String, required: Boolean): String? {
            val value = obj.members[name]
            if (value == null) {
                if (required) errors += error(ProviderConfigErrorCode.MISSING_REQUIRED, prefix + name, "A required field is missing.")
                return null
            }
            if (value !is JsonValue.JString) {
                errors += error(ProviderConfigErrorCode.TYPE_MISMATCH, prefix + name, "The JSON type does not match.")
                return null
            }
            return value.value
        }
        val id = takeString("id", required = true)
        val displayName = takeString("displayName", required = true)
        val config: SearchProviderConfig? = when (type) {
            "text" -> {
                val template = takeString("textSearchUrlTemplate", required = true)
                if (errors.isEmpty() && id != null && displayName != null && template != null) {
                    SearchProviderConfig.TextOnlyProvider(id, displayName, template)
                } else {
                    null
                }
            }
            "browser_form_visual" -> {
                val upload = takeString("uploadEndpointUrl", required = true)
                val file = takeString("fileFormFieldName", required = true)
                val extra = readExtra(obj, prefix, errors)
                val multimodal = takeString("multimodalQueryFieldName", required = false)
                val template = takeString("textSearchUrlTemplate", required = false)
                if (errors.isEmpty() && id != null && displayName != null && upload != null && file != null && extra != null) {
                    SearchProviderConfig.BrowserFormVisualProvider(
                        id = id,
                        displayName = displayName,
                        uploadEndpointUrl = upload,
                        fileFormFieldName = file,
                        extraFormFields = extra,
                        multimodalQueryFieldName = multimodal,
                        textSearchUrlTemplate = template,
                    )
                } else {
                    null
                }
            }
            else -> {
                val endpoint = takeString("endpointUrl", required = true)
                val format = readFormat(obj, prefix, errors)
                val model = takeString("modelName", required = false)
                val prompt = takeString("systemPrompt", required = false)
                val path = takeString("jsonRedirectPath", required = false)
                val file = takeString("fileFormFieldName", required = false)
                val credential = readCredential(obj, prefix, errors)
                if (errors.isEmpty() && id != null && displayName != null && endpoint != null && format != null && credential != CredentialRead.Failed) {
                    SearchProviderConfig.RestVisionProvider(
                        id = id,
                        displayName = displayName,
                        endpointUrl = endpoint,
                        requestFormat = format,
                        modelName = model,
                        systemPrompt = prompt,
                        jsonRedirectPath = path,
                        fileFormFieldName = file,
                        credential = (credential as? CredentialRead.Present)?.binding,
                    )
                } else {
                    null
                }
            }
        }
        if (errors.isNotEmpty() || config == null) return ProviderResult.Invalid(errors.ifEmpty {
            listOf(error(ProviderConfigErrorCode.MALFORMED_JSON, prefix, "Malformed JSON."))
        })
        val validated = ProviderValidator.validate(config).map { it.copy(field = prefix + it.field) }
        return if (validated.isEmpty()) ProviderResult.Valid(config) else ProviderResult.Invalid(validated)
    }

    private fun readExtra(
        obj: JsonValue.JObject,
        prefix: String,
        errors: MutableList<ProviderConfigError>,
    ): Map<String, String>? {
        val value = obj.members["extraFormFields"] ?: return emptyMap()
        if (value !is JsonValue.JObject) {
            errors += error(ProviderConfigErrorCode.TYPE_MISMATCH, prefix + "extraFormFields", "The JSON type does not match.")
            return null
        }
        if (value.members.isEmpty()) {
            errors += error(ProviderConfigErrorCode.INVALID_FIELD_VALUE, prefix + "extraFormFields", "The field value is invalid.")
            return null
        }
        val extra = LinkedHashMap<String, String>()
        var ok = true
        for ((key, member) in value.members) {
            if (member !is JsonValue.JString) {
                errors += error(
                    ProviderConfigErrorCode.TYPE_MISMATCH,
                    prefix + "extraFormFields." + key.take(64),
                    "The JSON type does not match.",
                )
                ok = false
            } else {
                extra[key] = member.value
            }
        }
        return if (ok) extra else null
    }

    private fun readFormat(
        obj: JsonValue.JObject,
        prefix: String,
        errors: MutableList<ProviderConfigError>,
    ): VisionRequestFormat? {
        val value = obj.members["requestFormat"]
        if (value == null) {
            errors += error(ProviderConfigErrorCode.MISSING_REQUIRED, prefix + "requestFormat", "A required field is missing.")
            return null
        }
        if (value !is JsonValue.JString) {
            errors += error(ProviderConfigErrorCode.TYPE_MISMATCH, prefix + "requestFormat", "The JSON type does not match.")
            return null
        }
        return when (value.value) {
            "openai_chat_completions" -> VisionRequestFormat.OPENAI_CHAT_COMPLETIONS
            "multipart_redirect_json" -> VisionRequestFormat.MULTIPART_REDIRECT_JSON
            else -> {
                errors += error(ProviderConfigErrorCode.INVALID_FIELD_VALUE, prefix + "requestFormat", "The field value is invalid.")
                null
            }
        }
    }

    private fun readCredential(
        obj: JsonValue.JObject,
        prefix: String,
        errors: MutableList<ProviderConfigError>,
    ): CredentialRead {
        val value = obj.members["credential"] ?: return CredentialRead.Absent
        if (value !is JsonValue.JObject) {
            errors += error(ProviderConfigErrorCode.TYPE_MISMATCH, prefix + "credential", "The JSON type does not match.")
            return CredentialRead.Failed
        }
        var failed = false
        for (key in value.members.keys) {
            if (key !in credentialKeys) {
                errors += error(
                    ProviderConfigErrorCode.PLAINTEXT_CREDENTIAL,
                    prefix + "credential." + key.take(64),
                    "A plaintext credential is not allowed.",
                )
                failed = true
            }
        }
        val ref = value.members["ref"]
        val header = value.members["headerName"]
        val scheme = value.members["scheme"]
        val refText = when (ref) {
            null -> {
                errors += error(ProviderConfigErrorCode.MISSING_REQUIRED, prefix + "credential.ref", "A required field is missing.")
                failed = true
                null
            }
            !is JsonValue.JString -> {
                errors += error(ProviderConfigErrorCode.TYPE_MISMATCH, prefix + "credential.ref", "The JSON type does not match.")
                failed = true
                null
            }
            else -> ref.value
        }
        val headerText = when (header) {
            null -> "Authorization"
            !is JsonValue.JString -> {
                errors += error(ProviderConfigErrorCode.TYPE_MISMATCH, prefix + "credential.headerName", "The JSON type does not match.")
                failed = true
                null
            }
            else -> header.value
        }
        val schemeValue = when (scheme) {
            null -> CredentialScheme.BEARER
            !is JsonValue.JString -> {
                errors += error(ProviderConfigErrorCode.TYPE_MISMATCH, prefix + "credential.scheme", "The JSON type does not match.")
                failed = true
                null
            }
            else -> when (scheme.value) {
                "bearer" -> CredentialScheme.BEARER
                "raw" -> CredentialScheme.RAW
                else -> {
                    errors += error(ProviderConfigErrorCode.INVALID_FIELD_VALUE, prefix + "credential.scheme", "The field value is invalid.")
                    failed = true
                    null
                }
            }
        }
        if (failed || refText == null || headerText == null || schemeValue == null) return CredentialRead.Failed
        return CredentialRead.Present(CredentialBinding(refText, headerText, schemeValue))
    }

    private fun parseObject(json: String): ProviderResult<JsonValue.JObject> {
        val parsed = try {
            StrictJson.parse(json)
        } catch (exception: StrictJsonException) {
            return ProviderResult.Invalid(
                listOf(error(exception.code, "$", exception.message ?: "Malformed JSON.")),
            )
        }
        if (parsed !is JsonValue.JObject) {
            return ProviderResult.Invalid(
                listOf(error(ProviderConfigErrorCode.TYPE_MISMATCH, "$", "The JSON type does not match.")),
            )
        }
        return ProviderResult.Valid(parsed)
    }

    private fun schemaAndVersion(root: JsonValue.JObject, schemaId: String): ProviderResult<Unit> {
        val errors = mutableListOf<ProviderConfigError>()
        when (val schema = root.members["schema"]) {
            null -> errors += error(ProviderConfigErrorCode.MISSING_REQUIRED, "schema", "A required field is missing.")
            !is JsonValue.JString -> errors += error(ProviderConfigErrorCode.TYPE_MISMATCH, "schema", "The JSON type does not match.")
            else -> if (schema.value != schemaId) {
                errors += error(ProviderConfigErrorCode.UNSUPPORTED_SCHEMA, "schema", "The schema is unsupported.")
            }
        }
        when (val version = root.members["version"]) {
            null -> errors += error(ProviderConfigErrorCode.MISSING_REQUIRED, "version", "A required field is missing.")
            !is JsonValue.JInt -> errors += error(ProviderConfigErrorCode.TYPE_MISMATCH, "version", "The JSON type does not match.")
            else -> if (version.value != VERSION) {
                errors += error(ProviderConfigErrorCode.UNSUPPORTED_VERSION, "version", "The schema version is unsupported.")
            }
        }
        return if (errors.isEmpty()) ProviderResult.Valid(Unit) else ProviderResult.Invalid(errors)
    }

    private fun error(code: ProviderConfigErrorCode, field: String, message: String) =
        ProviderConfigError(code, field, message)

    private sealed interface CredentialRead {
        data object Absent : CredentialRead
        data object Failed : CredentialRead
        data class Present(val binding: CredentialBinding) : CredentialRead
    }

    private inline fun <T> ProviderResult<T>.valueOr(onInvalid: (ProviderResult.Invalid) -> Nothing): T = when (this) {
        is ProviderResult.Valid -> value
        is ProviderResult.Invalid -> onInvalid(this)
    }

    private fun ProviderResult<*>.invalidOrContinue(): ProviderResult.Invalid? = this as? ProviderResult.Invalid
}
