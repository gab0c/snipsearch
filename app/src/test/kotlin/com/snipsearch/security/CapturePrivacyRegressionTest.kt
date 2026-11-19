package com.snipsearch.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CapturePrivacyRegressionTest {
    @Test
    fun manifestOptsIntoAssistContentAndDoesNotDeclareLocalNetworkOrForbiddenApis() {
        val manifest = file("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android.permission.INTERNET"))
        assertTrue(manifest.contains("android.permission.READ_ASSIST_STRUCTURE_SCREEN_CONTENT"))
        assertTrue(manifest.contains("android.permission.BIND_VOICE_INTERACTION"))
        assertTrue(manifest.contains("android:memtagMode=\"sync\""))
        assertTrue(manifest.contains("android.service.voice.VoiceInteractionService"))
        assertTrue(manifest.contains(".assistant.SnipVoiceInteractionSessionService"))
        assertTrue(manifest.contains(".assistant.SnipRecognitionServiceStub"))
        assertTrue(manifest.contains(".assistant.SnipQuickSettingsTileService"))
        assertTrue(manifest.contains(".assistant.TileCaptureActivity"))
        assertTrue(manifest.contains("android:exported=\"false\""))

        assertFalse(manifest.contains("ACCESS_LOCAL_NETWORK"))
        assertFalse(manifest.contains("BIND_ACCESSIBILITY_SERVICE"))
        assertFalse(manifest.contains("SYSTEM_ALERT_WINDOW"))
        assertFalse(manifest.contains("MediaProjection"))
        assertFalse(manifest.contains("FOREGROUND_SERVICE_MEDIA_PROJECTION"))
    }

    @Test
    fun interactionServiceOptsIntoAssistDataScreenshotsAndScreenContent() {
        val xml = file("src/main/res/xml/interaction_service.xml").readText()
        assertTrue(xml.contains("android:supportsAssist=\"true\""))
        assertTrue(xml.contains("android:usesAssistData=\"true\""))
        assertTrue(xml.contains("android:usesAssistScreenshots=\"true\""))
        assertTrue(xml.contains("android:usesAssistStructureScreenContent=\"true\""))
        assertTrue(xml.contains("com.snipsearch.assistant.SnipVoiceInteractionSessionService"))
        assertTrue(xml.contains("com.snipsearch.assistant.SnipRecognitionServiceStub"))
    }

    @Test
    fun userFacingCopyDoesNotClaimHomeLongPressOnGrapheneOs() {
        val strings = file("src/main/res/values/strings.xml").readText()
        assertFalse(strings.contains("GrapheneOS"))
        assertFalse(strings.contains("Home"))
        assertFalse(strings.contains("long-press"))
        assertFalse(strings.contains("long press"))
    }

    @Test
    fun productionSourcesDoNotPersistCapturesOrUseForbiddenCaptureApis() {
        val forbidden = listOf(
            "openFileOutput",
            "createTempFile",
            "FileOutputStream",
            "FileWriter",
            "RandomAccessFile",
            "filesDir",
            "cacheDir",
            "externalCacheDir",
            "getExternalFilesDir",
            "getExternalCacheDir",
            "openOrCreateDatabase",
            "AccessibilityService",
            "MediaProjection",
            "SYSTEM_ALERT_WINDOW",
            "ACCESS_LOCAL_NETWORK",
            "java.io.File",
        )
        val sources = file("src/main/kotlin").walkTopDown().filter { it.extension == "kt" }
        val hits = sources.flatMap { source ->
            val text = source.readText()
            forbidden.filter { token -> text.contains(token) }.map { token -> "${source.path}:$token" }
        }.toList()
        assertTrue(hits.joinToString("\n"), hits.isEmpty())
    }
}

private fun file(relative: String): File {
    val cwd = File("").absoluteFile
    val candidates = listOf(cwd, cwd.resolve("app"), cwd.parentFile)
    val root = candidates.first { it.resolve("src/main/AndroidManifest.xml").isFile }
    return root.resolve(relative)
}
