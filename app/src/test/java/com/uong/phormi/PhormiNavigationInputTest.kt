package com.uong.phormi

import org.junit.Assert.*
import org.junit.Test

class PhormiNavigationInputTest {
    @Test fun acceptsWebAddressesAndLocalDevelopment() {
        assertEquals("https://example.com/path?q=one", PhormiNavigationInput.webUrl("example.com/path?q=one"))
        assertEquals("http://localhost:8080", PhormiNavigationInput.webUrl("localhost:8080"))
        assertEquals("https://192.168.1.1", PhormiNavigationInput.webUrl("192.168.1.1"))
        assertEquals("https://example.com", PhormiNavigationInput.webUrl("HTTPS://EXAMPLE.COM"))
    }
    @Test fun searchesAreNotExecutableOrCredentialUrls() {
        for (text in listOf("hello world", "javascript:alert(1)", "data:text/html,hello", "name@example.com", "https://user:password@example.com", "https://example.com:99999"))
            assertNull(text, PhormiNavigationInput.webUrl(text))
    }
    @Test fun internationalHostsAreNormalized() {
        assertEquals("https://xn--bcher-kva.de", PhormiNavigationInput.webUrl("bücher.de"))
    }
    @Test fun sitePermissionsUseSecureOriginsOnly() {
        assertEquals("https://example.com", PhormiSitePermissions.secureOrigin("https://EXAMPLE.COM:443/path"))
        assertEquals("https://example.com:8443", PhormiSitePermissions.secureOrigin("https://example.com:8443/path"))
        assertNull(PhormiSitePermissions.secureOrigin("http://example.com"))
        assertNull(PhormiSitePermissions.secureOrigin("https://user@example.com"))
        assertNull(PhormiSitePermissions.secureOrigin("about:blank"))
    }
}
