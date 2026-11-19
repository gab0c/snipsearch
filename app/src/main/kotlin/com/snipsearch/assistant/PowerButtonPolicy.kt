package com.snipsearch.assistant

/**
 * Power-button routing selected by the user.
 *
 * Settings does not exist yet (Milestone 6). Until it does, long-press power opens
 * the overlay. GrapheneOS does not deliver this invocation type.
 */
open class PowerButtonPolicy {
    open fun action(): InvocationRouter.PowerButtonAction {
        return InvocationRouter.PowerButtonAction.OPEN_SNIPSEARCH
    }

    open fun delegatedPackage(): String? = null
}
