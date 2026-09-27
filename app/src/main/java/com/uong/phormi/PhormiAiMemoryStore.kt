package com.uong.phormi

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Short, device-local AI memory with user-controlled retention. */
object PhormiAiMemoryStore {
    private const val PREFS = "phormi_ai_memory"
    private const val KEY_ENTRIES = "entries"
    private const val KEY_RETENTION_MS = "retention_ms"
    private const val DEFAULT_RETENTION_MS = 30L * 24L * 60L * 60L * 1000L
    private const val MAX_ENTRIES = 80

    data class Entry(val at: Long, val user: String, val assistant: String)

    fun retentionMs(context: Context): Long = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_RETENTION_MS, DEFAULT_RETENTION_MS)

    fun setRetentionMs(context: Context, value: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(KEY_RETENTION_MS, value).apply()
        prune(context)
    }

    fun clear(context: Context) { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_ENTRIES).apply() }

    fun add(context: Context, user: String, assistant: String) {
        if (user.isBlank() && assistant.isBlank()) return
        val list = read(context).toMutableList()
        list += Entry(System.currentTimeMillis(), user.take(6000), assistant.take(6000))
        write(context, list.takeLast(MAX_ENTRIES))
        prune(context)
    }

    fun context(context: Context, maxEntries: Int = 8): String {
        val entries = prune(context).takeLast(maxEntries.coerceAtLeast(1))
        if (entries.isEmpty()) return ""
        return entries.joinToString("\n---\n") { entry ->
            "User: ${entry.user}\nAI: ${entry.assistant}"
        }.take(24000)
    }

    private fun read(context: Context): List<Entry> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ENTRIES, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    add(Entry(o.optLong("at"), o.optString("user"), o.optString("assistant")))
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun write(context: Context, list: List<Entry>) {
        val arr = JSONArray()
        list.forEach { entry ->
            arr.put(JSONObject().put("at", entry.at).put("user", entry.user).put("assistant", entry.assistant))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_ENTRIES, arr.toString()).apply()
    }

    private fun prune(context: Context): List<Entry> {
        val retention = retentionMs(context)
        if (retention <= 0L) { clear(context); return emptyList() }
        val all = read(context)
        val cutoff = System.currentTimeMillis() - retention
        val kept = all.filter { it.at >= cutoff }
        if (kept.size != all.size) write(context, kept)
        return kept
    }
}