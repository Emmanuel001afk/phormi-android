package com.uong.phormi

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

/** Single notification owner for Phormi's VPN connection state. */
object PhormiVpnNotification {
    const val CHANNEL_ID = "phormi_vpn_status"
    const val NOTIFICATION_ID = 4201

    fun show(context: Context, connected: Boolean, label: String, detail: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Phormi VPN", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, VpnActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = if (connected) "Phormi VPN · Connected" else "Phormi VPN"
        val text = listOf(label.trim(), detail.trim()).filter { it.isNotBlank() }.joinToString(" · ")
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle(title)
            .setContentText(text.ifBlank { if (connected) "Protected connection active" else "VPN disconnected" })
            .setContentIntent(open)
            .setOngoing(connected)
            .setAutoCancel(!connected)
            .setOnlyAlertOnce(true)
            .build()
        nm.notify(NOTIFICATION_ID, notification)
        if (!connected) nm.cancel(NOTIFICATION_ID)
    }

    fun clear(context: Context) {
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIFICATION_ID)
    }
}
