package com.uong.phormi

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/** In-app view of downloads created through Android DownloadManager, with live progress. */
class DownloadsActivity : AppCompatActivity() {
    data class DownloadItem(val id: Long, val title: String, val status: Int, val localUri: String?, val sourceUrl: String?, val size: Long, val downloaded: Long, val mimeType: String?)
    private val items = mutableListOf<DownloadItem>()
    private lateinit var adapter: BaseAdapter
    private lateinit var empty: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val poll = object : Runnable { override fun run() { if (!isFinishing && !isDestroyed) { loadDownloads(); handler.postDelayed(this, 700L) } } }

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
                view.setOnClickListener { openDownload(item) }
                view.setOnLongClickListener { cancelDownload(item); true }
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
                        cursor.getInt(statusCol), local,
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
            DownloadManager.STATUS_PENDING -> "Waiting to download$source"
            DownloadManager.STATUS_SUCCESSFUL -> "${category(item)} · Completed · ${if (item.size > 0) formatBytes(item.size) else "Completed"}$source"
            DownloadManager.STATUS_FAILED -> "Download failed$source"
            else -> "${category(item)} · Status unavailable$source"
        }
    }

    private fun category(item: DownloadItem): String {
        val mime = item.mimeType.orEmpty().lowercase(); val name = item.title.lowercase()
        return when {
            mime.startsWith("video/") || name.endsWith(".mp4") || name.endsWith(".webm") || name.endsWith(".mkv") -> "Video"
            mime.startsWith("image/") -> "Image"
            mime.startsWith("audio/") -> "Audio"
            mime == "application/pdf" || name.endsWith(".pdf") -> "PDF"
            name.endsWith(".zip") || name.endsWith(".rar") || name.endsWith(".7z") || name.endsWith(".tar") || mime.contains("zip") -> "Archive"
            mime.startsWith("text/") || listOf(".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx").any(name::endsWith) -> "Document"
            else -> "Other"
        }
    }

    private fun formatProgress(item: DownloadItem): String = if (item.size <= 0) "Progress unavailable" else "${((item.downloaded * 100L) / item.size).coerceIn(0L, 100L)}%"
    private fun formatBytes(value: Long): String = when { value < 1024 -> "$value B"; value < 1024 * 1024 -> "${value / 1024} KB"; value < 1024 * 1024 * 1024 -> "${value / (1024 * 1024)} MB"; else -> "${value / (1024 * 1024 * 1024)} GB" }

    private fun openDownload(item: DownloadItem) {
        if (item.status != DownloadManager.STATUS_SUCCESSFUL || item.localUri.isNullOrBlank()) { Toast.makeText(this, statusText(item), Toast.LENGTH_SHORT).show(); return }
        if (!PhormiFileOpener.open(this, Uri.parse(item.localUri), item.mimeType)) Toast.makeText(this, "No installed app can open ${item.title}", Toast.LENGTH_SHORT).show()
    }

    private fun cancelDownload(item: DownloadItem) {
        if (item.status == DownloadManager.STATUS_RUNNING || item.status == DownloadManager.STATUS_PENDING || item.status == DownloadManager.STATUS_PAUSED) {
            (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).remove(item.id)
            Toast.makeText(this, "Download removed", Toast.LENGTH_SHORT).show()
            loadDownloads()
        }
    }
}
