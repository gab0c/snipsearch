package com.snipsearch.assistant

/** RAM-only activity identities; no hierarchy, text, package polling, or extra permissions. */
internal class VisibleActivityWatch<T : Any> {
    private val visible = mutableSetOf<T>()
    private var active = false
    private var armed = false

    fun start() { stop(); active = true }
    fun stop() { active = false; armed = false; visible.clear() }

    // Initial visible-activity callbacks form the baseline before the user touches the overlay.
    fun onUserInteraction() { if (active && visible.isNotEmpty()) armed = true }

    fun onVisible(id: T): Boolean {
        if (!active) return false
        if (armed && id !in visible) return yieldOnce()
        visible += id
        return false
    }

    fun onInvisible(id: T): Boolean {
        if (!active) return false
        val removed = visible.remove(id)
        return if (armed && removed) yieldOnce() else false
    }

    private fun yieldOnce(): Boolean { stop(); return true }
}
