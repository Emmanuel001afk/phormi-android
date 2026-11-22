package com.uong.phormi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhormiDesktopModeTest {
    private val mobileUa = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/126.0 Mobile Safari/537.36"

    @Test
    fun `global default applies when no host override exists`() {
        assertFalse(PhormiDesktopMode.resolve("https://example.com", false, emptySet(), emptySet()))
        assertTrue(PhormiDesktopMode.resolve("https://example.com", true, emptySet(), emptySet()))
        assertTrue(PhormiDesktopMode.resolve(null, true, emptySet(), emptySet()))
        assertTrue(PhormiDesktopMode.resolve("about:blank", true, emptySet(), emptySet()))
    }

    @Test
    fun `per host overrides work in both directions`() {
        assertTrue(PhormiDesktopMode.resolve("https://example.com", false, setOf("example.com"), emptySet()))
        assertFalse(PhormiDesktopMode.resolve("https://example.com", true, emptySet(), setOf("example.com")))
        assertFalse(PhormiDesktopMode.resolve("https://example.com", true, setOf("example.com"), setOf("example.com")))
    }

    @Test
    fun `host matching ignores case and www`() {
        assertEquals("example.com", PhormiDesktopMode.normalizeHost("https://WWW.Example.com/path"))
        assertTrue(PhormiDesktopMode.resolve("https://www.example.com", false, setOf("Example.com"), emptySet()))
        assertFalse(PhormiDesktopMode.resolve("https://www.example.com", true, emptySet(), setOf("www.example.com")))
        assertNull(PhormiDesktopMode.normalizeHost("not a url"))
        assertNull(PhormiDesktopMode.normalizeHost(""))
    }

    @Test
    fun `toggle keeps mutually exclusive host sets`() {
        val (on, off) = PhormiDesktopMode.toggleHost("www.example.com", true, setOf("other.com"), setOf("example.com"))
        assertTrue("example.com" in on)
        assertFalse("example.com" in off)
        assertTrue("other.com" in on)
        val (on2, off2) = PhormiDesktopMode.toggleHost("example.com", false, on, off)
        assertFalse("example.com" in on2)
        assertTrue("example.com" in off2)
    }

    @Test
    fun `desktop user agent keeps the mobile chrome major version`() {
        val desktop = PhormiDesktopMode.userAgent(mobileUa, true)
        assertTrue(desktop.startsWith("Mozilla/5.0 (X11; Linux x86_64)"))
        assertTrue(desktop.contains("Chrome/126.0.0.0"))
        assertFalse(desktop.contains("Mobile"))
        assertEquals(mobileUa, PhormiDesktopMode.userAgent(mobileUa, false))
    }

    @Test
    fun `menu label reflects current state`() {
        assertEquals("▣  Desktop site · On", PhormiDesktopMode.menuLabel(true))
        assertEquals("▣  Desktop site · Off", PhormiDesktopMode.menuLabel(false))
    }

    @Test
    fun `viewport reconciliation script is idempotent and local`() {
        val script = PhormiDesktopMode.viewportScript()
        assertTrue(script.contains("window.screen.width"))
        assertTrue(script.contains("meta[name=\"viewport\"]"))
        assertFalse(script.contains("http://"))
        assertFalse(script.contains("https://"))
    }

    @Test
    fun `desktop viewport is the real screen width, never a fixed slab`() {
        // A fixed 1280px viewport scaled into a 412px phone made text unreadable and
        // pushed edge controls off-screen. The width must come from the device.
        val script = PhormiDesktopMode.viewportScript()
        assertTrue(script.contains("initial-scale=1"))
        assertFalse("no fixed-width slab", script.contains("width=1280"))
    }

    @Test
    fun `rotation reflows layouts without navigating`() {
        val script = PhormiDesktopMode.reflowScript()
        assertTrue(script.contains("resize"))
        assertFalse(script.contains("location.reload"))
        assertFalse(script.contains("http://"))
    }
}
