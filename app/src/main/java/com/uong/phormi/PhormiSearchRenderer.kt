package com.uong.phormi

import java.net.URLEncoder

/** A merged result ready for presentation. Kept free of Android types so it can be unit-tested on the JVM. */
data class PhormiSearchResult(
    val title: String,
    val url: String,
    val snippet: String,
    val source: String,
    val rank: Int
)

data class PhormiSearchRenderState(
    val query: String,
    val results: List<PhormiSearchResult>,
    val loading: Boolean,
    val completed: Int = 0,
    val total: Int = 0,
    val successfulEngines: List<String> = emptyList(),
    val finishedEngines: List<String> = emptyList(),
    val aiAnswer: String? = null
)

/** Opaque #RRGGBB values taken from the app's semantic day/night palette. */
data class PhormiSearchPalette(
    val background: String,
    val surface: String,
    val surfaceSoft: String,
    val text: String,
    val secondary: String,
    val accent: String,
    val accentSoft: String,
    val onAccent: String,
    val coral: String,
    val border: String
)

data class PhormiSearchAssets(val template: String, val css: String, val script: String)

/**
 * Renders the local results document. All provider-controlled text is escaped here,
 * independently of the parser, and template substitution is single-pass so a query
 * containing a placeholder-looking string can never inject markup or assets.
 */
object PhormiSearchRenderer {
    private const val INITIAL_VISIBLE_RESULTS = 10
    private const val PAGE_SIZE = 10

    fun render(
        state: PhormiSearchRenderState,
        palette: PhormiSearchPalette,
        assets: PhormiSearchAssets
    ): String {
        val query = state.query.trim()
        val safeResults = state.results.mapNotNull { result ->
            PhormiSearchUrls.webUrlOrNull(result.url)?.let { result.copy(url = it) }
        }
        val content = buildString {
            append(renderStatus(state, safeResults.size))
            append(renderAiAnswer(state.aiAnswer))
            when {
                state.loading && safeResults.isEmpty() -> append(renderSkeleton())
                safeResults.isEmpty() -> append(renderEmptyState(state, query))
                else -> append(renderResults(safeResults))
            }
            append(renderFooter(state, query))
        }
        val values = mapOf(
            "TITLE" to escape(if (query.isBlank()) "Phormi Search" else "$query · Phormi Search"),
            "QUERY" to escape(query),
            "BODY_CLASS" to if (state.loading) "is-loading" else "is-ready",
            "PALETTE" to paletteCss(palette),
            "CSS" to assets.css,
            "SCRIPT" to assets.script,
            "CONTENT" to content
        )
        return Regex("\\{\\{([A-Z_]+)\\}\\}").replace(assets.template) { match ->
            values[match.groupValues[1]] ?: match.value
        }
    }

    private fun renderStatus(state: PhormiSearchRenderState, count: Int): String = when {
        state.loading && count == 0 ->
            """<p class="status" role="status"><span class="spinner" aria-hidden="true"></span>Searching the web…</p>"""
        state.loading ->
            """<p class="status updating" role="status"><span class="spinner" aria-hidden="true"></span>Updating results…</p>"""
        count > 0 ->
            """<p class="result-count">${count} ${if (count == 1) "result" else "results"}</p>"""
        else -> ""
    }

    private fun renderAiAnswer(answer: String?): String {
        val hidden = if (answer.isNullOrBlank()) " hidden" else ""
        val text = escape(answer.orEmpty())
        return """
            <section id="ai-answer" class="ai-answer"$hidden aria-label="AI overview">
              <div class="ai-heading"><span class="ai-spark" aria-hidden="true">✦</span>AI overview</div>
              <div id="ai-answer-text" class="ai-text">$text</div>
              <p class="ai-note">Generated from search results. Check important details.</p>
            </section>
        """.trimIndent()
    }

    private fun renderSkeleton(): String = buildString {
        append("""<div class="skeletons" aria-hidden="true">""")
        repeat(4) {
            append(
                """
                <div class="skeleton">
                  <div class="skeleton-line short"></div>
                  <div class="skeleton-line title"></div>
                  <div class="skeleton-line"></div>
                  <div class="skeleton-line medium"></div>
                </div>
                """.trimIndent()
            )
        }
        append("</div>")
    }

    private fun renderEmptyState(state: PhormiSearchRenderState, query: String): String {
        val providersFailed = state.finishedEngines.isNotEmpty() && state.successfulEngines.isEmpty()
        val heading = if (providersFailed) "Search is temporarily unavailable" else "No results found"
        val message = if (providersFailed) {
            "Phormi couldn't reach the search providers. Check your connection and try again."
        } else {
            "Try different keywords, check the spelling, or open this search directly in another engine."
        }
        val google = PhormiSearchProviders.browseUrl("Google", query).orEmpty()
        return """
            <section class="empty-state">
              <div class="empty-icon" aria-hidden="true">⌕</div>
              <h1>${escape(heading)}</h1>
              <p>${escape(message)}</p>
              <div class="empty-actions">
                <a class="button primary" href="${escape(refreshUrl(query))}">Try again</a>
                <a class="button" href="${escape(google)}">Open in Google</a>
              </div>
            </section>
        """.trimIndent()
    }

    private fun renderResults(results: List<PhormiSearchResult>): String = buildString {
        append("""<ol id="results" class="results" aria-label="Search results">""")
        results.forEachIndexed { index, result ->
            val host = PhormiSearchUrls.siteName(result.url)
            val path = PhormiSearchUrls.breadcrumb(result.url).removePrefix(host).removePrefix(" › ").trim()
            val initial = host.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "•"
            val sources = result.source.split(" · ").filter { it.isNotBlank() }.joinToString(", ") { escape(it) }
            append(
                """
                <li class="result" data-index="$index">
                  <div class="site-row">
                    <span class="site-mark" aria-hidden="true">${escape(initial)}</span>
                    <div class="site-meta">
                      <span class="site-name">${escape(host)}</span>
                      ${if (path.isBlank()) "" else """<span class="site-path">${escape(path)}</span>"""}
                    </div>
                    <details class="result-menu">
                      <summary aria-label="Details for ${escape(result.title)}">⋯</summary>
                      <div class="menu-panel">
                        <p class="menu-label">Found via</p>
                        <p class="menu-sources">$sources</p>
                        <a href="${escape(result.url)}">Open original page</a>
                      </div>
                    </details>
                  </div>
                  <h2 class="result-title"><a href="${escape(result.url)}">${escape(result.title)}</a></h2>
                  ${if (result.snippet.isBlank()) "" else """<p class="snippet">${escape(result.snippet)}</p>"""}
                </li>
                """.trimIndent()
            )
        }
        append("</ol>")
        if (results.size > INITIAL_VISIBLE_RESULTS) {
            append(
                """
                <div class="more-wrap">
                  <button id="more-results" class="button more" type="button"
                          data-page-size="$PAGE_SIZE" hidden>More results</button>
                </div>
                """.trimIndent()
            )
        }
    }

    private fun renderFooter(state: PhormiSearchRenderState, query: String): String {
        val coverage = when {
            state.loading -> "Search providers are still responding."
            state.successfulEngines.isEmpty() -> "No search providers returned results."
            else -> {
                val noun = if (state.successfulEngines.size == 1) "provider" else "providers"
                "Results were collected from ${state.successfulEngines.size} search $noun."
            }
        }
        val chips = PhormiSearchProviders.all.joinToString("") { provider ->
            val url = provider.browseUrl(query)
            """<a class="engine-chip" href="${escape(url)}">${escape(provider.name)}</a>"""
        }
        return """
            <footer class="footer">
              <details class="footer-block">
                <summary>About these results</summary>
                <p>Phormi combines public results from several search providers, removes duplicates and ranks
                   the matches on your device. It is not an independent search index, so coverage can differ
                   from any single engine.</p>
                <p class="coverage">${escape(coverage)}</p>
              </details>
              <details class="footer-block">
                <summary>Try another search engine</summary>
                <div class="engine-chips">$chips</div>
              </details>
              <a class="refresh-link" href="${escape(refreshUrl(query))}">Refresh results</a>
            </footer>
        """.trimIndent()
    }

    private fun refreshUrl(query: String): String =
        "https://phormi.local/refresh?q=" + URLEncoder.encode(query, "UTF-8")

    private fun paletteCss(p: PhormiSearchPalette): String = """
        :root {
          --bg: ${p.background};
          --surface: ${p.surface};
          --surface-soft: ${p.surfaceSoft};
          --text: ${p.text};
          --secondary: ${p.secondary};
          --accent: ${p.accent};
          --accent-soft: ${p.accentSoft};
          --on-accent: ${p.onAccent};
          --coral: ${p.coral};
          --border: ${p.border};
        }
    """.trimIndent()

    private fun escape(value: String): String = buildString(value.length) {
        for (ch in value) {
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                '`' -> append("&#96;")
                else -> append(ch)
            }
        }
    }
}
