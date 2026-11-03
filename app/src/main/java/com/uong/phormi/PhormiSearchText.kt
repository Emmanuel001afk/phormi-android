package com.uong.phormi

import java.util.Locale

/** Presentation-safe text cleanup shared by provider parsers and JVM tests. */
object PhormiSearchText {
    private val navigationTitles = setOf(
        "images", "videos", "maps", "news", "shopping", "books", "flights", "finance",
        "all", "web", "search", "settings", "privacy", "terms", "sign in", "log in",
        "next", "previous", "more", "menu", "home", "feedback", "help", "skip to content",
        "accept", "reject", "cookies", "cookie settings"
    )

    fun collapseWhitespace(value: String): String =
        value.replace(Regex("[\\u00A0\\u2007\\u202F]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    fun isNavigationTitle(title: String): Boolean {
        val normalized = collapseWhitespace(title).lowercase(Locale.US).trim('.', ':', '›', '»', '|')
        return normalized.length < 3 || normalized in navigationTitles
    }

    /** Remove duplicated titles and provider chrome, then cut on a word boundary. */
    fun cleanSnippet(raw: String, title: String, maxLength: Int = 260): String {
        var text = collapseWhitespace(raw)
        val cleanTitle = collapseWhitespace(title)
        if (cleanTitle.isNotBlank() && text.startsWith(cleanTitle, ignoreCase = true)) {
            text = text.substring(cleanTitle.length).trimStart(' ', '-', '–', '—', '|', ':', '·', '.')
        }
        text = text.replace(Regex("^(?:(?:read more|show more|skip to content)\\s*[:.-]?\\s*)+", RegexOption.IGNORE_CASE), "")
            .trim()
        if (text.length <= maxLength) return text
        val cut = text.take(maxLength)
        val boundary = cut.lastIndexOfAny(charArrayOf(' ', '.', '!', '?', ';', ','))
        val trimmed = if (boundary > maxLength / 2) cut.substring(0, boundary) else cut
        return trimmed.trimEnd(' ', '-', '–', '—', '|', ':', '·', '.', ',') + "…"
    }
}
