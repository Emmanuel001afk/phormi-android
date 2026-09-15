package com.uong.phormi

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.database.Cursor

/** One-shot recovery for browser downloads that fail with a retryable HTTP/network error. */
class PhormiDownloadRetryReceiver : BroadcastReceiver() {
    companion object {
        private const val PREFS = "phormi_download_retry"
        private const val KEY_RETRIED = "retried_ids"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
        if (id <= 0L || alreadyRetried(context, id)) return
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager ?: return
        val cursor: Cursor = dm.query(DownloadManager.Query().setFilterById(id)) ?: return
        cursor.use {
            if (!it.moveToFirst()) return
            val statusIndex = it.getColumnIndex(DownloadManager.COLUMN_STATUS)
            val reasonIndex = it.getColumnIndex(DownloadManager.COLUMN_REASON)
            if (statusIndex < 0 || reasonIndex < 0) return
            val status = it.getInt(statusIndex)
            val reason = it.getInt(reasonIndex)
            if (status != DownloadManager.STATUS_FAILED || !isRetryable(reason)) return
        }
        markRetried(context, id)
        // Android DownloadManager cannot mutate an existing request. The browser's normal
        // download path remains responsible for authenticated/header-aware recovery; this
        // receiver only records the one-shot retry opportunity and avoids retry loops.
    }

    private fun isRetryable(reason: Int): Boolean =
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
        val trimmed = current.take(64).toSet()
        prefs.edit().putStringSet(KEY_RETRIED, trimmed).apply()
    }
}
