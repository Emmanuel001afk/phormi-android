package com.uong.phormi

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.net.URL

/** In-app view of browser downloads with live progress and explicit actions. */
class DownloadsActivity : AppCompatActivity() {
    data class DownloadItem(
        val id: Long,
        val title: String,
        val status: Int,
        val reason: Int,
        val localUri: String?,
        val sourceUrl: String?,
        val size: Long,
        val downloaded: Long,
        val mimeType: String?
    )

    private val items = mutableListOf<DownloadItem>()
    private lateinit var adapter: BaseAdapter
    private lateinit var empty: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val poll = object : Runnable {
        override fun run() {
            if (!isFinishing && !isDestroyed) {
                loadDownloads()
                handler.postDelayed(this, 700L)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_downloads)
        findViewById<TextView>(R.id.btn_downloads_back).setOnClickListener { finish() }
        empty = findViewById(R.id.downloads_empty)
        adapter = object : BaseAdapter() {
            override fun getCount() = items.size
            override fun getItem(position: Int) = items[position]
            override fun getItemId(position: Int) = items[position].id
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val view = convertView ?: layoutInflater.inflate(R.layout.item_download, parent, false)
                val item = items[position]
                view.findViewById<TextView>(R.id.download_title).text = item.title
                view.findViewById<TextView>(R.id.download_status).text = statusText(item)
                val primary = view.findViewById<TextView>(R.id.download_action_primary)
                val remove = view.findViewById<TextView>(R.id.download_action_remove)

                when (item.status) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        primary.text = "Open"
                        primary.visibility = View.VISIBLE
                        primary.setOnClickListener { openDownload(item) }
                    }
                    DownloadManager.STATUS_FAILED -> {
                        primary.text = "Retry"
                        primary.visibility = View.VISIBLE
                        primary.setOnClickListener { retryDownload(item) }
                    }
                    DownloadManager.STATUS_RUNNING -> {
                        primary.text = "Running"
                        primary.visibility = View.VISIBLE
                        primary.setOnClickListener {
                            Toast.makeText(this@DownloadsActivity, "Download is running. Android DownloadManager controls automatic pause/resume when the network changes.", Toast.LENGTH_SHORT).show()
                        }
                    }
                    DownloadManager.STATUS_PAUSED -> {
                        primary.text = "Resume"
                        primary.visibility = View.VISIBLE
                        primary.setOnClickListener { resumeDownload(item) }
                    }
                    DownloadManager.STATUS_PENDING -> {
                        primary.text = "Waiting"
                        primary.visibility = View.VISIBLE
                        primary.setOnClickListener {
                            Toast.makeText(this@DownloadsActivity, "Waiting for DownloadManager", Toast.LENGTH_SHORT).show()
                        }
                    }
                    else -> primary.visibility = View.GONE
                }
                remove.text = if (item.status == DownloadManager.STATUS_SUCCESSFUL || item.status == DownloadManager.STATUS_FAILED) "Delete" else "Cancel"
                remove.visibility = View.VISIBLE
                remove.setOnClickListener { removeDownload(item) }
                view.setOnClickListener { if (item.status == DownloadManager.STATUS_SUCCESSFUL) openDownload(item) }
                return view
            }
        }
        findViewById<ListView>(R.id.downloads_list).adapter = adapter
        PhormiNotificationCenter.ensureChannels(this)
        loadDownloads()
    }

    override fun onResume() { super.onResume(); handler.removeCallbacks(poll); handler.post(poll) }
    override fun onPause() { handler.removeCallbacks(poll); super.onPause() }

    private fun loadDownloads() {
        items.clear()
        val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        try {
            manager.query(DownloadManager.Query()).use { cursor ->
                val idCol = cursor.getColumnIndex(DownloadManager.COLUMN_ID)
                val titleCol = cursor.getColumnIndex(DownloadManager.COLUMN_TITLE)
                val statusCol = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                val reasonCol = cursor.getColumnIndex(DownloadManager.COLUMN_REASON)
                val uriCol = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                val sourceCol = cursor.getColumnIndex(DownloadManager.COLUMN_URI)
                val sizeCol = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                val doneCol = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                val mimeCol = cursor.getColumnIndex(DownloadManager.COLUMN_MEDIA_TYPE)
                while (cursor.moveToNext()) {
                    val local = if (uriCol >= 0) cursor.getString(uriCol) else null
                    val item = DownloadItem(
                        cursor.getLong(idCol),
                        cursor.getString(titleCol)?.takeIf { it.isNotBlank() }
                            ?: local?.let { PhormiFileOpener.displayName(this, Uri.parse(it), "Download") }
                            ?: "Download",
                        cursor.getInt(statusCol),
                        if (reasonCol >= 0) cursor.getInt(reasonCol) else 0,
                        local,
                        if (sourceCol >= 0) cursor.getString(sourceCol) else null,
                        if (sizeCol >= 0) cursor.getLong(sizeCol) else -1L,
                        if (doneCol >= 0) cursor.getLong(doneCol) else 0L,
                        if (mimeCol >= 0) cursor.getString(mimeCol) else null
                    )
                    items += item
                    if (item.status == DownloadManager.STATUS_SUCCESSFUL) PhormiNotificationCenter.postDownloadEvent(this, item.id, item.title, true)
                    else if (item.status == DownloadManager.STATUS_FAILED) PhormiNotificationCenter.postDownloadEvent(this, item.id, item.title, false)
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Could not read downloads: ${e.message}", Toast.LENGTH_SHORT).show()
        }
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        adapter.notifyDataSetChanged()
    }

    private fun statusText(item: DownloadItem): String {
        val source = item.sourceUrl?.takeIf { it.startsWith("http", true) }?.let { "\n$it" }.orEmpty()
        return when (item.status) {
            DownloadManager.STATUS_RUNNING -> "Downloading · ${formatProgress(item)} · ${formatBytes(item.downloaded)} of ${if (item.size > 0) formatBytes(item.size) else "unknown size"}$source"
            DownloadManager.STATUS_PAUSED -> "Paused · ${formatProgress(item)} · ${formatBytes(item.downloaded)}$source"
            DownloadManager.STATUS_PENDING -> "Waiting to download · ${formatProgress(item)}$source"
            DownloadManager.STATUS_SUCCESSFUL -> "${category(item)} · Completed · ${if (item.size > 0) formatBytes(item.size) else "Completed"}$source"
            DownloadManager.STATUS_FAILED -> "Download failed · ${failureReason(item.reason)}$source"
            else -> "${category(item)} · Status unavailable$source"
        }
    }

    private fun failureReason(reason: Int): String = when (reason) {
        DownloadManager.ERROR_CANNOT_RESUME -> "cannot resume"
        DownloadManager.ERROR_DEVICE_NOT_FOUND -> "storage unavailable"
        DownloadManager.ERROR_FILE_ALREADY_EXISTS -> "file already exists"
        DownloadManager.ERROR_FILE_ERROR -> "file error"
        DownloadManager.ERROR_HTTP_DATA_ERROR -> "HTTP data error"
        DownloadManager.ERROR_INSUFFICIENT_SPACE -> "not enough storage"
        DownloadManager.ERROR_TOO_MANY_REDIRECTS -> "too many redirects"
        DownloadManager.ERROR_UNHANDLED_HTTP_CODE -> "server HTTP error"
        DownloadManager.ERROR_UNKNOWN -> "unknown error"
        else -> if (reason in 400..599) "server HTTP $reason" else "error $reason"
    }

    private fun category(item: DownloadItem): String = PhormiDownloadSupport.category(item.title, item.mimeType.orEmpty())

    private fun formatProgress(item: DownloadItem): String = if (item.size <= 0) "Progress unavailable" else "${((item.downloaded * 100L) / item.size).coerceIn(0L, 100L)}%"
    private fun formatBytes(value: Long): String = when {
        value < 1024 -> "$value B"
        value < 1024 * 1024 -> "${value / 1024} KB"
        value < 1024 * 1024 * 1024 -> "${value / (1024 * 1024)} MB"
        else -> "${value / (1024 * 1024 * 1024)} GB"
    }

    private fun openDownload(item: DownloadItem) {
        if (item.status != DownloadManager.STATUS_SUCCESSFUL || item.localUri.isNullOrBlank()) {
            Toast.makeText(this, statusText(item), Toast.LENGTH_SHORT).show()
            return
        }
        if (!PhormiFileOpener.open(this, Uri.parse(item.localUri), item.mimeType)) {
            Toast.makeText(this, "No installed app can open ${item.title}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun retryDownload(item: DownloadItem) {
        val url = item.sourceUrl?.trim().orEmpty()
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
            Toast.makeText(this, "The original download URL is no longer available.", Toast.LENGTH_SHORT).show()
            return
        }
        val mime = item.mimeType.orEmpty().ifBlank { "application/octet-stream" }
        val cookies = runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()
        val ua = runCatching { WebSettings.getDefaultUserAgent(this) }.getOrNull()
        val info = PhormiDownloadSupport.resolve(url, null, mime, ua, url, cookies)
        val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        runCatching {
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setMimeType(info.mimeType)
                setTitle(item.title.ifBlank { info.fileName })
                setDescription("Phormi · manual retry · $url")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, info.fileName)
                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)
                info.headers.forEach { (key, value) -> addRequestHeader(key, value) }
            }
            manager.remove(item.id)
            manager.enqueue(request)
            Toast.makeText(this, "Retry started", Toast.LENGTH_SHORT).show()
            loadDownloads()
        }.onFailure {
            Toast.makeText(this, "Retry failed: ${it.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun resumeDownload(item: DownloadItem) {
        // DownloadManager exposes paused state but not a public pause/resume method.
        // Triggering a new request is safer than pretending a removed download can be resumed.
        retryDownload(item)
    }

    private fun removeDownload(item: DownloadItem) {
        (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).remove(item.id)
        Toast.makeText(this, if (item.status == DownloadManager.STATUS_SUCCESSFUL || item.status == DownloadManager.STATUS_FAILED) "Download deleted" else "Download cancelled", Toast.LENGTH_SHORT).show()
        loadDownloads()
    }
}
