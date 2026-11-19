package com.snipsearch.assistant

import android.app.assist.AssistStructure

/**
 * A window that set `FLAG_SECURE` is painted black in the screenshot. Its assist
 * structure root reports [android.app.assist.AssistStructure.ViewNode.isAssistBlocked].
 * A null structure means assist data was not supplied. That is not a secure window.
 */
object AssistStructures {
    fun isBlocked(structure: AssistStructure?): Boolean {
        if (structure == null) {
            return false
        }
        for (index in 0 until structure.windowNodeCount) {
            val root = structure.getWindowNodeAt(index).rootViewNode
            if (root.isAssistBlocked) {
                return true
            }
        }
        return false
    }
}
