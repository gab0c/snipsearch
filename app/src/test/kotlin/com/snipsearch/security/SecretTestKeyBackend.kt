package com.snipsearch.security

import android.security.keystore.StrongBoxUnavailableException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * JVM stand-in for Android Keystore. Keys are SunJCE AES-256 software keys.
 * Tests that use this backend cannot prove StrongBox or TEE hardware backing.
 */
internal class SecretTestKeyBackend : SecretKeyBackend {
    val keys = mutableMapOf<String, SecretKey>()
    val requests = mutableListOf<SecretKeyGenerationRequest>()
    var loadCount = 0
    var strongBoxUnavailable = false
    var unavailableEvenWithoutStrongBox = false
    var generationFailure: Exception? = null
    var loadFailure: Exception? = null
    var protectionOverride: SecretKeyProtection? = null

    private val protections = mutableMapOf<String, SecretKeyProtection>()

    @Synchronized
    override fun loadKey(alias: String): SecretKey? {
        loadCount += 1
        loadFailure?.let { throw it }
        return keys[alias]
    }

    @Synchronized
    override fun generateKey(request: SecretKeyGenerationRequest): SecretKey {
        requests.add(request)
        if (request.strongBoxBacked && strongBoxUnavailable) {
            throw StrongBoxUnavailableException("StrongBox unavailable")
        }
        if (!request.strongBoxBacked && unavailableEvenWithoutStrongBox) {
            throw StrongBoxUnavailableException("Unexpected StrongBoxUnavailableException")
        }
        if (!request.strongBoxBacked) {
            generationFailure?.let { throw it }
        }
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        keys[request.alias] = key
        protections[request.alias] = if (request.strongBoxBacked) {
            SecretKeyProtection.STRONGBOX
        } else {
            SecretKeyProtection.TRUSTED_ENVIRONMENT
        }
        return key
    }

    @Synchronized
    override fun protectionOf(key: SecretKey): SecretKeyProtection {
        protectionOverride?.let { return it }
        val alias = keys.entries.firstOrNull { it.value == key }?.key
        return protections[alias] ?: SecretKeyProtection.UNKNOWN
    }
}

internal fun secretTestCipher(
    backend: SecretTestKeyBackend = SecretTestKeyBackend(),
    strongBoxAvailable: () -> Boolean = { true },
    cipherFactory: () -> javax.crypto.Cipher = {
        javax.crypto.Cipher.getInstance(KeystoreSecretCipher.TRANSFORMATION)
    },
): KeystoreSecretCipher = KeystoreSecretCipher(
    backend,
    strongBoxAvailable,
    cipherFactory = cipherFactory,
)
