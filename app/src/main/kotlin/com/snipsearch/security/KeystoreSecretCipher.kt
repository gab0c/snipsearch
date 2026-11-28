package com.snipsearch.security

import android.content.Context
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.StrongBoxUnavailableException
import android.security.keystore.UserNotAuthenticatedException
import java.security.GeneralSecurityException
import java.security.ProviderException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts provider secrets with AES-256-GCM under an Android Keystore key.
 *
 * Envelope layout, before standard Base64: version byte `0x01`, a fresh 12-byte IV,
 * then ciphertext with a 128-bit GCM tag. The version byte is also bound as associated data.
 * Buffers stay in RAM. Caller-owned plaintext passed to encrypt is not cleared; the caller
 * controls its lifetime. Decrypted plaintext is adopted by SecretBytes and cleared on close.
 * Temporary IV and ciphertext buffers are cleared after use.
 * This type does not persist envelopes or wire settings.
 *
 * The production key is StrongBox-backed when that feature is present, with a single
 * fallback to the trusted execution environment if StrongBox generation throws
 * [StrongBoxUnavailableException]. Software and unknown protection levels fail closed.
 * There is no software-key fallback and no automatic key deletion. Unit tests may inject
 * a JVM [SecretKeyBackend]; those tests do not prove device StrongBox or TEE backing.
 *
 * [decrypt] returns [SecretBytes] that the caller must close. [associatedData] is bound
 * into the GCM tag so a ciphertext cannot be swapped across contexts.
 */
class KeystoreSecretCipher internal constructor(
    private val backend: SecretKeyBackend,
    private val strongBoxAvailable: () -> Boolean,
    private val keyAlias: String = KEY_ALIAS,
    private val cipherFactory: () -> Cipher = { Cipher.getInstance(TRANSFORMATION) },
) {
    fun encrypt(plaintext: ByteArray, associatedData: ByteArray = ByteArray(0)): String {
        if (plaintext.isEmpty() || plaintext.size > SecretEnvelope.MAX_SECRET_BYTES) {
            throw SecretCipherException.InvalidInput("Secret must be between 1 and 4096 bytes")
        }
        if (associatedData.size > SecretEnvelope.MAX_ASSOCIATED_DATA_BYTES) {
            throw SecretCipherException.InvalidInput("Associated data exceeds maximum size")
        }
        val key = encryptionKey()
        val aad = SecretEnvelope.associatedData(SecretEnvelope.VERSION_1, associatedData)
        var iv: ByteArray? = null
        var ciphertext: ByteArray? = null
        try {
            val cipher = cipherFactory()
            cipher.init(Cipher.ENCRYPT_MODE, key)
            cipher.updateAAD(aad)
            ciphertext = cipher.doFinal(plaintext)
            iv = cipher.iv
            if (iv == null || iv.size != SecretEnvelope.IV_BYTES ||
                ciphertext.size != plaintext.size + SecretEnvelope.TAG_BYTES
            ) {
                throw SecretCipherException.KeystoreFailure()
            }
            return SecretEnvelope.encode(iv, ciphertext)
        } catch (t: Exception) {
            throw mapCipherFailure(t)
        } finally {
            MemoryScrubber.zero(aad)
            MemoryScrubber.zero(iv)
            MemoryScrubber.zero(ciphertext)
        }
    }

    /**
     * Reads [secret] without closing it. The caller controls [secret]'s lifetime via [use].
     */
    fun encrypt(secret: SecretBytes, associatedData: ByteArray = ByteArray(0)): String =
        secret.read { encrypt(it, associatedData) }

    fun decrypt(envelope: String, associatedData: ByteArray = ByteArray(0)): SecretBytes {
        if (associatedData.size > SecretEnvelope.MAX_ASSOCIATED_DATA_BYTES) {
            throw SecretCipherException.InvalidInput("Associated data exceeds maximum size")
        }
        val parts = SecretEnvelope.decode(envelope)
        try {
            val key = decryptionKey()
            val aad = SecretEnvelope.associatedData(parts.version, associatedData)
            try {
                val cipher = cipherFactory()
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    key,
                    GCMParameterSpec(SecretEnvelope.TAG_BITS, parts.iv),
                )
                cipher.updateAAD(aad)
                val plaintext = cipher.doFinal(parts.ciphertext)
                if (plaintext.isEmpty()) {
                    MemoryScrubber.zero(plaintext)
                    throw SecretCipherException.MalformedEnvelope("Envelope plaintext is empty")
                }
                return SecretBytes.adopt(plaintext)
            } catch (t: Exception) {
                throw mapCipherFailure(t)
            } finally {
                MemoryScrubber.zero(aad)
                parts.scrub()
            }
        } finally {
            parts.scrub()
        }
    }

    private fun encryptionKey(): SecretKey {
        val key = synchronized(KEY_LOCK) {
            val existing = try {
                backend.loadKey(keyAlias)
            } catch (e: Exception) {
                throw mapKeystoreFailure(e)
            }
            if (existing != null) {
                existing
            } else {
                val wantStrongBox = strongBoxAvailable()
                try {
                    backend.generateKey(SecretKeyGenerationRequest(keyAlias, wantStrongBox))
                } catch (e: ProviderException) {
                    if (wantStrongBox && e.isStrongBoxUnavailable()) {
                        try {
                            backend.generateKey(
                                SecretKeyGenerationRequest(keyAlias, strongBoxBacked = false),
                            )
                        } catch (fallback: Exception) {
                            throw mapKeystoreFailure(fallback)
                        }
                    } else {
                        throw mapKeystoreFailure(e)
                    }
                } catch (e: Exception) {
                    throw mapKeystoreFailure(e)
                }
            }
        }
        requireHardware(key)
        return key
    }

    private fun decryptionKey(): SecretKey {
        val key = try {
            backend.loadKey(keyAlias)
        } catch (e: Exception) {
            throw mapKeystoreFailure(e)
        } ?: throw SecretCipherException.KeyUnavailable()
        requireHardware(key)
        return key
    }

    private fun requireHardware(key: SecretKey) {
        val protection = try {
            backend.protectionOf(key)
        } catch (e: Exception) {
            throw mapKeystoreFailure(e)
        }
        if (!protection.isEligible) {
            throw SecretCipherException.HardwareBackingUnavailable(protection)
        }
    }

    private fun Throwable.isStrongBoxUnavailable(): Boolean =
        this is StrongBoxUnavailableException || cause is StrongBoxUnavailableException

    private fun mapKeystoreFailure(e: Exception): Throwable = mapCipherFailure(e)

    private fun mapCipherFailure(e: Throwable): Throwable {
        if (e is SecretCipherException) {
            return e
        }
        if (e is UserNotAuthenticatedException || e.cause is UserNotAuthenticatedException) {
            return SecretCipherException.DeviceLocked(e)
        }
        if (e is KeyPermanentlyInvalidatedException || e.cause is KeyPermanentlyInvalidatedException) {
            return SecretCipherException.KeyUnavailable(e)
        }
        if (e is AEADBadTagException) {
            return SecretCipherException.AuthenticationFailed(e)
        }
        if (e is ProviderException && e.cause is android.security.KeyStoreException) {
            val code = (e.cause as android.security.KeyStoreException).numericErrorCode
            if (code == android.security.KeyStoreException.ERROR_KEY_DOES_NOT_EXIST ||
                code == android.security.KeyStoreException.ERROR_KEY_CORRUPTED
            ) {
                return SecretCipherException.KeyUnavailable(e)
            }
        }
        if (e is GeneralSecurityException || e is ProviderException ||
            e is java.io.IOException || e is IllegalStateException
        ) {
            return SecretCipherException.KeystoreFailure(e)
        }
        throw e
    }

    companion object {
        const val KEY_ALIAS = "snipsearch_provider_secrets_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        private val KEY_LOCK = Any()

        fun create(context: Context): KeystoreSecretCipher {
            val packageManager = context.applicationContext.packageManager
            return KeystoreSecretCipher(
                SecretAndroidKeyStoreBackend(),
                { secretStrongBoxAvailable(packageManager) },
            )
        }
    }
}
