package com.uong.phormi

import org.junit.Assert.*
import org.junit.Test

class PhormiDataSaverTest {
    @Test fun `off keeps normal browsing`() {
        val s = PhormiDataSaver.settings(PhormiDataSaver.OFF)
        assertFalse(s.cacheFirst)
        assertFalse(s.blockImages)
        assertFalse(s.mediaGestureRequired)
        assertFalse(PhormiDataSaver.isActive(PhormiDataSaver.OFF))
    }

    @Test fun `standard saves data without hiding images`() {
        val s = PhormiDataSaver.settings(PhormiDataSaver.STANDARD)
        assertTrue("cache-first is the whole point of standard", s.cacheFirst)
        assertTrue("autoplay must not fetch video before a tap", s.mediaGestureRequired)
        assertFalse(s.blockImages)
    }

    @Test fun `text only blocks images and keeps the rest of standard`() {
        val s = PhormiDataSaver.settings(PhormiDataSaver.TEXT_ONLY)
        assertTrue(s.blockImages)
        assertTrue(s.cacheFirst)
        assertTrue(s.mediaGestureRequired)
        assertFalse(PhormiDataSaver.settings(PhormiDataSaver.STANDARD).blockImages)
    }

    @Test fun `legacy always value still means text only`() {
        // Older builds wrote "always". Treating it as Off would silently start loading
        // images for a user who had asked for none.
        assertEquals(PhormiDataSaver.settings(PhormiDataSaver.TEXT_ONLY), PhormiDataSaver.settings(PhormiDataSaver.ALWAYS))
        assertEquals(PhormiDataSaver.label(PhormiDataSaver.TEXT_ONLY), PhormiDataSaver.label(PhormiDataSaver.ALWAYS))
    }

    @Test fun `an unknown stored value is off, not a crash`() {
        assertEquals(PhormiDataSaver.settings(PhormiDataSaver.OFF), PhormiDataSaver.settings("garbage"))
        assertFalse(PhormiDataSaver.isActive(null))
        assertTrue(PhormiDataSaver.isActive(PhormiDataSaver.STANDARD))
        assertTrue(PhormiDataSaver.isActive(PhormiDataSaver.TEXT_ONLY))
    }

    @Test fun `every selectable mode is described and none claims a percentage`() {
        assertEquals(3, PhormiDataSaver.modes.size)
        assertEquals("off", PhormiDataSaver.modes.first())
        for (mode in PhormiDataSaver.modes) {
            assertTrue("label for $mode", PhormiDataSaver.label(mode).isNotBlank())
            val description = PhormiDataSaver.description(mode)
            assertTrue("description for $mode", description.isNotBlank())
            // No invented savings figure: WebView has no compression proxy.
            assertFalse("no percentage for $mode", description.contains("%"))
        }
    }

    @Test fun `image block keeps layout space and never uses a network resource`() {
        val script = PhormiDataSaver.imageBlockScript()
        assertTrue(script.contains("img"))
        assertTrue("hidden, not display:none, so the page does not collapse", script.contains("visibility:hidden"))
        assertFalse(script.contains("http://"))
        assertFalse(script.contains("https://"))
    }

    @Test fun `text only refuses image downloads before they happen`() {
        // blockNetworkImage is deprecated and inert from API 30, so the request itself
        // has to be refused; hiding an image that already downloaded saves no data.
        for (url in listOf(
            "https://cdn.example.com/hero.jpg",
            "https://example.com/a.png?w=800",
            "http://example.com/pic.WEBP",
            "https://example.com/anim.gif",
            "https://example.com/icon.svg#frag",
            "https://example.com/photo.avif",
            "https://example.com/logo.ico"
        )) {
            assertTrue(url, PhormiDataSaver.blocksImageRequest(PhormiDataSaver.TEXT_ONLY, url, false))
        }
    }

    @Test fun `standard and off still load images`() {
        assertFalse(PhormiDataSaver.blocksImageRequest(PhormiDataSaver.STANDARD, "https://e.com/a.jpg", false))
        assertFalse(PhormiDataSaver.blocksImageRequest(PhormiDataSaver.OFF, "https://e.com/a.jpg", false))
        assertFalse(PhormiDataSaver.blocksImageRequest(null, "https://e.com/a.jpg", false))
    }

    @Test fun `the document itself is never blocked`() {
        // Blocking a main-frame request would blank the page instead of saving data.
        assertFalse(PhormiDataSaver.blocksImageRequest(PhormiDataSaver.TEXT_ONLY, "https://example.com/", true))
        assertFalse(PhormiDataSaver.blocksImageRequest(PhormiDataSaver.TEXT_ONLY, "https://example.com/page.jpg", true))
    }

    @Test fun `non-image and already-local resources are left alone`() {
        for (url in listOf(
            "https://example.com/app.js",
            "https://example.com/style.css",
            "https://example.com/api/data.json",
            "https://example.com/video.mp4",
            "data:image/png;base64,iVBORw0KGgo=",
            "blob:https://example.com/1234",
            ""
        )) {
            assertFalse(url, PhormiDataSaver.blocksImageRequest(PhormiDataSaver.TEXT_ONLY, url, false))
        }
        assertFalse(PhormiDataSaver.blocksImageRequest(PhormiDataSaver.TEXT_ONLY, null, false))
    }

    @Test fun `an explicit refresh bypasses the cache the saving mode would prefer`() {
        val standard = PhormiDataSaver.settings(PhormiDataSaver.STANDARD)
        assertTrue("a normal load prefers the cache", PhormiDataSaver.preferCache(standard, false))
        assertFalse("a user refresh must reach the network", PhormiDataSaver.preferCache(standard, true))
        // Off never prefers the cache, so a refresh changes nothing.
        val off = PhormiDataSaver.settings(PhormiDataSaver.OFF)
        assertFalse(PhormiDataSaver.preferCache(off, false))
        assertFalse(PhormiDataSaver.preferCache(off, true))
    }

    @Test fun `a mode change reloads only when the mode actually changed`() {
        assertTrue(PhormiDataSaver.requiresReload(PhormiDataSaver.OFF, PhormiDataSaver.TEXT_ONLY))
        assertTrue(PhormiDataSaver.requiresReload(PhormiDataSaver.TEXT_ONLY, PhormiDataSaver.STANDARD))
        assertFalse(PhormiDataSaver.requiresReload(PhormiDataSaver.STANDARD, PhormiDataSaver.STANDARD))
        // Picking the mode that is already active must not reload the page.
        assertFalse(PhormiDataSaver.requiresReload(PhormiDataSaver.OFF, PhormiDataSaver.OFF))
    }
}
