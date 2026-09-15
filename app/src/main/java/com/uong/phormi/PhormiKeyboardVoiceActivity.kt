package com.uong.phormi

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognizerIntent

/** Activity-hosted speech UI used by the system keyboard. */
class PhormiKeyboardVoiceActivity : Activity() {
    private val localeTag: String get() = intent?.getStringExtra(EXTRA_LOCALE).orEmpty().ifBlank { "en-US" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_PERMISSION)
        } else {
            launchRecognizer()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSION && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) launchRecognizer() else finish()
    }

    private fun launchRecognizer() {
        val recognition = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, localeTag)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, localeTag)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to Phormi Keyboard")
        }
        runCatching { startActivityForResult(recognition, REQUEST_RECOGNITION) }.onFailure { finish() }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_RECOGNITION && resultCode == RESULT_OK) {
            data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let { PhormiKeyboardExternalBridge.commitText(this, it) }
        }
        finish()
    }

    companion object {
        private const val REQUEST_PERMISSION = 7100
        private const val REQUEST_RECOGNITION = 7101
        const val EXTRA_LOCALE = "phormi_voice_locale"
    }
}
