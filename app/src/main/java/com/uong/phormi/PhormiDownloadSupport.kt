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
        val guessed = URLUtil.guessFileName(cleanUrl, contentDisposition, resolvedMime)
        val fileName = sanitizeFileName(improveGenericName(guessed, cleanUrl, resolvedMime))
        val headers = linkedMapOf<String, String>()
        if (!userAgent.isNullOrBlank()) headers["User-Agent"] = userAgent
        if (!referer.isNullOrBlank()) headers["Referer"] = referer
        if (!cookies.isNullOrBlank()) headers["Cookie"] = cookies
        return RequestInfo(cleanUrl, fileName, resolvedMime, headers, category(fileName, resolvedMime))
    }

    private fun guessMime(url: String): String? {
        val ext = runCatching { URL(url).path.substringAfterLast('.', "").lowercase() }.getOrNull()
        return ext?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
    }

    private fun improveGenericName(name: String, url: String, mime: String): String {
        var result = name.trim()
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
        if (result.isBlank() || result.equals("downloadfile", true) || result.endsWith(".bin", true)) {
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
            m.startsWith("video/") || n.endsWith(".mp4") || n.endsWith(".webm") || n.endsWith(".mkv") -> "Video"
            m.startsWith("image/") -> "Image"
            m.startsWith("audio/") -> "Audio"
            m == "application/pdf" || n.endsWith(".pdf") -> "PDF"
            n.endsWith(".zip") || n.endsWith(".rar") || n.endsWith(".7z") || n.endsWith(".tar") || m.contains("zip") -> "Archive"
            m.startsWith("text/") || listOf(".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx").any(n::endsWith) -> "Document"
            else -> "Other"
        }
    }
}
