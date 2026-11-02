package com.uong.phormi

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Experimental snapshot serializer only. Not wired to an authenticated cloud-sync service.
 *
 * The payload mirrors the shared Phormi sync contract. It deliberately excludes
 * website cookies, passwords, authentication tokens, downloads and private
 * browsing data. Local tab arrays are stored as ordered JSON, not StringSet,
 * because StringSet does not preserve tab order.
 */
class PhormiSyncStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val devicePrefs: SharedPreferences =
        context.getSharedPreferences(DEVICE_PREFS_NAME, Context.MODE_PRIVATE)

    fun deviceId(): String {
        val existing = devicePrefs.getString(KEY_DEVICE_ID, null)
        if (!existing.isNullOrBlank()) return existing
        val created = UUID.randomUUID().toString()
        devicePrefs.edit().putString(KEY_DEVICE_ID, created).apply()
        return created
    }

    /** Export only state permitted by shared/phormi_sync_contract.json. */
    fun exportSnapshot(accountId: String? = null): JSONObject {
        val urls = readArray(KEY_TAB_URLS)
        val titles = readArray(KEY_TAB_TITLES)
        val tabs = JSONArray()
        require(urls.length() <= MAX_TABS) { "Snapshot exceeds $MAX_TABS tabs; no data was silently truncated" }
        val ids = readArray("tab_ids")
        val updated = readArray("tab_last_used")
        val count = urls.length()

        for (index in 0 until count) {
            val url = urls.optString(index, NEW_TAB_URL).trim().ifBlank { NEW_TAB_URL }
            val title = titles.optString(index, "")
            tabs.put(
                JSONObject()
                    .put("id", "${deviceId()}:tab-${ids.optInt(index, index + 1)}")
                    .put("url", url)
                    .put("title", title)
                    .put("updatedAt", updated.optLong(index, 0L))
            )
        }

        return JSONObject()
            .put("schema", 1)
            .put("product", "Phormi")
            .put("accountId", accountId ?: JSONObject.NULL)
            .put("deviceId", deviceId())
            .put("updatedAt", System.currentTimeMillis())
            .put("tabs", tabs)
            .put("activeTabIndex", prefs.getInt(KEY_ACTIVE_INDEX, 0).coerceIn(0, maxOf(0, count - 1)))
            .put("searchProvider", prefs.getString(KEY_SEARCH_PROVIDER, "Google"))
            .put("themeMode", prefs.getString(KEY_THEME_MODE, "system"))
            .put("dailyAccent", prefs.getBoolean(KEY_DAILY_ACCENT, false))
            .put("wallpaperReference", prefs.getString(KEY_WALLPAPER_URI, null) ?: JSONObject.NULL)
            .put("keepScreenOn", prefs.getBoolean(KEY_KEEP_SCREEN_ON, false))
            .put("customShortcuts", parseShortcuts(prefs.getString(KEY_CUSTOM_SHORTCUTS, "[]")))
    }

    /** Replace local metadata only. A future caller must separately authenticate, authorize,
     * merge conflicts and ask the user before invoking this; this method performs no networking. */
    fun importSnapshot(snapshot: JSONObject): Boolean {
        if (snapshot.optInt("schema", -1) != 1 || snapshot.optString("product") != "Phormi") {
            return false
        }

        val tabs = snapshot.optJSONArray("tabs") ?: JSONArray()
        val urls = JSONArray()
        val titles = JSONArray()
        if (tabs.length() > MAX_TABS) return false
        val count = tabs.length()

        for (i in 0 until count) {
            val tab = tabs.optJSONObject(i) ?: continue
            val raw = tab.optString("url", NEW_TAB_URL).trim().ifBlank { NEW_TAB_URL }
            val url = if (raw == NEW_TAB_URL) raw else PhormiNavigationInput.webUrl(raw) ?: return false
            urls.put(url)
            titles.put(tab.optString("title", ""))
        }
        if (urls.length() == 0) {
            urls.put(NEW_TAB_URL)
            titles.put("")
        }

        val active = snapshot.optInt("activeTabIndex", 0).coerceIn(0, urls.length() - 1)
        val oldIds = readArray("tab_ids")
        val highestId = (0 until oldIds.length()).maxOfOrNull { oldIds.optInt(it, 0) } ?: 0
        if (highestId > Int.MAX_VALUE - urls.length()) return false
        val freshIds = JSONArray()
        repeat(urls.length()) { freshIds.put(highestId + it + 1) }
        val editor = prefs.edit()
            .putString(KEY_TAB_URLS, urls.toString())
            .putString(KEY_TAB_TITLES, titles.toString())
            // Never attach old profile/history/lock IDs by array position to imported URLs.
            .putString("tab_ids", freshIds.toString()).remove("tab_profiles").remove("tab_webview_states")
            .remove("tab_search_queries").remove("tab_created_at").remove("tab_last_used")
            .putInt(KEY_ACTIVE_INDEX, active)
            .putString(KEY_SEARCH_PROVIDER, snapshot.optString("searchProvider", "Google"))
            .putString(KEY_THEME_MODE, snapshot.optString("themeMode", "system"))
            .putBoolean(KEY_DAILY_ACCENT, snapshot.optBoolean("dailyAccent", false))
            .putBoolean(KEY_KEEP_SCREEN_ON, snapshot.optBoolean("keepScreenOn", false))
            .putString(KEY_CUSTOM_SHORTCUTS, parseShortcuts(snapshot.opt("customShortcuts")).toString())

        if (snapshot.optString("deviceId") == deviceId() && snapshot.has("wallpaperReference") && !snapshot.isNull("wallpaperReference")) {
            editor.putString(KEY_WALLPAPER_URI, snapshot.optString("wallpaperReference"))
        } else {
            editor.remove(KEY_WALLPAPER_URI)
        }
        return editor.commit()
    }

    private fun readArray(key: String): JSONArray {
        val raw = prefs.getString(key, "[]") ?: "[]"
        return runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
    }

    private fun parseShortcuts(value: Any?): JSONArray {
        return when (value) {
            is JSONArray -> value
            is String -> runCatching { JSONArray(value) }.getOrElse { JSONArray() }
            else -> JSONArray()
        }
    }

    companion object {
        private const val PREFS_NAME = "phormi_tabs"
        private const val DEVICE_PREFS_NAME = "phormi_device"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_TAB_URLS = "tab_urls"
        private const val KEY_TAB_TITLES = "tab_titles"
        private const val KEY_ACTIVE_INDEX = "active_index"
        private const val KEY_SEARCH_PROVIDER = "search_provider"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_DAILY_ACCENT = "daily_accent"
        private const val KEY_WALLPAPER_URI = "wallpaper_uri"
        private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        private const val KEY_CUSTOM_SHORTCUTS = "custom_shortcuts"
        private const val NEW_TAB_URL = "about:blank"
        private const val MAX_TABS = 500
    }
}
