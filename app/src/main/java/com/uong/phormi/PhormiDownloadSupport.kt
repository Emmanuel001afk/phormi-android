package com.uong.phormi

import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import java.net.URL
import java.net.URLDecoder

/** Centralizes filename, MIME, source-link, and authenticated download headers. */
object PhormiDownloadSupport {
    data class RequestInfo(
        val sourceUrl: String,
        val fileName: String,
        val mimeType: String,
        val headers: Map<String, String>,
        val category: String
    )

    fun resolve(
        url: String,
        contentDisposition: String?,
        mimeType: String?,
        userAgent: String?,
        referer: String?,
        cookies: String?
    ): RequestInfo {
        val cleanUrl = url.trim()
        val serverMime = mimeType?.substringBefore(';')?.trim()?.lowercase(java.util.Locale.ROOT).takeIf { !it.isNullOrBlank() }
        val urlMime = guessMime(cleanUrl)
        val initialMime = serverMime ?: urlMime ?: "application/octet-stream"
        val explicitName = contentDispositionFileName(contentDisposition)
        val suggested = explicitName ?: URLUtil.guessFileName(cleanUrl, contentDisposition, initialMime)
        val fileName = sanitizeFileName(improveGenericName(suggested, cleanUrl, initialMime, explicitName != null))
        val filenameMime = guessMime(fileName)
        val resolvedMime = if (serverMime == null || serverMime.equals("application/octet-stream", true)) filenameMime ?: urlMime ?: initialMime
            else serverMime
        val headers = linkedMapOf<String, String>()
        if (!userAgent.isNullOrBlank()) headers["User-Agent"] = userAgent
        if (!referer.isNullOrBlank()) headers["Referer"] = referer
        if (!cookies.isNullOrBlank()) headers["Cookie"] = cookies
        return RequestInfo(cleanUrl, fileName, resolvedMime, headers, category(fileName, resolvedMime))
    }

    /** Handles both filename="..." and RFC 5987 filename*=UTF-8''... forms. */
    private fun contentDispositionFileName(value: String?): String? {
        if (value.isNullOrBlank()) return null
        val encoded = Regex("(?i)filename\\s*\\*\\s*=\\s*([^;]+)").find(value)?.groupValues?.getOrNull(1)?.trim()?.trim('"')
        if (!encoded.isNullOrBlank()) {
            val parts = encoded.split("'", limit = 3)
            val charset = if (parts.size == 3) parts[0].ifBlank { "UTF-8" } else "UTF-8"
            val name = if (parts.size == 3) parts[2] else encoded
            runCatching { URLDecoder.decode(name.replace("+", "%2B"), charset) }.getOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
        }
        val quoted = Regex("(?i)filename\\s*=\\s*\"([^\"]+)\"").find(value)?.groupValues?.getOrNull(1)
        if (!quoted.isNullOrBlank()) return quoted.trim()
        return Regex("(?i)filename\\s*=\\s*([^;]+)").find(value)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun guessMime(value: String): String? {
        val path = runCatching { java.net.URI(value).path }.getOrNull() ?: value.substringBefore('?').substringBefore('#')
        val ext = path.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)
        return when (ext) {
            "m3u8" -> "application/vnd.apple.mpegurl"; "mpd" -> "application/dash+xml"
            "mkv" -> "video/x-matroska"; "webm" -> "video/webm"; "m4a" -> "audio/mp4"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
        }
    }

    private fun improveGenericName(name: String, url: String, mime: String, explicitName: Boolean): String {
        var result = name.trim()
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
        val pathName = runCatching {
            URLDecoder.decode(URL(url).path.substringAfterLast('/').replace("+", "%2B"), "UTF-8")
        }.getOrNull().orEmpty()
        val urlReallyEndsInBin = pathName.endsWith(".bin", true)

        // Do not invent a .bin filename just because the server supplied a generic
        // application/octet-stream response. Preserve a real .bin URL or an explicit
        // Content-Disposition filename because those can be intentional binary files.
        val syntheticBin = result.endsWith(".bin", true) && !explicitName && !urlReallyEndsInBin
        if (result.isBlank() || result.equals("downloadfile", true) || result.equals("download", true) || syntheticBin) {
            if (pathName.isNotBlank() && pathName != "/") result = pathName.substringAfterLast('/')
            if ((result.isBlank() || (result.endsWith(".bin", true) && !urlReallyEndsInBin)) && !ext.isNullOrBlank()) {
                result = result.removeSuffix(".bin").removeSuffix(".BIN").ifBlank { "phormi_download" } + "." + ext
            }
            if (result.isBlank() || result.equals("downloadfile", true)) result = "phormi_download"
        }
        if (!result.contains('.') && !ext.isNullOrBlank()) result += ".${ext}"
        return result
    }

    private fun sanitizeFileName(name: String): String {
        val cleaned = name.filterNot { it in '\u202A'..'\u202E' || it in '\u2066'..'\u2069' || it == '\u200E' || it == '\u200F' }.replace(Regex("[\\\\/:*?\"<>|\\p{Cc}]+"), "_").trim().trim('.')
        val extension = cleaned.substringAfterLast('.', "").takeIf { it.length in 1..12 }?.let { ".$it" }.orEmpty()
        val base = if (extension.isNotEmpty()) cleaned.dropLast(extension.length) else cleaned
        var clipped = base.take(160)
        if (clipped.lastOrNull()?.isHighSurrogate() == true) clipped = clipped.dropLast(1)
        while (clipped.toByteArray(Charsets.UTF_8).size > 180) clipped = clipped.dropLast(if (clipped.last().isLowSurrogate()) 2 else 1)
        return (clipped + extension).ifBlank { "phormi_download" }
    }

    fun category(fileName: String, mime: String): String {
        val n = fileName.lowercase()
        val m = mime.lowercase()
        return when {
            m.startsWith("video/") || n.endsWith(".mp4") || n.endsWith(".webm") || n.endsWith(".mkv") || n.endsWith(".mov") -> "Video"
            m.startsWith("image/") -> "Image"
            m.startsWith("audio/") -> "Audio"
            m == "application/pdf" || n.endsWith(".pdf") -> "PDF"
            n.endsWith(".zip") || n.endsWith(".rar") || n.endsWith(".7z") || n.endsWith(".tar") || m.contains("zip") -> "Archive"
            m.startsWith("text/") || listOf(".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx").any(n::endsWith) -> "Document"
            else -> "Other"
        }
    }
}
