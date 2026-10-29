package com.uong.phormi

import android.app.Application
import android.webkit.WebView

/** Process initialization only. Browser tabs and downloads are owned by their respective surfaces. */
class PhormiRepairApplication : Application() {
    override fun onCreate() {
        // Ghost mode uses its own process and must have a separate Chromium data directory.
        if (android.os.Build.VERSION.SDK_INT >= 28 &&
            android.app.Application.getProcessName().endsWith(":ghost")) {
            runCatching { WebView.setDataDirectorySuffix("ghost") }
        }
        super.onCreate()
        PhormiNotificationManager.ensureChannels(this)
        // No Activity polling/reflection here. A second download listener used to replace
        // MainActivity's listener, losing its source WebView and breaking blob downloads.
        // Browser AI now has exactly one queue consumer in MainActivity.
    }
}
