package com.snipsearch.assistant

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager

/**
 * Turns a package-scoped voice-command intent into an explicit component.
 * Falls back to `ACTION_ASSIST` for the same package when nothing resolves the first action.
 */
object DelegationTargets {
    fun explicit(packageManager: PackageManager, voiceCommand: Intent): Intent? {
        val packageName = voiceCommand.`package` ?: return null
        resolve(packageManager, voiceCommand)?.let { return it }
        return resolve(packageManager, InvocationRouter.assistFallbackIntent(packageName))
    }

    private fun resolve(packageManager: PackageManager, intent: Intent): Intent? {
        val info = packageManager.resolveActivity(
            intent,
            PackageManager.ResolveInfoFlags.of(0),
        ) ?: return null
        val activity = info.activityInfo ?: return null
        return Intent(intent.action)
            .setComponent(ComponentName(activity.packageName, activity.name))
            .setPackage(activity.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
