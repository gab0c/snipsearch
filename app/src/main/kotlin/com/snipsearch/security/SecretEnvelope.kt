package com.snipsearch.security

import java.util.Base64

internal class SecretEnvelopeParts(
    val version: Byte,
    val iv: ByteArray,
    val ciphertext: ByteArray,
) {
    fun scrub() {
        MemoryScrubber.zero(iv)
        MemoryScrubber.zero(ciphertext)
    }
}

internal object SecretEnvelope {
    const val VERSION_1: Byte = 1
    const val IV_BYTES = 12
    const val TAG_BYTES = 16
    const val TAG_BITS = 128
    const val MAX_SECRET_BYTES = 4096
    const val MAX_ASSOCIATED_DATA_BYTES = 1024
    const val MIN_ENVELOPE_BYTES = 1 + IV_BYTES + TAG_BYTES + 1
    const val MAX_ENVELOPE_BYTES = 1 + IV_BYTES + MAX_SECRET_BYTES + TAG_BYTES
    const val MAX_ENVELOPE_CHARS = ((MAX_ENVELOPE_BYTES + 2) / 3) * 4

    private val DOMAIN = "com.snipsearch.secret".encodeToByteArray()

    fun encode(iv: ByteArray, ciphertext: ByteArray): String {
        require(iv.size == IV_BYTES) { "Invalid IV size" }
        require(ciphertext.size in (TAG_BYTES + 1)..(MAX_SECRET_BYTES + TAG_BYTES)) {
            "Invalid ciphertext size"
        }
        val payload = ByteArray(1 + IV_BYTES + ciphertext.size)
        payload[0] = VERSION_1
        iv.copyInto(payload, destinationOffset = 1)
        ciphertext.copyInto(payload, destinationOffset = 1 + IV_BYTES)
        return try {
            Base64.getEncoder().encodeToString(payload)
        } finally {
            MemoryScrubber.zero(payload)
        }
    }

    fun decode(envelope: String): SecretEnvelopeParts {
        if (envelope.isEmpty()) {
            throw SecretCipherException.MalformedEnvelope("Envelope is empty")
        }
        if (envelope.length > MAX_ENVELOPE_CHARS) {
            throw SecretCipherException.MalformedEnvelope("Envelope exceeds maximum size")
        }
        val decoded = try {
            Base64.getDecoder().decode(envelope)
        } catch (_: IllegalArgumentException) {
            throw SecretCipherException.MalformedEnvelope("Envelope is not valid Base64")
        }
        try {
            if (Base64.getEncoder().encodeToString(decoded) != envelope) {
                throw SecretCipherException.MalformedEnvelope("Envelope is not canonical Base64")
            }
            if (decoded.size < MIN_ENVELOPE_BYTES) {
                throw SecretCipherException.MalformedEnvelope("Envelope is truncated")
            }
            if (decoded.size > MAX_ENVELOPE_BYTES) {
                throw SecretCipherException.MalformedEnvelope("Envelope exceeds maximum size")
            }
            if (decoded[0] != VERSION_1) {
                throw SecretCipherException.MalformedEnvelope("Unsupported envelope version")
            }
            return SecretEnvelopeParts(
                decoded[0],
                decoded.copyOfRange(1, 1 + IV_BYTES),
                decoded.copyOfRange(1 + IV_BYTES, decoded.size),
            )
        } finally {
            MemoryScrubber.zero(decoded)
        }
    }

    fun associatedData(version: Byte, callerAssociatedData: ByteArray): ByteArray {
        val out = ByteArray(1 + DOMAIN.size + 1 + callerAssociatedData.size)
        out[0] = version
        DOMAIN.copyInto(out, destinationOffset = 1)
        out[1 + DOMAIN.size] = 0
        callerAssociatedData.copyInto(out, destinationOffset = 1 + DOMAIN.size + 1)
        return out
    }
}
