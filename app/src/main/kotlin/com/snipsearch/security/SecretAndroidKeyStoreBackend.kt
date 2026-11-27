package com.snipsearch.security

import android.content.pm.PackageManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory

class SecretAndroidKeyStoreBackend : SecretKeyBackend {
    override fun loadKey(alias: String): SecretKey? {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val entry = keyStore.getKey(alias, null) ?: return null
        if (entry !is SecretKey) {
            throw java.security.KeyStoreException("Unexpected key entry type")
        }
        return entry
    }

    override fun generateKey(request: SecretKeyGenerationRequest): SecretKey =
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            .apply { init(request.toKeyGenParameterSpec()) }
            .generateKey()

    override fun protectionOf(key: SecretKey): SecretKeyProtection {
        val info = SecretKeyFactory.getInstance(key.algorithm, ANDROID_KEYSTORE)
            .getKeySpec(key, KeyInfo::class.java) as KeyInfo
        return secretProtectionFromSecurityLevel(info.securityLevel)
    }

    companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
    }
}

internal fun SecretKeyGenerationRequest.toKeyGenParameterSpec(): KeyGenParameterSpec {
    val builder = KeyGenParameterSpec.Builder(alias, purposes)
        .setBlockModes(blockMode)
        .setEncryptionPaddings(encryptionPadding)
        .setKeySize(keySizeBits)
        .setUnlockedDeviceRequired(unlockedDeviceRequired)
        .setRandomizedEncryptionRequired(true)
    if (strongBoxBacked) {
        builder.setIsStrongBoxBacked(true)
    }
    return builder.build()
}

internal fun secretStrongBoxAvailable(packageManager: PackageManager): Boolean =
    packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)

internal fun secretProtectionFromSecurityLevel(securityLevel: Int): SecretKeyProtection =
    when (securityLevel) {
        KeyProperties.SECURITY_LEVEL_STRONGBOX -> SecretKeyProtection.STRONGBOX
        KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> SecretKeyProtection.TRUSTED_ENVIRONMENT
        KeyProperties.SECURITY_LEVEL_UNKNOWN_SECURE -> SecretKeyProtection.UNKNOWN_SECURE
        KeyProperties.SECURITY_LEVEL_SOFTWARE -> SecretKeyProtection.SOFTWARE
        else -> SecretKeyProtection.UNKNOWN
    }
