package com.snipsearch.security

import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class SecretEnvelopeTest {
    @Test
    fun encodeAndDecodeRoundTripAndBounds() {
        val iv = ByteArray(SecretEnvelope.IV_BYTES) { (it + 1).toByte() }
        val ciphertext = ByteArray(SecretEnvelope.TAG_BYTES + 1) { (it + 3).toByte() }
        val envelope = SecretEnvelope.encode(iv, ciphertext)
        val parts = SecretEnvelope.decode(envelope)
        assertEquals(SecretEnvelope.VERSION_1, parts.version)
        assertArrayEquals(iv, parts.iv)
        assertArrayEquals(ciphertext, parts.ciphertext)

        val maxPayload = ByteArray(SecretEnvelope.MAX_ENVELOPE_BYTES)
        assertEquals(
            Base64.getEncoder().encodeToString(maxPayload).length,
            SecretEnvelope.MAX_ENVELOPE_CHARS,
        )
        val maxCiphertext = ByteArray(SecretEnvelope.MAX_SECRET_BYTES + SecretEnvelope.TAG_BYTES) { 1 }
        SecretEnvelope.decode(SecretEnvelope.encode(iv, maxCiphertext)).also { decoded ->
            assertEquals(SecretEnvelope.MAX_ENVELOPE_BYTES, 1 + decoded.iv.size + decoded.ciphertext.size)
        }
        val over = ByteArray(SecretEnvelope.MAX_ENVELOPE_BYTES + 1)
        over[0] = SecretEnvelope.VERSION_1
        assertThrows(SecretCipherException.MalformedEnvelope::class.java) {
            SecretEnvelope.decode(Base64.getEncoder().encodeToString(over))
        }
    }

    @Test
    fun associatedDataBindsVersionAndCallerBytes() {
        val first = SecretEnvelope.associatedData(SecretEnvelope.VERSION_1, byteArrayOf(1, 2))
        val second = SecretEnvelope.associatedData(SecretEnvelope.VERSION_1, byteArrayOf(1, 3))
        assertEquals(SecretEnvelope.VERSION_1, first[0])
        assertFalse(first.contentEquals(second))
    }

    @Test
    fun encodeRejectsInvalidSizes() {
        assertThrows(IllegalArgumentException::class.java) {
            SecretEnvelope.encode(ByteArray(11), ByteArray(SecretEnvelope.TAG_BYTES + 1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            SecretEnvelope.encode(ByteArray(SecretEnvelope.IV_BYTES), ByteArray(SecretEnvelope.TAG_BYTES))
        }
    }
}
