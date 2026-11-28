package com.snipsearch.security

import android.security.keystore.KeyProperties
import javax.crypto.SecretKey

enum class SecretKeyProtection(val isHardwareBacked: Boolean) {
    STRONGBOX(true),
    TRUSTED_ENVIRONMENT(true),
    UNKNOWN_SECURE(true),
    SOFTWARE(false),
    UNKNOWN(false),
    ;

    val isEligible: Boolean get() = this == STRONGBOX || this == TRUSTED_ENVIRONMENT
}

data class SecretKeyGenerationRequest(
    val alias: String,
    val strongBoxBacked: Boolean,
) {
    val keySizeBits: Int get() = 256
    val unlockedDeviceRequired: Boolean get() = true
    val blockMode: String get() = KeyProperties.BLOCK_MODE_GCM
    val encryptionPadding: String get() = KeyProperties.ENCRYPTION_PADDING_NONE
    val purposes: Int get() = KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
}

/** Platform keystore boundary. Deliberately exposes no delete operation. */
interface SecretKeyBackend {
    fun loadKey(alias: String): SecretKey?

    fun generateKey(request: SecretKeyGenerationRequest): SecretKey

    fun protectionOf(key: SecretKey): SecretKeyProtection
}
