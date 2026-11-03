package com.uong.phormi

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhormiSearchRendererTest {
    private val assets = PhormiSearchTestFixtures.assets()
    private val palette = PhormiSearchTestFixtures.palette

    private fun render(
        query: String = "kotlin android",
        results: List<PhormiSearchResult> = listOf(PhormiSearchTestFixtures.result()),
        loading: Boolean = false,
        successful: List<String> = listOf("Google"),
        finished: List<String> = listOf("Google"),
        aiAnswer: String? = null
    ): String = PhormiSearchRenderer.render(
        PhormiSearchRenderState(
            query = query,
            results = results,
            loading = loading,
            completed = finished.size,
            total = 11,
            successfulEngines = successful,
            finishedEngines = finished,
            aiAnswer = aiAnswer
        ),
        palette,
        assets
    )

    @Test
    fun `renders a normal results page instead of raw engine telemetry`() {
        val html = render()
        assertTrue(html.contains("Phormi"))
        assertTrue(html.contains("1 result"))
        assertTrue(html.contains("Example result"))
        assertTrue(html.contains("example.com"))
        assertTrue(html.contains("About these results"))
        assertTrue(html.contains("Try another search engine"))
        assertFalse(html.contains("Collecting"))
        assertFalse(html.contains("answered"))
        assertFalse(html.contains("source-badge"))
        assertFalse(html.contains("Unified results"))
    }

    @Test
    fun `escapes provider controlled text and query placeholders`() {
        val malicious = PhormiSearchTestFixtures.result(
            title = "<script>alert(1)</script>",
            snippet = "\" onmouseover=\"alert(2)",
            source = "Google · <img src=x onerror=alert(3)>"
        )
        val html = render(query = "{{CSS}}<script>alert(4)</script>", results = listOf(malicious))
        assertFalse(html.contains("<script>alert"))
        assertFalse(html.contains("<img src=x"))
        assertTrue(html.contains("&lt;script&gt;"))
        assertTrue(html.contains("&quot;"))
        // Single-pass substitution: the query's placeholder-looking text is not re-expanded.
        assertTrue(html.contains("{{CSS}}&lt;script&gt;"))
        assertTrue(html.contains("--accent: #3659E3"))
    }

    @Test
    fun `loading state shows skeletons and no result count`() {
        val html = render(results = emptyList(), loading = true, successful = emptyList(), finished = emptyList())
        val main = html.substringAfter("<main").substringBefore("</main>")
        assertTrue(main.contains("Searching the web"))
        assertTrue(main.contains("skeleton"))
        // The stylesheet legitimately defines .result-count; the loading document must not render one.
        assertFalse(main.contains("result-count"))
    }

    @Test
    fun `empty state distinguishes provider failure from no matches`() {
        val failed = render(results = emptyList(), successful = emptyList(), finished = listOf("Google", "Bing"))
        assertTrue(failed.contains("Search is temporarily unavailable"))
        assertTrue(failed.contains("Try again"))

        val noMatches = render(results = emptyList(), successful = listOf("Google"), finished = listOf("Google"))
        assertTrue(noMatches.contains("No results found"))
    }

    @Test
    fun `unsafe result urls are never rendered`() {
        val html = render(results = listOf(
            PhormiSearchTestFixtures.result(url = "javascript:alert(1)"),
            PhormiSearchTestFixtures.result(url = "data:text/html,hello"),
            PhormiSearchTestFixtures.result(url = "https://user:pass@example.com/"),
            PhormiSearchTestFixtures.result(title = "Safe", url = "https://example.com/safe")
        ))
        assertFalse(html.contains("javascript:"))
        assertFalse(html.contains("data:text/html"))
        assertFalse(html.contains("user:pass"))
        assertTrue(html.contains("https://example.com/safe"))
        assertTrue(html.contains("1 result"))
    }

    @Test
    fun `ai overview is hidden until an answer exists and keeps its heading`() {
        val without = render()
        assertTrue(without.contains("id=\"ai-answer\""))
        assertTrue(without.contains("hidden"))
        val with = render(aiAnswer = "Use coroutines on a dispatcher.")
        assertTrue(with.contains("AI overview"))
        assertTrue(with.contains("Use coroutines on a dispatcher."))
        assertTrue(with.contains("id=\"ai-answer-text\""))
        assertFalse(with.contains("id=\"ai-answer\" class=\"ai-answer\" hidden"))
    }

    @Test
    fun `more results button is present only for long lists`() {
        val many = (1..12).map { PhormiSearchTestFixtures.result(title = "Result $it", url = "https://example.com/$it") }
        assertTrue(render(results = many).contains("id=\"more-results\""))
        assertFalse(render(results = many.take(3)).contains("id=\"more-results\""))
    }

    @Test
    fun `template placeholders are fully resolved`() {
        val html = render()
        assertFalse(Regex("\\{\\{[A-Z_]+\\}\\}").containsMatchIn(html))
    }
}
