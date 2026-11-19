package com.snipsearch.assistant

import android.app.PendingIntent
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.service.voice.VoiceInteractionService
import com.snipsearch.R

/**
 * Opens the overlay when SnipSearch is the assistant.
 *
 * The tap starts [TileCaptureActivity] so this UID is in the foreground before the
 * session asks for a screenshot. Android 17 drops that screenshot when the assistant
 * starts a session from the background.
 */
class SnipQuickSettingsTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        val tile = qsTile ?: return
        val active = isAssistantActive()
        tile.state = if (active) Tile.STATE_INACTIVE else Tile.STATE_UNAVAILABLE
        tile.subtitle = getString(
            if (active) R.string.tile_subtitle_ready else R.string.tile_subtitle_needs_role,
        )
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        if (isAssistantActive()) {
            val launch = Intent(this, TileCaptureActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            val pending = PendingIntent.getActivity(
                this,
                REQUEST_CAPTURE,
                launch,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            startActivityAndCollapse(pending)
            return
        }
        val roleManager = getSystemService(RoleManager::class.java) ?: return
        if (!roleManager.isRoleAvailable(RoleManager.ROLE_ASSISTANT) ||
            roleManager.isRoleHeld(RoleManager.ROLE_ASSISTANT)
        ) {
            return
        }
        val request = roleManager.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pending = PendingIntent.getActivity(
            this,
            REQUEST_ROLE,
            request,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        startActivityAndCollapse(pending)
    }

    private fun isAssistantActive(): Boolean {
        return VoiceInteractionService.isActiveService(
            this,
            ComponentName(this, SnipVoiceInteractionService::class.java),
        )
    }

    companion object {
        private const val REQUEST_ROLE = 1
        private const val REQUEST_CAPTURE = 2
    }
}
