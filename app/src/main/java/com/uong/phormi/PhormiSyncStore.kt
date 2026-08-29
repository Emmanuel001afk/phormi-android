package com.uong.phormi

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Cross-device, sync-safe browser state.
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
        val count = minOf(urls.length(), MAX_TABS)

        for (index in 0 until count) {
            val url = urls.optString(index, NEW_TAB_URL).trim().ifBlank { NEW_TAB_URL }
            val title = titles.optString(index, "")
            tabs.put(
                JSONObject()
                    .put("id", "tab-${index + 1}")
                    .put("url", url)
                    .put("title", title)
                    .put("updatedAt", System.currentTimeMillis())
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
            .put("searchProvider", prefs.getString(KEY_SEARCH_PROVIDER, "Phormi Search"))
            .put("themeMode", prefs.getString(KEY_THEME_MODE, "system"))
            .put("dailyAccent", prefs.getBoolean(KEY_DAILY_ACCENT, false))
            .put("wallpaperReference", prefs.getString(KEY_WALLPAPER_URI, null) ?: JSONObject.NULL)
            .put("keepScreenOn", prefs.getBoolean(KEY_KEEP_SCREEN_ON, false))
            .put("customShortcuts", parseShortcuts(prefs.getString(KEY_CUSTOM_SHORTCUTS, "[]")))
    }

    /** Import after authenticating the same Phormi account. */
    fun importSnapshot(snapshot: JSONObject): Boolean {
        if (snapshot.optInt("schema", -1) != 1 || snapshot.optString("product") != "Phormi") {
            return false
        }

        val tabs = snapshot.optJSONArray("tabs") ?: JSONArray()
        val urls = JSONArray()
        val titles = JSONArray()
        val count = minOf(tabs.length(), MAX_TABS)

        for (i in 0 until count) {
            val tab = tabs.optJSONObject(i) ?: continue
            val url = tab.optString("url", NEW_TAB_URL).trim().ifBlank { NEW_TAB_URL }
            urls.put(url)
            titles.put(tab.optString("title", ""))
        }
        if (urls.length() == 0) {
            urls.put(NEW_TAB_URL)
            titles.put("")
        }

        val active = snapshot.optInt("activeTabIndex", 0).coerceIn(0, urls.length() - 1)
        val editor = prefs.edit()
            .putString(KEY_TAB_URLS, urls.toString())
            .putString(KEY_TAB_TITLES, titles.toString())
            .putInt(KEY_ACTIVE_INDEX, active)
            .putString(KEY_SEARCH_PROVIDER, snapshot.optString("searchProvider", "Phormi Search"))
            .putString(KEY_THEME_MODE, snapshot.optString("themeMode", "system"))
            .putBoolean(KEY_DAILY_ACCENT, snapshot.optBoolean("dailyAccent", false))
            .putBoolean(KEY_KEEP_SCREEN_ON, snapshot.optBoolean("keepScreenOn", false))
            .putString(KEY_CUSTOM_SHORTCUTS, parseShortcuts(snapshot.opt("customShortcuts")).toString())

        if (snapshot.has("wallpaperReference") && !snapshot.isNull("wallpaperReference")) {
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
        private const val MAX_TABS = 5
    }
}
