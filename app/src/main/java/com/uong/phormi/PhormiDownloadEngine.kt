package com.uong.phormi

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Call
import org.json.JSONArray
import org.json.JSONObject

/**
 * Browser download engine. Unlike Android DownloadManager, this owns the transfer loop so
 * Phormi can pause, resume from the existing byte position, preserve browser headers/cookies,
 * and keep one stable error state instead of repeatedly emitting the same error.
 */
object PhormiDownloadEngine {
    private const val PREFS = "phormi_downloads_v2"
    private const val KEY_ITEMS = "items"
    private val lock = Any()

    enum class State { QUEUED, RUNNING, PAUSED, COMPLETED, FAILED }

    data class Item(
        val id: String,
        val title: String,
        val sourceUrl: String,
        val mimeType: String,
        val category: String,
        val state: State,
        val downloaded: Long,
        val total: Long,
        val localUri: String?,
        val error: String?,
        val createdAt: Long
    )

    data class Record(
        val id: String,
        val title: String,
        val sourceUrl: String,
        val mimeType: String,
        val category: String,
        val headers: Map<String, String>,
        val state: State,
        val downloaded: Long,
        val total: Long,
        val localUri: String?,
        val error: String?,
        val createdAt: Long
    )

    fun enqueue(context: Context, webView: WebView?, url: String, contentDisposition: String?, mimeType: String?, userAgentOverride: String? = null, contentLength: Long = -1L) {
        if (url.isBlank()) return
        if (url.startsWith("blob:", true) || url.startsWith("data:", true)) {
            // Keep the existing, working blob/data implementation in MainActivity intact.
            invokeExistingStartDownload(context, url, contentDisposition, mimeType)
            return
        }
        val userAgent = userAgentOverride?.takeIf { it.isNotBlank() }
            ?: webView?.settings?.userAgentString?.takeIf { it.isNotBlank() }
            ?: WebSettings.getDefaultUserAgent(context)
        val referer = webView?.url?.takeIf { it.startsWith("http", true) } ?: url
        runCatching { CookieManager.getInstance().flush() }
        val cookies = CookieManager.getInstance().getCookie(url)
            ?: CookieManager.getInstance().getCookie(referer)
        val info = PhormiDownloadSupport.resolve(url, contentDisposition, mimeType, userAgent, referer, cookies)
        val id = UUID.randomUUID().toString()
        val record = Record(
            id = id,
            title = info.fileName,
            sourceUrl = info.sourceUrl,
            mimeType = info.mimeType,
            category = info.category,
            headers = info.headers,
            state = State.QUEUED,
            downloaded = 0L,
            total = contentLength.takeIf { it > 0L } ?: -1L,
            localUri = null,
            error = null,
            createdAt = System.currentTimeMillis()
        )
        save(context, record)
        start(context, ACTION_ENQUEUE, id)
    }

    fun items(context: Context): List<Item> = records(context).map {
        Item(it.id, it.title, it.sourceUrl, it.mimeType, it.category, it.state, it.downloaded, it.total, it.localUri, it.error, it.createdAt)
    }.sortedByDescending { it.createdAt }

    fun pause(context: Context, id: String) {
        if (record(context, id) != null) updateState(context, id, State.PAUSED, null)
        start(context, ACTION_PAUSE, id)
    }

    fun resume(context: Context, id: String) {
        if (record(context, id) != null) updateState(context, id, State.QUEUED, null)
        start(context, ACTION_RESUME, id)
    }
    fun cancel(context: Context, id: String) {
        // Remove the row immediately; the service still receives the command to cancel
        // the in-flight HTTP call. Delete any already-created MediaStore file as well.
        val existing = record(context, id)
        existing?.localUri?.let { uri ->
            runCatching { context.contentResolver.delete(Uri.parse(uri), null, null) }
        }
        remove(context, id)
        start(context, ACTION_CANCEL, id)
    }

    fun record(context: Context, id: String): Record? = records(context).firstOrNull { it.id == id }


    private fun start(context: Context, action: String, id: String) {
        val intent = Intent(context, PhormiDownloadService::class.java).apply {
            this.action = action
            putExtra(EXTRA_ID, id)
        }
        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (e: Exception) {
            // Do not silently lose the linkage. The row stays visible with an actionable error.
            if (action == ACTION_ENQUEUE || action == ACTION_RESUME) {
                updateState(context, id, State.FAILED, "Download service could not start: ${e.message ?: "Android rejected the transfer"}")
            }
        }
    }

    private fun invokeExistingStartDownload(context: Context, url: String, cd: String?, mime: String?) {
        val activity = context as? MainActivity ?: return
        runCatching {
            val method = MainActivity::class.java.getDeclaredMethod(
                "startDownload", String::class.java, String::class.java, String::class.java
            )
            method.isAccessible = true
            method.invoke(activity, url, cd, mime)
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun records(context: Context): List<Record> = synchronized(lock) {
        val array = runCatching { JSONArray(prefs(context).getString(KEY_ITEMS, "[]") ?: "[]") }.getOrElse { JSONArray() }
        val out = mutableListOf<Record>()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val headers = mutableMapOf<String, String>()
            val h = o.optJSONObject("headers")
            if (h != null) h.keys().forEach { key -> headers[key] = h.optString(key) }
            val state = runCatching { State.valueOf(o.optString("state", State.FAILED.name)) }.getOrDefault(State.FAILED)
            out += Record(
                id = o.optString("id"),
                title = o.optString("title", "Download"),
                sourceUrl = o.optString("sourceUrl"),
                mimeType = o.optString("mimeType", "application/octet-stream"),
                category = o.optString("category", "Other"),
                headers = headers,
                state = state,
                downloaded = o.optLong("downloaded", 0L),
                total = o.optLong("total", -1L),
                localUri = o.optString("localUri").takeIf { it.isNotBlank() },
                error = o.optString("error").takeIf { it.isNotBlank() },
                createdAt = o.optLong("createdAt", 0L)
            )
        }
        out.filter { it.id.isNotBlank() }
    }

    internal fun save(context: Context, record: Record) = synchronized(lock) {
        val list = records(context).toMutableList()
        val index = list.indexOfFirst { it.id == record.id }
        if (index >= 0) list[index] = record else list += record
        write(context, list)
    }

    internal fun remove(context: Context, id: String) = synchronized(lock) {
        write(context, records(context).filterNot { it.id == id })
    }

    private fun write(context: Context, list: List<Record>) {
        val array = JSONArray()
        list.forEach { r ->
            val headers = JSONObject()
            r.headers.forEach { (k, v) -> headers.put(k, v) }
            array.put(JSONObject()
                .put("id", r.id)
                .put("title", r.title)
                .put("sourceUrl", r.sourceUrl)
                .put("mimeType", r.mimeType)
                .put("category", r.category)
                .put("headers", headers)
                .put("state", r.state.name)
                .put("downloaded", r.downloaded)
                .put("total", r.total)
                .put("localUri", r.localUri ?: "")
                .put("error", r.error ?: "")
                .put("createdAt", r.createdAt))
        }
        prefs(context).edit().putString(KEY_ITEMS, array.toString()).apply()
    }

    internal fun updateState(context: Context, id: String, state: State, error: String? = null) {
        val current = record(context, id) ?: return
        save(context, current.copy(state = state, error = error))
    }

    internal fun updateProgress(context: Context, id: String, downloaded: Long, total: Long) {
        val current = record(context, id) ?: return
        save(context, current.copy(downloaded = downloaded, total = total, error = null))
    }

    internal fun updateUri(context: Context, id: String, uri: String, total: Long) {
        val current = record(context, id) ?: return
        save(context, current.copy(localUri = uri, total = total))
    }

    const val ACTION_ENQUEUE = "com.uong.phormi.download.ENQUEUE"
    const val ACTION_PAUSE = "com.uong.phormi.download.PAUSE"
    const val ACTION_RESUME = "com.uong.phormi.download.RESUME"
    const val ACTION_CANCEL = "com.uong.phormi.download.CANCEL"
    const val EXTRA_ID = "download_id"
}

class PhormiDownloadService : Service() {
    private val executor = Executors.newCachedThreadPool()
    private val running = ConcurrentHashMap<String, Boolean>()
    private val activeCalls = ConcurrentHashMap<String, Call>()
    private val httpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private enum class Control { NONE, PAUSE, CANCEL }
    private val cancelSignals = ConcurrentHashMap<String, Control>()

    companion object {
        private const val CHANNEL_ID = "phormi_downloads"
        private const val NOTIFICATION_ID = 4417
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, notification("Phormi downloads", "Preparing download…", 0, false))
        // Reconnect persisted queued/running records if Android recreates the service. This is
        // the missing bridge that keeps the Downloads screen and the real transfer lifecycle in sync.
        PhormiDownloadEngine.items(this)
            .filter { it.state == PhormiDownloadEngine.State.QUEUED || it.state == PhormiDownloadEngine.State.RUNNING }
            .forEach { launch(it.id) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra(PhormiDownloadEngine.EXTRA_ID)
        when (intent?.action) {
            PhormiDownloadEngine.ACTION_ENQUEUE -> if (!id.isNullOrBlank()) launch(id)
            PhormiDownloadEngine.ACTION_PAUSE -> if (!id.isNullOrBlank()) pause(id)
            PhormiDownloadEngine.ACTION_RESUME -> if (!id.isNullOrBlank()) resume(id)
            PhormiDownloadEngine.ACTION_CANCEL -> if (!id.isNullOrBlank()) cancel(id)
        }
        refreshNotification()
        return START_STICKY
    }

    private fun launch(id: String) {
        if (running.putIfAbsent(id, true) != null) return
        cancelSignals[id] = Control.NONE
        PhormiDownloadEngine.updateState(this, id, PhormiDownloadEngine.State.QUEUED, null)
        executor.execute {
            try {
                download(id)
            } finally {
                running.remove(id)
                if (cancelSignals[id] != Control.CANCEL) cancelSignals.remove(id)
                refreshNotification()
                maybeStop()
            }
        }
    }

    private fun pause(id: String) {
        cancelSignals[id] = Control.PAUSE
        activeCalls[id]?.cancel()
        if (!running.containsKey(id)) PhormiDownloadEngine.updateState(this, id, PhormiDownloadEngine.State.PAUSED, null)
    }

    private fun resume(id: String) {
        cancelSignals[id] = Control.NONE
        if (!running.containsKey(id)) {
            launch(id)
            return
        }
        // If the user taps resume while the pause command is still unwinding, wait for the
        // old request to release the slot, then start the same record again from its byte offset.
        executor.execute {
            repeat(25) {
                if (!running.containsKey(id)) {
                    if (PhormiDownloadEngine.record(this, id) != null) launch(id)
                    return@execute
                }
                Thread.sleep(100L)
            }
        }
    }

    private fun cancel(id: String) {
        cancelSignals[id] = Control.CANCEL
        activeCalls[id]?.cancel()
        val record = PhormiDownloadEngine.record(this, id)
        record?.localUri?.let { deleteUri(it) }
        PhormiDownloadEngine.remove(this, id)
    }

    private fun download(id: String) {
        var attempts = 0
        while (true) {
            val record = PhormiDownloadEngine.record(this, id) ?: return
            when (cancelSignals[id]) {
                Control.CANCEL -> return
                Control.PAUSE -> {
                    PhormiDownloadEngine.updateState(this, id, PhormiDownloadEngine.State.PAUSED, null)
                    return
                }
                else -> Unit
            }
            PhormiDownloadEngine.updateState(this, id, PhormiDownloadEngine.State.RUNNING, null)
            try {
                val result = performRequest(record)
                if (result) return
            } catch (paused: PauseException) {
                if (cancelSignals[id] == Control.CANCEL) return
                PhormiDownloadEngine.updateState(this, id, PhormiDownloadEngine.State.PAUSED, null)
                return
            } catch (cancelled: CancelException) {
                PhormiDownloadEngine.remove(this, id)
                return
            } catch (http: HttpFailure) {
                if (cancelSignals[id] == Control.CANCEL) return
                if (http.code == 429 || http.code in 500..599) {
                    attempts++
                    if (attempts <= 3) {
                        Thread.sleep((attempts * 2000L).coerceAtMost(8000L))
                        continue
                    }
                }
                PhormiDownloadEngine.updateState(this, id, PhormiDownloadEngine.State.FAILED, humanHttpError(http.code))
                return
            } catch (network: IOException) {
                if (cancelSignals[id] == Control.CANCEL) return
                if (cancelSignals[id] == Control.PAUSE) {
                    PhormiDownloadEngine.updateState(this, id, PhormiDownloadEngine.State.PAUSED, null)
                    return
                }
                // A broken mobile/Wi-Fi connection is not a permanent failure. Keep the row in
                // a waiting state and retry quietly until the user pauses or cancels it.
                attempts = 0
                PhormiDownloadEngine.updateState(this, id, PhormiDownloadEngine.State.QUEUED, "Waiting for connection…")
                Thread.sleep(3000L)
            } catch (t: Throwable) {
                PhormiDownloadEngine.updateState(this, id, PhormiDownloadEngine.State.FAILED, t.message ?: "Download failed")
                return
            }
        }
    }

    private fun performRequest(record: PhormiDownloadEngine.Record): Boolean {
        val existing = record.downloaded.coerceAtLeast(0L)
        val requestBuilder = Request.Builder().url(record.sourceUrl).get()
        record.headers.forEach { (key, value) ->
            if (key.isNotBlank() && value.isNotBlank()) requestBuilder.header(key, value)
        }
        requestBuilder.header("Accept", acceptFor(record.mimeType))
        requestBuilder.header("Accept-Language", java.util.Locale.getDefault().toLanguageTag() + ",en;q=0.8")
        requestBuilder.header("Accept-Encoding", "identity")
        if (existing > 0L) requestBuilder.header("Range", "bytes=$existing-")

        val call = httpClient.newCall(requestBuilder.build())
        activeCalls[record.id] = call
        try {
            call.execute().use { response ->
                val code = response.code
                if (code == 416 && existing > 0L) {
                    // The saved partial byte range is no longer valid. Restart the same download
                    // cleanly instead of leaving a permanently broken resume state.
                    PhormiDownloadEngine.updateProgress(this, record.id, 0L, -1L)
                    return performRequest(record.copy(downloaded = 0L, localUri = record.localUri))
                }
                if (!response.isSuccessful) throw HttpFailure(code)
                val body = response.body ?: throw IOException("Server returned an empty download")
                val append = existing > 0L && code == 206
                val bodyLength = body.contentLength()
                val total = when {
                    code == 206 && bodyLength >= 0L -> existing + bodyLength
                    bodyLength >= 0L -> bodyLength
                    else -> record.total
                }
                val actualStart = if (append) existing else 0L
                if (!append && existing > 0L) {
                    PhormiDownloadEngine.updateProgress(this, record.id, 0L, total)
                }

                val uri = ensureDestination(record, actualStart == 0L)
                    ?: throw IOException("Could not create the Downloads file")
                PhormiDownloadEngine.updateUri(this, record.id, uri, total)

                body.byteStream().buffered().use { input ->
                    val output = openOutput(uri, append)
                    output.use { out ->
                        val buffer = ByteArray(64 * 1024)
                        var downloaded = actualStart
                        var lastPersist = System.currentTimeMillis()
                        while (true) {
                            when (cancelSignals[record.id]) {
                                Control.CANCEL -> throw CancelException()
                                Control.PAUSE -> throw PauseException()
                                else -> Unit
                            }
                            val read = input.read(buffer)
                            if (read < 0) break
                            if (read == 0) continue
                            out.write(buffer, 0, read)
                            downloaded += read
                            val now = System.currentTimeMillis()
                            if (now - lastPersist >= 350L) {
                                lastPersist = now
                                PhormiDownloadEngine.updateProgress(this, record.id, downloaded, total)
                                refreshNotification()
                            }
                        }
                        out.flush()
                        PhormiDownloadEngine.updateProgress(this, record.id, downloaded, total)
                        if (total > 0L && downloaded < total) {
                            throw IOException("Connection ended before the file was complete")
                        }
                        publish(uri)
                        PhormiDownloadEngine.updateState(this, record.id, PhormiDownloadEngine.State.COMPLETED, null)
                        return true
                    }
                }
            }
        } finally {
            activeCalls.remove(record.id, call)
        }
    }

    private fun ensureDestination(record: PhormiDownloadEngine.Record, truncate: Boolean): String? {
        if (!truncate && !record.localUri.isNullOrBlank()) return record.localUri
        if (!record.localUri.isNullOrBlank()) return record.localUri
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, record.title)
                put(MediaStore.Downloads.MIME_TYPE, record.mimeType.ifBlank { "application/octet-stream" })
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            return uri?.toString()
        }
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!dir.exists()) dir.mkdirs()
        return File(dir, record.title).toURI().toString()
    }

    private fun openOutput(uriString: String, append: Boolean): java.io.OutputStream {
        val uri = Uri.parse(uriString)
        if (uri.scheme == "content") {
            return contentResolver.openOutputStream(uri, if (append) "wa" else "w")
                ?: throw IOException("Could not open download")
        }
        val path = uri.path ?: throw IOException("Invalid download path")
        val file = File(path)
        if (!file.parentFile.exists()) file.parentFile.mkdirs()
        return FileOutputStream(file, append)
    }

    private fun publish(uriString: String) {
        val uri = Uri.parse(uriString)
        if (uri.scheme == "content" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
            contentResolver.update(uri, values, null, null)
        } else if (uri.scheme == "file") {
            val path = uri.path ?: return
            android.media.MediaScannerConnection.scanFile(this, arrayOf(path), null, null)
        }
    }

    private fun deleteUri(uriString: String) {
        runCatching {
            val uri = Uri.parse(uriString)
            if (uri.scheme == "content") contentResolver.delete(uri, null, null)
            else uri.path?.let { File(it).delete() }
        }
    }

    private fun acceptFor(mime: String): String = when {
        mime.startsWith("video/") -> "video/*,*/*;q=0.8"
        mime.startsWith("audio/") -> "audio/*,*/*;q=0.8"
        mime.startsWith("image/") -> "image/*,*/*;q=0.8"
        mime == "application/pdf" -> "application/pdf,*/*;q=0.8"
        else -> "*/*"
    }

    private fun humanHttpError(code: Int): String = when (code) {
        400 -> "Server rejected the download (HTTP 400 Bad Request)"
        401 -> "Download requires authentication (HTTP 401 Unauthorized)"
        403 -> "Server refused the download (HTTP 403 Forbidden)"
        404 -> "File was not found (HTTP 404 Not Found)"
        408 -> "Server timed out (HTTP 408 Request Timeout)"
        409 -> "Server rejected the request (HTTP 409 Conflict)"
        410 -> "File is no longer available (HTTP 410 Gone)"
        429 -> "Server rate-limited the download (HTTP 429 Too Many Requests)"
        500 -> "Server error (HTTP 500 Internal Server Error)"
        502 -> "Gateway error (HTTP 502 Bad Gateway)"
        503 -> "Server is temporarily unavailable (HTTP 503 Service Unavailable)"
        504 -> "Gateway timed out (HTTP 504 Gateway Timeout)"
        else -> if (code in 400..499) "Server rejected the download (HTTP $code Client Error)" else "Server error (HTTP $code)"
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun notification(title: String, text: String, progress: Int, indeterminate: Boolean): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, DownloadsActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else Notification.Builder(this)
        return builder.setSmallIcon(applicationInfo.icon)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, progress.coerceIn(0, 100), indeterminate)
            .build()
    }

    private fun refreshNotification() {
        val active = PhormiDownloadEngine.items(this).filter { it.state == PhormiDownloadEngine.State.RUNNING || it.state == PhormiDownloadEngine.State.QUEUED }
        if (active.isEmpty()) return
        val first = active.first()
        val percent = if (first.total > 0L) ((first.downloaded * 100L) / first.total).toInt() else 0
        val text = if (first.total > 0L) "$percent% · ${formatBytes(first.downloaded)} of ${formatBytes(first.total)}" else "${formatBytes(first.downloaded)} downloaded"
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(first.title, text, percent, first.total <= 0L))
    }

    private fun maybeStop() {
        val active = PhormiDownloadEngine.items(this).any { it.state == PhormiDownloadEngine.State.RUNNING || it.state == PhormiDownloadEngine.State.QUEUED }
        if (!active) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun formatBytes(value: Long): String = when {
        value < 1024 -> "$value B"
        value < 1024 * 1024 -> "${value / 1024} KB"
        value < 1024 * 1024 * 1024 -> "${value / (1024 * 1024)} MB"
        else -> "${value / (1024 * 1024 * 1024)} GB"
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        PhormiDownloadEngine.items(this).filter { it.state == PhormiDownloadEngine.State.RUNNING || it.state == PhormiDownloadEngine.State.QUEUED }
            .forEach { PhormiDownloadEngine.updateState(this, it.id, PhormiDownloadEngine.State.PAUSED, "Paused by Android after the background transfer time limit") }
        stopSelf(startId)
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private class PauseException : Exception()
    private class CancelException : Exception()
    private class HttpFailure(val code: Int) : Exception()
}
