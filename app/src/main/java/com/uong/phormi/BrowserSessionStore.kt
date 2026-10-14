package com.uong.phormi

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** Browser-owned state. A WebView's transient about:blank is never the source of truth. */
internal data class BrowserTabSnapshot(
    val id: Int,
    val url: String,
    val title: String,
    val lastUsed: Long,
    val createdAt: Long,
    val profile: String = "Default",
    val webViewState: String? = null,
    val recoveryPaused: Boolean = false
)

internal data class BrowserSession(
    val tabs: List<BrowserTabSnapshot>,
    val activeId: Int,
    val homeVisible: Boolean
)

internal object BrowserSessionRules {
    const val NEW_TAB = "about:blank"

    fun durableUrl(known: String, observed: String?): String =
        observed?.trim()?.takeIf { it.isNotEmpty() && it != NEW_TAB }
            ?: known.trim().ifEmpty { NEW_TAB }

    fun expired(lastUsed: Long, now: Long, retentionMs: Long?, protected: Boolean): Boolean =
        !protected && retentionMs != null && retentionMs > 0 && lastUsed > 0 &&
            now >= lastUsed && now - lastUsed >= retentionMs
}

/**
 * A versioned, transactionally-written session plus a last-known-good backup.
 * Legacy parallel arrays are mirrored for the tab overview and migrated on first read.
 * Routine saves retain cached WebView histories; only explicit tab removal removes a record.
 */
internal class BrowserSessionStore(private val prefs: SharedPreferences) {
    companion object {
        private const val KEY = "browser_session_v2"
        private const val BACKUP = "browser_session_v2_backup"
        private const val MAX_STATE_CHARS = 100_000
        private const val TOTAL_STATE_CHARS = 700_000
    }

    fun read(now: Long = System.currentTimeMillis()): BrowserSession? {
        decode(prefs.getString(KEY, null), now)?.let { return it }
        decode(prefs.getString(BACKUP, null), now)?.let { return it }
        return readLegacy(now)
    }

    fun write(session: BrowserSession, synchronous: Boolean = false) {
        if (session.tabs.isEmpty()) return // Never replace a saved session during teardown/restore.
        val json = encode(session)
        val previous = prefs.getString(KEY, null)
        val urls = JSONArray(); val titles = JSONArray(); val ids = JSONArray()
        val lastUsed = JSONArray(); val created = JSONArray(); val profiles = JSONArray()
        session.tabs.forEach {
            urls.put(it.url); titles.put(it.title); ids.put(it.id)
            lastUsed.put(it.lastUsed); created.put(it.createdAt); profiles.put(it.profile)
        }
        val editor = prefs.edit()
            .putString(KEY, json)
            .putString("tab_urls", urls.toString())
            .putString("tab_titles", titles.toString())
            .putString("tab_ids", ids.toString())
            .putString("tab_last_used", lastUsed.toString())
            .putString("tab_created_at", created.toString())
            .putString("tab_profiles", profiles.toString())
            .putInt("active_index", session.tabs.indexOfFirst { it.id == session.activeId }.coerceAtLeast(0))
            .putBoolean("home_visible", session.homeVisible)
        if (previous != json && decode(previous, System.currentTimeMillis()) != null) {
            editor.putString(BACKUP, previous)
        }
        // The new snapshot owns histories; do not duplicate megabytes in the legacy arrays.
        editor.remove("tab_webview_states")
        if (synchronous) editor.commit() else editor.apply()
    }

    internal fun encode(session: BrowserSession): String {
        var stateBudget = TOTAL_STATE_CHARS
        val states = session.tabs.sortedByDescending { it.id == session.activeId }.associate { tab ->
            val state = tab.webViewState?.takeIf { it.length <= MAX_STATE_CHARS && it.length <= stateBudget }
            stateBudget -= state?.length ?: 0
            tab.id to state
        }
        val tabs = JSONArray()
        session.tabs.forEach { tab ->
            tabs.put(JSONObject().put("id", tab.id).put("url", tab.url)
                .put("title", tab.title).put("lastUsed", tab.lastUsed).put("createdAt", tab.createdAt)
                .put("profile", tab.profile).put("state", states[tab.id] ?: "")
                .put("recoveryPaused", tab.recoveryPaused))
        }
        return JSONObject().put("version", 2).put("activeId", session.activeId)
            .put("homeVisible", session.homeVisible).put("tabs", tabs).toString()
    }

    internal fun decode(raw: String?, now: Long): BrowserSession? = runCatching {
        if (raw.isNullOrBlank()) return null
        val root = JSONObject(raw)
        if (root.optInt("version") != 2) return null
        val array = root.getJSONArray("tabs")
        val ids = mutableSetOf<Int>()
        val tabs = buildList {
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                val id = o.getInt("id")
                if (id <= 0 || !ids.add(id)) return null
                val created = o.optLong("createdAt").takeIf { it > 0 } ?: now
                add(BrowserTabSnapshot(id, o.optString("url").ifBlank { BrowserSessionRules.NEW_TAB },
                    o.optString("title").ifBlank { "New tab" },
                    o.optLong("lastUsed").takeIf { it > 0 } ?: created, created,
                    o.optString("profile").ifBlank { "Default" },
                    o.optString("state").takeIf { it.isNotEmpty() && it.length <= MAX_STATE_CHARS },
                    o.optBoolean("recoveryPaused")))
            }
        }
        if (tabs.isEmpty()) return null
        val active = root.optInt("activeId").takeIf { it in ids } ?: tabs.first().id
        BrowserSession(tabs, active, root.optBoolean("homeVisible", tabs.first { it.id == active }.url == BrowserSessionRules.NEW_TAB))
    }.getOrNull()

    private fun readLegacy(now: Long): BrowserSession? = runCatching {
        fun array(key: String) = runCatching { JSONArray(prefs.getString(key, "[]")) }.getOrElse { JSONArray() }
        val urls = array("tab_urls")
        if (urls.length() == 0) return null
        val titles = array("tab_titles"); val ids = array("tab_ids")
        val used = array("tab_last_used"); val created = array("tab_created_at")
        val profiles = array("tab_profiles"); val states = array("tab_webview_states")
        val assigned = mutableSetOf<Int>()
        var nextId = (0 until ids.length()).maxOfOrNull { ids.optInt(it) }?.coerceAtLeast(0)?.plus(1) ?: 1
        val tabs = (0 until urls.length()).map { i ->
            val oldId = ids.optInt(i)
            val id = if (oldId > 0 && assigned.add(oldId)) oldId else nextId++.also { assigned.add(it) }
            val lastUsed = used.optLong(i).takeIf { it > 0 } ?: now
            BrowserTabSnapshot(id, urls.optString(i).trim().ifBlank { BrowserSessionRules.NEW_TAB },
                titles.optString(i).ifBlank { "New tab" }, lastUsed,
                created.optLong(i).takeIf { it > 0 } ?: lastUsed,
                profiles.optString(i).ifBlank { "Default" }, states.optString(i).takeIf { it.isNotBlank() })
        }
        val active = tabs[prefs.getInt("active_index", 0).coerceIn(tabs.indices)].id
        BrowserSession(tabs, active, prefs.getBoolean("home_visible", tabs.first { it.id == active }.url == BrowserSessionRules.NEW_TAB))
    }.getOrNull()
}
