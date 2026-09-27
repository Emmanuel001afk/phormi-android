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
import androidx.core.content.ContextCompat

/** Central notification surface for browser events. */
object PhormiNotificationManager {
    const val CHANNEL_DOWNLOADS = "phormi_downloads_events"
    const val CHANNEL_WEBSITES = "phormi_website_notifications"
    const val CHANNEL_BROWSER = "phormi_browser_notifications"
    const val CHANNEL_AI = "phormi_ai_notifications"

    private fun manager(context: Context): NotificationManager =
        context.getSystemService(NotificationManager::class.java)

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        manager(context).createNotificationChannels(listOf(
            NotificationChannel(CHANNEL_DOWNLOADS, "Downloads", NotificationManager.IMPORTANCE_LOW),
            NotificationChannel(CHANNEL_WEBSITES, "Website notifications", NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel(CHANNEL_BROWSER, "Browser", NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel(CHANNEL_AI, "AI", NotificationManager.IMPORTANCE_DEFAULT)
        ))
    }

    fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun post(
        context: Context,
        channel: String,
        id: Int,
        title: String,
        text: String,
        intent: Intent? = null,
        ongoing: Boolean = false,
        autoCancel: Boolean = true
    ) {
        ensureChannels(context)
        if (!canPost(context)) return
        val pending = intent?.let {
            PendingIntent.getActivity(
                context, id, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(context.applicationInfo.icon)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pending)
            .setOngoing(ongoing)
            .setAutoCancel(autoCancel)
            .setOnlyAlertOnce(ongoing)
            .build()
        manager(context).notify(id, notification)
    }

    fun cancel(context: Context, id: Int) {
        manager(context).cancel(id)
    }
}
