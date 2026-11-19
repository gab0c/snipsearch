package com.snipsearch.assistant

import android.content.Intent
import android.os.Bundle

/**
 * Routes a session from the AOSP `invocation_type` extra.
 *
 * The integers for gesture, Home long-press, and power long-press match the hidden
 * `com.android.internal.app.AssistUtils` constants on the AOSP android16-release branch
 * and on GrapheneOS's Android 17 branch. The tile value is SnipSearch's own.
 *
 * Allowlist:
 * - `1` gesture (swipe up from a bottom corner) opens the overlay.
 * - `5` Home long-press opens the overlay on AOSP 3-button navigation. This is not a
 *   confirmed way in on GrapheneOS. On GrapheneOS the confirmed entries are the corner
 *   swipe and the Quick Settings tile.
 * - `100` Quick Settings tile opens the overlay.
 * - `6` long-press power follows [PowerButtonAction]. That value does not arrive on
 *   GrapheneOS, which keeps the power menu. Until Settings exists the action defaults
 *   to opening the overlay.
 *
 * Every other value, including a missing extra, scrubs and hides. `7` (physical assist
 * key) and `8` (navigation-handle long-press) are not SnipSearch triggers. AOSP sends
 * the navigation handle to Contextual Search, not to the assistant role.
 */
object InvocationRouter {
    const val EXTRA_INVOCATION_TYPE = "invocation_type"

    const val INVOCATION_TYPE_GESTURE = 1
    const val INVOCATION_TYPE_HOME_BUTTON_LONG_PRESS = 5
    const val INVOCATION_TYPE_POWER_BUTTON_LONG_PRESS = 6
    const val INVOCATION_TYPE_ASSIST_BUTTON = 7
    const val INVOCATION_TYPE_NAV_HANDLE_LONG_PRESS = 8
    const val INVOCATION_TYPE_QUICK_SETTINGS_TILE = 100

    /** [Bundle.getInt] default when the extra is absent. Also used for a null bundle. */
    const val MISSING_INVOCATION_TYPE = -1

    enum class PowerButtonAction {
        IGNORE,
        DELEGATE,
        OPEN_SNIPSEARCH,
    }

    enum class Kind {
        OPEN_OVERLAY,
        SCRUB_AND_HIDE,
        DELEGATE,
    }

    data class Decision(
        val kind: Kind,
        val delegation: Intent? = null,
    )

    fun bundleFor(invocationType: Int): Bundle {
        return Bundle().apply { putInt(EXTRA_INVOCATION_TYPE, invocationType) }
    }

    fun invocationTypeOf(args: Bundle?): Int {
        if (args == null || !args.containsKey(EXTRA_INVOCATION_TYPE)) {
            return MISSING_INVOCATION_TYPE
        }
        return try {
            args.getInt(EXTRA_INVOCATION_TYPE, MISSING_INVOCATION_TYPE)
        } catch (_: RuntimeException) {
            MISSING_INVOCATION_TYPE
        }
    }

    fun opensOverlay(
        invocationType: Int,
        powerButtonAction: PowerButtonAction,
        delegatedPackage: String?,
    ): Boolean {
        return decide(invocationType, powerButtonAction, delegatedPackage).kind == Kind.OPEN_OVERLAY
    }

    fun decide(
        invocationType: Int,
        powerButtonAction: PowerButtonAction,
        delegatedPackage: String?,
    ): Decision {
        return when (invocationType) {
            INVOCATION_TYPE_GESTURE,
            INVOCATION_TYPE_HOME_BUTTON_LONG_PRESS,
            INVOCATION_TYPE_QUICK_SETTINGS_TILE,
            -> Decision(Kind.OPEN_OVERLAY)
            INVOCATION_TYPE_POWER_BUTTON_LONG_PRESS -> decidePowerButton(powerButtonAction, delegatedPackage)
            else -> Decision(Kind.SCRUB_AND_HIDE)
        }
    }

    fun voiceCommandIntent(packageName: String): Intent {
        return Intent(Intent.ACTION_VOICE_COMMAND)
            .setPackage(packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun assistFallbackIntent(packageName: String): Intent {
        return Intent(Intent.ACTION_ASSIST)
            .setPackage(packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun decidePowerButton(
        action: PowerButtonAction,
        delegatedPackage: String?,
    ): Decision {
        return when (action) {
            PowerButtonAction.IGNORE -> Decision(Kind.SCRUB_AND_HIDE)
            PowerButtonAction.OPEN_SNIPSEARCH -> Decision(Kind.OPEN_OVERLAY)
            PowerButtonAction.DELEGATE -> {
                val packageName = delegatedPackage?.takeIf { it.isNotBlank() }
                    ?: return Decision(Kind.SCRUB_AND_HIDE)
                Decision(Kind.DELEGATE, voiceCommandIntent(packageName))
            }
        }
    }
}
