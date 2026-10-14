package com.uong.phormi

import android.app.Application
import android.webkit.WebView

/** Process initialization only. Browser/keyboard activities own their own lifecycle and input. */
class PhormiRepairApplication : Application() {
    override fun onCreate() {
        // This must happen before any Chromium call in the isolated private-browser process.
        if (android.os.Build.VERSION.SDK_INT >= 28 && Application.getProcessName().endsWith(":ghost")) {
            WebView.setDataDirectorySuffix("ghost")
        }
        super.onCreate()
        PhormiNotificationManager.ensureChannels(this)
        // Old versions could retain a reflected menu command indefinitely. Never replay a
        // stale navigation after an upgrade or independently of the user's current surface.
        getSharedPreferences("phormi_command_bus", MODE_PRIVATE).edit().clear().apply()
        // Profile expiry runs only after Main restores its tabs, so restored profiles are protected.
    }
}
