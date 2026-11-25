package com.uong.phormi

import org.junit.Assert.*
import org.junit.Test

class PhormiNetworkPolicyTest {
    @Test fun `https is always allowed`() {
        assertTrue(PhormiNetworkPolicy.reach("https://example.com/a").allowed)
        assertTrue(PhormiNetworkPolicy.reach("about:blank").allowed)
    }

    @Test fun `a LAN transfer server opens over plain http`() {
        // The real case: a phone-to-PC transfer page on the local network.
        for (url in listOf(
            "http://192.168.1.20:8080/transfer",
            "http://10.0.0.5",
            "http://172.16.4.9:9000",
            "http://localhost:3000",
            "http://127.0.0.1:8080",
            "http://files.local",
            "http://nas:5000"
        )) {
            assertTrue(url, PhormiNetworkPolicy.reach(url).allowed)
        }
    }

    @Test fun `a public http site is a warning the user can accept`() {
        val reach = PhormiNetworkPolicy.reach("http://example.com")
        assertFalse(reach.allowed)
        assertTrue(reach.reason.contains("HTTP"))
        assertTrue(PhormiNetworkPolicy.warningMessage("http://example.com").contains("not encrypted"))
    }

    @Test fun `permission remembered for a host and its port`() {
        val trusted = PhormiNetworkPolicy.withTrustedHost(emptySet(), "http://example.com:8080/page")
        assertEquals(setOf("example.com"), trusted)
        assertTrue(PhormiNetworkPolicy.reach("http://example.com/other", trusted).allowed)
        // A different host is not grandfathered in.
        assertFalse(PhormiNetworkPolicy.reach("http://other.com", trusted).allowed)
    }

    @Test fun `local network preference changes only the label not reachability`() {
        val flagged = PhormiNetworkPolicy.reach("http://192.168.1.2", allowLan = true)
        val unflagged = PhormiNetworkPolicy.reach("http://192.168.1.2", allowLan = false)
        assertTrue(flagged.allowed)
        assertTrue(unflagged.allowed)
        assertTrue(flagged.reason.contains("Local network"))
        assertFalse(unflagged.reason.contains("Local network page"))
        assertFalse(PhormiNetworkPolicy.reach("http://example.com", allowLan = false).allowed)
    }

    @Test fun `only web schemes are reachable`() {
        for (url in listOf("file:///sdcard/a.html", "javascript:alert(1)", "intent://x", "")) {
            assertFalse(url, PhormiNetworkPolicy.reach(url).allowed)
        }
    }

    @Test fun `web addresses reject missing hosts and embedded credentials`() {
        assertFalse(PhormiNetworkPolicy.reach("https:example.com").allowed)
        assertFalse(PhormiNetworkPolicy.reach("https://user:pass@example.com").allowed)
    }

    @Test fun `private ranges are recognised without catching public addresses`() {
        assertTrue(PhormiNetworkPolicy.isPrivateHost("192.168.0.10"))
        assertTrue(PhormiNetworkPolicy.isPrivateHost("172.31.0.1"))
        assertTrue(PhormiNetworkPolicy.isPrivateHost("169.254.1.1"))
        assertFalse(PhormiNetworkPolicy.isPrivateHost("172.32.0.1"))
        assertFalse(PhormiNetworkPolicy.isPrivateHost("8.8.8.8"))
        assertFalse(PhormiNetworkPolicy.isPrivateHost("example.com"))
    }
}
