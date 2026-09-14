package com.uong.phormi

import android.content.Context

/** Persistent keyboard configuration. The keyboard remains independent from the browser UI. */
object PhormiKeyboardPreferences {
    private const val PREFS = "phormi_keyboard"
    const val KEY_SUGGESTIONS = "suggestions"
    const val KEY_AUTOCORRECT = "autocorrect"
    const val KEY_AUTO_CAPS = "auto_caps"
    const val KEY_HAPTIC = "haptic"
    const val KEY_SOUND = "sound"
    const val KEY_AI_EMOJI = "ai_emoji"
    const val KEY_LANGUAGE = "language"
    const val KEY_HEIGHT = "height"
    const val KEY_KEY_WIDTH = "key_width"
    const val KEY_THEME = "theme"
    const val KEY_WALLPAPER = "wallpaper"
    const val KEY_AI_KEY = "pollinations_key"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun suggestions(c: Context) = prefs(c).getBoolean(KEY_SUGGESTIONS, true)
    fun autocorrect(c: Context) = prefs(c).getBoolean(KEY_AUTOCORRECT, true)
    fun autoCaps(c: Context) = prefs(c).getBoolean(KEY_AUTO_CAPS, true)
    fun haptic(c: Context) = prefs(c).getBoolean(KEY_HAPTIC, true)
    fun sound(c: Context) = prefs(c).getBoolean(KEY_SOUND, false)
    fun aiEmoji(c: Context) = prefs(c).getBoolean(KEY_AI_EMOJI, true)
    fun language(c: Context) = prefs(c).getString(KEY_LANGUAGE, "English (US) + Yoruba") ?: "English (US) + Yoruba"
    fun height(c: Context) = prefs(c).getInt(KEY_HEIGHT, 100)
    fun keyWidth(c: Context) = prefs(c).getInt(KEY_KEY_WIDTH, 100)
    fun theme(c: Context) = prefs(c).getString(KEY_THEME, "system") ?: "system"
    fun wallpaper(c: Context) = prefs(c).getString(KEY_WALLPAPER, "") ?: ""
    fun pollinationsKey(c: Context) = prefs(c).getString(KEY_AI_KEY, "") ?: ""
    fun set(c: Context, key: String, value: Boolean) = prefs(c).edit().putBoolean(key, value).apply()
    fun setInt(c: Context, key: String, value: Int) = prefs(c).edit().putInt(key, value.coerceIn(75, 135)).apply()
    fun setString(c: Context, key: String, value: String) = prefs(c).edit().putString(key, value).apply()
}
