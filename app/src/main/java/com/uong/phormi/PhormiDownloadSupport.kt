package com.uong.phormi

import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import java.net.URL
import java.net.URLDecoder

/** Centralizes filename, MIME, source-link, and browser-session download headers. */
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
        val resolvedMime = (mimeType?.substringBefore(';')?.trim().takeIf { !it.isNullOrBlank() }
            ?: guessMime(cleanUrl)
            ?: "application/octet-stream")
        val suggested = contentDispositionFileName(contentDisposition)
            ?: URLUtil.guessFileName(cleanUrl, contentDisposition, resolvedMime)
        val fileName = sanitizeFileName(improveGenericName(suggested, cleanUrl, resolvedMime))
        val headers = linkedMapOf<String, String>()
        if (!userAgent.isNullOrBlank()) headers["User-Agent"] = userAgent
        if (!referer.isNullOrBlank() && referer != cleanUrl) headers["Referer"] = referer
        if (!cookies.isNullOrBlank()) headers["Cookie"] = cookies

        // These headers make direct downloads look like the resource request made by
        // the WebView rather than a bare background client. They are especially useful
        // for CDNs that require a browser-style Accept header or hot-link protection.
        headers["Accept"] = acceptFor(resolvedMime)
        headers["Accept-Language"] = "en-US,en;q=0.9"
        headers["Cache-Control"] = "no-cache"
        headers["Pragma"] = "no-cache"
        if (!referer.isNullOrBlank()) {
            val origin = runCatching {
                val u = URL(referer)
                "${u.protocol}://${u.authority}"
            }.getOrNull()
            if (!origin.isNullOrBlank() && origin != originOf(cleanUrl)) {
                headers["Origin"] = origin
            }
        }

        return RequestInfo(cleanUrl, fileName, resolvedMime, headers, category(fileName, resolvedMime))
    }

    private fun acceptFor(mime: String): String = when {
        mime.startsWith("video/") -> "video/*,*/*;q=0.8"
        mime.startsWith("audio/") -> "audio/*,*/*;q=0.8"
        mime.startsWith("image/") -> "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8"
        mime == "application/pdf" -> "application/pdf,*/*;q=0.8"
        else -> "*/*"
    }

    private fun originOf(value: String): String? = runCatching {
        val u = URL(value)
        "${u.protocol}://${u.authority}"
    }.getOrNull()

    /** Handles filename="..." and RFC 5987 filename*=UTF-8''... forms. */
    private fun contentDispositionFileName(value: String?): String? {
        if (value.isNullOrBlank()) return null
        val encoded = Regex("(?i)filename\\s*\\*\\s*=\\s*(?:UTF-8''|[^']*'[^']*')?([^;]+)").find(value)?.groupValues?.getOrNull(1)
        if (!encoded.isNullOrBlank()) return decodeDispositionName(encoded)
        val quoted = Regex("(?i)filename\\s*=\\s*\\\"([^\\\"]+)\\\"").find(value)?.groupValues?.getOrNull(1)
        if (!quoted.isNullOrBlank()) return quoted.trim()
        val plain = Regex("(?i)filename\\s*=\\s*([^;]+)").find(value)?.groupValues?.getOrNull(1)
        return plain?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun decodeDispositionName(value: String): String = runCatching {
        URLDecoder.decode(value.trim().trim('"'), "UTF-8")
    }.getOrElse { value.trim().trim('"') }

    private fun guessMime(url: String): String? {
        val ext = runCatching { URL(url).path.substringAfterLast('.', "").lowercase() }.getOrNull()
        return ext?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
    }

    private fun improveGenericName(name: String, url: String, mime: String): String {
        var result = name.trim()
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
        if (result.isBlank() || result.equals("downloadfile", true) || result.equals("download", true) || result.endsWith(".bin", true)) {
            val pathName = runCatching {
                URLDecoder.decode(URL(url).path.substringAfterLast('/'), "UTF-8")
            }.getOrNull().orEmpty()
            if (pathName.isNotBlank() && pathName != "/") result = pathName.substringAfterLast('/')
            if ((result.isBlank() || result.endsWith(".bin", true)) && !ext.isNullOrBlank()) {
                result = result.removeSuffix(".bin").removeSuffix(".BIN").ifBlank { "phormi_download" } + "." + ext
            }
        }
        if (!result.contains('.') && !ext.isNullOrBlank()) result += ".${ext}"
        return result
    }

    private fun sanitizeFileName(name: String): String {
        val cleaned = name.replace(Regex("[\\\\/:*?\"<>|\\r\\n]+"), "_").trim()
        return cleaned.take(180).ifBlank { "phormi_download" }
    }

    fun category(fileName: String, mime: String): String {
        val n = fileName.lowercase()
        val m = mime.lowercase()
        return when {
            m.startsWith("video/") || listOf(".mp4", ".webm", ".mkv", ".mov", ".m4v", ".ts").any(n::endsWith) -> "Video"
            m.startsWith("image/") -> "Image"
            m.startsWith("audio/") -> "Audio"
            m == "application/pdf" || n.endsWith(".pdf") -> "PDF"
            n.endsWith(".apk") || m == "application/vnd.android.package-archive" -> "APK"
            n.endsWith(".zip") || n.endsWith(".rar") || n.endsWith(".7z") || n.endsWith(".tar") || n.endsWith(".gz") || m.contains("zip") -> "Archive"
            m.startsWith("text/") || listOf(".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx", ".epub").any(n::endsWith) -> "Document"
            else -> "Other"
        }
    }
}
