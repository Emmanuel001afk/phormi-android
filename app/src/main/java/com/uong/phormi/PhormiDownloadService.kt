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
import android.provider.MediaStore
import android.webkit.CookieManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

object PhormiDownloadStore {
    const val STATE_QUEUED = "queued"
    const val STATE_DOWNLOADING = "downloading"
    const val STATE_PAUSED = "paused"
    const val STATE_COMPLETED = "completed"
    const val STATE_FAILED = "failed"

    data class Record(
        val id: Long,
        val url: String,
        val fileName: String,
        val mimeType: String,
        val category: String,
        val referer: String?,
        val userAgent: String?,
        val cookies: String?,
        val tempPath: String,
        val localUri: String?,
        val totalBytes: Long,
        val downloadedBytes: Long,
        val state: String,
        val error: String?
    )

    private const val PREFS = "phormi_downloads"
    private const val KEY_ITEMS = "items"

    @Synchronized
    fun all(context: Context): List<Record> = read(context).sortedByDescending { it.id }

    @Synchronized
    fun get(context: Context, id: Long): Record? = read(context).firstOrNull { it.id == id }

    @Synchronized
    fun add(context: Context, info: PhormiDownloadSupport.RequestInfo): Record {
        val existing = read(context)
        val id = (existing.maxOfOrNull { it.id } ?: System.currentTimeMillis()) + 1L
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir, ".phormi_downloads")
        dir.mkdirs()
        val record = Record(
            id = id,
            url = info.sourceUrl,
            fileName = info.fileName,
            mimeType = info.mimeType,
            category = info.category,
            referer = info.headers["Referer"],
            userAgent = info.headers["User-Agent"],
            cookies = info.headers["Cookie"],
            tempPath = File(dir, "$id.part").absolutePath,
            localUri = null,
            totalBytes = -1L,
            downloadedBytes = 0L,
            state = STATE_QUEUED,
            error = null
        )
        write(context, existing + record)
        return record
    }

    @Synchronized
    fun update(context: Context, id: Long, transform: (Record) -> Record) {
        write(context, read(context).map { if (it.id == id) transform(it) else it })
    }

    @Synchronized
    fun remove(context: Context, id: Long): Record? {
        val current = read(context)
        val removed = current.firstOrNull { it.id == id }
        write(context, current.filterNot { it.id == id })
        return removed
    }

    fun hasActive(context: Context): Boolean = all(context).any { it.state == STATE_QUEUED || it.state == STATE_DOWNLOADING }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun read(context: Context): List<Record> {
        val raw = prefs(context).getString(KEY_ITEMS, "[]") ?: "[]"
        val array = runCatching { org.json.JSONArray(raw) }.getOrElse { org.json.JSONArray() }
        val out = mutableListOf<Record>()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            out += Record(
                id = o.optLong("id"),
                url = o.optString("url"),
                fileName = o.optString("fileName", "phormi_download"),
                mimeType = o.optString("mimeType", "application/octet-stream"),
                category = o.optString("category", "Other"),
                referer = o.optString("referer").takeIf { it.isNotBlank() },
                userAgent = o.optString("userAgent").takeIf { it.isNotBlank() },
                cookies = o.optString("cookies").takeIf { it.isNotBlank() },
                tempPath = o.optString("tempPath"),
                localUri = o.optString("localUri").takeIf { it.isNotBlank() },
                totalBytes = o.optLong("totalBytes", -1L),
                downloadedBytes = o.optLong("downloadedBytes", 0L),
                state = o.optString("state", STATE_FAILED),
                error = o.optString("error").takeIf { it.isNotBlank() }
            )
        }
        return out.filter { it.id > 0 && it.url.isNotBlank() }
    }

    private fun write(context: Context, records: List<Record>) {
        val array = org.json.JSONArray()
        records.forEach { r ->
            array.put(org.json.JSONObject().apply {
                put("id", r.id)
                put("url", r.url)
                put("fileName", r.fileName)
                put("mimeType", r.mimeType)
                put("category", r.category)
                put("referer", r.referer ?: "")
                put("userAgent", r.userAgent ?: "")
                put("cookies", r.cookies ?: "")
                put("tempPath", r.tempPath)
                put("localUri", r.localUri ?: "")
                put("totalBytes", r.totalBytes)
                put("downloadedBytes", r.downloadedBytes)
                put("state", r.state)
                put("error", r.error ?: "")
            })
        }
        prefs(context).edit().putString(KEY_ITEMS, array.toString()).apply()
    }
}

class PhormiDownloadService : Service() {
    companion object {
        private const val CHANNEL_ID = "phormi_downloads_v2"
        private const val NOTIFICATION_ID = 4107
        const val ACTION_ENQUEUE = "com.uong.phormi.download.ENQUEUE"
        const val ACTION_PAUSE = "com.uong.phormi.download.PAUSE"
        const val ACTION_RESUME = "com.uong.phormi.download.RESUME"
        const val ACTION_DELETE = "com.uong.phormi.download.DELETE"
        private const val EXTRA_ID = "download_id"

        fun enqueue(context: Context, info: PhormiDownloadSupport.RequestInfo): Long {
            val record = PhormiDownloadStore.add(context, info)
            start(context, ACTION_ENQUEUE, record.id)
            return record.id
        }

        fun pause(context: Context, id: Long) = start(context, ACTION_PAUSE, id)
        fun resume(context: Context, id: Long) = start(context, ACTION_RESUME, id)
        fun delete(context: Context, id: Long) = start(context, ACTION_DELETE, id)

        fun resumePending(context: Context) {
            if (!PhormiDownloadStore.hasActive(context)) return
            start(context, ACTION_RESUME, -1L)
        }

        private fun start(context: Context, action: String, id: Long) {
            val intent = Intent(context, PhormiDownloadService::class.java).apply {
                this.action = action
                putExtra(EXTRA_ID, id)
            }
            runCatching {
                if (Build.VERSION.SDK_INT >= 26) ContextCompat.startForegroundService(context, intent)
                else context.startService(intent)
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<Long, Job>()
    private val calls = ConcurrentHashMap<Long, okhttp3.Call>()
    private val paused = ConcurrentHashMap.newKeySet<Long>()
    private val client by lazy {
        OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, notification("Phormi downloads", "Preparing…", null))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ENQUEUE -> intent.getLongExtra(EXTRA_ID, -1L).takeIf { it > 0 }?.let { launchDownload(it) }
            ACTION_PAUSE -> intent.getLongExtra(EXTRA_ID, -1L).takeIf { it > 0 }?.let { pauseInternal(it) }
            ACTION_RESUME -> {
                val id = intent.getLongExtra(EXTRA_ID, -1L)
                if (id > 0) launchDownload(id)
                else PhormiDownloadStore.all(this)
                    .filter { it.state == PhormiDownloadStore.STATE_QUEUED || it.state == PhormiDownloadStore.STATE_DOWNLOADING }
                    .forEach { launchDownload(it.id) }
            }
            ACTION_DELETE -> intent.getLongExtra(EXTRA_ID, -1L).takeIf { it > 0 }?.let { deleteInternal(it) }
        }
        return START_NOT_STICKY
    }

    private fun launchDownload(id: Long) {
        if (jobs[id]?.isActive == true) return
        paused.remove(id)
        jobs[id] = scope.launch { runDownload(id) }.also { job ->
            job.invokeOnCompletion { jobs.remove(id); calls.remove(id); maybeStop() }
        }
    }

    private fun pauseInternal(id: Long) {
        paused.add(id)
        calls[id]?.cancel()
        jobs[id]?.cancel()
        PhormiDownloadStore.get(this, id)?.let { record ->
            if (record.state != PhormiDownloadStore.STATE_COMPLETED) {
                PhormiDownloadStore.update(this, id) { it.copy(state = PhormiDownloadStore.STATE_PAUSED, error = null) }
            }
        }
        updateNotification()
    }

    private fun deleteInternal(id: Long) {
        paused.add(id)
        calls[id]?.cancel()
        jobs[id]?.cancel()
        val record = PhormiDownloadStore.remove(this, id) ?: return
        runCatching { File(record.tempPath).delete() }
        record.localUri?.let { uri -> runCatching { contentResolver.delete(Uri.parse(uri), null, null) } }
        updateNotification()
    }

    private suspend fun runDownload(id: Long) {
        var record = PhormiDownloadStore.get(this, id) ?: return
        if (record.state == PhormiDownloadStore.STATE_COMPLETED) return
        PhormiDownloadStore.update(this, id) { it.copy(state = PhormiDownloadStore.STATE_DOWNLOADING, error = null) }
        record = PhormiDownloadStore.get(this, id) ?: return
        val temp = File(record.tempPath)
        temp.parentFile?.mkdirs()
        var existing = temp.length().coerceAtLeast(0L)

        try {
            val response = executeDownloadRequest(record, existing)
            response.use { res ->
                if (!res.isSuccessful && res.code != 206) {
                    val message = when (res.code) {
                        401, 403 -> "Server refused the download (HTTP ${res.code}). The link may require the page's current session, referer, or an unexpired signed URL."
                        404 -> "File not found (HTTP 404)"
                        416 -> "Resume range is no longer valid (HTTP 416)"
                        else -> "HTTP ${res.code}"
                    }
                    throw IllegalStateException(message)
                }
                val append = existing > 0 && res.code == 206
                if (!append) {
                    existing = 0L
                    temp.outputStream().use { }
                }
                val body = res.body ?: throw IllegalStateException("Empty response")
                val total = if (body.contentLength() >= 0) existing + body.contentLength() else -1L
                PhormiDownloadStore.update(this, id) { it.copy(state = PhormiDownloadStore.STATE_DOWNLOADING, totalBytes = total, downloadedBytes = existing, error = null) }
                body.byteStream().use { input ->
                    FileOutputStream(temp, append).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var downloaded = existing
                        var lastPersist = System.currentTimeMillis()
                        while (true) {
                            if (paused.contains(id)) throw PauseCancellation()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            downloaded += count
                            val now = System.currentTimeMillis()
                            if (now - lastPersist >= 350L) {
                                lastPersist = now
                                PhormiDownloadStore.update(this, id) { it.copy(downloadedBytes = downloaded, totalBytes = total, state = PhormiDownloadStore.STATE_DOWNLOADING) }
                                updateNotification()
                            }
                        }
                        output.fd.sync()
                        existing = downloaded
                    }
                }
            }
            if (paused.contains(id)) {
                PhormiDownloadStore.update(this, id) { it.copy(state = PhormiDownloadStore.STATE_PAUSED, downloadedBytes = existing) }
                return
            }
            val localUri = publishToDownloads(temp, record.fileName, record.mimeType)
            PhormiDownloadStore.update(this, id) {
                it.copy(
                    state = PhormiDownloadStore.STATE_COMPLETED,
                    downloadedBytes = existing,
                    totalBytes = if (it.totalBytes > 0) it.totalBytes else existing,
                    localUri = localUri,
                    error = null
                )
            }
            updateNotification()
        } catch (e: PauseCancellation) {
            PhormiDownloadStore.update(this, id) { it.copy(state = PhormiDownloadStore.STATE_PAUSED, downloadedBytes = temp.length()) }
            updateNotification()
        } catch (e: CancellationException) {
            if (paused.contains(id)) {
                PhormiDownloadStore.update(this, id) { it.copy(state = PhormiDownloadStore.STATE_PAUSED, downloadedBytes = temp.length()) }
            } else {
                PhormiDownloadStore.update(this, id) { it.copy(state = PhormiDownloadStore.STATE_FAILED, downloadedBytes = temp.length(), error = "Download cancelled") }
            }
            updateNotification()
        } catch (e: Throwable) {
            PhormiDownloadStore.update(this, id) { it.copy(state = PhormiDownloadStore.STATE_FAILED, downloadedBytes = temp.length(), error = e.message ?: "Download failed") }
            updateNotification()
        } finally {
            calls.remove(id)
        }
    }

    private fun executeDownloadRequest(record: PhormiDownloadStore.Record, existing: Long): Response {
        val cookieFromWebView = runCatching { CookieManager.getInstance().getCookie(record.url) }.getOrNull()
        val cookies = cookieFromWebView?.takeIf { it.isNotBlank() } ?: record.cookies
        val referer = record.referer?.takeIf { it.isNotBlank() }
        val userAgent = record.userAgent?.takeIf { it.isNotBlank() }
            ?: "Mozilla/5.0 (Linux; Android ${Build.VERSION.RELEASE}; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0 Mobile Safari/537.36"

        fun buildRequest(range: Long?, browserHeaders: Boolean): Request {
            val b = Request.Builder().url(record.url)
                .header("User-Agent", userAgent)
                .header("Accept", acceptFor(record.mimeType))
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Accept-Encoding", "identity")
                .header("Connection", "keep-alive")
            referer?.let { b.header("Referer", it) }
            cookies?.takeIf { it.isNotBlank() }?.let { b.header("Cookie", it) }
            if (browserHeaders) {
                val origin = originOf(referer)
                if (!origin.isNullOrBlank()) b.header("Origin", origin)
                b.header("Sec-Fetch-Dest", fetchDest(record.mimeType))
                b.header("Sec-Fetch-Mode", "navigate")
                b.header("Sec-Fetch-Site", if (origin != null) "same-site" else "none")
                b.header("Upgrade-Insecure-Requests", "1")
            }
            if (range != null && range > 0) b.header("Range", "bytes=$range-")
            return b.build()
        }

        fun call(request: Request): Response {
            val c = client.newCall(request)
            calls[record.id] = c
            return c.execute()
        }

        var response = call(buildRequest(if (existing > 0) existing else null, true))
        if (response.code == 416 && existing > 0) {
            response.close()
            response = call(buildRequest(null, true))
        } else if ((response.code == 401 || response.code == 403) && existing > 0) {
            response.close()
            response = call(buildRequest(null, true))
        }
        return response
    }

    private fun acceptFor(mime: String): String = when {
        mime.startsWith("video/") -> "video/*,*/*;q=0.8"
        mime.startsWith("audio/") -> "audio/*,*/*;q=0.8"
        mime.startsWith("image/") -> "image/avif,image/webp,image/apng,image/*,*/*;q=0.8"
        mime == "application/pdf" -> "application/pdf,*/*;q=0.8"
        mime.contains("zip") || mime.contains("rar") || mime.contains("7z") || mime.contains("tar") -> "application/octet-stream,*/*;q=0.8"
        else -> "*/*"
    }

    private fun fetchDest(mime: String): String = when {
        mime.startsWith("image/") -> "image"
        mime.startsWith("video/") -> "video"
        mime.startsWith("audio/") -> "audio"
        else -> "document"
    }

    private fun originOf(url: String?): String? = runCatching {
        val u = URI(url ?: return@runCatching null)
        if (u.scheme.isNullOrBlank() || u.host.isNullOrBlank()) null else "${u.scheme}://${u.host}${if (u.port > 0) ":${u.port}" else ""}"
    }.getOrNull()

    private fun publishToDownloads(temp: File, fileName: String, mimeType: String): String {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Could not create Downloads file")
            try {
                contentResolver.openOutputStream(uri)?.use { output ->
                    FileInputStream(temp).use { input -> input.copyTo(output, 64 * 1024) }
                } ?: throw IllegalStateException("Could not open Downloads file")
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                contentResolver.update(uri, values, null, null)
                temp.delete()
                return uri.toString()
            } catch (t: Throwable) {
                contentResolver.delete(uri, null, null)
                throw t
            }
        }
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!downloads.exists()) downloads.mkdirs()
        var target = File(downloads, fileName)
        var n = 1
        while (target.exists()) {
            val dot = fileName.lastIndexOf('.')
            val base = if (dot > 0) fileName.substring(0, dot) else fileName
            val ext = if (dot > 0) fileName.substring(dot) else ""
            target = File(downloads, "$base ($n)$ext")
            n++
        }
        if (!temp.renameTo(target)) {
            FileInputStream(temp).use { input -> FileOutputStream(target).use { output -> input.copyTo(output, 64 * 1024) } }
            temp.delete()
        }
        android.media.MediaScannerConnection.scanFile(this, arrayOf(target.absolutePath), arrayOf(mimeType), null)
        return androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", target).toString()
    }

    private fun updateNotification() {
        val active = PhormiDownloadStore.all(this).firstOrNull { it.state == PhormiDownloadStore.STATE_DOWNLOADING || it.state == PhormiDownloadStore.STATE_QUEUED }
        val pausedCount = PhormiDownloadStore.all(this).count { it.state == PhormiDownloadStore.STATE_PAUSED }
        if (active != null) {
            val percent = if (active.totalBytes > 0) ((active.downloadedBytes * 100L) / active.totalBytes).toInt().coerceIn(0, 100) else 0
            val text = if (active.totalBytes > 0) "$percent% · ${formatBytes(active.downloadedBytes)} / ${formatBytes(active.totalBytes)}" else formatBytes(active.downloadedBytes)
            val n = notification(active.fileName, text, if (active.totalBytes > 0) percent else null)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, n)
        } else if (pausedCount > 0) {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, notification("Downloads paused", "$pausedCount paused", null))
        } else {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIFICATION_ID)
        }
    }

    private fun maybeStop() {
        if (jobs.isEmpty() && !PhormiDownloadStore.hasActive(this)) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else updateNotification()
    }

    private fun notification(title: String, text: String, progress: Int?): Notification {
        val openIntent = PendingIntent.getActivity(this, 0, Intent(this, DownloadsActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(progress != null)
            .setOnlyAlertOnce(true)
        if (progress != null) builder.setProgress(100, progress, false)
        return builder.build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Phormi downloads", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun formatBytes(value: Long): String = when {
        value < 1024 -> "$value B"
        value < 1024 * 1024 -> "${value / 1024} KB"
        value < 1024 * 1024 * 1024 -> "${value / (1024 * 1024)} MB"
        else -> "${value / (1024 * 1024 * 1024)} GB"
    }

    override fun onBind(intent: Intent?) = null
    override fun onDestroy() {
        jobs.values.forEach { it.cancel() }
        calls.values.forEach { it.cancel() }
        scope.cancel()
        super.onDestroy()
    }

    private class PauseCancellation : Exception()
}
