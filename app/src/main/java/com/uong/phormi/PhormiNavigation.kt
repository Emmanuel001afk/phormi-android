package com.uong.phormi

import java.util.Locale

/**
 * One navigation heuristic shared by the toolbar omnibox and the new-tab search field.
 *
 * Previously `navigateFromStartPage()` and `navigateFromUrlBar()` duplicated a
 * `raw.contains(".") && !raw.contains(" ")` test that treated queries such as
 * "version 1.5" or "what.is" as host names. This version is stricter and also
 * guarantees that a typed non-web scheme is never handed to WebView.
 */
object PhormiNavigation {
    enum class Kind { SEARCH, ADDRESS }

    data class Target(val kind: Kind, val value: String)

    fun looksLikeHostName(raw: String): Boolean {
        val value = raw.trim()
        if (value.isBlank() || value.any(Char::isWhitespace)) return false
        if (!value.contains('.')) return false
        val tld = value.substringAfterLast('.').substringBefore('/').substringBefore(':')
        if (tld.length < 2 || !tld.all(Char::isLetter)) return false
        val host = value.substringBefore('/').substringBefore(':').substringBefore('?')
        val parts = host.split('.')
        return parts.size >= 2 && parts.all { it.isNotBlank() && it.all { c -> c.isLetterOrDigit() || c == '-' } }
    }

    /** Returns null for blank input. Non-web schemes become searches, never WebView loads. */
    fun resolve(rawInput: String): Target? {
        val raw = rawInput.trim()
        if (raw.isEmpty()) return null
        if (raw.startsWith("http://", true) || raw.startsWith("https://", true)) {
            return Target(Kind.ADDRESS, raw)
        }
        if (raw.contains("://")) {
            val scheme = raw.substringBefore("://").lowercase(Locale.ROOT)
            if (scheme != "http" && scheme != "https") return Target(Kind.SEARCH, raw)
            return Target(Kind.ADDRESS, raw)
        }
        if (looksLikeHostName(raw)) return Target(Kind.ADDRESS, "https://$raw")
        return Target(Kind.SEARCH, raw)
    }
}
