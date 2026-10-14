package com.uong.phormi

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import androidx.core.graphics.ColorUtils
import java.util.Calendar

/** One accent contract for browser chrome, IME actions and offline wallpapers. */
internal object PhormiAppearance {
    data class Accent(val id: String, val label: String, val color: Int)
    val accents = listOf(
        Accent("iris", "Iris", Color.rgb(151, 143, 255)),
        Accent("tide", "Tide", Color.rgb(56, 205, 205)),
        Accent("fern", "Fern", Color.rgb(93, 211, 154)),
        Accent("rose", "Rose", Color.rgb(244, 145, 177)),
        Accent("amber", "Amber", Color.rgb(244, 189, 99))
    )
    private fun prefs(context: Context) = context.getSharedPreferences("phormi_appearance", Context.MODE_PRIVATE)
    fun isDark(context: Context): Boolean = when (context.getSharedPreferences("phormi_tabs", Context.MODE_PRIVATE).getString("theme_mode", "System")) {
        "Dark" -> true
        "Light" -> false
        else -> context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    }
    fun daily(context: Context): Boolean = prefs(context).getBoolean("daily_accent",
        context.getSharedPreferences("phormi_tabs", Context.MODE_PRIVATE).getBoolean("daily_accent", false))
    fun setDaily(context: Context, enabled: Boolean) { prefs(context).edit().putBoolean("daily_accent", enabled).apply() }
    fun selected(context: Context): String = prefs(context).getString("accent", "iris") ?: "iris"
    fun setAccent(context: Context, id: String) {
        if (accents.none { it.id == id }) return
        prefs(context).edit().putString("accent", id).putBoolean("daily_accent", false).apply()
    }
    fun accent(context: Context, dark: Boolean = isDark(context)): Int {
        val color = if (daily(context)) accents[(Calendar.getInstance().get(Calendar.DAY_OF_YEAR) - 1) % accents.size].color
            else accents.firstOrNull { it.id == selected(context) }?.color ?: accents.first().color
        if (dark) return color
        // Accent text remains readable on light browser/key surfaces, not pale-on-white.
        var accessible = color
        while (ColorUtils.calculateContrast(accessible, Color.WHITE) < 4.5) accessible = ColorUtils.blendARGB(accessible, Color.BLACK, 0.10f)
        return accessible
    }
    fun onAccent(color: Int): Int = if (ColorUtils.calculateContrast(Color.WHITE, color) >= ColorUtils.calculateContrast(Color.BLACK, color)) Color.WHITE else Color.rgb(12, 18, 30)
}
