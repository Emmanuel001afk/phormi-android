package com.uong.phormi

import java.net.URI
import java.net.URLDecoder
import java.util.Locale

/** Pure URL helpers shared by the search pipeline, renderer and JVM tests. */
object PhormiSearchUrls {
    private val trackingKeys = setOf(
        "fbclid", "gclid", "dclid", "msclkid", "mc_cid", "mc_eid", "igshid", "yclid", "ref", "ref_src"
    )
    private val searchHosts = setOf(
        "google.com", "www.google.com", "bing.com", "www.bing.com",
        "duckduckgo.com", "html.duckduckgo.com", "search.brave.com",
        "search.yahoo.com", "ecosia.org", "www.ecosia.org",
        "mojeek.com", "www.mojeek.com", "startpage.com", "www.startpage.com",
        "qwant.com", "www.qwant.com", "yandex.com", "swisscows.com"
    )
    private val searchPaths = setOf(
        "", "/", "/search", "/search/", "/html", "/html/", "/sp/search", "/sp/search/",
        "/web", "/web/", "/en/web", "/en/web/", "/url", "/url/", "/aclk", "/aclk/"
    )

    /** Accept only ordinary web links; never render javascript:, data:, file: or credentialed URLs. */
    fun webUrlOrNull(value: String): String? {
        val trimmed = value.trim()
        if (trimmed.isEmpty() || trimmed.any { it.isISOControl() || it.isWhitespace() }) return null
        return runCatching {
            val uri = URI(trimmed)
            val scheme = uri.scheme?.lowercase(Locale.US)
            if ((scheme == "http" || scheme == "https") &&
                !uri.host.isNullOrBlank() && uri.rawUserInfo == null
            ) trimmed else null
        }.getOrNull()
    }

    /** Canonical identity for deduplication. Host is case-insensitive; path/query values are not. */
    fun canonical(value: String): String = runCatching {
        val uri = URI(value)
        val host = uri.host?.lowercase(Locale.US)?.removePrefix("www.") ?: return value
        val port = if (uri.port > 0 && !((uri.scheme == "https" && uri.port == 443) ||
                (uri.scheme == "http" && uri.port == 80))) ":${uri.port}" else ""
        val path = (uri.rawPath ?: "").trimEnd('/')
        val query = uri.rawQuery.orEmpty().split('&').filter { it.isNotBlank() }.mapNotNull { pair ->
            val key = pair.substringBefore('=')
            val decodedKey = runCatching { URLDecoder.decode(key, "UTF-8") }.getOrDefault(key)
            if (decodedKey.startsWith("utm_") || decodedKey.lowercase(Locale.US) in trackingKeys) null
            else pair
        }.sorted().joinToString("&")
        "${uri.scheme.lowercase(Locale.US)}://$host$port$path${if (query.isBlank()) "" else "?$query"}"
    }.getOrDefault(value)

    fun host(value: String): String = runCatching {
        URI(value).host?.lowercase(Locale.US)?.removePrefix("www.").orEmpty()
    }.getOrDefault("")

    fun siteName(value: String): String = host(value).ifBlank { "Website" }

    /** Short, human-readable location instead of a raw URL under every title. */
    fun breadcrumb(value: String): String {
        val host = siteName(value)
        val segments = runCatching {
            URI(value).rawPath.orEmpty().split('/').filter { it.isNotBlank() }.map { segment ->
                runCatching { URLDecoder.decode(segment, "UTF-8") }.getOrDefault(segment)
                    .replace('-', ' ').replace('_', ' ').trim()
            }.filter { it.isNotBlank() && it.lowercase(Locale.US) !in setOf("index.html", "index.htm", "index.php") }
        }.getOrDefault(emptyList())
        if (segments.isEmpty()) return host
        val shown = segments.take(3).joinToString(" › ")
        val suffix = if (segments.size > 3) " › …" else ""
        val location = "$host › $shown$suffix"
        return if (location.length > 96) location.take(93).trimEnd() + "…" else location
    }

    /** True for the providers' own search/navigation pages, not for unrelated docs on the same brand domain. */
    fun isProviderNavigation(value: String): Boolean = runCatching {
        val uri = URI(value)
        val host = uri.host?.lowercase(Locale.US).orEmpty()
        val path = uri.rawPath.orEmpty().trimEnd('/').ifBlank { "/" }
        host in searchHosts && (path in searchPaths || path == "/")
    }.getOrDefault(false)
}
