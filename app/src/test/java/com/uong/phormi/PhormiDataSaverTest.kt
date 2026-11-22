package com.uong.phormi

import org.junit.Assert.*
import org.junit.Test

class PhormiDataSaverTest {
    @Test fun `off keeps normal browsing`() {
        val s = PhormiDataSaver.settings(PhormiDataSaver.OFF)
        assertFalse(s.cacheFirst)
        assertFalse(s.blockImages)
        assertFalse(s.mediaGestureRequired)
    }

    @Test fun `standard saves data without hiding images`() {
        val s = PhormiDataSaver.settings(PhormiDataSaver.STANDARD)
        assertTrue(s.cacheFirst)
        assertTrue(s.mediaGestureRequired)
        assertFalse(s.blockImages)
    }

    @Test fun `image mode blocks images`() {
        assertTrue(PhormiDataSaver.settings(PhormiDataSaver.TEXT_ONLY).blockImages)
        assertFalse(PhormiDataSaver.settings(PhormiDataSaver.STANDARD).blockImages)
        // An older stored value is still honoured, not ignored.
        assertTrue(PhormiDataSaver.settings(PhormiDataSaver.ALWAYS).blockImages)
    }

    @Test fun `labels never imply a compression proxy`() {
        assertTrue(PhormiDataSaver.label(PhormiDataSaver.STANDARD).contains("cache"))
        assertFalse(PhormiDataSaver.description(PhormiDataSaver.TEXT_ONLY).contains("%"))
        assertTrue(PhormiDataSaver.imageBlockScript().contains("img"))
    }
}
