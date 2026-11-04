package com.uong.phormi

import org.junit.Assert.*
import org.junit.Test

class PhormiBrowserChromeTest {
    @Test fun `phone without bottom bar keeps navigation in the top bar`() {
        val v = PhormiBrowserChrome.resolve(PhormiBrowserChrome.State(onHome = false, wide = false, bottomEnabled = false))
        assertTrue(v.top)
        assertFalse(v.bottom)
        assertTrue(v.home)
        assertTrue(v.url)
        assertTrue(v.topBack)
        assertTrue(v.topForward)
        assertFalse(v.refresh)
        assertFalse(v.topAi)
        assertFalse(v.topDownloads)
    }

    @Test fun `bottom bar owns navigation when enabled`() {
        val v = PhormiBrowserChrome.resolve(PhormiBrowserChrome.State(onHome = false, wide = false, bottomEnabled = true))
        assertTrue(v.top)
        assertTrue(v.bottom)
        assertFalse(v.topBack)
        assertFalse(v.topForward)
        assertTrue(v.refresh)
        assertFalse(v.favorite)
    }

    @Test fun `home and fullscreen do not show browsing chrome`() {
        val home = PhormiBrowserChrome.resolve(PhormiBrowserChrome.State(onHome = true, wide = false, bottomEnabled = true))
        assertTrue(home.top)
        assertFalse(home.bottom)
        assertFalse(home.url)
        assertFalse(home.home)
        assertTrue(home.newTab)
        val fullscreen = PhormiBrowserChrome.resolve(PhormiBrowserChrome.State(onHome = false, wide = true, bottomEnabled = true, fullscreen = true))
        assertFalse(fullscreen.top)
        assertFalse(fullscreen.bottom)
    }

    @Test fun `wide screens may expose inline extras without duplicating the bottom bar`() {
        val v = PhormiBrowserChrome.resolve(PhormiBrowserChrome.State(onHome = false, wide = true, bottomEnabled = false, activeDownloads = 2))
        assertTrue(v.tabStrip)
        assertTrue(v.refresh)
        assertTrue(v.topAi)
        assertTrue(v.topDownloads)
        assertTrue(v.topBack)
        assertFalse(v.bottom)
    }
}
