package com.uong.phormi

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** System-clipboard history for the Phormi IME. Each clipboard entry remains one item, including newlines. */
object PhormiKeyboardClipboardStore {
    data class Item(val text: String, val pinned: Boolean, val createdAt: Long)

    private const val PREFS = "phormi_keyboard_clipboard"
    private const val KEY_ITEMS = "items_v2"
    private const val MAX_ITEMS = 50

    @Synchronized fun list(context: Context): List<Item> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ITEMS, "[]") ?: "[]"
        return runCatching {
            val a = JSONArray(raw)
            buildList {
                for (i in 0 until a.length()) {
                    val o = a.optJSONObject(i) ?: continue
                    val text = o.optString("text", "")
                    if (text.isNotEmpty()) add(Item(text, o.optBoolean("pinned", false), o.optLong("createdAt", 0L)))
                }
            }
        }.getOrDefault(emptyList())
    }

    @Synchronized fun record(context: Context, value: CharSequence?) {
        val text = value?.toString().orEmpty()
        if (text.isEmpty()) return
        val existing = list(context)
        val old = existing.firstOrNull { it.text == text }
        val next = mutableListOf(Item(text, old?.pinned == true, System.currentTimeMillis()))
        next += existing.filterNot { it.text == text }
        save(context, next)
    }

    @Synchronized fun togglePinned(context: Context, text: String) {
        val next = list(context).map { if (it.text == text) it.copy(pinned = !it.pinned) else it }
        save(context, next)
    }

    @Synchronized fun remove(context: Context, text: String) = save(context, list(context).filterNot { it.text == text })

    @Synchronized fun clearUnpinned(context: Context) = save(context, list(context).filter { it.pinned })

    @Synchronized fun clear(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_ITEMS).apply()

    fun capturePrimaryClipboard(context: Context) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val clip = cm.primaryClip ?: return
        if (clip.itemCount == 0) return
        val item = clip.getItemAt(0)
        item.coerceToText(context)?.let { record(context, it) }
    }

    private fun save(context: Context, source: List<Item>) {
        val pinned = source.filter { it.pinned }
        val unpinned = source.filterNot { it.pinned }.take(MAX_ITEMS - pinned.size.coerceAtMost(MAX_ITEMS))
        val a = JSONArray()
        (pinned + unpinned).take(MAX_ITEMS).forEach { a.put(JSONObject().put("text", it.text).put("pinned", it.pinned).put("createdAt", it.createdAt)) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_ITEMS, a.toString()).apply()
    }
}
