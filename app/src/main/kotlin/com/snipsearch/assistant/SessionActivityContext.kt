package com.snipsearch.assistant

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Bundle

/** External apps use the normal activity layer; the assistant window must then yield. */
internal class SessionActivityContext(base: Context, private val onLaunched: () -> Unit) : ContextWrapper(base) {
    override fun startActivity(intent: Intent) = startActivity(intent, null)

    override fun startActivity(intent: Intent, options: Bundle?) {
        // A refused launch must leave the overlay available for fallback/error UI.
        baseContext.startActivity(intent, options)
        onLaunched()
    }
}
