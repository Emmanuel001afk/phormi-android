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
        val resolvedMime = (mimeType?.substringBefore(';')?.trim().takeIf { !it.isNullOrBlank() }
            ?: guessMime(cleanUrl)
            ?: "application/octet-stream")
        val suggested = contentDispositionFileName(contentDisposition)
            ?: URLUtil.guessFileName(cleanUrl, contentDisposition, resolvedMime)
        val fileName = sanitizeFileName(improveGenericName(suggested, cleanUrl, resolvedMime))
        val headers = linkedMapOf<String, String>()
        if (!userAgent.isNullOrBlank()) headers["User-Agent"] = userAgent
        if (!referer.isNullOrBlank()) headers["Referer"] = referer
        if (!cookies.isNullOrBlank()) headers["Cookie"] = cookies
        return RequestInfo(cleanUrl, fileName, resolvedMime, headers, category(fileName, resolvedMime))
    }

    /** Handles both filename="..." and RFC 5987 filename*=UTF-8''... forms. */
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
