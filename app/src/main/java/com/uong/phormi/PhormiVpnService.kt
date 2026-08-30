package com.uong.phormi

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * VPN service shell for Phormi.
 *
 * Full OpenVPN/WireGuard crypto needs a native core (e.g. OpenVPN for Android).
 * This service:
 *  - Holds the Android VPN permission and notification
 *  - Can open a local TUN interface when the user confirms
 *  - Works together with VpnActivity which hands an .ovpn profile to an
 *    installed OpenVPN client for real encrypted tunneling
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

    private var tun: ParcelFileDescriptor? = null
    private val alive = AtomicBoolean(false)
    private var worker: Thread? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                stopTunnel()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_CONNECT, null -> {
                val label = intent?.getStringExtra(EXTRA_SERVER_LABEL) ?: "Phormi VPN"
                startForeground(42, buildNotification(label))
                // Local TUN is only a holder so the system shows VPN active.
                // Real OpenVPN encryption is done by handing the .ovpn to a client app.
                if (!isRunning) {
                    openLocalTun(label)
                }
            }
        }
        return START_STICKY
    }

    private fun openLocalTun(label: String) {
        try {
            val builder = Builder()
                .setSession("Phormi · $label")
                .addAddress("10.94.0.2", 32)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("1.1.1.1")
                .addDnsServer("8.8.8.8")
                .setMtu(1500)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setMetered(false)
            }
            tun?.close()
            tun = builder.establish()
            if (tun == null) {
                stopSelf()
                return
            }
            isRunning = true
            currentLabel = label
            alive.set(true)
            // Drain TUN so the interface stays up; real packets should go via OpenVPN client.
            worker = Thread {
                val input = FileInputStream(tun!!.fileDescriptor)
                val buffer = ByteBuffer.allocate(32767)
                try {
                    while (alive.get()) {
                        buffer.clear()
                        val n = input.read(buffer.array())
                        if (n <= 0) {
                            Thread.sleep(50)
                            continue
                        }
                        // Drop: without OpenVPN protocol we do not forward raw IP.
                    }
                } catch (_: Exception) {
                }
            }.also { it.isDaemon = true; it.start() }
        } catch (_: Exception) {
            stopTunnel()
            stopSelf()
        }
    }

    private fun stopTunnel() {
        alive.set(false)
        try { worker?.interrupt() } catch (_: Exception) {}
        worker = null
        try { tun?.close() } catch (_: Exception) {}
        tun = null
        isRunning = false
        currentLabel = ""
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        stopTunnel()
        super.onDestroy()
    }

    private fun buildNotification(label: String): Notification {
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
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, PhormiVpnService::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return b.setContentTitle("Phormi VPN")
            .setContentText(label)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Disconnect", stop).build())
            .setOngoing(true)
            .build()
    }
}
