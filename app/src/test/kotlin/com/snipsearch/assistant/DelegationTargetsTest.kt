package com.snipsearch.assistant

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class DelegationTargetsTest {
    @Test
    fun voiceCommandResolvesToAnExplicitComponent() {
        val voice = InvocationRouter.voiceCommandIntent("com.example.voice")
        install(voice, "com.example.voice", "com.example.voice.ListenActivity")

        val explicit = DelegationTargets.explicit(packageManager(), voice)

        val resolved = requireNotNull(explicit)
        assertEquals(Intent.ACTION_VOICE_COMMAND, resolved.action)
        assertEquals("com.example.voice", resolved.component?.packageName)
        assertEquals("com.example.voice.ListenActivity", resolved.component?.className)
        assertTrue(resolved.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun fallsBackToAssistForTheSamePackage() {
        val voice = InvocationRouter.voiceCommandIntent("com.example.voice")
        val assist = InvocationRouter.assistFallbackIntent("com.example.voice")
        install(assist, "com.example.voice", "com.example.voice.AssistActivity")

        val explicit = DelegationTargets.explicit(packageManager(), voice)

        val resolved = requireNotNull(explicit)
        assertEquals(Intent.ACTION_ASSIST, resolved.action)
        assertEquals("com.example.voice.AssistActivity", resolved.component?.className)
    }

    @Test
    fun returnsNullWhenNothingResolves() {
        val voice = InvocationRouter.voiceCommandIntent("com.example.missing")
        assertNull(DelegationTargets.explicit(packageManager(), voice))
    }

    private fun packageManager() = RuntimeEnvironment.getApplication().packageManager

    private fun install(intent: Intent, packageName: String, className: String) {
        val info = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                this.packageName = packageName
                name = className
                exported = true
                applicationInfo = ApplicationInfo().apply { this.packageName = packageName }
            }
        }
        shadowOf(packageManager()).addResolveInfoForIntent(intent, listOf(info))
    }
}
