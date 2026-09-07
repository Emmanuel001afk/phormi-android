package com.uong.phormi

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
        knownMime?.takeIf { it.isNotBlank() }?.let { return it }
        if (uri.scheme == "content") context.contentResolver.getType(uri)?.takeIf { it.isNotBlank() }?.let { return it }
        val name = displayName(context, uri, "")
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext.isNotBlank()) MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)?.let { return it }
        return "application/octet-stream"
    }

    fun open(context: Context, uri: Uri, knownMime: String? = null): Boolean {
        val mime = resolveMimeType(context, uri, knownMime)
        if (mime.startsWith("image/") || mime.startsWith("video/") || mime.startsWith("audio/")) {
            return runCatching {
                context.startActivity(Intent(context, PhormiMediaViewerActivity::class.java).apply {
                    putExtra("uri", uri); putExtra("mime", mime); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                true
            }.getOrDefault(false)
        }
        fun intent(type: String) = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, type)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pm = context.packageManager
        return try {
            try {
                pm.getPackageInfo("android", 0)
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
