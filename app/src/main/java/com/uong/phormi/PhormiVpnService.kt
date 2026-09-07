package com.uong.phormi

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build

/** Compatibility foreground shell. The external OpenVPN engine owns the TUN interface. */
class PhormiVpnService : VpnService() {
    companion object {
        const val ACTION_CONNECT = "com.uong.phormi.vpn.CONNECT"
        const val ACTION_DISCONNECT = "com.uong.phormi.vpn.DISCONNECT"
        const val ACTION_STATUS = "com.uong.phormi.vpn.STATUS"
        const val EXTRA_SERVER_LABEL = "server_label"
        const val EXTRA_STATUS = "status"
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
                PhormiVpnNotification.clear(this)
                stopForegroundCompat()
                stopSelf()
            }
            ACTION_CONNECT -> {
                currentLabel = intent.getStringExtra(EXTRA_SERVER_LABEL).orEmpty()
                isRunning = true
                startForeground(42, buildNotification("Connecting · $currentLabel"))
            }
            ACTION_STATUS -> {
                val status = intent.getStringExtra(EXTRA_STATUS).orEmpty()
                val connected = status.equals("connected", true)
                isRunning = connected
                if (connected) {
                    currentLabel = intent.getStringExtra(EXTRA_SERVER_LABEL).orEmpty().ifBlank { currentLabel }
                    startForeground(42, buildNotification("Connected · $currentLabel"))
                } else {
                    isRunning = false
                    PhormiVpnNotification.clear(this)
                    stopForegroundCompat()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE)
        else @Suppress("DEPRECATION") stopForeground(true)
    }

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Phormi VPN", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, VpnActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Phormi VPN")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
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
