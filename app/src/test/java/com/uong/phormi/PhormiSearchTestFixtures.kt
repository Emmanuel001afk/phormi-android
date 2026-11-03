package com.uong.phormi

import java.io.File

internal object PhormiSearchTestFixtures {
    val palette = PhormiSearchPalette(
        background = "#FAF5E9",
        surface = "#FFFDF7",
        surfaceSoft = "#FFF4D8",
        text = "#192653",
        secondary = "#5B6178",
        accent = "#3659E3",
        accentSoft = "#E6EBFF",
        onAccent = "#FFFFFF",
        coral = "#FF9678",
        border = "#E4D9C3"
    )

    fun assets(): PhormiSearchAssets {
        fun read(name: String): String {
            val candidates = listOf(
                File("src/main/assets/search/$name"),
                File("app/src/main/assets/search/$name")
            )
            val file = candidates.firstOrNull { it.isFile }
                ?: error("Missing search asset: $name")
            return file.readText(Charsets.UTF_8)
        }
        return PhormiSearchAssets(read("results.html"), read("results.css"), read("results.js"))
    }

    fun result(
        title: String = "Example result",
        url: String = "https://example.com/page",
        snippet: String = "A useful description.",
        source: String = "Google",
        rank: Int = 0
    ) = PhormiSearchResult(title, url, snippet, source, rank)
}
