package com.uong.phormi

import org.junit.Assert.*
import org.junit.Test

class PhormiSitePermissionsTest {

    private class MemoryStore : PhormiSitePermissions.Store {
        val values = mutableMapOf<String, String>()
        override fun read(origin: String): String? = values[origin]
        override fun write(origin: String, value: String?) {
            if (value == null) values.remove(origin) else values[origin] = value
        }
        override fun clear() = values.clear()
    }

    @Test fun originsAreNormalisedToSchemeAndAuthority() {
        assertEquals("https://example.com", PhormiSitePermissions.normalizeOrigin("https://example.com"))
        assertEquals("https://example.com", PhormiSitePermissions.normalizeOrigin("https://example.com/"))
        assertEquals("https://example.com", PhormiSitePermissions.normalizeOrigin("https://example.com/some/path?q=1"))
        assertEquals("http://example.com:8080", PhormiSitePermissions.normalizeOrigin("http://Example.COM:8080/"))
        assertEquals("https://example.com", PhormiSitePermissions.normalizeOrigin("example.com"))
        assertEquals("", PhormiSitePermissions.normalizeOrigin(null))
    }

    @Test fun unknownOrAskDecisionsRequireAUserPrompt() {
        assertNull(PhormiSitePermissions.decision(null))
        assertNull(PhormiSitePermissions.decision(PhormiSitePermissions.ASK))
        assertEquals(true, PhormiSitePermissions.decision(PhormiSitePermissions.ALLOW))
        assertEquals(false, PhormiSitePermissions.decision(PhormiSitePermissions.BLOCK))
    }

    @Test fun oneTimeGrantsAreNotPersistedButAlwaysGrantsAre() {
        val store = MemoryStore()
        val origin = PhormiSitePermissions.normalizeOrigin("https://news.example.com/")
        val (oncePolicy, onceArgs) = PhormiSitePermissions.grantFor(0)
        assertEquals("", oncePolicy)
        assertEquals(true to false, onceArgs)
        val (alwaysPolicy, alwaysArgs) = PhormiSitePermissions.grantFor(1)
        assertEquals(PhormiSitePermissions.ALLOW, alwaysPolicy)
        // Not retained by WebView: our store owns the decision and can revoke it.
        assertEquals(true to false, alwaysArgs)
        store.write(origin, alwaysPolicy)
        assertEquals(true, PhormiSitePermissions.decision(store.read(origin)))
        PhormiSitePermissions.forget(origin, store)
        assertNull(PhormiSitePermissions.decision(store.read(origin)))
    }

    @Test fun blockIsRememberedPerOrigin() {
        val store = MemoryStore()
        val (policy, args) = PhormiSitePermissions.grantFor(2)
        assertEquals(false to false, args)
        store.write("https://a.example", policy)
        assertEquals(false, PhormiSitePermissions.decision(store.read("https://a.example")))
        assertNull(PhormiSitePermissions.decision(store.read("https://b.example")))
    }
}
