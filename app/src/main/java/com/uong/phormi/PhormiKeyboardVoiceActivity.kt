package com.uong.phormi

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent

/** Reliable activity-hosted speech UI used by the system keyboard when needed. */
class PhormiKeyboardVoiceActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val localeTag = intent.getStringExtra(EXTRA_LOCALE).orEmpty()
        val language = localeTag.ifBlank { "en-US" }
        val recognition = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to Phormi Keyboard")
        }
        runCatching { startActivityForResult(recognition, REQUEST) }.onFailure { finish() }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST && resultCode == RESULT_OK) {
            data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let { PhormiKeyboardExternalBridge.commitText(this, it) }
        }
        finish()
    }

    companion object {
        private const val REQUEST = 7101
        const val EXTRA_LOCALE = "phormi_voice_locale"
    }
}
