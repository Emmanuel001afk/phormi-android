package com.uong.phormi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhormiSearchTextTest {
    @Test
    fun `collapses whitespace and non breaking spaces`() {
        assertEquals("a b c", PhormiSearchText.collapseWhitespace("  a\u00A0 b\n c  "))
    }

    @Test
    fun `filters navigation chrome titles`() {
        assertTrue(PhormiSearchText.isNavigationTitle("Images"))
        assertTrue(PhormiSearchText.isNavigationTitle("Sign in"))
        assertTrue(PhormiSearchText.isNavigationTitle("Next ›"))
        assertTrue(PhormiSearchText.isNavigationTitle("OK"))
        assertFalse(PhormiSearchText.isNavigationTitle("Android Developers"))
    }

    @Test
    fun `snippet cleanup removes duplicated title and truncates on a word boundary`() {
        val raw = "Example title - This is a useful description that keeps going. " + "More text. ".repeat(40)
        val snippet = PhormiSearchText.cleanSnippet(raw, "Example title", maxLength = 80)
        assertFalse(snippet.startsWith("Example title"))
        assertTrue(snippet.length <= 81)
        assertTrue(snippet.endsWith("…"))
        assertFalse(snippet.contains("  "))
    }
}
