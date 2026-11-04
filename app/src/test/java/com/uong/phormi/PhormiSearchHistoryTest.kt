package com.uong.phormi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhormiSearchHistoryTest {
    @Test
    fun `search urls are stable local history entries`() {
        val url = PhormiSearchHistory.searchUrl("  hello world  ")
        assertEquals("https://phormi.local/search?q=hello+world", url)
        assertTrue(PhormiSearchHistory.isSearchUrl(url))
        assertFalse(PhormiSearchHistory.isRefreshUrl(url))
        assertEquals("hello world", PhormiSearchHistory.queryFromUrl(url))
    }

    @Test
    fun `refresh urls are distinct from restorable search urls`() {
        val url = "https://phormi.local/refresh?q=hello%20world"
        assertTrue(PhormiSearchHistory.isRefreshUrl(url))
        assertTrue(PhormiSearchHistory.isLocalSearchUrl(url))
        assertFalse(PhormiSearchHistory.isSearchUrl(url))
        assertEquals("hello world", PhormiSearchHistory.queryFromUrl(url))
    }

    @Test
    fun `unsafe or unrelated urls are not local search entries`() {
        for (url in listOf(
            null,
            "",
            "about:blank",
            "https://example.com/search?q=hello",
            "https://phormi.local.evil.com/search?q=hello",
            "https://phormi.local/download",
            "https://phormi.local/search",
            "https://phormi.local/search?q=",
            "javascript:alert(1)"
        )) {
            assertFalse(url, PhormiSearchHistory.isLocalSearchUrl(url))
            assertNull(url, PhormiSearchHistory.queryFromUrl(url))
        }
    }

    @Test
    fun `query decoding preserves unicode and reserved characters`() {
        val query = "café & cats/?q=100% #phormi"
        val url = PhormiSearchHistory.searchUrl(query)
        assertEquals(query, PhormiSearchHistory.queryFromUrl(url))
        assertTrue(PhormiSearchHistory.isSearchUrl(url))
    }

    @Test
    fun `document cache restores the exact cached html without refetching`() {
        val cache = PhormiSearchDocumentCache()
        val url = PhormiSearchHistory.searchUrl("hello")
        val html = "<html><body>results</body></html>"
        cache.put(url, html)
        assertEquals(html, cache.get(url))
        assertEquals(html, cache.latest())
        assertEquals(1, cache.size())
    }

    @Test
    fun `document cache is bounded and ignores non search keys`() {
        val cache = PhormiSearchDocumentCache(maxEntries = 2, maxBytes = 1024)
        cache.put("https://example.com/", "not a search document")
        assertEquals(0, cache.size())
        val a = PhormiSearchHistory.searchUrl("a")
        val b = PhormiSearchHistory.searchUrl("b")
        val c = PhormiSearchHistory.searchUrl("c")
        cache.put(a, "a")
        cache.put(b, "b")
        cache.get(a)
        cache.put(c, "c")
        assertEquals(2, cache.size())
        assertNull(cache.get(b))
        assertEquals("a", cache.get(a))
        assertEquals("c", cache.get(c))
    }

    @Test
    fun `document cache enforces byte budget`() {
        val cache = PhormiSearchDocumentCache(maxEntries = 10, maxBytes = 10)
        val a = PhormiSearchHistory.searchUrl("a")
        val b = PhormiSearchHistory.searchUrl("b")
        cache.put(a, "123456789")
        cache.put(b, "123456789")
        assertEquals(1, cache.size())
        assertNull(cache.get(a))
        assertEquals("123456789", cache.get(b))
    }
}
