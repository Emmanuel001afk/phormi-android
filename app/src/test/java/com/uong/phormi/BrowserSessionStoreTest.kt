package com.uong.phormi

import android.app.Application
import android.content.Context
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class BrowserSessionStoreTest {
    private val now = 1_800_000_000_000L
    private lateinit var store: BrowserSessionStore
    private val prefs get() = RuntimeEnvironment.getApplication().getSharedPreferences("session_test", Context.MODE_PRIVATE)
    private fun tab(id: Int, url: String = "https://example.com/$id", used: Long = now) = BrowserTabSnapshot(id, url, "Page $id", used, now - 9000, "Work", "cached_history_$id")

    @Before fun setup() { prefs.edit().clear().commit(); store = BrowserSessionStore(prefs) }

    @Test fun `urls titles profiles and navigation states round trip without a live WebView`() {
        val session = BrowserSession(listOf(tab(4), tab(8)), 8, true)
        store.write(session, true)
        assertEquals(session, store.read(now))
        assertEquals("https://example.com/4", JSONArray(prefs.getString("tab_urls", null)).getString(0))
        assertEquals(1, prefs.getInt("active_index", -1))
    }

    @Test fun `routine metadata saves retain cached histories`() {
        val first = BrowserSession(listOf(tab(1), tab(2)), 1, false)
        store.write(first)
        store.write(first.copy(homeVisible = true))
        assertEquals("cached_history_2", store.read(now)?.tabs?.get(1)?.webViewState)
    }

    @Test fun `empty teardown cannot wipe the last session`() {
        store.write(BrowserSession(listOf(tab(1)), 1, false))
        store.write(BrowserSession(emptyList(), -1, true))
        assertEquals(1, store.read(now)?.tabs?.size)
    }

    @Test fun `corrupt primary recovers a complete last-known-good snapshot`() {
        val original = BrowserSession(listOf(tab(1), tab(2)), 2, false)
        store.write(original)
        store.write(original.copy(homeVisible = true))
        prefs.edit().putString("browser_session_v2", "{broken").commit()
        assertEquals(original, store.read(now))
    }

    @Test fun `legacy migration preserves every tab and fixes duplicate IDs`() {
        prefs.edit().putString("tab_urls", "[\"https://one.test\",\"https://two.test\",\"about:blank\"]")
            .putString("tab_ids", "[2,2,0]").putString("tab_last_used", "[$now,${now - 2000},${now - 1000}]")
            .putInt("active_index", 1).commit()
        val migrated = store.read(now)!!
        assertEquals(3, migrated.tabs.size)
        assertEquals(3, migrated.tabs.map { it.id }.toSet().size)
        assertEquals("https://two.test", migrated.tabs.first { it.id == migrated.activeId }.url)
        assertEquals(now - 2000, migrated.tabs[1].lastUsed)
    }

    @Test fun `unloaded WebView URLs cannot erase a known address`() {
        assertEquals("https://saved.test", BrowserSessionRules.durableUrl("https://saved.test", null))
        assertEquals("https://saved.test", BrowserSessionRules.durableUrl("https://saved.test", "about:blank"))
        assertEquals("https://saved.test", BrowserSessionRules.durableUrl("https://saved.test", ""))
        assertEquals("https://redirect.test", BrowserSessionRules.durableUrl("https://saved.test", "https://redirect.test"))
    }

    @Test fun `retention is per idle tab not a global purge`() {
        val month = 30L * 24 * 60 * 60 * 1000
        val tabs = listOf(tab(1, used = now - month - 1), tab(2, used = now - 100), tab(3, used = now - month - 1))
        val expired = tabs.filter { BrowserSessionRules.expired(it.lastUsed, now, month, it.id == 3) }
        assertEquals(listOf(1), expired.map { it.id })
        assertFalse(BrowserSessionRules.expired(0, now, month, false))
        assertFalse(BrowserSessionRules.expired(now + 100, now, month, false))
        assertFalse(BrowserSessionRules.expired(now - month * 2, now, null, false))
    }

    @Test fun `crash-paused tabs keep their identity and address`() {
        store.write(BrowserSession(listOf(tab(7).copy(recoveryPaused = true)), 7, false))
        assertTrue(store.read(now)!!.tabs.single().recoveryPaused)
        assertEquals("https://example.com/7", store.read(now)!!.tabs.single().url)
    }
}
