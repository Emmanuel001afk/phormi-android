package com.uong.phormi

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.URLUtil
import java.net.URL

/**
 * Gives failed browser downloads one controlled second attempt with the current
 * browser session. DownloadManager already performs its own transient retries;
 * this receiver is only for a terminal HTTP failure such as 403/429/5xx.
 */
class PhormiDownloadRetryReceiver : BroadcastReceiver() {
    companion object {
        private const val PREFS = "phormi_download_retries"
        private const val KEY_RETRIED = "retried_ids"
        private const val MAX_RETRIES = 1
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
        if (id < 0L) return

        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val cursor = runCatching { manager.query(DownloadManager.Query().setFilterById(id)) }.getOrNull() ?: return
        cursor.use { c ->
            if (!c.moveToFirst()) return
            val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            if (status != DownloadManager.STATUS_FAILED) return
            val reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
            val url = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_URI)).orEmpty()
            val title = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE)).orEmpty()
            val mime = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_MEDIA_TYPE)).orEmpty()
            if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) return
            if (!isRecoverableHttpFailure(reason)) return
            if (alreadyRetried(context, id)) return
            markRetried(context, id)

            val ua = runCatching { WebSettings.getDefaultUserAgent(context) }.getOrNull()
            val cookies = runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()
            val headers = linkedMapOf<String, String>()
            if (!ua.isNullOrBlank()) headers["User-Agent"] = ua
            if (!cookies.isNullOrBlank()) headers["Cookie"] = cookies
            headers["Accept"] = if (mime.isBlank()) "*/*" else "$mime,*/*;q=0.8"
            headers["Accept-Language"] = "en-US,en;q=0.9"
            headers["Referer"] = url
            runCatching {
                val parsed = URL(url)
                headers["Origin"] = "${parsed.protocol}://${parsed.authority}"
            }
            headers["Cache-Control"] = "no-cache"

            val safeName = URLUtil.guessFileName(url, null, mime.ifBlank { "application/octet-stream" })
                .takeIf { it.isNotBlank() && !it.equals("downloadfile", true) }
                ?: title.ifBlank { "phormi_download" }

            runCatching {
                val request = DownloadManager.Request(android.net.Uri.parse(url)).apply {
                    setTitle(safeName)
                    setMimeType(mime.ifBlank { "application/octet-stream" })
                    setDescription("Phormi · automatic browser recovery · $url")
                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, safeName)
                    setAllowedOverMetered(true)
                    setAllowedOverRoaming(true)
                    headers.forEach { (key, value) -> addRequestHeader(key, value) }
                }
                manager.enqueue(request)
            }
        }
    }

    private fun isRecoverableHttpFailure(reason: Int): Boolean =
        reason in 400..599 || reason == DownloadManager.ERROR_HTTP_DATA_ERROR || reason == DownloadManager.ERROR_CANNOT_RESUME

    private fun alreadyRetried(context: Context, id: Long): Boolean {
        val set = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_RETRIED, emptySet()) ?: emptySet()
        return id.toString() in set
    }

    private fun markRetried(context: Context, id: Long) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = (prefs.getStringSet(KEY_RETRIED, emptySet()) ?: emptySet()).toMutableSet()
        current.add(id.toString())
        // Keep the preference bounded so a long-lived browser does not accumulate IDs forever.
        val trimmed = current.takeLast(64).toSet()
        prefs.edit().putStringSet(KEY_RETRIED, trimmed).apply()
    }
}
