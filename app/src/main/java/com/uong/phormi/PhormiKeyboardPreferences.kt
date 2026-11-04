package com.uong.phormi

import android.content.Context

/** Local-only keyboard preferences. */
object PhormiKeyboardPreferences {
    private const val PREFS = "phormi_keyboard"
    private const val SUGGESTIONS = "suggestions"
    private const val AUTOCORRECT = "autocorrect"
    private const val AUTO_CAPS = "auto_caps"
    private const val HAPTIC = "haptic"
    private const val SOUND = "sound"
    private const val HEIGHT_SCALE = "keyboard_height_scale"
    private const val TEXT_SCALE = "keyboard_text_scale"
    private const val WIDTH_SCALE = "keyboard_width_scale"
    private const val OFFSET_X = "keyboard_offset_x"
    private const val OFFSET_Y = "keyboard_offset_y"
    private const val FLOATING = "keyboard_floating"
    private const val AI_EMOJI = "ai_emoji"
    private const val CLOUD_SUGGESTIONS = "online_suggestions"
    private const val THEME = "keyboard_theme"
    private const val WALLPAPER_URI = "keyboard_wallpaper_uri"
    private const val POLLINATIONS_KEY = "pollinations_api_key"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    const val KEY_LANGUAGE = "selected_language"
    fun language(context: Context): String = prefs(context).getString(KEY_LANGUAGE, "").orEmpty().takeIf { it in PhormiKeyboardLayouts.supported }.orEmpty()
    fun setLanguage(context: Context, language: String) { prefs(context).edit().putString(KEY_LANGUAGE, language).apply() }

    const val KEY_GLIDE = "offline_glide"
    fun glide(context: Context) = prefs(context).getBoolean(KEY_GLIDE, true)

    const val KEY_ONLINE_SUGGESTIONS = "online_suggestions"
    fun onlineSuggestions(context: Context) = prefs(context).let {
        if (it.contains(KEY_ONLINE_SUGGESTIONS)) it.getBoolean(KEY_ONLINE_SUGGESTIONS, false)
        else it.getBoolean("cloud_suggestions", false)
    }

    fun suggestions(context: Context) = prefs(context).getBoolean(SUGGESTIONS, true)
    fun autocorrect(context: Context) = prefs(context).getBoolean(AUTOCORRECT, true)
    fun autoCaps(context: Context) = prefs(context).getBoolean(AUTO_CAPS, true)
    fun haptic(context: Context) = prefs(context).getBoolean(HAPTIC, true)
    fun sound(context: Context) = prefs(context).getBoolean(SOUND, false)
    fun aiEmoji(context: Context) = prefs(context).getBoolean(AI_EMOJI, true)

    /**
     * Sending typed prefixes to third-party dictionary services is opt-in and off by
     * default. The keyboard is offline-first unless the user explicitly enables this.
     */
    fun cloudSuggestions(context: Context) = onlineSuggestions(context)

    fun pollinationsKey(context: Context): String =
        prefs(context).getString(POLLINATIONS_KEY, "").orEmpty()

    fun setPollinationsKey(context: Context, value: String?) =
        prefs(context).edit().putString(POLLINATIONS_KEY, value?.trim().orEmpty()).apply()

    fun heightScale(context: Context): Float = prefs(context).getFloat(HEIGHT_SCALE, 1.0f).coerceIn(0.60f, 1.40f)
    fun keyTextScale(context: Context): Float = prefs(context).getFloat(TEXT_SCALE, 1.0f).coerceIn(0.85f, 1.25f)
    fun widthScale(context: Context): Float = prefs(context).getFloat(WIDTH_SCALE, 1.00f).coerceIn(0.55f, 1.00f)
    fun offsetX(context: Context): Float = prefs(context).getFloat(OFFSET_X, 0f).coerceIn(-0.9f, 0.9f)
    fun offsetY(context: Context): Float = prefs(context).getFloat(OFFSET_Y, 0f).coerceIn(-0.9f, 0.9f)
    fun floating(context: Context): Boolean = prefs(context).getBoolean(FLOATING, false)
    fun setFloating(context: Context, value: Boolean) = prefs(context).edit().putBoolean(FLOATING, value).apply()
    fun theme(context: Context): Int = prefs(context).getInt(THEME, 0).coerceIn(0, 7)
    fun setTheme(context: Context, value: Int) = prefs(context).edit().putInt(THEME, value.coerceIn(0, 7)).apply()
    fun wallpaperUri(context: Context): String? = prefs(context).getString(WALLPAPER_URI, null)
    fun setWallpaperUri(context: Context, value: String?) = prefs(context).edit().apply {
        if (value.isNullOrBlank()) remove(WALLPAPER_URI) else putString(WALLPAPER_URI, value)
    }.apply()
    fun set(context: Context, key: String, value: Boolean) = prefs(context).edit().putBoolean(key, value).apply()
    fun setHeightScale(context: Context, value: Float) = prefs(context).edit().putFloat(HEIGHT_SCALE, value.coerceIn(0.60f, 1.40f)).apply()
    fun setKeyTextScale(context: Context, value: Float) = prefs(context).edit().putFloat(TEXT_SCALE, value.coerceIn(0.85f, 1.25f)).apply()
    fun setWidthScale(context: Context, value: Float) = prefs(context).edit().putFloat(WIDTH_SCALE, value.coerceIn(0.55f, 1.00f)).apply()
    fun setOffsetX(context: Context, value: Float) = prefs(context).edit().putFloat(OFFSET_X, value.coerceIn(-0.9f, 0.9f)).apply()
    fun setOffsetY(context: Context, value: Float) = prefs(context).edit().putFloat(OFFSET_Y, value.coerceIn(-0.9f, 0.9f)).apply()
    fun resetSize(context: Context) = prefs(context).edit()
        .putFloat(HEIGHT_SCALE, 1.0f)
        .putFloat(TEXT_SCALE, 1.0f)
        .putFloat(WIDTH_SCALE, 1.0f)
        .putFloat(OFFSET_X, 0f)
        .putFloat(OFFSET_Y, 0f)
        .putBoolean(FLOATING, false)
        .apply()

    const val KEY_SUGGESTIONS = SUGGESTIONS
    const val KEY_AUTOCORRECT = AUTOCORRECT
    const val KEY_AUTO_CAPS = AUTO_CAPS
    const val KEY_HAPTIC = HAPTIC
    const val KEY_SOUND = SOUND
    const val KEY_AI_EMOJI = AI_EMOJI
    const val KEY_CLOUD_SUGGESTIONS = CLOUD_SUGGESTIONS
}
