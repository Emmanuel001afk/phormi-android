package com.uong.phormi

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Small local favorites store, deliberately separate from Bookmarks. */
object PhormiFavorites {
    private const val PREFS = "phormi_favorites"
    private const val KEY = "items"
    data class Favorite(val title: String, val url: String, val addedAt: Long)

    fun getAll(context: Context): List<Favorite> {
        val arr = runCatching { JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]")) }.getOrElse { JSONArray() }
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val url = o.optString("url").trim()
                if (url.isNotBlank()) add(Favorite(o.optString("title", url).ifBlank { url }, url, o.optLong("addedAt", 0L)))
            }
        }.sortedByDescending { it.addedAt }
    }

    fun contains(context: Context, url: String): Boolean = getAll(context).any { it.url == url }

    fun add(context: Context, title: String, url: String) {
        if (!url.startsWith("http")) return
        if (contains(context, url)) return
        val arr = JSONArray()
        getAll(context).forEach { arr.put(JSONObject().put("title", it.title).put("url", it.url).put("addedAt", it.addedAt)) }
        arr.put(JSONObject().put("title", title.ifBlank { url }).put("url", url).put("addedAt", System.currentTimeMillis()))
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }

    fun remove(context: Context, url: String) {
        val arr = JSONArray()
        getAll(context).filterNot { it.url == url }.forEach { arr.put(JSONObject().put("title", it.title).put("url", it.url).put("addedAt", it.addedAt)) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }

    fun toggle(context: Context, title: String, url: String): Boolean {
        return if (contains(context, url)) { remove(context, url); false } else { add(context, title, url); true }
    }
}
