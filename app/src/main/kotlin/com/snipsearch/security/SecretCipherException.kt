package com.snipsearch.security

import java.security.GeneralSecurityException

sealed class SecretCipherException(
    message: String,
    cause: Throwable? = null,
) : GeneralSecurityException(message, cause) {
    class InvalidInput(message: String) : SecretCipherException(message)

    class MalformedEnvelope(message: String) : SecretCipherException(message)

    class AuthenticationFailed(cause: Throwable? = null) :
        SecretCipherException(
            "Secret envelope failed authentication (tampered, wrong key, or wrong associated data)",
            cause,
        )

    class DeviceLocked(cause: Throwable? = null) :
        SecretCipherException("Secret key requires an unlocked device", cause)

    class KeyUnavailable(cause: Throwable? = null) :
        SecretCipherException(
            "Secret key is missing or permanently invalidated; stored secrets must be re-entered",
            cause,
        )

    class KeystoreFailure(cause: Throwable? = null) :
        SecretCipherException("Android Keystore operation failed", cause)

    class HardwareBackingUnavailable(val protection: SecretKeyProtection) :
        SecretCipherException("Secret key is not hardware-backed ($protection)")
}
