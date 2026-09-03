package com.uong.phormi

import android.app.DownloadManager
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.text.DateFormat
import java.util.Date

/** In-app view of downloads created through Android DownloadManager. */
class DownloadsActivity : AppCompatActivity() {
    data class DownloadItem(
        val id: Long,
        val title: String,
        val status: Int,
        val localUri: String?,
        val size: Long,
        val downloaded: Long,
        val mimeType: String?
    )

    private val items = mutableListOf<DownloadItem>()
    private lateinit var adapter: BaseAdapter
    private lateinit var empty: TextView

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
                val view = convertView ?: layoutInflater.inflate(
                    R.layout.item_download,
                    parent,
                    false
                )
                val item = items[position]
                view.findViewById<TextView>(R.id.download_title).text = item.title
                view.findViewById<TextView>(R.id.download_status).text = statusText(item)
                view.setOnClickListener { openDownload(item) }
                view.setOnLongClickListener {
                    cancelDownload(item)
                    true
                }
                return view
            }
        }
        findViewById<ListView>(R.id.downloads_list).adapter = adapter
        loadDownloads()
    }

    override fun onResume() {
        super.onResume()
        if (::adapter.isInitialized) loadDownloads()
    }

    private fun loadDownloads() {
        items.clear()
        val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val query = DownloadManager.Query()
        try {
            manager.query(query).use { cursor ->
                val idCol = cursor.getColumnIndex(DownloadManager.COLUMN_ID)
                val titleCol = cursor.getColumnIndex(DownloadManager.COLUMN_TITLE)
                val statusCol = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                val uriCol = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                val sizeCol = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                val doneCol = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                val mimeCol = cursor.getColumnIndex(DownloadManager.COLUMN_MEDIA_TYPE)
                while (cursor.moveToNext()) {
                    items += DownloadItem(
                        id = cursor.getLong(idCol),
                        title = cursor.getString(titleCol)?.takeIf { it.isNotBlank() } ?: run {
                            val local = if (uriCol >= 0) cursor.getString(uriCol) else null
                            if (local.isNullOrBlank()) "Download" else
                                PhormiFileOpener.displayName(this@DownloadsActivity, Uri.parse(local), "Download")
                        },
                        status = cursor.getInt(statusCol),
                        localUri = if (uriCol >= 0) cursor.getString(uriCol) else null,
                        size = if (sizeCol >= 0) cursor.getLong(sizeCol) else -1L,
                        downloaded = if (doneCol >= 0) cursor.getLong(doneCol) else 0L,
                        mimeType = if (mimeCol >= 0) cursor.getString(mimeCol) else null
                    )
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Could not read downloads: ${e.message}", Toast.LENGTH_SHORT).show()
        }
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        adapter.notifyDataSetChanged()
    }

    private fun statusText(item: DownloadItem): String {
        return when (item.status) {
            DownloadManager.STATUS_SUCCESSFUL -> {
                val size = if (item.size > 0) formatBytes(item.size) else "Completed"
                "Completed · $size"
            }
            DownloadManager.STATUS_RUNNING -> "Downloading · ${formatProgress(item)}"
            DownloadManager.STATUS_PAUSED -> "Paused · ${formatProgress(item)}"
            DownloadManager.STATUS_PENDING -> "Waiting to download"
            DownloadManager.STATUS_FAILED -> "Download failed"
            else -> "Status unavailable"
        }
    }

    private fun formatProgress(item: DownloadItem): String {
        if (item.size <= 0) return formatBytes(item.downloaded)
        return "${((item.downloaded * 100L) / item.size).coerceIn(0L, 100L)}%"
    }

    private fun formatBytes(value: Long): String {
        if (value < 1024) return "$value B"
        if (value < 1024 * 1024) return "${value / 1024} KB"
        if (value < 1024 * 1024 * 1024) return "${value / (1024 * 1024)} MB"
        return "${value / (1024 * 1024 * 1024)} GB"
    }

    private fun openDownload(item: DownloadItem) {
        if (item.status != DownloadManager.STATUS_SUCCESSFUL || item.localUri.isNullOrBlank()) {
            Toast.makeText(this, statusText(item), Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val uri = Uri.parse(item.localUri)
            if (!PhormiFileOpener.open(this, uri, item.mimeType)) {
                Toast.makeText(this, "No installed app can open ${item.title}", Toast.LENGTH_SHORT).show()
            }
        } catch (_: Exception) {
            Toast.makeText(this, "No app can open this file", Toast.LENGTH_SHORT).show()
        }
    }

    private fun cancelDownload(item: DownloadItem) {
        if (item.status == DownloadManager.STATUS_RUNNING ||
            item.status == DownloadManager.STATUS_PENDING ||
            item.status == DownloadManager.STATUS_PAUSED
        ) {
            val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            manager.remove(item.id)
            Toast.makeText(this, "Download removed", Toast.LENGTH_SHORT).show()
            loadDownloads()
        }
    }
}
