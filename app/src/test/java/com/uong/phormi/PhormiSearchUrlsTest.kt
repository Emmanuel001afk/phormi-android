package com.uong.phormi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhormiSearchUrlsTest {
    @Test
    fun `accepts only ordinary web urls`() {
        assertEquals("https://example.com/a", PhormiSearchUrls.webUrlOrNull("https://example.com/a"))
        assertEquals("http://example.com/", PhormiSearchUrls.webUrlOrNull("http://example.com/"))
        assertNull(PhormiSearchUrls.webUrlOrNull("javascript:alert(1)"))
        assertNull(PhormiSearchUrls.webUrlOrNull("data:text/html,hi"))
        assertNull(PhormiSearchUrls.webUrlOrNull("file:///etc/passwd"))
        assertNull(PhormiSearchUrls.webUrlOrNull("https://user:pass@example.com/"))
        assertNull(PhormiSearchUrls.webUrlOrNull("https://example.com/a b"))
        assertNull(PhormiSearchUrls.webUrlOrNull(""))
    }

    @Test
    fun `canonical identity strips tracking but preserves path and query case`() {
        val a = PhormiSearchUrls.canonical("https://www.Example.com/Path/?b=2&utm_source=x&a=1#frag")
        val b = PhormiSearchUrls.canonical("https://example.com/Path?a=1&b=2")
        assertEquals(a, b)
        assertTrue(a.contains("/Path"))
        assertFalse(a.contains("utm_"))
        assertFalse(a.contains("#"))
        assertFalse(PhormiSearchUrls.canonical("https://example.com/?fbclid=abc").contains("fbclid"))
    }

    @Test
    fun `breadcrumb is short and human readable`() {
        val crumb = PhormiSearchUrls.breadcrumb("https://www.example.com/docs/getting-started/install?x=1")
        assertTrue(crumb.startsWith("example.com"))
        assertTrue(crumb.contains("docs"))
        assertTrue(crumb.contains("getting started"))
        assertFalse(crumb.contains("https://"))
        assertFalse(crumb.contains("?x=1"))
    }

    @Test
    fun `provider navigation is filtered without blocking unrelated brand docs`() {
        assertTrue(PhormiSearchUrls.isProviderNavigation("https://www.google.com/search?q=test"))
        assertTrue(PhormiSearchUrls.isProviderNavigation("https://search.brave.com/search?q=test"))
        assertTrue(PhormiSearchUrls.isProviderNavigation("https://www.startpage.com/sp/search?query=test"))
        assertFalse(PhormiSearchUrls.isProviderNavigation("https://developers.google.com/search/docs"))
        assertFalse(PhormiSearchUrls.isProviderNavigation("https://example.com/search"))
    }
}
