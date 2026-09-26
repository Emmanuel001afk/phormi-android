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
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import androidx.appcompat.app.AppCompatActivity

/** Chrome-style in-app download list backed by PhormiDownloadEngine. */
class DownloadsActivity : AppCompatActivity() {
    data class Row(
        val id: String,
        val title: String,
        val status: String,
        val progress: Int,
        val downloaded: Long,
        val total: Long,
        val state: String,
        val localUri: String?,
        val mimeType: String?,
        val error: String? = null,
        val legacy: Boolean = false
    )

    private val rows = mutableListOf<Row>()
    private lateinit var adapter: BaseAdapter
    private lateinit var empty: TextView
    private lateinit var list: ListView
    private val handler = Handler(Looper.getMainLooper())
    private val poll = object : Runnable {
        override fun run() {
            if (!isFinishing && !isDestroyed) {
                loadDownloads()
                handler.postDelayed(this, 500L)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_downloads)
        findViewById<TextView>(R.id.btn_downloads_back).setOnClickListener { finish() }
        empty = findViewById(R.id.downloads_empty)
        list = findViewById(R.id.downloads_list)
        adapter = object : BaseAdapter() {
            override fun getCount() = rows.size
            override fun getItem(position: Int) = rows[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val view = convertView ?: layoutInflater.inflate(R.layout.item_download, parent, false)
                val row = rows[position]
                view.findViewById<TextView>(R.id.download_title).text = row.title
                view.findViewById<TextView>(R.id.download_status).text = row.status
                val progress = view.findViewById<ProgressBar>(R.id.download_progress)
                progress.progress = row.progress
                progress.visibility = if (row.total > 0L && row.state != "COMPLETED") View.VISIBLE else View.GONE

                val action = view.findViewById<TextView>(R.id.download_action)
                val delete = view.findViewById<TextView>(R.id.download_delete)
                val playable = row.state == "COMPLETED" && isPlayable(row.mimeType, row.title)
                action.visibility = if (playable || (!row.legacy && row.state !in setOf("COMPLETED", "CANCELLED"))) View.VISIBLE else View.GONE
                action.text = if (playable) "Play" else when (row.state) {
                    "RUNNING" -> "Pause"
                    "PAUSED", "QUEUED" -> "Resume"
                    "FAILED" -> "Retry"
                    else -> "Resume"
                }
                action.contentDescription = if (playable) "Play media" else when (row.state) {
                    "RUNNING" -> "Pause download"
                    "FAILED" -> "Retry download"
                    else -> "Resume download"
                }
                action.setOnClickListener {
                    if (playable) {
                        openRow(row)
                        return@setOnClickListener
                    }
                    when (row.state) {
                        "RUNNING" -> PhormiDownloadEngine.pause(this@DownloadsActivity, row.id)
                        "PAUSED", "QUEUED", "FAILED" -> PhormiDownloadEngine.resume(this@DownloadsActivity, row.id)
                    }
                }
                // Legacy DownloadManager rows are real files too. Keep Delete available for
                // them instead of making the old download path impossible to clean up.
                delete.visibility = View.VISIBLE
                delete.text = if (row.state == "COMPLETED" || row.state == "CANCELLED") "Delete" else "Cancel"
                delete.contentDescription = if (row.state == "COMPLETED" || row.state == "CANCELLED") "Delete download" else "Cancel download"
                delete.setOnClickListener {
                    if (row.legacy && row.id.startsWith("d:")) {
                        val downloadId = row.id.removePrefix("d:").toLongOrNull()
                        if (downloadId != null) {
                            runCatching {
                                (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).remove(downloadId)
                            }
                            loadDownloads()
                        }
                    } else if (row.state == "COMPLETED" || row.state == "CANCELLED") {
                        PhormiDownloadEngine.delete(this@DownloadsActivity, row.id)
                    } else {
                        PhormiDownloadEngine.cancel(this@DownloadsActivity, row.id)
                    }
                }
                view.setOnClickListener { openRow(row) }
                view.setOnLongClickListener {
                    if (!row.legacy && row.state !in setOf("COMPLETED", "CANCELLED")) {
                        PhormiDownloadEngine.cancel(this@DownloadsActivity, row.id)
                    }
                    true
                }
                return view
            }
        }
        list.adapter = adapter
        loadDownloads()
    }

    override fun onResume() { super.onResume(); handler.removeCallbacks(poll); handler.post(poll) }
    override fun onPause() { handler.removeCallbacks(poll); super.onPause() }

    private fun loadDownloads() {
        rows.clear()
        PhormiDownloadEngine.items(this).forEach { item ->
            val progress = if (item.total > 0L) ((item.downloaded * 100L) / item.total).toInt().coerceIn(0, 100) else 0
            val status = when (item.state) {
                PhormiDownloadEngine.State.RUNNING -> "Downloading · $progress% · ${formatBytes(item.downloaded)} of ${if (item.total > 0) formatBytes(item.total) else "unknown size"}"
                PhormiDownloadEngine.State.QUEUED -> if (item.error.isNullOrBlank()) "Waiting to download · ${formatBytes(item.downloaded)} of ${if (item.total > 0) formatBytes(item.total) else "unknown size"}" else item.error
                PhormiDownloadEngine.State.PAUSED -> "Paused · $progress% · ${formatBytes(item.downloaded)} of ${if (item.total > 0) formatBytes(item.total) else "unknown size"}"
                PhormiDownloadEngine.State.COMPLETED -> "${item.category} · Completed · ${formatBytes(item.total.coerceAtLeast(item.downloaded))}"
                PhormiDownloadEngine.State.FAILED -> "Failed · ${item.error ?: "Download failed"}"
                PhormiDownloadEngine.State.CANCELLED -> "Cancelled · Ready to delete"
            }
            rows += Row("p:${item.id}", item.title, status ?: "", progress, item.downloaded, item.total, item.state.name, item.localUri, item.mimeType, item.error, false)
        }

        // Preserve already-completed downloads created by the older DownloadManager path.
        // Failed legacy rows are intentionally not mirrored here, so an old HTTP 403/503 does
        // not keep generating the same error message on every refresh.
        val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        runCatching {
            manager.query(DownloadManager.Query()).use { cursor ->
                val idCol = cursor.getColumnIndex(DownloadManager.COLUMN_ID)
                val titleCol = cursor.getColumnIndex(DownloadManager.COLUMN_TITLE)
                val statusCol = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                val uriCol = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                val sizeCol = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                val mimeCol = cursor.getColumnIndex(DownloadManager.COLUMN_MEDIA_TYPE)
                while (cursor.moveToNext()) {
                    if (cursor.getInt(statusCol) != DownloadManager.STATUS_SUCCESSFUL) continue
                    val local = if (uriCol >= 0) cursor.getString(uriCol) else null
                    val title = cursor.getString(titleCol)?.takeIf { it.isNotBlank() }
                        ?: local?.let { PhormiFileOpener.displayName(this, Uri.parse(it), "Download") }
                        ?: "Download"
                    val size = if (sizeCol >= 0) cursor.getLong(sizeCol) else -1L
                    rows += Row(
                        id = "d:${cursor.getLong(idCol)}",
                        title = title,
                        status = "Completed · ${formatBytes(size)}",
                        progress = 100,
                        downloaded = size,
                        total = size,
                        state = "COMPLETED",
                        localUri = local,
                        mimeType = if (mimeCol >= 0) cursor.getString(mimeCol) else null,
                        legacy = true
                    )
                }
            }
        }
        rows.sortByDescending { it.id }
        empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        list.visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE
        adapter.notifyDataSetChanged()
    }

    private fun openRow(row: Row) {
        if (row.state != "COMPLETED") return
        val uri = row.localUri?.takeIf { it.isNotBlank() }?.let(Uri::parse)
        if (uri == null) {
            Toast.makeText(this, "The downloaded file is no longer available.", Toast.LENGTH_LONG).show()
            return
        }
        val mime = PhormiFileOpener.resolveMimeType(this, uri, row.mimeType)
        if (!PhormiFileOpener.open(this, uri, mime) && !PhormiFileOpener.openExternal(this, uri, mime)) {
            Toast.makeText(this, "No installed app can open ${row.title}", Toast.LENGTH_LONG).show()
        }
    }

    private fun isPlayable(mimeType: String?, title: String): Boolean {
        val mime = mimeType.orEmpty().lowercase()
        if (mime.startsWith("video/") || mime.startsWith("audio/")) return true
        return listOf(
            ".mp4", ".m4v", ".webm", ".mkv", ".mov", ".avi", ".3gp", ".3g2",
            ".ts", ".m2ts", ".mts", ".mpeg", ".mpg", ".ogv", ".flv", ".wmv",
            ".mp3", ".m4a", ".flac", ".wav", ".ogg", ".oga", ".aac", ".opus"
        ).any(title.lowercase()::endsWith)
    }

    private fun formatBytes(value: Long): String = when {
        value < 0L -> "unknown size"
        value < 1024L -> "\$value B"
        value < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", value / 1024.0)
        value < 1024L * 1024L * 1024L -> String.format(Locale.US, "%.1f MB", value / (1024.0 * 1024.0))
        else -> String.format(Locale.US, "%.2f GB", value / (1024.0 * 1024.0 * 1024.0))
    }
}
