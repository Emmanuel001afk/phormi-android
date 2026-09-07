package com.uong.phormi

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/** Local Phormi-owned notification layer. This intentionally does not pretend to be Web Push. */
object PhormiNotificationCenter {
    private const val CHANNEL_ID = "phormi_browser"
    private const val CHANNEL_NAME = "Phormi browser"
    private const val PREFS = "phormi_notifications"

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Phormi browser, download and security events"
                }
            )
        }
    }

    fun postDownloadEvent(context: Context, downloadId: Long, title: String, success: Boolean) {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        ensureChannels(context)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = "download_$downloadId"
        val value = if (success) 1 else -1
        if (prefs.getInt(key, 0) == value) return
        prefs.edit().putInt(key, value).apply()

        val intent = PendingIntent.getActivity(
            context,
            (downloadId xor (downloadId ushr 32)).toInt(),
            Intent(context, DownloadsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = if (success) "Download completed" else "Download failed"
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_phormi_logo)
            .setContentTitle(title.ifBlank { "Phormi download" })
            .setContentText(text)
            .setContentIntent(intent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        NotificationManagerCompat.from(context).notify((downloadId xor (downloadId ushr 32)).toInt(), notification)
    }
}
