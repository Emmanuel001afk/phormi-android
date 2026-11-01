package com.uong.phormi

import java.net.IDN
import java.net.URI
import java.util.Locale

/** Distinguish web addresses from search terms without interpreting arbitrary executable schemes. */
object PhormiNavigationInput {
    fun webUrl(input: String): String? {
        val value = input.trim()
        if (value.isEmpty() || value.any { it == '\n' || it == '\r' || it == '\t' }) return null
        val explicit = value.startsWith("https://", true) || value.startsWith("http://", true)
        if (!explicit && value.any(Char::isWhitespace)) return null
        val candidate = if (explicit) value.replace(" ", "%20") else {
            val host = value.substringBefore('/').substringBefore('?').substringBefore('#')
            if (!(host.contains('.') || host.startsWith("[") || host.substringBefore(':').equals("localhost", true))) return null
            // Bare addresses containing @ or an unrelated scheme are searches, not credentials/URLs.
            if (host.contains('@') || value.contains("://")) return null
            "${if (host.substringBefore(':').equals("localhost", true)) "http" else "https"}://$value"
        }
        return runCatching {
            val uri = URI(candidate)
            if (uri.userInfo != null || uri.rawAuthority.isNullOrBlank() || !uri.scheme.lowercase(Locale.ROOT).let { it == "http" || it == "https" }) return null
            val authority = uri.rawAuthority
            val port = if (authority.startsWith("[")) authority.substringAfter(']', "").removePrefix(":") else authority.substringAfter(':', "")
            if (port.isNotEmpty() && (port.toIntOrNull() ?: -1) !in 1..65535) return null
            val host = if (authority.startsWith("[")) authority.substringBefore(']') + "]" else IDN.toASCII(authority.substringBefore(':'), IDN.USE_STD3_ASCII_RULES)
            if (host.isBlank() || host.contains('@')) return null
            URI(uri.scheme.lowercase(Locale.ROOT) + "://" + host.lowercase(Locale.ROOT) + (if (port.isEmpty()) "" else ":$port") +
                (uri.rawPath ?: "") + (uri.rawQuery?.let { "?$it" } ?: "") + (uri.rawFragment?.let { "#$it" } ?: "")).toASCIIString()
        }.getOrNull()
    }
}
