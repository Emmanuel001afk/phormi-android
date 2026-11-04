package com.uong.phormi

import org.junit.Assert.*
import org.junit.Test

class PhormiTabOverviewTest {
    private val site = PhormiTabOverview.TabEntry(1, "New Tab", "https://github.com/", groupId = "work", groupName = "Work")
    private val blank = PhormiTabOverview.TabEntry(2, "New tab", "about:blank")
    private val other = PhormiTabOverview.TabEntry(3, "Example", "https://example.com")

    @Test fun `requested destination survives a temporarily blank WebView`() {
        assertEquals("https://github.com/", PhormiTabOverview.navigationUrl(null, "https://github.com/", "about:blank"))
        assertEquals("https://github.com/", PhormiTabOverview.navigationUrl(null, "https://github.com/", null))
        assertEquals("https://github.com/", PhormiTabOverview.navigationUrl("https://github.com/", "about:blank", "about:blank"))
    }

    @Test fun `explicit Home and committed blank pages remain new tabs`() {
        assertEquals("about:blank", PhormiTabOverview.navigationUrl(null, "about:blank", "https://old.example"))
        assertEquals("about:blank", PhormiTabOverview.navigationUrl(null, null, null))
        assertTrue(PhormiTabOverview.isNewTab(" about:blank "))
        assertFalse(PhormiTabOverview.isNewTab("https://example.com"))
    }

    @Test fun `stale new-tab titles are repaired from the destination`() {
        assertEquals("github.com", PhormiTabOverview.displayTitle("New Tab", "https://www.github.com/"))
        assertEquals("GitHub", PhormiTabOverview.displayTitle(" GitHub ", "https://github.com/"))
        assertEquals("New tab", PhormiTabOverview.displayTitle("New Tab", "about:blank"))
        assertEquals("G", PhormiTabOverview.monogram(site.url))
        assertEquals("+", PhormiTabOverview.monogram(blank.url))
    }

    @Test fun `sections are ordered and never duplicate the active tab`() {
        val ghost = other.copy(id = 4, ghost = true)
        val sections = PhormiTabOverview.sections(listOf(site, blank, other, ghost), 1,
            listOf(PhormiTabOverview.GroupInfo("work", "Work")))
        assertEquals(listOf("current", "ghost", "websites", "new"), sections.map { it.key })
        assertEquals(listOf(1, 4, 3, 2), sections.flatMap { it.tabs }.map { it.id })
    }

    @Test fun `named groups keep their manager order and contain only their members`() {
        val tabs = listOf(site, other.copy(groupId = "read", groupName = "Reading"), blank.copy(id = 5))
        val sections = PhormiTabOverview.sections(tabs, 5,
            listOf(PhormiTabOverview.GroupInfo("read", "Reading"), PhormiTabOverview.GroupInfo("work", "Work"), PhormiTabOverview.GroupInfo("empty", "Empty")))
        assertEquals(listOf("current", "group:read", "group:work"), sections.map { it.key })
        assertEquals(listOf(5, 3, 1), sections.flatMap { it.tabs }.map { it.id })
    }

    @Test fun `search includes address group and environment without remote lookup`() {
        val tabs = listOf(site.copy(profile = "Research"), blank, other)
        assertEquals(listOf(1), PhormiTabOverview.filter(tabs, "GITHUB").map { it.id })
        assertEquals(listOf(1), PhormiTabOverview.filter(tabs, "work").map { it.id })
        assertEquals(listOf(1), PhormiTabOverview.filter(tabs, "research").map { it.id })
        assertEquals(tabs, PhormiTabOverview.filter(tabs, "  "))
        assertTrue(PhormiTabOverview.filter(tabs, "no-such-tab").isEmpty())
    }

    @Test fun `layout adapts to width mode and large text`() {
        assertEquals(1, PhormiTabOverview.columns("vertical", 800, 1f))
        assertEquals(2, PhormiTabOverview.columns("grid", 390, 1f))
        assertEquals(2, PhormiTabOverview.columns("grid", 320, 1f))
        assertEquals(3, PhormiTabOverview.columns("grid", 600, 1f))
        assertEquals(4, PhormiTabOverview.columns("grid", 840, 1f))
        assertEquals(1, PhormiTabOverview.columns("grid", 800, 1.5f))
        assertEquals(1, PhormiTabOverview.columns("horizontal", 800, 1f))
        assertEquals("grid", PhormiTabOverview.normalizeMode("grid"))
        assertEquals("vertical", PhormiTabOverview.normalizeMode("unknown"))
        assertTrue(PhormiTabOverview.carouselWidth(390) in 240..300)
    }

    @Test fun `each overview mode has a distinct item form`() {
        assertEquals("tab-card:list", PhormiTabOverview.layoutTag("vertical"))
        assertEquals("tab-card:grid", PhormiTabOverview.layoutTag("grid"))
        assertEquals("tab-card:cards", PhormiTabOverview.layoutTag("horizontal"))
        assertEquals(3, setOf(PhormiTabOverview.layoutTag("vertical"), PhormiTabOverview.layoutTag("grid"), PhormiTabOverview.layoutTag("horizontal")).size)
    }

    @Test fun `counts and addresses are human readable`() {
        assertEquals("1 tab", PhormiTabOverview.countLabel(1))
        assertEquals("4 tabs", PhormiTabOverview.countLabel(4))
        assertEquals("Ready to browse", PhormiTabOverview.address("about:blank"))
        assertEquals("https://github.com/", PhormiTabOverview.address(site.url))
        assertEquals("github.com", PhormiTabOverview.shortAddress(site.url))
        assertEquals("New tab", PhormiTabOverview.shortAddress("about:blank"))
    }
}
