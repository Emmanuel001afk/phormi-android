package com.uong.phormi

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap

/** Opens downloaded/local content with the best available Android app. */
object PhormiFileOpener {
    fun displayName(context: Context, uri: Uri, fallback: String = "File"): String {
        if (uri.scheme == "content") {
            runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (index >= 0) cursor.getString(index)?.takeIf { it.isNotBlank() }?.let { return it }
                    }
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/').takeIf { !it.isNullOrBlank() } ?: fallback
    }

    fun resolveMimeType(context: Context, uri: Uri, knownMime: String? = null): String {
        knownMime?.takeIf { it.isNotBlank() && !it.equals("application/octet-stream", true) }?.let { return it }
        if (uri.scheme == "content") context.contentResolver.getType(uri)?.takeIf { it.isNotBlank() }?.let { return it }
        val name = displayName(context, uri, "")
        val ext = name.substringAfterLast('.', "").lowercase()
        val explicit = when (ext) {
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "mp4", "m4v" -> "video/mp4"
            "mov" -> "video/quicktime"
            "avi" -> "video/x-msvideo"
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "flac" -> "audio/flac"
            "wav" -> "audio/wav"
            "ogg", "oga" -> "audio/ogg"
            "m3u8" -> "application/x-mpegURL"
            "apk" -> "application/vnd.android.package-archive"
            "zip" -> "application/zip"
            "rar" -> "application/vnd.rar"
            "7z" -> "application/x-7z-compressed"
            else -> null
        }
        explicit?.let { return it }
        if (ext.isNotBlank()) MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)?.let { return it }
        return "application/octet-stream"
    }

    fun openExternal(context: Context, uri: Uri, knownMime: String? = null): Boolean {
        val mime = resolveMimeType(context, uri, knownMime)
        fun view(type: String) = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, type)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            try {
                context.startActivity(Intent.createChooser(view(mime), "Open with"))
            } catch (_: ActivityNotFoundException) {
                context.startActivity(Intent.createChooser(view("*/*"), "Open with"))
            }
            true
        }.getOrDefault(false)
    }

    @SuppressLint("UnsafeOptInUsageError")
    fun open(context: Context, uri: Uri, knownMime: String? = null): Boolean {
        val mime = resolveMimeType(context, uri, knownMime)
        if (mime.startsWith("image/") || mime.startsWith("video/") || mime.startsWith("audio/")) {
            val internal = runCatching {
                context.startActivity(Intent(context, PhormiMediaViewerActivity::class.java).apply {
                    putExtra("uri", uri); putExtra("mime", mime); putExtra("title", displayName(context, uri)); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                true
            }.getOrDefault(false)
            if (internal) return true
            return openExternal(context, uri, mime)
        }
        fun intent(type: String) = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, type)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            try {
                context.startActivity(intent(mime))
                true
            } catch (_: ActivityNotFoundException) {
                context.startActivity(intent("*/*"))
                true
            }
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }
}
