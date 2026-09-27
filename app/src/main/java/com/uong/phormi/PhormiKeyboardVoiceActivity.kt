package com.uong.phormi

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle

/** Activity-hosted speech UI used by the system keyboard. */
class PhormiKeyboardVoiceActivity : Activity() {
    private var localeTag: String = "en-US"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        localeTag = intent?.getStringExtra(EXTRA_LOCALE).orEmpty().ifBlank { "en-US" }
        if (android.os.Build.VERSION.SDK_INT >= 23 &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_PERMISSION)
        } else {
            startRecognitionAndFinish()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSION && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startRecognitionAndFinish()
        } else finish()
    }

    private fun startRecognitionAndFinish() {
        PhormiKeyboardServiceV2.startIntegratedVoiceRecognitionFromContext(this, localeTag)
        finish()
    }

    companion object {
        private const val REQUEST_PERMISSION = 7100
        const val EXTRA_LOCALE = "phormi_voice_locale"

        fun requestPermissionFromKeyboard(service: PhormiKeyboardServiceV2, localeTag: String) {
            service.startActivity(Intent(service, PhormiKeyboardVoiceActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_LOCALE, localeTag))
        }
    }

