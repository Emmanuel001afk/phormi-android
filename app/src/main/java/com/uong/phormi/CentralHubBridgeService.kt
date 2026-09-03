package com.uong.phormi

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.lang.ref.WeakReference

/**
 * Local-only communication tunnel for the Central Hub website/AI.
 * Binds only to 127.0.0.1 and requires the shared bearer token.
 * The protocol is versioned and command names are extensible.
 */
class CentralHubBridgeService : Service() {
    companion object {
        const val PORT = 17841
        const val PROTOCOL = "phormi-central-hub-v1"
        private const val CHANNEL_ID = "phormi_central_hub_bridge"
        private const val NOTIFICATION_ID = 17841
        private const val PREFS = "phormi_central_hub_bridge"
        private const val KEY_TOKEN = "bridge_token"
        private const val DEFAULT_TOKEN = "l-amGaiCLGqHg8DK6EIcGlJte07Sa0whfBVSEQ3IXUw"

        @Volatile private var activityRef: WeakReference<MainActivity> = WeakReference(null)

        fun bindActivity(activity: MainActivity) { activityRef = WeakReference(activity) }
        fun unbindActivity(activity: MainActivity) {
            if (activityRef.get() === activity) activityRef.clear()
        }

        fun token(context: Context): String {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return prefs.getString(KEY_TOKEN, null) ?: DEFAULT_TOKEN.also {
                prefs.edit().putString(KEY_TOKEN, it).apply()
            }
        }
    }

    private val running = AtomicBoolean(false)
    private var server: ServerSocket? = null
    private var worker: Thread? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, notification())
        startServer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        running.set(false)
        try { server?.close() } catch (_: Exception) { }
        worker?.interrupt()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startServer() {
        if (!running.compareAndSet(false, true)) return
        worker = Thread {
            try {
                server = ServerSocket(PORT, 32, InetAddress.getByName("127.0.0.1"))
                while (running.get()) {
                    try {
                        val socket = server?.accept() ?: break
                        Thread { handle(socket) }.start()
                    } catch (_: SocketException) {
                        if (running.get()) continue else break
                    }
                }
            } catch (_: Exception) {
                running.set(false)
            }
        }.apply { name = "Phormi-CentralHub-Bridge"; isDaemon = true; start() }
    }

    private fun handle(socket: Socket) {
        socket.use { s ->
            s.soTimeout = 9000
            val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
            val requestLine = reader.readLine() ?: return
            val headers = linkedMapOf<String, String>()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
            }
            val parts = requestLine.split(' ')
            if (parts.size < 2) return
            val method = parts[0].uppercase()
            val target = parts[1]
            if (method == "OPTIONS") { writeResponse(s, 204, ""); return }

            val auth = headers["authorization"].orEmpty()
            if (auth != "Bearer ${token(this)}") {
                writeResponse(s, 401, JSONObject().put("ok", false).put("error", "unauthorized").toString())
                return
            }

            val bodyLength = headers["content-length"]?.toIntOrNull() ?: 0
            val body = CharArray(bodyLength)
            if (bodyLength > 0) reader.read(body)

            when {
                method == "GET" && target.substringBefore('?') == "/v1/ping" -> {
                    writeResponse(s, 200, JSONObject()
                        .put("ok", true)
                        .put("protocol", PROTOCOL)
                        .put("service", "Phormi")
                        .put("port", PORT)
                        .toString())
                }
                method == "GET" && target.substringBefore('?') == "/v1/state" -> {
                    writeResponse(s, 200, dispatchToActivity(JSONObject().put("command", "state")))
                }
                method == "GET" && (target.substringBefore('?') == "/v1/screenshot" || target.substringBefore('?') == "/v1/frame") -> {
                    writeResponse(s, 200, dispatchToActivity(JSONObject().put("command", "screenshot")))
                }
                method == "POST" && target.substringBefore('?') == "/v1/command" -> {
                    val command = runCatching { JSONObject(String(body)) }.getOrElse {
                        JSONObject().put("ok", false).put("error", "invalid_json").toString()
                    }
                    if (command is JSONObject) writeResponse(s, 200, dispatchToActivity(command))
                }
                else -> writeResponse(s, 404, JSONObject().put("ok", false).put("error", "not_found").toString())
            }
        }
    }

    private fun dispatchToActivity(command: JSONObject): String {
        val activity = activityRef.get()
            ?: return JSONObject().put("ok", false).put("error", "browser_not_active").toString()
        val latch = CountDownLatch(1)
        var result = JSONObject().put("ok", false).put("error", "command_timeout")
        Handler(Looper.getMainLooper()).post {
            result = activity.handleCentralHubCommand(command)
            latch.countDown()
        }
        latch.await(8, TimeUnit.SECONDS)
        return result.toString()
    }

    private fun writeResponse(socket: Socket, status: Int, body: String) {
        val reason = when (status) { 200 -> "OK"; 204 -> "No Content"; 401 -> "Unauthorized"; 404 -> "Not Found"; else -> "OK" }
        val bytes = body.toByteArray(Charsets.UTF_8)
        val out: OutputStream = socket.getOutputStream()
        val head = "HTTP/1.1 $status $reason\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "Access-Control-Allow-Headers: Authorization, Content-Type\r\n" +
            "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.UTF_8)); out.write(bytes); out.flush()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Central Hub bridge", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun notification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle("Phormi Central Hub bridge")
            .setContentText("Local browser communication is ready")
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
    }
}
