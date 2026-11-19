package com.snipsearch.assistant

import android.content.Intent
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * Role Manager requires a recognition service component. SnipSearch does not listen.
 * Every session ends immediately with [SpeechRecognizer.ERROR_CLIENT].
 */
class SnipRecognitionServiceStub : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback) {
        listener.error(SpeechRecognizer.ERROR_CLIENT)
    }

    override fun onCancel(listener: Callback) = Unit

    override fun onStopListening(listener: Callback) = Unit
}
