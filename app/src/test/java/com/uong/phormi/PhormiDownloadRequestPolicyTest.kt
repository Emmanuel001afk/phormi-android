package com.uong.phormi

import org.junit.Assert.*
import org.junit.Test

class PhormiDownloadRequestPolicyTest {
    @Test fun `download destination follows browser cleartext permission`() {
        assertTrue(PhormiDownloadRequestPolicy.addressReach(
            "https://cdn.example/file.zip", emptySet(), allowLan = true
        ).allowed)
        assertFalse(PhormiDownloadRequestPolicy.addressReach(
            "http://example.com/file.zip", emptySet(), allowLan = true
        ).allowed)
        val trusted = setOf("example.com")
        assertTrue(PhormiDownloadRequestPolicy.addressReach(
            "http://example.com/file.zip", trusted, allowLan = true
        ).allowed)
        assertTrue(PhormiDownloadRequestPolicy.addressReach(
            "http://192.168.1.20/file.zip", emptySet(), allowLan = false
        ).allowed)
        assertFalse(PhormiDownloadRequestPolicy.addressReach(
            "file:///tmp/file.zip", emptySet(), allowLan = true
        ).allowed)
    }

    @Test fun `referrer cookies are never a fallback for a cookie-less download target`() {
        val headers = PhormiDownloadRequestPolicy.initialHeaders(
            defaults = mapOf(
                "User-Agent" to "Phormi test",
                "Cookie" to "session=referrer-secret",
                "Referer" to "https://account.example/private"
            ),
            observedForExactUrl = emptyMap(),
            destinationUrl = "https://cdn.example/file.zip",
            destinationCookies = null
        )

        assertNull(header(headers, "Cookie"))
        assertEquals("Phormi test", header(headers, "User-Agent"))
        assertEquals("https://account.example/", header(headers, "Referer"))
    }

    @Test fun `only the destination cookie is used`() {
        val headers = PhormiDownloadRequestPolicy.initialHeaders(
            defaults = mapOf("Cookie" to "session=referrer-secret"),
            observedForExactUrl = mapOf("Cookie" to "session=observed-target"),
            destinationUrl = "https://cdn.example/file.zip",
            destinationCookies = "download=destination-cookie"
        )

        assertEquals("download=destination-cookie", header(headers, "Cookie"))
        assertFalse(headers.values.any { it.contains("referrer-secret") })
    }

    @Test fun `secret and navigation context headers are not persisted`() {
        val persisted = PhormiDownloadRequestPolicy.headersForPersistence(mapOf(
            "User-Agent" to "Phormi",
            "Accept" to "*/*",
            "Cookie" to "secret-cookie",
            "Authorization" to "Bearer secret-token",
            "X-Api-Key" to "key",
            "X-CSRF-Token" to "csrf",
            "X-ApiKey" to "alternate-api-key",
            "Access-Key" to "access-key",
            "Set-Cookie" to "cookie-header",
            "Referer" to "https://account.example/private?query=secret",
            "Origin" to "https://account.example"
        ))

        assertEquals(mapOf("User-Agent" to "Phormi", "Accept" to "*/*"), persisted)
    }

    @Test fun `cross-origin and downgrade redirects strip credentials and referrer`() {
        val headers = mapOf(
            "Cookie" to "session=secret",
            "Authorization" to "Bearer secret",
            "X-Session-Token" to "token",
            "Referer" to "https://account.example/private",
            "Origin" to "https://account.example",
            "User-Agent" to "Phormi",
            "Range" to "bytes=20-"
        )

        val crossOrigin = PhormiDownloadRequestPolicy.headersForRedirect(
            "https://account.example/file", "https://cdn.example/file", headers
        )
        assertEquals(mapOf("User-Agent" to "Phormi", "Range" to "bytes=20-"), crossOrigin)

        val downgrade = PhormiDownloadRequestPolicy.headersForRedirect(
            "https://account.example/file", "http://account.example/file", headers
        )
        assertEquals(mapOf("User-Agent" to "Phormi", "Range" to "bytes=20-"), downgrade)

        val sameOrigin = PhormiDownloadRequestPolicy.headersForRedirect(
            "https://account.example/a", "https://account.example/b", headers
        )
        assertEquals(headers, sameOrigin)
    }

    @Test fun `redirect resolution is relative and restricted to safe web URLs`() {
        assertEquals(
            "https://example.com/download/file.zip",
            PhormiDownloadRequestPolicy.resolveRedirect("https://example.com/path/page", "../download/file.zip")
        )
        assertNull(PhormiDownloadRequestPolicy.resolveRedirect("https://example.com/", "javascript:alert(1)"))
        assertFalse(PhormiDownloadRequestPolicy.isHttpUrl("https://user:pass@example.com/file"))
        assertFalse(PhormiDownloadRequestPolicy.isHttpUrl("file:///tmp/private"))
    }

    private fun header(headers: Map<String, String>, name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}
