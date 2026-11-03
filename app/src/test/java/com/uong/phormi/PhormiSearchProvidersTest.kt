package com.uong.phormi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhormiSearchProvidersTest {
    @Test
    fun `provider list is stable and browse urls are normal web searches`() {
        assertEquals(11, PhormiSearchProviders.all.size)
        assertEquals(11, PhormiSearchProviders.all.map { it.name }.distinct().size)
        val query = "kotlin & android"
        PhormiSearchProviders.all.forEach { provider ->
            val fetch = provider.fetchUrl(query)
            val browse = provider.browseUrl(query)
            assertTrue(fetch.startsWith("https://"))
            assertTrue(browse.startsWith("https://"))
            assertTrue(browse.contains("kotlin+%26+android") || browse.contains("kotlin%20%26%20android"))
        }
        assertTrue(PhormiSearchProviders.browseUrl("DuckDuckGo", query)!!.startsWith("https://duckduckgo.com/?q="))
        assertTrue(PhormiSearchProviders.all.first { it.name == "DuckDuckGo" }.fetchUrl(query).contains("html.duckduckgo.com"))
    }
}
