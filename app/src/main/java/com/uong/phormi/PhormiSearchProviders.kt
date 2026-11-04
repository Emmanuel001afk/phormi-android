package com.uong.phormi

import java.net.URLEncoder

/** One provider contract for fetching result pages and opening the same search in a normal browser page. */
data class PhormiSearchProvider(
    val name: String,
    val fetchUrl: (String) -> String,
    val browseUrl: (String) -> String = fetchUrl
)

object PhormiSearchProviders {
    private fun encode(query: String): String = URLEncoder.encode(query, "UTF-8")

    val all: List<PhormiSearchProvider> = listOf(
        PhormiSearchProvider(
            "Google",
            { q -> "https://www.google.com/search?q=${encode(q)}&num=8" },
            { q -> "https://www.google.com/search?q=${encode(q)}" }
        ),
        PhormiSearchProvider(
            "Bing",
            { q -> "https://www.bing.com/search?q=${encode(q)}&count=8" },
            { q -> "https://www.bing.com/search?q=${encode(q)}" }
        ),
        PhormiSearchProvider(
            "DuckDuckGo",
            { q -> "https://html.duckduckgo.com/html/?q=${encode(q)}" },
            { q -> "https://duckduckgo.com/?q=${encode(q)}" }
        ),
        PhormiSearchProvider("Brave", { q -> "https://search.brave.com/search?q=${encode(q)}" }),
        PhormiSearchProvider("Yahoo", { q -> "https://search.yahoo.com/search?p=${encode(q)}" }),
        PhormiSearchProvider("Ecosia", { q -> "https://www.ecosia.org/search?q=${encode(q)}" }),
        PhormiSearchProvider("Mojeek", { q -> "https://www.mojeek.com/search?q=${encode(q)}" }),
        PhormiSearchProvider("Startpage", { q -> "https://www.startpage.com/sp/search?query=${encode(q)}" }),
        PhormiSearchProvider("Qwant", { q -> "https://www.qwant.com/?q=${encode(q)}&t=web" }),
        PhormiSearchProvider("Yandex", { q -> "https://yandex.com/search/?text=${encode(q)}" }),
        PhormiSearchProvider("Swisscows", { q -> "https://swisscows.com/en/web?query=${encode(q)}" })
    )

    fun browseUrl(name: String, query: String): String? =
        all.firstOrNull { it.name == name }?.browseUrl?.invoke(query)
}
