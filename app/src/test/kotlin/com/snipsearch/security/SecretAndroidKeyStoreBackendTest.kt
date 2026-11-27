package com.snipsearch.security

import android.content.pm.PackageManager
import android.security.keystore.KeyProperties
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class SecretAndroidKeyStoreBackendTest {
    @Test
    fun keyGenParameterSpecMatchesRequest() {
        listOf(true, false).forEach { strongBox ->
            val spec = SecretKeyGenerationRequest("snipsearch_provider_secrets_v1", strongBox)
                .toKeyGenParameterSpec()
            assertEquals("snipsearch_provider_secrets_v1", spec.keystoreAlias)
            assertEquals(256, spec.keySize)
            assertArrayEquals(arrayOf(KeyProperties.BLOCK_MODE_GCM), spec.blockModes)
            assertArrayEquals(arrayOf(KeyProperties.ENCRYPTION_PADDING_NONE), spec.encryptionPaddings)
            assertEquals(
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                spec.purposes,
            )
            assertTrue(spec.isUnlockedDeviceRequired)
            assertTrue(spec.isRandomizedEncryptionRequired)
            assertFalse(spec.isUserAuthenticationRequired)
            assertEquals(strongBox, spec.isStrongBoxBacked)
        }
    }

    @Test
    fun securityLevelMapsToSecretKeyProtection() {
        assertEquals(
            SecretKeyProtection.STRONGBOX,
            secretProtectionFromSecurityLevel(KeyProperties.SECURITY_LEVEL_STRONGBOX),
        )
        assertEquals(
            SecretKeyProtection.TRUSTED_ENVIRONMENT,
            secretProtectionFromSecurityLevel(KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT),
        )
        assertEquals(
            SecretKeyProtection.UNKNOWN_SECURE,
            secretProtectionFromSecurityLevel(KeyProperties.SECURITY_LEVEL_UNKNOWN_SECURE),
        )
        assertEquals(
            SecretKeyProtection.SOFTWARE,
            secretProtectionFromSecurityLevel(KeyProperties.SECURITY_LEVEL_SOFTWARE),
        )
        assertEquals(
            SecretKeyProtection.UNKNOWN,
            secretProtectionFromSecurityLevel(KeyProperties.SECURITY_LEVEL_UNKNOWN),
        )
        assertEquals(SecretKeyProtection.UNKNOWN, secretProtectionFromSecurityLevel(999))
    }

    @Test
    fun strongBoxFeatureFollowsPackageManager() {
        val app = RuntimeEnvironment.getApplication()
        val shadow = Shadows.shadowOf(app.packageManager)
        shadow.setSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE, true)
        assertTrue(secretStrongBoxAvailable(app.packageManager))
        shadow.setSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE, false)
        assertFalse(secretStrongBoxAvailable(app.packageManager))
    }

    @Test
    fun createDoesNotFallBackToSoftwareWhenAndroidKeyStoreIsMissing() {
        val cipher = KeystoreSecretCipher.create(RuntimeEnvironment.getApplication())
        try {
            cipher.encrypt("sk-test-0123456789-SECRET".encodeToByteArray())
            throw AssertionError("Robolectric supplied a working AndroidKeyStore provider")
        } catch (error: SecretCipherException.KeystoreFailure) {
            assertTrue(error.cause != null)
        }
    }
}
