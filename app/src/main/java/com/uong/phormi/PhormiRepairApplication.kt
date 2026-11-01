package com.uong.phormi

import android.app.Application
import android.webkit.WebView

/** Process initialization only. Browser tabs and downloads are owned by their respective surfaces. */
class PhormiRepairApplication : Application() {
    private val themeListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "theme_mode") android.os.Handler(android.os.Looper.getMainLooper()).post { PhormiUiColors.applyTheme(this) }
    }
    override fun onCreate() {
        // Ghost mode uses its own process and must have a separate Chromium data directory.
        if (android.os.Build.VERSION.SDK_INT >= 28 &&
            android.app.Application.getProcessName().endsWith(":ghost")) {
            runCatching { WebView.setDataDirectorySuffix("ghost") }
        }
        super.onCreate()
        PhormiUiColors.applyTheme(this)
        getSharedPreferences("phormi_tabs", MODE_PRIVATE).registerOnSharedPreferenceChangeListener(themeListener)
        PhormiNotificationManager.ensureChannels(this)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: android.app.Activity, state: android.os.Bundle?) {
                if (activity is MainActivity || activity is MenuActivity || activity is TabsOverviewActivity ||
                    activity is BookmarksActivity || activity is FavoritesActivity || activity is AccountsActivity ||
                    activity is HistoryActivity || activity is PhormiSecurityCenterActivity ||
                    activity is PhormiBrowserSettingsActivity || activity is PhormiKeyboardSettingsActivity || activity is GhostActivity)
                    PhormiWindowInsets.apply(activity)
            }
            override fun onActivityStarted(activity: android.app.Activity) = Unit
            override fun onActivityResumed(activity: android.app.Activity) = Unit
            override fun onActivityPaused(activity: android.app.Activity) = Unit
            override fun onActivityStopped(activity: android.app.Activity) = Unit
            override fun onActivitySaveInstanceState(activity: android.app.Activity, state: android.os.Bundle) = Unit
            override fun onActivityDestroyed(activity: android.app.Activity) = Unit
        })
        // No Activity polling/reflection here. A second download listener used to replace
        // MainActivity's listener, losing its source WebView and breaking blob downloads.
        // Browser AI now has exactly one queue consumer in MainActivity.
    }
}
