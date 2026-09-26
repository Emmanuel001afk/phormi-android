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
            "webm" -> "video/webm"
            "mp4", "m4v" -> "video/mp4"
            "mov" -> "video/quicktime"
            "avi" -> "video/x-msvideo"
            "3gp" -> "video/3gpp"
            "3g2" -> "video/3gpp2"
            "mkv" -> "video/x-matroska"
            "ts", "m2ts", "mts" -> "video/mp2t"
            "mpeg", "mpg" -> "video/mpeg"
            "ogv" -> "video/ogg"
            "flv" -> "video/x-flv"
            "wmv" -> "video/x-ms-wmv"
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

    private fun isCodeOrText(name: String, mime: String): Boolean {
        if (mime.startsWith("text/")) return true
        val ext = name.lowercase().substringAfterLast('.', "")
        return ext in setOf("kt","kts","java","js","jsx","ts","tsx","py","c","h","cpp","hpp","cc","cs","go","rs","swift","dart","php","rb","sh","bash","zsh","fish","html","htm","css","scss","sass","xml","json","json5","yaml","yml","toml","ini","cfg","conf","properties","gradle","sql","graphql","gql","md","markdown","txt","log","csv","env","vue","svelte","astro")
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
        if (isCodeOrText(displayName(context, uri), mime)) {
            return runCatching {
                context.startActivity(Intent(context, PhormiCodeViewerActivity::class.java).apply {
                    putExtra("uri", uri)
                    putExtra("title", displayName(context, uri))
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                true
            }.getOrElse { openExternal(context, uri, mime) }
        }
        if (mime == "application/pdf") {
            return runCatching {
                context.startActivity(Intent(context, PhormiPdfViewerActivity::class.java).apply {
                    putExtra("uri", uri)
                    putExtra("title", displayName(context, uri))
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                true
            }.getOrElse { openExternal(context, uri, mime) }
        }
        if (mime == "application/zip") {
            return runCatching {
                context.startActivity(Intent(context, PhormiZipViewerActivity::class.java).apply {
                    putExtra("uri", uri)
                    putExtra("title", displayName(context, uri))
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                true
            }.getOrElse { openExternal(context, uri, mime) }
        }
        if (mime == "application/vnd.android.package-archive") {
            return runCatching {
                val installer = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mime)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(Intent.createChooser(installer, "Install APK with"))
                true
            }.getOrDefault(false)
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
