package com.uong.phormi

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build

/**
 * Compatibility shell kept for the existing manifest.
 *
 * IMPORTANT: Phormi no longer creates a local TUN interface here. The previous
 * implementation routed 0.0.0.0/0 into a TUN and discarded every packet,
 * which made the entire phone appear offline. The real OpenVPN engine now owns
 * the Android VPN interface.
 */
class PhormiVpnService : VpnService() {
    companion object {
        const val ACTION_CONNECT = "com.uong.phormi.vpn.CONNECT"
        const val ACTION_DISCONNECT = "com.uong.phormi.vpn.DISCONNECT"
        const val EXTRA_SERVER_LABEL = "server_label"
        const val CHANNEL_ID = "phormi_vpn"

        @Volatile var isRunning: Boolean = false
            private set
        @Volatile var currentLabel: String = ""
            private set
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                isRunning = false
                currentLabel = ""
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_CONNECT -> {
                // Never establish a local TUN here. OpenVPN for Android owns it.
                currentLabel = intent.getStringExtra(EXTRA_SERVER_LABEL).orEmpty()
                isRunning = false
                startForeground(42, buildNotification("Controlled by OpenVPN engine"))
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?) = super.onBind(intent)

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Phormi VPN", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, VpnActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Phormi VPN")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentIntent(open)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("Phormi VPN")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentIntent(open)
                .setOngoing(true)
                .build()
        }
    }
}
