package com.uong.phormi

import android.content.Context
import androidx.core.content.ContextCompat

object PhormiUiColors {
    fun surface(context: Context) = ContextCompat.getColor(context, R.color.phormi_surface)
    fun text(context: Context) = ContextCompat.getColor(context, R.color.phormi_text)
    fun secondary(context: Context) = ContextCompat.getColor(context, R.color.phormi_secondary)
    fun accent(context: Context) = ContextCompat.getColor(context, R.color.phormi_accent)
    fun applyTheme(context: Context) {
        val mode = when (context.getSharedPreferences("phormi_tabs", Context.MODE_PRIVATE).getString("theme_mode", "System")?.lowercase()) {
            "dark" -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
            "light" -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
            else -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(mode)
    }
}
