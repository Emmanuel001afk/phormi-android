package com.uong.phormi

import android.content.Context
import org.json.JSONArray

/** Explicitly inserted emoji only; never records emoji from passwords or private editors. */
internal object PhormiEmojiStore {
    private fun prefs(context: Context) = context.getSharedPreferences("phormi_emoji", Context.MODE_PRIVATE)
    fun recent(context: Context): List<String> = runCatching {
        val values = JSONArray(prefs(context).getString("recent", "[]"))
        (0 until values.length()).map { values.optString(it) }.filter { it.isNotBlank() }.take(48)
    }.getOrDefault(emptyList())
    fun add(context: Context, emoji: String) {
        prefs(context).edit().putString("recent", JSONArray((listOf(emoji) + recent(context).filterNot { it == emoji }).take(48)).toString()).apply()
    }
    fun tone(context: Context) = prefs(context).getInt("tone", 0).coerceIn(0, 5)
    fun setTone(context: Context, value: Int) { prefs(context).edit().putInt("tone", value.coerceIn(0, 5)).apply() }
}
