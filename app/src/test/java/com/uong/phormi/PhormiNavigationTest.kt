package com.uong.phormi

import org.junit.Assert.*
import org.junit.Test

class PhormiNavigationTest {

    @Test fun explicitWebUrlsAreLoadedUnchanged() {
        assertEquals(
            PhormiNavigation.Target(PhormiNavigation.Kind.ADDRESS, "https://example.org/path?q=1"),
            PhormiNavigation.resolve("https://example.org/path?q=1")
        )
        assertEquals(
            PhormiNavigation.Target(PhormiNavigation.Kind.ADDRESS, "http://example.org"),
            PhormiNavigation.resolve("http://example.org")
        )
    }

    @Test fun bareHostsAreUpgradedToHttps() {
        assertEquals(
            PhormiNavigation.Target(PhormiNavigation.Kind.ADDRESS, "https://example.com"),
            PhormiNavigation.resolve("example.com")
        )
        assertEquals(
            PhormiNavigation.Target(PhormiNavigation.Kind.ADDRESS, "https://news.bbc.co.uk"),
            PhormiNavigation.resolve("news.bbc.co.uk")
        )
    }

    @Test fun ordinaryQueriesRemainSearches() {
        assertEquals(PhormiNavigation.Kind.SEARCH, PhormiNavigation.resolve("best keyboard for android")!!.kind)
        assertEquals(PhormiNavigation.Kind.SEARCH, PhormiNavigation.resolve("version 1.5")!!.kind)
        assertEquals(PhormiNavigation.Kind.SEARCH, PhormiNavigation.resolve("phormi")!!.kind)
    }

    @Test fun nonWebSchemesAreNeverHandedToWebView() {
        assertEquals(PhormiNavigation.Kind.SEARCH, PhormiNavigation.resolve("javascript:alert(1)")!!.kind)
        assertEquals(PhormiNavigation.Kind.SEARCH, PhormiNavigation.resolve("file:///sdcard/secret")!!.kind)
        assertEquals(PhormiNavigation.Kind.SEARCH, PhormiNavigation.resolve("data:text/html,<b>x</b>")!!.kind)
    }

    @Test fun blankInputIsIgnored() {
        assertNull(PhormiNavigation.resolve(""))
        assertNull(PhormiNavigation.resolve("   "))
    }
}
