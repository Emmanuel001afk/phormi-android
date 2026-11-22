package com.uong.phormi

import org.junit.Assert.*
import org.junit.Test

class PhormiBrowserChromeTest {
    private fun state(
        onHome: Boolean = false,
        wide: Boolean = false,
        blank: Boolean = false,
        bottom: Boolean = false,
        fullscreen: Boolean = false,
        splitLocked: Boolean = false,
        downloads: Int = 0
    ) = PhormiBrowserChrome.State(onHome, wide, blank, bottom, fullscreen, splitLocked, downloads)

    @Test fun `phone top bar keeps Chrome order and a permanent refresh`() {
        val v = PhormiBrowserChrome.resolve(state())
        assertTrue(v.top)
        assertFalse(v.bottom)
        assertTrue(v.url)
        assertTrue(v.topBack)
        assertTrue(v.topForward)
        // The refresh control must exist on every phone screen size now.
        assertTrue(v.refresh)
        assertTrue(v.stop)
        assertTrue(v.newTab)
        assertFalse(v.topAi)
        assertFalse(v.topDownloads)
    }

    @Test fun `blank tab shows empty chrome instead of a reload arrow`() {
        val blank = PhormiBrowserChrome.resolve(state(blank = true))
        assertTrue(blank.refresh)
        assertFalse(blank.stop)
        val home = PhormiBrowserChrome.resolve(state(onHome = true))
        assertTrue(home.top)
        assertTrue(home.refresh)
        assertFalse(home.url)
    }

    @Test fun `fullscreen and split lock hide the chrome entirely`() {
        val fullscreen = PhormiBrowserChrome.resolve(state(wide = true, bottom = true, fullscreen = true))
        assertFalse(fullscreen.top)
        assertFalse(fullscreen.bottom)
        assertFalse(fullscreen.refresh)
        val locked = PhormiBrowserChrome.resolve(state(wide = true, splitLocked = true))
        assertFalse(locked.top)
        assertFalse(locked.refresh)
    }

    @Test fun `bottom bar owns back forward without removing the top row`() {
        val v = PhormiBrowserChrome.resolve(state(wide = true, bottom = true, downloads = 2))
        assertTrue(v.bottom)
        assertFalse(v.topBack)
        assertFalse(v.topForward)
        assertTrue(v.url)
        assertTrue(v.refresh)
        assertTrue(v.newTab)
        // Wide screens still get the strip and inline extras; the bottom bar only
        // owns Back/Forward, so nothing else is removed from the top row.
        assertTrue(v.tabStrip)
        assertTrue(v.topAi)
        assertTrue(v.topDownloads)
    }

    @Test fun `blank tab still exposes back forward and new tab`() {
        val v = PhormiBrowserChrome.resolve(state(blank = true))
        assertTrue(v.topBack)
        assertTrue(v.topForward)
        assertTrue(v.newTab)
        assertFalse(v.stop)
    }

    @Test fun `a cleared tab has an empty address, not the previous site`() {
        assertEquals("", PhormiBrowserChrome.addressText("about:blank", null))
        assertEquals("", PhormiBrowserChrome.addressText("https://youtube.com", ""))
        assertEquals("", PhormiBrowserChrome.addressText(null, null))
        assertTrue(PhormiBrowserChrome.isBlankUrl("about:blank"))
        assertTrue(PhormiBrowserChrome.isBlankUrl(""))
        assertFalse(PhormiBrowserChrome.isBlankUrl("https://youtube.com"))
        assertEquals("example.com", PhormiBrowserChrome.tabLabel("New tab", "https://www.example.com/watch"))
        assertEquals("A video", PhormiBrowserChrome.tabLabel("A video", "https://www.example.com/watch"))
    }

    @Test fun `desktop user agent never reports Mobile`() {
        val ua = PhormiBrowserChrome.sanitizeDesktopUserAgent(
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Mobile Safari/537.36"
        )
        assertFalse(ua.contains("Mobile"))
        assertTrue(ua.contains("Chrome/141"))
    }
}
