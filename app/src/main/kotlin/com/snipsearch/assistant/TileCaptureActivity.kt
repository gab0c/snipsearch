package com.snipsearch.assistant

import android.app.Activity
import android.os.Bundle

/**
 * Puts SnipSearch in the foreground long enough for a tile capture.
 *
 * Android 17 strips [android.service.voice.VoiceInteractionSession.SHOW_WITH_SCREENSHOT]
 * from a session the assistant starts itself unless that app's UID is already the
 * foreground UID (or a hotword fired in the last 30 seconds). The session window
 * does not satisfy the check. The Quick Settings tile starts this translucent
 * activity from the user's tap; the activity shows the session with capture flags,
 * then finishes so the previous screen is what comes back when the session hides.
 */
class TileCaptureActivity : Activity() {
    private var requested = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
        overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
    }

    override fun onResume() {
        super.onResume()
        if (requested) {
            finish()
            return
        }
        requested = true
        SnipVoiceInteractionService.showOverlayFromTile()
        finish()
    }
}
