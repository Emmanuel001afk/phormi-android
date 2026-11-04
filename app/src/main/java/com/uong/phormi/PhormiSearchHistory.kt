package com.uong.phormi

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Stable, local-only URLs for Phormi Search history entries.
 *
 * The browser never resolves `phormi.local` on the network. MainActivity serves these
 * URLs from a per-tab document cache through `shouldInterceptRequest`, so Back/Forward
 * can restore the exact results document instead of landing on `about:blank`.
 */
object PhormiSearchHistory {
    const val HOST = "phormi.local"
    const val ORIGIN = "https://$HOST"
    private const val SEARCH_PATH = "/search"
    private const val REFRESH_PATH = "/refresh"

    fun searchUrl(query: String): String =
        "$ORIGIN$SEARCH_PATH?q=" + URLEncoder.encode(query.trim(), "UTF-8")

    fun isSearchUrl(url: String?): Boolean = path(url) == SEARCH_PATH && !queryFromUrl(url).isNullOrBlank()

    fun isRefreshUrl(url: String?): Boolean = path(url) == REFRESH_PATH && !queryFromUrl(url).isNullOrBlank()

    fun isLocalSearchUrl(url: String?): Boolean = isSearchUrl(url) || isRefreshUrl(url)

    fun queryFromUrl(url: String?): String? {
        val uri = parse(url) ?: return null
        if (uri.host != HOST) return null
        val path = uri.path.orEmpty()
        if (path != SEARCH_PATH && path != REFRESH_PATH) return null
        val rawQuery = uri.rawQuery ?: return null
        for (part in rawQuery.split('&')) {
            val eq = part.indexOf('=')
            val key = if (eq >= 0) part.substring(0, eq) else part
            if (decode(key) != "q") continue
            val value = if (eq >= 0) part.substring(eq + 1) else ""
            return decode(value).takeIf { it.isNotBlank() }
        }
        return null
    }

    private fun path(url: String?): String? {
        val uri = parse(url) ?: return null
        return if (uri.host == HOST) uri.path.orEmpty() else null
    }

    private fun parse(url: String?): URI? {
        if (url.isNullOrBlank()) return null
        return runCatching { URI(url) }.getOrNull()
    }

    private fun decode(value: String): String =
        runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
}

/**
 * Bounded LRU cache of rendered search documents for one tab.
 *
 * Keys are the stable [PhormiSearchHistory] URLs. Values are complete HTML documents.
 * The cache is intentionally in-memory only: after process death the tab restores by
 * re-running the persisted query, and old history entries can be reconstructed on demand.
 */
class PhormiSearchDocumentCache(
    private val maxEntries: Int = 8,
    private val maxBytes: Long = 4L * 1024L * 1024L
) {
    private val lock = Any()
    private val documents = object : LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
            size > maxEntries
    }
    private var bytes: Long = 0

    fun put(url: String, html: String) {
        if (!PhormiSearchHistory.isSearchUrl(url) || html.isEmpty()) return
        synchronized(lock) {
            val previous = documents.put(url, html)
            bytes += html.toByteArray(Charsets.UTF_8).size
            if (previous != null) bytes -= previous.toByteArray(Charsets.UTF_8).size
            evictIfNeeded()
        }
    }

    fun get(url: String): String? = synchronized(lock) { documents[url] }

    fun latest(): String? = synchronized(lock) { documents.values.lastOrNull() }

    fun clear() = synchronized(lock) {
        documents.clear()
        bytes = 0
    }

    fun size(): Int = synchronized(lock) { documents.size }

    private fun evictIfNeeded() {
        while (bytes > maxBytes && documents.isNotEmpty()) {
            val eldest = documents.entries.iterator().next()
            bytes -= eldest.value.toByteArray(Charsets.UTF_8).size
            documents.remove(eldest.key)
        }
    }
}
