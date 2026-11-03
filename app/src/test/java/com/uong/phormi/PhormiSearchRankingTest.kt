package com.uong.phormi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhormiSearchRankingTest {
    @Test
    fun `deduplicates canonical urls and keeps the longest snippet`() {
        val merged = PhormiSearchRanking.merge(listOf(
            PhormiSearchResult("A", "https://example.com/page?utm_source=x", "short", "Google", 0),
            PhormiSearchResult("A", "https://www.example.com/page", "a much longer and more useful snippet", "Bing", 1)
        ), "example")
        assertEquals(1, merged.size)
        assertEquals("a much longer and more useful snippet", merged[0].snippet)
        assertTrue(merged[0].source.contains("Google"))
        assertTrue(merged[0].source.contains("Bing"))
    }

    @Test
    fun `cross provider agreement outranks a single provider match`() {
        val merged = PhormiSearchRanking.merge(listOf(
            PhormiSearchResult("Shared", "https://shared.example/", "", "Google", 2),
            PhormiSearchResult("Shared", "https://shared.example/", "", "Bing", 2),
            PhormiSearchResult("Solo", "https://solo.example/", "", "Google", 0)
        ), "query")
        assertEquals("https://shared.example/", merged.first().url)
    }

    @Test
    fun `unsafe urls are dropped and output is capped`() {
        val raw = (1..40).map { PhormiSearchResult("R$it", "https://example$it.com/", "", "Google", it) } +
            PhormiSearchResult("Bad", "javascript:alert(1)", "", "Google", 0)
        val merged = PhormiSearchRanking.merge(raw, "query")
        assertEquals(30, merged.size)
        assertTrue(merged.none { it.url.startsWith("javascript:") })
        assertEquals((0 until 30).toList(), merged.map { it.rank })
    }

    @Test
    fun `ranking is deterministic`() {
        val raw = listOf(
            PhormiSearchResult("A", "https://a.example/", "", "Google", 0),
            PhormiSearchResult("B", "https://b.example/", "", "Bing", 0),
            PhormiSearchResult("C", "https://a.example/2", "", "Google", 1)
        )
        repeat(5) {
            assertEquals(PhormiSearchRanking.merge(raw, "query"), PhormiSearchRanking.merge(raw, "query"))
        }
    }
}
