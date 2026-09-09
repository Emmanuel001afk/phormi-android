package com.uong.phormi

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** System-clipboard history for the Phormi IME. Sensitive clipboard entries are never persisted. */
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
                    if (text.isNotEmpty() && !looksSensitive(text)) add(Item(text, o.optBoolean("pinned", false), o.optLong("createdAt", 0L)))
                }
            }
        }.getOrDefault(emptyList())
    }

    @Synchronized fun record(context: Context, value: CharSequence?) {
        val text = value?.toString().orEmpty()
        if (text.isEmpty() || looksSensitive(text)) return
        val existing = list(context)
        val old = existing.firstOrNull { it.text == text }
        val next = mutableListOf(Item(text, old?.pinned == true, System.currentTimeMillis()))
        next += existing.filterNot { it.text == text }
        save(context, next)
    }

    @Synchronized fun togglePinned(context: Context, text: String) {
        if (looksSensitive(text)) return
        val next = list(context).map { if (it.text == text) it.copy(pinned = !it.pinned) else it }
        save(context, next)
    }

    @Synchronized fun remove(context: Context, text: String) = save(context, list(context).filterNot { it.text == text })

    @Synchronized fun clearUnpinned(context: Context) = save(context, list(context).filter { it.pinned })

    @Synchronized fun clear(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_ITEMS).apply()

    fun capturePrimaryClipboard(context: Context) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val clip = cm.primaryClip ?: return
        if (clip.itemCount == 0 || isSensitive(clip.description)) return
        clip.getItemAt(0).coerceToText(context)?.let { record(context, it) }
    }

    private fun isSensitive(description: ClipDescription?): Boolean {
        if (description == null) return false
        if (android.os.Build.VERSION.SDK_INT >= 24) {
            if (description.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false) == true) return true
        }
        return false
    }

    /** Conservative local filters for common secrets when the source app omitted Android's sensitive flag. */
    private fun looksSensitive(text: String): Boolean {
        val compact = text.replace(Regex("\\s+"), "")
        if (compact.length in 12..19 && compact.all(Char::isDigit)) return true
        if (text.trim().matches(Regex("\\d{4,8}"))) return true
        if (text.contains("BEGIN PRIVATE KEY") || text.contains("BEGIN RSA PRIVATE KEY") || text.contains("BEGIN OPENSSH PRIVATE KEY")) return true
        if (Regex("(?i)\\b(bearer|authorization)\\s+[A-Za-z0-9._~+/=-]{12,}").containsMatchIn(text)) return true
        if (Regex("(?i)\\b(api[_-]?key|secret|access[_-]?token|refresh[_-]?token)\\s*[:=]\\s*\\S+").containsMatchIn(text)) return true
        return false
    }

    private fun save(context: Context, source: List<Item>) {
        val safe = source.filterNot { looksSensitive(it.text) }
        val pinned = safe.filter { it.pinned }
        val unpinned = safe.filterNot { it.pinned }.take(MAX_ITEMS - pinned.size.coerceAtMost(MAX_ITEMS))
        val a = JSONArray()
        (pinned + unpinned).take(MAX_ITEMS).forEach { a.put(JSONObject().put("text", it.text).put("pinned", it.pinned).put("createdAt", it.createdAt)) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_ITEMS, a.toString()).apply()
    }
}
