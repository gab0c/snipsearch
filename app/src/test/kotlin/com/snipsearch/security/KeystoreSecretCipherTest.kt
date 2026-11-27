package com.snipsearch.security

import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import java.security.ProviderException
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import kotlin.concurrent.thread
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode

/**
 * These tests inject a JVM [SecretTestKeyBackend] (SunJCE AES-256). They cannot prove
 * real StrongBox or TEE hardware backing.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class KeystoreSecretCipherTest {
    private val plaintext = "sk-test-0123456789-SECRET".encodeToByteArray()

    @Test
    fun roundTripReturnsIdenticalBytesAndLeavesSecretOpen() {
        val cipher = secretTestCipher()
        cipher.decrypt(cipher.encrypt(plaintext)).use { secret ->
            assertArrayEquals(plaintext, secret.copyOf())
            assertFalse(secret.isClosed)
        }
        val owned = SecretBytes.adopt(plaintext.copyOf())
        val envelope = cipher.encrypt(owned)
        assertFalse(owned.isClosed)
        cipher.decrypt(envelope).use { secret ->
            assertArrayEquals(plaintext, secret.copyOf())
        }
        val closed = cipher.decrypt(cipher.encrypt(plaintext))
        closed.use { assertArrayEquals(plaintext, it.copyOf()) }
        assertTrue(closed.isClosed)
    }

    @Test
    fun freshIvOnEveryEncryption() {
        val cipher = secretTestCipher()
        val envelopes = List(64) { cipher.encrypt(plaintext) }
        assertEquals(64, envelopes.toSet().size)
        val ivs = envelopes.map { envelope ->
            val parts = SecretEnvelope.decode(envelope)
            val raw = Base64.getDecoder().decode(envelope)
            assertEquals(1 + 12 + plaintext.size + 16, raw.size)
            assertEquals(1.toByte(), parts.version)
            assertEquals(12, parts.iv.size)
            parts.iv.copyOf()
        }
        assertEquals(64, ivs.map { it.contentToString() }.toSet().size)
    }

    @Test
    fun tamperRejection() {
        val cipher = secretTestCipher()
        val envelope = cipher.encrypt(plaintext)
        val raw = Base64.getDecoder().decode(envelope)
        for (index in 1 until raw.size) {
            val flipped = raw.copyOf()
            flipped[index] = (flipped[index].toInt() xor 0x01).toByte()
            val tampered = Base64.getEncoder().encodeToString(flipped)
            assertFailsWith<SecretCipherException.AuthenticationFailed> {
                cipher.decrypt(tampered).close()
            }
        }
        val versionFlipped = raw.copyOf()
        versionFlipped[0] = (versionFlipped[0].toInt() xor 0x01).toByte()
        assertFailsWith<SecretCipherException.MalformedEnvelope> {
            cipher.decrypt(Base64.getEncoder().encodeToString(versionFlipped)).close()
        }
    }

    @Test
    fun truncationIsRejected() {
        val cipher = secretTestCipher()
        val raw = Base64.getDecoder().decode(cipher.encrypt(plaintext))
        val trimmed = raw.copyOf(raw.size - 1)
        assertFailsWith<SecretCipherException.AuthenticationFailed> {
            cipher.decrypt(Base64.getEncoder().encodeToString(trimmed)).close()
        }
        val tiny = ByteArray(SecretEnvelope.MIN_ENVELOPE_BYTES - 1) { 1 }
        assertFailsWith<SecretCipherException.MalformedEnvelope> {
            cipher.decrypt(Base64.getEncoder().encodeToString(tiny)).close()
        }
        assertFailsWith<SecretCipherException.MalformedEnvelope> {
            cipher.decrypt("").close()
        }
    }

    @Test
    fun oversizedEnvelopeNeverTouchesKeystore() {
        val backend = SecretTestKeyBackend()
        val cipher = secretTestCipher(backend)
        val oversized = "A".repeat(SecretEnvelope.MAX_ENVELOPE_CHARS + 4)
        assertFailsWith<SecretCipherException.MalformedEnvelope> {
            cipher.decrypt(oversized).close()
        }
        assertEquals(0, backend.loadCount)
    }

    @Test
    fun corruptAndNonCanonicalBase64() {
        val cipher = secretTestCipher()
        val envelope = cipher.encrypt(plaintext)
        val paddedPlaintext = "padded-secret!".encodeToByteArray()
        val paddedEnvelope = cipher.encrypt(paddedPlaintext)
        assertTrue(paddedEnvelope.endsWith("=="))
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val padBitsIndex = paddedEnvelope.length - 3
        val originalChar = paddedEnvelope[padBitsIndex]
        val alphabetIndex = alphabet.indexOf(originalChar)
        assertTrue(alphabetIndex >= 0)
        val nonCanonical = paddedEnvelope.replaceRange(
            padBitsIndex,
            padBitsIndex + 1,
            alphabet[alphabetIndex xor 1].toString(),
        )
        assertFalse(nonCanonical == paddedEnvelope)
        assertFailsWith<SecretCipherException.MalformedEnvelope> { cipher.decrypt("!!!!").close() }
        assertFailsWith<SecretCipherException.MalformedEnvelope> {
            cipher.decrypt(envelope.replaceRange(4, 5, "\n")).close()
        }
        assertFailsWith<SecretCipherException.MalformedEnvelope> {
            cipher.decrypt("$envelope ").close()
        }
        assertFailsWith<SecretCipherException.MalformedEnvelope> {
            cipher.decrypt(paddedEnvelope.trimEnd('=')).close()
        }
        assertFailsWith<SecretCipherException.MalformedEnvelope> {
            cipher.decrypt(nonCanonical).close()
        }
    }

    @Test
    fun associatedDataIsBound() {
        val cipher = secretTestCipher()
        val a = "provider:a".encodeToByteArray()
        val b = "provider:b".encodeToByteArray()
        val envelope = cipher.encrypt(plaintext, a)
        assertFailsWith<SecretCipherException.AuthenticationFailed> {
            cipher.decrypt(envelope, b).close()
        }
        assertFailsWith<SecretCipherException.AuthenticationFailed> {
            cipher.decrypt(envelope).close()
        }
        cipher.decrypt(envelope, a).use { assertArrayEquals(plaintext, it.copyOf()) }
    }

    @Test
    fun wrongKeyFailsAuthentication() {
        val first = secretTestCipher()
        val envelope = first.encrypt(plaintext)
        val otherBackend = SecretTestKeyBackend()
        val other = secretTestCipher(otherBackend)
        other.encrypt("different-secret-value".encodeToByteArray())
        assertFailsWith<SecretCipherException.AuthenticationFailed> {
            other.decrypt(envelope).close()
        }
    }

    @Test
    fun aliasIsReusedAcrossInstances() {
        val backend = SecretTestKeyBackend()
        val first = secretTestCipher(backend)
        val second = secretTestCipher(backend)
        val envelope = first.encrypt(plaintext)
        assertEquals(1, backend.requests.size)
        second.decrypt(envelope).use { assertArrayEquals(plaintext, it.copyOf()) }
        second.encrypt(plaintext)
        assertEquals(1, backend.requests.size)
        assertTrue(backend.keys.containsKey(KeystoreSecretCipher.KEY_ALIAS))
    }

    @Test
    fun decryptNeverGeneratesAKey() {
        val envelope = secretTestCipher().encrypt(plaintext)
        val backend = SecretTestKeyBackend()
        val cipher = secretTestCipher(backend)
        assertFailsWith<SecretCipherException.KeyUnavailable> {
            cipher.decrypt(envelope).close()
        }
        assertTrue(backend.requests.isEmpty())
    }

    @Test
    fun generationRequestsStrongBoxFlags() {
        val backend = SecretTestKeyBackend()
        secretTestCipher(backend, strongBoxAvailable = { true }).encrypt(plaintext)
        assertEquals(1, backend.requests.size)
        val request = backend.requests.single()
        assertEquals(KeystoreSecretCipher.KEY_ALIAS, request.alias)
        assertTrue(request.strongBoxBacked)
        assertEquals(256, request.keySizeBits)
        assertTrue(request.unlockedDeviceRequired)
        assertEquals(KeyProperties.BLOCK_MODE_GCM, request.blockMode)
        assertEquals(KeyProperties.ENCRYPTION_PADDING_NONE, request.encryptionPadding)
        assertEquals(
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            request.purposes,
        )
    }

    @Test
    fun strongBoxFallbackUsesTeeOnce() {
        val backend = SecretTestKeyBackend().apply { strongBoxUnavailable = true }
        val cipher = secretTestCipher(backend, strongBoxAvailable = { true })
        cipher.decrypt(cipher.encrypt(plaintext)).use { assertArrayEquals(plaintext, it.copyOf()) }
        assertEquals(listOf(true, false), backend.requests.map { it.strongBoxBacked })
    }

    @Test
    fun missingStrongBoxFeatureSkipsStrongBox() {
        val backend = SecretTestKeyBackend()
        secretTestCipher(backend, strongBoxAvailable = { false }).encrypt(plaintext)
        assertEquals(listOf(false), backend.requests.map { it.strongBoxBacked })
    }

    @Test
    fun noFallbackLoopWhenTeeGenerationFails() {
        val withoutFeature = SecretTestKeyBackend().apply { unavailableEvenWithoutStrongBox = true }
        assertFailsWith<SecretCipherException.KeystoreFailure> {
            secretTestCipher(withoutFeature, strongBoxAvailable = { false }).encrypt(plaintext)
        }
        assertEquals(1, withoutFeature.requests.size)

        val teeFails = SecretTestKeyBackend().apply {
            strongBoxUnavailable = true
            generationFailure = ProviderException("tee fail")
        }
        assertFailsWith<SecretCipherException.KeystoreFailure> {
            secretTestCipher(teeFails, strongBoxAvailable = { true }).encrypt(plaintext)
        }
        assertEquals(2, teeFails.requests.size)
    }

    @Test
    fun hardwareBackingIsEnforcedWithoutDeletingTheKey() {
        val software = SecretTestKeyBackend().apply { protectionOverride = SecretKeyProtection.SOFTWARE }
        val softwareCipher = secretTestCipher(software)
        repeat(2) {
            val error = assertFailsWith<SecretCipherException.HardwareBackingUnavailable> {
                softwareCipher.encrypt(plaintext)
            }
            assertEquals(SecretKeyProtection.SOFTWARE, error.protection)
        }
        assertEquals(1, software.requests.size)
        assertTrue(software.keys.containsKey(KeystoreSecretCipher.KEY_ALIAS))

        val unknown = SecretTestKeyBackend().apply { protectionOverride = SecretKeyProtection.UNKNOWN }
        val unknownCipher = secretTestCipher(unknown)
        val unknownError = assertFailsWith<SecretCipherException.HardwareBackingUnavailable> {
            unknownCipher.encrypt(plaintext)
        }
        assertEquals(SecretKeyProtection.UNKNOWN, unknownError.protection)

        val secure = SecretTestKeyBackend().apply {
            protectionOverride = SecretKeyProtection.UNKNOWN_SECURE
        }
        secretTestCipher(secure).decrypt(secretTestCipher(secure).encrypt(plaintext)).use {
            assertArrayEquals(plaintext, it.copyOf())
        }
    }

    @Test
    fun failureMappingDoesNotDeleteTheKey() {
        val backend = SecretTestKeyBackend()
        var mode = "ok"
        val cipher = secretTestCipher(backend, cipherFactory = {
            when (mode) {
                "locked" -> throw UserNotAuthenticatedException()
                "invalidated" -> throw KeyPermanentlyInvalidatedException()
                else -> Cipher.getInstance(KeystoreSecretCipher.TRANSFORMATION)
            }
        })
        val envelope = cipher.encrypt(plaintext)
        mode = "locked"
        assertFailsWith<SecretCipherException.DeviceLocked> { cipher.encrypt(plaintext) }
        assertFailsWith<SecretCipherException.DeviceLocked> { cipher.decrypt(envelope).close() }
        mode = "invalidated"
        assertFailsWith<SecretCipherException.KeyUnavailable> { cipher.encrypt(plaintext) }
        assertFailsWith<SecretCipherException.KeyUnavailable> { cipher.decrypt(envelope).close() }
        mode = "ok"
        backend.loadFailure = java.security.KeyStoreException("store error")
        assertFailsWith<SecretCipherException.KeystoreFailure> { cipher.decrypt(envelope).close() }
        assertTrue(backend.keys.containsKey(KeystoreSecretCipher.KEY_ALIAS))
    }

    @Test
    fun inputBounds() {
        val cipher = secretTestCipher()
        assertFailsWith<SecretCipherException.InvalidInput> { cipher.encrypt(ByteArray(0)) }
        assertFailsWith<SecretCipherException.InvalidInput> {
            cipher.encrypt(ByteArray(SecretEnvelope.MAX_SECRET_BYTES + 1) { 7 })
        }
        val max = ByteArray(SecretEnvelope.MAX_SECRET_BYTES) { (it and 0xff).toByte() }
        cipher.decrypt(cipher.encrypt(max)).use { assertArrayEquals(max, it.copyOf()) }
        val bigAad = ByteArray(SecretEnvelope.MAX_ASSOCIATED_DATA_BYTES + 1)
        assertFailsWith<SecretCipherException.InvalidInput> { cipher.encrypt(plaintext, bigAad) }
        val envelope = cipher.encrypt(plaintext)
        assertFailsWith<SecretCipherException.InvalidInput> { cipher.decrypt(envelope, bigAad).close() }
    }

    @Test
    fun callerPlaintextIsUntouched() {
        val cipher = secretTestCipher()
        val original = plaintext.copyOf()
        cipher.encrypt(original)
        assertArrayEquals(plaintext, original)
    }

    @Test
    fun exceptionsAndStringsDoNotLeakSecrets() {
        val cipher = secretTestCipher()
        val envelope = cipher.encrypt(plaintext)
        val plainText = plaintext.decodeToString()
        val plainBase64 = Base64.getEncoder().encodeToString(plaintext)
        assertFalse(envelope.contains(plainText))
        assertFalse(envelope.contains(plainBase64))
        val raw = Base64.getDecoder().decode(envelope)
        raw[raw.lastIndex] = (raw[raw.lastIndex].toInt() xor 0x01).toByte()
        val tampered = Base64.getEncoder().encodeToString(raw)
        val locked = secretTestCipher(cipherFactory = {
            throw UserNotAuthenticatedException()
        })
        val missing = secretTestCipher(SecretTestKeyBackend())
        val failing = secretTestCipher(SecretTestKeyBackend().apply {
            loadFailure = java.security.KeyStoreException("store error")
        })
        val software = secretTestCipher(
            SecretTestKeyBackend().apply { protectionOverride = SecretKeyProtection.SOFTWARE },
        )
        val failures = listOf(
            assertFailsWith<SecretCipherException.AuthenticationFailed> {
                cipher.decrypt(tampered).close()
            },
            assertFailsWith<SecretCipherException.MalformedEnvelope> {
                cipher.decrypt("!!!!").close()
            },
            assertFailsWith<SecretCipherException.DeviceLocked> {
                locked.encrypt(plaintext)
            },
            assertFailsWith<SecretCipherException.KeyUnavailable> {
                missing.decrypt(envelope).close()
            },
            assertFailsWith<SecretCipherException.KeystoreFailure> {
                failing.decrypt(envelope).close()
            },
            assertFailsWith<SecretCipherException.HardwareBackingUnavailable> {
                software.encrypt(plaintext)
            },
            assertFailsWith<SecretCipherException.InvalidInput> {
                cipher.encrypt(ByteArray(0))
            },
        )
        failures.forEach { error ->
            val trace = error.stackTraceToString()
            assertFalse(trace.contains(plainText))
            assertFalse(trace.contains(envelope))
        }
        val secret = SecretBytes.adopt(plaintext.copyOf())
        assertFalse(secret.toString().contains(plainText))
        secret.close()
    }

    @Test
    fun concurrentFirstEncryptionGeneratesOneKey() {
        val backend = SecretTestKeyBackend()
        val first = secretTestCipher(backend, strongBoxAvailable = { true })
        val second = secretTestCipher(backend, strongBoxAvailable = { true })
        val start = CountDownLatch(1)
        val done = CountDownLatch(8)
        val envelopes = mutableListOf<String>()
        val errors = mutableListOf<Throwable>()
        repeat(8) { index ->
            thread {
                try {
                    start.await()
                    val envelope = (if (index % 2 == 0) first else second).encrypt(plaintext)
                    synchronized(envelopes) { envelopes.add(envelope) }
                } catch (error: Throwable) {
                    synchronized(errors) { errors.add(error) }
                } finally {
                    done.countDown()
                }
            }
        }
        start.countDown()
        assertTrue(done.await(30, TimeUnit.SECONDS))
        assertTrue(errors.toString(), errors.isEmpty())
        assertEquals(1, backend.requests.size)
        assertEquals(8, envelopes.size)
        envelopes.forEach { envelope ->
            first.decrypt(envelope).use { assertArrayEquals(plaintext, it.copyOf()) }
        }
    }

    private inline fun <reified T : Throwable> assertFailsWith(block: () -> Unit): T {
        try {
            block()
        } catch (error: Throwable) {
            if (error is T) {
                return error
            }
            throw error
        }
        fail("expected ${T::class.java.simpleName}")
        throw AssertionError()
    }
}
