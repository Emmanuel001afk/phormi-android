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
    private const val HEIGHT = "keyboard_height"
    private const val AI_EMOJI = "ai_emoji"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun suggestions(context: Context) = prefs(context).getBoolean(SUGGESTIONS, true)
    fun autocorrect(context: Context) = prefs(context).getBoolean(AUTOCORRECT, true)
    fun autoCaps(context: Context) = prefs(context).getBoolean(AUTO_CAPS, true)
    fun haptic(context: Context) = prefs(context).getBoolean(HAPTIC, true)
    fun sound(context: Context) = prefs(context).getBoolean(SOUND, false)
    fun aiEmoji(context: Context) = prefs(context).getBoolean(AI_EMOJI, true)

    /** 0..6 maps from Extra short through Extra tall. */
    fun height(context: Context): Int = prefs(context).getInt(HEIGHT, 3).coerceIn(0, 6)
    fun heightScale(context: Context): Float {
        // The IME renderer calls this for every keyboard surface. Starting the
        // enhancement loop here keeps live suggestions/reactions tied to the
        // actual system-IME lifecycle without adding another visible service.
        PhormiKeyboardAiBridge.start(context)
        return when (height(context)) {
            0 -> 0.85f
            1 -> 0.92f
            2 -> 0.97f
            3 -> 1.00f
            4 -> 1.08f
            5 -> 1.17f
            else -> 1.27f
        }
    }

    fun set(context: Context, key: String, value: Boolean) = prefs(context).edit().putBoolean(key, value).apply()
    fun setHeight(context: Context, value: Int) = prefs(context).edit().putInt(HEIGHT, value.coerceIn(0, 6)).apply()

    const val KEY_SUGGESTIONS = SUGGESTIONS
    const val KEY_AUTOCORRECT = AUTOCORRECT
    const val KEY_AUTO_CAPS = AUTO_CAPS
    const val KEY_HAPTIC = HAPTIC
    const val KEY_SOUND = SOUND
    const val KEY_AI_EMOJI = AI_EMOJI
}
