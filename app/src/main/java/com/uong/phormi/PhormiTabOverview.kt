package com.uong.phormi

import java.net.URI
import java.util.Locale

/** Pure presentation/state rules shared by the browser, tab overview and regression tests. */
object PhormiTabOverview {
    const val NEW_TAB_URL = "about:blank"

    data class TabEntry(
        val id: Int,
        val title: String,
        val url: String,
        val profile: String = "Default",
        val groupId: String = "",
        val groupName: String = "",
        val ghost: Boolean = false
    )
    data class GroupInfo(val id: String, val name: String)
    data class Section(val key: String, val title: String, val tabs: List<TabEntry>, val groupId: String? = null)

    /**
     * A newly created WebView reports about:blank before its first commit. The
     * requested/last committed navigation is authoritative during that interval;
     * otherwise a pause or overview snapshot can erase the destination metadata.
     * Home explicitly resets lastKnownUrl, so a real return Home still stays blank.
     */
    fun navigationUrl(deferredUrl: String?, lastKnownUrl: String?, webViewUrl: String?): String =
        clean(deferredUrl) ?: clean(lastKnownUrl) ?: clean(webViewUrl) ?: NEW_TAB_URL

    fun isNewTab(url: String?): Boolean = clean(url)?.equals(NEW_TAB_URL, true) != false
    fun host(url: String?): String = runCatching { URI(url.orEmpty()).host }.getOrNull().orEmpty()
        .lowercase(Locale.ROOT).removePrefix("www.")

    fun displayTitle(title: String?, url: String?): String {
        val value = title.orEmpty().trim()
        val placeholder = value.isBlank() || value.equals("New tab", true) ||
            value.equals("Tab", true) || value == url || value == host(url)
        if (!isNewTab(url) && placeholder) {
            if (PhormiSearchHistory.isSearchUrl(url.orEmpty())) {
                return PhormiSearchHistory.queryFromUrl(url.orEmpty())?.let { "Search · $it" } ?: "Phormi Search"
            }
            return host(url).ifBlank { value.ifBlank { "New tab" } }
        }
        return value.ifBlank { "New tab" }
    }

    fun address(url: String?): String = if (isNewTab(url)) "Ready to browse" else url.orEmpty().trim()
    fun monogram(url: String?): String = host(url).firstOrNull()?.uppercase(Locale.ROOT) ?: "+"

    fun filter(tabs: List<TabEntry>, query: String): List<TabEntry> {
        val q = query.trim().lowercase(Locale.ROOT)
        if (q.isEmpty()) return tabs
        return tabs.filter { tab ->
            listOf(displayTitle(tab.title, tab.url), tab.url, host(tab.url), tab.profile, tab.groupName)
                .any { it.lowercase(Locale.ROOT).contains(q) }
        }
    }

    /** Every tab appears exactly once. Current > private > named groups > websites > empty tabs. */
    fun sections(tabs: List<TabEntry>, activeId: Int, groups: List<GroupInfo> = emptyList()): List<Section> {
        if (tabs.isEmpty()) return emptyList()
        val out = mutableListOf<Section>()
        val current = tabs.filter { it.id == activeId }
        if (current.isNotEmpty()) out += Section("current", "Current tab", current)
        val remaining = tabs.filterNot { it.id == activeId }.toMutableList()
        fun take(label: String, key: String, groupId: String? = null, predicate: (TabEntry) -> Boolean) {
            val members = remaining.filter(predicate)
            if (members.isNotEmpty()) {
                out += Section(key, label, members, groupId)
                remaining.removeAll(members.toSet())
            }
        }
        take("Ghost tabs", "ghost") { it.ghost }
        groups.forEach { group -> take(group.name, "group:${group.id}", group.id) { it.groupId == group.id } }
        take("Other tabs", "websites") { !isNewTab(it.url) }
        take("New tabs", "new") { isNewTab(it.url) }
        return out
    }

    fun normalizeMode(mode: String?): String = when (mode) {
        "horizontal" -> "horizontal"
        "grid" -> "grid"
        else -> "vertical"
    }
    fun columns(mode: String, screenWidthDp: Int, fontScale: Float): Int =
        if (mode == "grid" && screenWidthDp >= 360 && fontScale <= 1.3f) 2 else 1
    fun carouselWidth(screenWidthDp: Int): Int = (screenWidthDp * 0.84f).toInt().coerceIn(240, 320)
    fun countLabel(count: Int): String = "$count ${if (count == 1) "tab" else "tabs"}"
    private fun clean(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }
}
