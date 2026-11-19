package com.snipsearch.assistant

import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession

/**
 * Active assistant entry point.
 *
 * [showOverlayFromTile] is called from [TileCaptureActivity] while this UID is in
 * the foreground. Android 17 strips screenshot and assist flags from a session the
 * app starts itself unless the caller is already the foreground UID. A session
 * window alone does not count.
 */
class SnipVoiceInteractionService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        instance = this
    }

    override fun onShutdown() {
        if (instance === this) {
            instance = null
        }
        super.onShutdown()
    }

    companion object {
        @Volatile
        private var instance: SnipVoiceInteractionService? = null

        /**
         * @return false when this process is not the active assistant, so the system
         * has not bound the service and there is no session to show.
         */
        fun showOverlayFromTile(): Boolean {
            val service = instance ?: return false
            service.showSession(
                InvocationRouter.bundleFor(InvocationRouter.INVOCATION_TYPE_QUICK_SETTINGS_TILE),
                CAPTURE_FLAGS,
            )
            return true
        }

        /**
         * Screenshot and assist data. [SHOW_WITH_ASSIST_STRUCTURE_SCREEN_CONTENT] is
         * ignored unless [SHOW_WITH_ASSIST] is set as well.
         */
        const val CAPTURE_FLAGS =
            VoiceInteractionSession.SHOW_WITH_ASSIST or
                VoiceInteractionSession.SHOW_WITH_SCREENSHOT or
                VoiceInteractionSession.SHOW_WITH_ASSIST_STRUCTURE_SCREEN_CONTENT
    }
}
