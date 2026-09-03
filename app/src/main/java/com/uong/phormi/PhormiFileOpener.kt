package com.uong.phormi

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap

/** Opens downloaded/local files with the best available Android handler. */
object PhormiFileOpener {
    fun displayName(context: Context, uri: Uri, fallback: String = "Download"): String {
        if (uri.scheme == "content") {
            runCatching {
                context.contentResolver.query(
                    uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
                )?.use { c ->
                    val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (i >= 0 && c.moveToFirst()) {
                        val name = c.getString(i)
                        if (!name.isNullOrBlank()) return name
                    }
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: fallback
    }

    fun mimeType(context: Context, uri: Uri, known: String?): String {
        if (!known.isNullOrBlank() && known != "application/octet-stream") return known
        context.contentResolver.getType(uri)?.let { if (it.isNotBlank()) return it }
        val name = displayName(context, uri)
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    fun open(context: Context, uri: Uri, knownMime: String? = null): Boolean {
        val mime = mimeType(context, uri, knownMime)
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(view)
            true
        } catch (_: ActivityNotFoundException) {
            val chooser = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "*/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try { context.startActivity(chooser); true } catch (_: Exception) { false }
        }
    }
}
