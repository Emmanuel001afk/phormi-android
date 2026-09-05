package com.uong.phormi

import android.content.Context
import org.json.JSONArray

/** Small on-device keyboard clipboard history. Sensitive clipboard content is never sent to a server. */
object PhormiKeyboardClipboardStore {
    private const val PREFS = "phormi_keyboard_clipboard"
    private const val KEY_ITEMS = "items"
    private const val MAX_ITEMS = 20

    @Synchronized
    fun list(context: Context): List<String> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ITEMS, null).orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val a = JSONArray(raw)
            buildList {
                for (i in 0 until a.length()) add(a.optString(i))
            }.filter { it.isNotBlank() }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun add(context: Context, value: String) {
        val clean = value.trim()
        if (clean.isBlank()) return
        val next = (list(context).filterNot { it == clean }.toMutableList()).apply { add(0, clean) }
        val kept = next.take(MAX_ITEMS)
        val a = JSONArray()
        kept.forEach(a::put)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_ITEMS, a.toString()).apply()
    }

    @Synchronized
    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_ITEMS).apply()
    }
}
