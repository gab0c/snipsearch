package com.snipsearch.ui.overlay

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import com.snipsearch.R

/** Custom Tabs wire protocol, with no service binding, prefetch, or extra dependency. */
internal object BrowserLinkLauncher {
    internal const val SESSION = "android.support.customtabs.extra.SESSION"
    internal val browserProbe: Uri = Uri.parse("https://example.invalid/")

    internal fun intent(context: Context, uri: Uri): Intent? {
        if (uri.scheme != "https" && uri.scheme != "http") return null
        // Resolve a generic web URL, so a result URL's app-link handler cannot override the browser.
        val probe = Intent(Intent.ACTION_VIEW, browserProbe).addCategory(Intent.CATEGORY_BROWSABLE)
        val manager = context.packageManager
        val resolved = manager.resolveActivity(probe, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo
        // An unresolved default may return Android's chooser, which must never be package-pinned.
        val browser = resolved?.takeIf { chosen ->
            manager.queryIntentActivities(probe, PackageManager.MATCH_DEFAULT_ONLY).any {
                it.activityInfo?.packageName == chosen.packageName && it.activityInfo?.name == chosen.name
            }
        }
        return Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .apply {
                if (browser?.exported == true && browser.packageName != "android" && browser.packageName != context.packageName) {
                    setPackage(browser.packageName)
                }
                // A present, null session binder requests a Custom Tab without creating a session.
                putExtras(Bundle().apply { putBinder(SESSION, null) })
            }
    }

    fun open(context: Context, uri: Uri): Boolean {
        val tab = intent(context, uri) ?: return false
        try { context.startActivity(tab); return true }
        catch (_: ActivityNotFoundException) { /* Fall back to a regular tab in the same browser. */ }
        catch (_: SecurityException) { /* Some browsers may refuse the Custom Tab intent. */ }
        try {
            context.startActivity(Intent(tab).apply { removeExtra(SESSION) })
            return true
        } catch (_: ActivityNotFoundException) { /* No browser can open the link. */ }
        catch (_: SecurityException) { /* Keep the pane usable if the browser refuses the launch. */ }
        Toast.makeText(context, R.string.overlay_no_action_app, Toast.LENGTH_SHORT).show()
        return false
    }
}
