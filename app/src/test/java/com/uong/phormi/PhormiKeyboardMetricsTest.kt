package com.uong.phormi

import org.junit.Assert.*
import org.junit.Test

class PhormiKeyboardMetricsTest {
    @Test fun `base height adapts to landscape and tablet without using one fixed number`() {
        assertEquals(260, PhormiKeyboardMetrics.baseHeightDp(800, 400))
        assertEquals(320, PhormiKeyboardMetrics.baseHeightDp(800, 1280))
        assertEquals(296, PhormiKeyboardMetrics.baseHeightDp(390, 844))
    }

    @Test fun `window height respects scale and screen cap`() {
        val normal = PhormiKeyboardMetrics.windowHeightDp(390, 844, 1.0f, false)
        val large = PhormiKeyboardMetrics.windowHeightDp(390, 844, 1.4f, false)
        val compact = PhormiKeyboardMetrics.windowHeightDp(390, 844, 0.6f, false)
        assertTrue(normal in 220..692)
        assertTrue(large >= normal)
        assertTrue(compact <= normal)
        assertTrue(PhormiKeyboardMetrics.windowHeightDp(390, 400, 1.4f, false) <= 328)
    }

    @Test fun `lettering shrinks with narrow keys and grows with large text preference`() {
        val wide = PhormiKeyboardMetrics.keyTextSp("a", 34f, 52f, 1.0f)
        val narrow = PhormiKeyboardMetrics.keyTextSp("a", 18f, 52f, 1.0f)
        val accessible = PhormiKeyboardMetrics.keyTextSp("a", 34f, 52f, 1.25f)
        assertTrue(narrow < wide)
        assertTrue(accessible > wide)
        assertTrue(wide in 15f..22f)
        assertTrue(PhormiKeyboardMetrics.keyTextSp("Search", 34f, 52f, 1.0f) < wide)
    }

    @Test fun `row geometry keeps long layouts inside the viewport`() {
        assertEquals(1, PhormiKeyboardMetrics.keyMarginHDp(12))
        assertEquals(2, PhormiKeyboardMetrics.keyMarginHDp(10))
        val width = PhormiKeyboardMetrics.keyWidthDp(360f, 12)
        assertTrue(width >= 12f)
        assertTrue(PhormiKeyboardMetrics.homeInsetDp(9, width) in 6f..22f)
        assertTrue(PhormiKeyboardMetrics.keyHeightDp(296, 1.0f) >= 32f)
    }
}
