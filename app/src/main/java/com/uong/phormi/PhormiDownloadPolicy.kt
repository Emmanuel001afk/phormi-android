package com.uong.phormi

import java.net.URI

/** Pure resume rules: disk length is authoritative, and a rejected range restarts only once. */
internal object PhormiDownloadPolicy {
    data class Plan(val append: Boolean, val restart: Boolean, val total: Long)
    private val range = Regex("bytes (\\d+)-(\\d+)/(\\d+|\\*)", RegexOption.IGNORE_CASE)
    fun plan(existing: Long, code: Int, contentRange: String?, bodyLength: Long, knownTotal: Long): Plan {
        if (code == 416 && existing > 0) return Plan(false, true, -1)
        if (code != 206) return Plan(false, false, bodyLength.takeIf { it >= 0 } ?: knownTotal)
        val match = contentRange?.trim()?.let(range::matchEntire)
        val start = match?.groupValues?.get(1)?.toLongOrNull()
        val end = match?.groupValues?.get(2)?.toLongOrNull()
        val total = match?.groupValues?.get(3)?.toLongOrNull()
        if (start == null || end == null || start != existing || end < start || total != null && total <= end) return Plan(false, true, -1)
        return Plan(existing > 0, false, total ?: if (bodyLength >= 0) existing + bodyLength else knownTotal)
    }
    fun isStream(url: String, mime: String?): Boolean {
        val path = runCatching { URI(url).path.orEmpty().lowercase(java.util.Locale.ROOT) }.getOrDefault("")
        return path.endsWith(".m3u8") || path.endsWith(".mpd") || mime?.substringBefore(';')?.lowercase(java.util.Locale.ROOT) in setOf(
            "application/vnd.apple.mpegurl", "application/x-mpegurl", "audio/mpegurl", "audio/x-mpegurl", "application/dash+xml")
    }
    fun origin(url: String): String? = runCatching {
        val uri = URI(url)
        val scheme = uri.scheme?.lowercase(java.util.Locale.ROOT) ?: return null
        val host = uri.host?.lowercase(java.util.Locale.ROOT) ?: return null
        val port = if (uri.port != -1) uri.port else if (scheme == "https") 443 else 80
        "$scheme://$host:$port"
    }.getOrNull()
}
