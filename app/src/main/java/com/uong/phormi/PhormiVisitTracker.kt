package com.uong.phormi

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Local visit-frequency store used only for normal-tab Quick Access suggestions. */
object PhormiVisitTracker {
    private const val PREFS = "phormi_most_visited"
    private const val KEY = "sites"
    private const val THRESHOLD = 10

    data class Site(val title: String, val url: String, val host: String, val visits: Int)

    fun record(context: Context, title: String?, url: String?) {
        val clean = url?.trim().orEmpty()
        if (!clean.startsWith("http://") && !clean.startsWith("https://")) return
        val host = runCatching { Uri.parse(clean).host.orEmpty().lowercase(Locale.US).removePrefix("www.") }.getOrDefault("")
        if (host.isBlank() || host == "phormi.local") return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val old = read(prefs)
        val existing = old[host]
        old[host] = Site(title?.ifBlank { host } ?: host, clean, host, (existing?.visits ?: 0) + 1)
        val arr = JSONArray()
        old.values.sortedByDescending { it.visits }.take(50).forEach {
            arr.put(JSONObject().put("title", it.title).put("url", it.url).put("host", it.host).put("visits", it.visits))
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    fun top(context: Context, limit: Int = 10): List<Site> =
        read(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)).values
            .filter { it.visits >= THRESHOLD }
            .sortedWith(compareByDescending<Site> { it.visits }.thenBy { it.host })
            .take(limit)

    /** Remove one site's Quick Access source record without touching unrelated history. */
    fun remove(context: Context, urlOrHost: String) {
        val key = urlOrHost.trim().lowercase(Locale.US).let { value ->
            runCatching { Uri.parse(value).host?.removePrefix("www.") }.getOrNull()?.takeIf { it.isNotBlank() } ?: value.removePrefix("www.")
        }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val remaining = read(prefs).filterKeys { it != key }
        val arr = JSONArray()
        remaining.values.forEach { arr.put(JSONObject().put("title", it.title).put("url", it.url).put("host", it.host).put("visits", it.visits)) }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    fun clear(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()

    private fun read(prefs: android.content.SharedPreferences): MutableMap<String, Site> {
        val result = linkedMapOf<String, Site>()
        val arr = runCatching { JSONArray(prefs.getString(KEY, "[]") ?: "[]") }.getOrElse { JSONArray() }
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val host = o.optString("host").trim().lowercase(Locale.US)
            if (host.isNotBlank()) result[host] = Site(
                o.optString("title", host).ifBlank { host },
                o.optString("url", "https://$host"),
                host,
                o.optInt("visits", 0)
            )
        }
        return result
    }
}
