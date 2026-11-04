package com.uong.phormi

import android.content.Context
import androidx.core.content.ContextCompat

/**
 * Semantic UI colors for the Phormi brand.
 *
 * Keep new UI code on these helpers (or the matching `@color/phormi_*` resources)
 * instead of hardcoding slate/cyan values, so the browser, menus, settings and
 * keyboard stay aligned with the app icon in both light and dark mode.
 */
object PhormiUiColors {
    fun background(context: Context) = ContextCompat.getColor(context, R.color.phormi_background)
    fun surface(context: Context) = ContextCompat.getColor(context, R.color.phormi_surface)
    fun surfaceSoft(context: Context) = ContextCompat.getColor(context, R.color.phormi_surface_soft)
    fun text(context: Context) = ContextCompat.getColor(context, R.color.phormi_text)
    fun secondary(context: Context) = ContextCompat.getColor(context, R.color.phormi_secondary)
    fun accent(context: Context) = ContextCompat.getColor(context, R.color.phormi_accent)
    fun accentSoft(context: Context) = ContextCompat.getColor(context, R.color.phormi_accent_soft)
    fun onAccent(context: Context) = ContextCompat.getColor(context, R.color.phormi_on_accent)
    fun coral(context: Context) = ContextCompat.getColor(context, R.color.phormi_coral)
    fun onCoral(context: Context) = ContextCompat.getColor(context, R.color.phormi_on_coral)
    fun coralText(context: Context) = ContextCompat.getColor(context, R.color.phormi_coral_text)
    fun border(context: Context) = ContextCompat.getColor(context, R.color.phormi_border)
    fun success(context: Context) = ContextCompat.getColor(context, R.color.phormi_success)
    fun warning(context: Context) = ContextCompat.getColor(context, R.color.phormi_warning)
    fun danger(context: Context) = ContextCompat.getColor(context, R.color.phormi_danger)
    fun scrim(context: Context) = ContextCompat.getColor(context, R.color.phormi_scrim)
    fun mediaBackground(context: Context) = ContextCompat.getColor(context, R.color.phormi_media_background)
    fun mediaScrim(context: Context) = ContextCompat.getColor(context, R.color.phormi_media_scrim)
    fun mediaScrimStrong(context: Context) = ContextCompat.getColor(context, R.color.phormi_media_scrim_strong)
    fun onMedia(context: Context) = ContextCompat.getColor(context, R.color.phormi_on_media)

    /** Stable, semantic group palette. Index is persisted by TabGroupManager. */
    fun groupColor(context: Context, index: Int): Int = when (Math.floorMod(index, TabGroupManager.COLOR_COUNT)) {
        0 -> accent(context)
        1 -> coralText(context)
        2 -> success(context)
        3 -> warning(context)
        4 -> danger(context)
        else -> secondary(context)
    }

    fun isDark(context: Context): Boolean =
        (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    fun applyTheme(context: Context) {
        val mode = when (context.getSharedPreferences("phormi_tabs", Context.MODE_PRIVATE).getString("theme_mode", "System")?.lowercase()) {
            "dark" -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
            "light" -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
            else -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(mode)
    }
}
