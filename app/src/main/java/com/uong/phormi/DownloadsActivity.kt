package com.uong.phormi

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-app downloads list (like Chrome/Opera).
 * Shows files downloaded by Phormi. Tap to open/play.
 */
class DownloadsActivity : AppCompatActivity() {

    data class DownloadItem(
        val id: Long,
        val title: String,
        val status: Int,
        val localUri: String?,
        val mediaType: String?,
        val lastModified: Long,
        val totalSize: Long
    )

    private lateinit var listView: ListView
    private lateinit var emptyText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_downloads)

        listView = findViewById(R.id.downloads_list)
        emptyText = findViewById(R.id.empty_text)

        findViewById<TextView>(R.id.btn_back).setOnClickListener { finish() }

        loadDownloads()
    }

    override fun onResume() {
        super.onResume()
        loadDownloads()
    }

    private fun loadDownloads() {
        val items = queryPhormiDownloads()
        if (items.isEmpty()) {
            listView.visibility = View.GONE
            emptyText.visibility = View.VISIBLE
            return
        }
        emptyText.visibility = View.GONE
        listView.visibility = View.VISIBLE

        val adapter = object : ArrayAdapter<DownloadItem>(this, android.R.layout.simple_list_item_2, android.R.id.text1, items) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent)
                val item = items[position]
                val text1 = view.findViewById<TextView>(android.R.id.text1)
                val text2 = view.findViewById<TextView>(android.R.id.text2)
                text1.setTextColor(0xFFF1F5F9.toInt())
                text2.setTextColor(0xFF94A3B8.toInt())
                text1.text = item.title
                text2.text = statusLabel(item) + "  ·  " + sizeLabel(item.totalSize) + "  ·  " + dateLabel(item.lastModified)
                return view
            }
        }
        listView.adapter = adapter

        listView.setOnItemClickListener { _, _, position, _ ->
            openItem(items[position])
        }
    }

    private fun queryPhormiDownloads(): List<DownloadItem> {
        val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val query = DownloadManager.Query()
        // All downloads; we filter by description containing Phormi
        val cursor: Cursor = try {
            dm.query(query) ?: return emptyList()
        } catch (e: Exception) {
            return emptyList()
        }

        val list = mutableListOf<DownloadItem>()
        val colId = cursor.getColumnIndex(DownloadManager.COLUMN_ID)
        val colTitle = cursor.getColumnIndex(DownloadManager.COLUMN_TITLE)
        val colStatus = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
        val colLocal = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
        val colMedia = cursor.getColumnIndex(DownloadManager.COLUMN_MEDIA_TYPE)
        val colTime = cursor.getColumnIndex(DownloadManager.COLUMN_LAST_MODIFIED_TIMESTAMP)
        val colSize = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
        val colDesc = cursor.getColumnIndex(DownloadManager.COLUMN_DESCRIPTION)

        while (cursor.moveToNext()) {
            val desc = if (colDesc >= 0) cursor.getString(colDesc) ?: "" else ""
            // Prefer Phormi downloads; also show anything in public Downloads if needed
            val title = if (colTitle >= 0) cursor.getString(colTitle) ?: "File" else "File"
            val isOurs = desc.contains("Phormi", ignoreCase = true) ||
                desc.contains("Downloading via", ignoreCase = true)
            if (!isOurs && desc.isNotBlank()) continue

            list.add(
                DownloadItem(
                    id = if (colId >= 0) cursor.getLong(colId) else 0L,
                    title = title,
                    status = if (colStatus >= 0) cursor.getInt(colStatus) else 0,
                    localUri = if (colLocal >= 0) cursor.getString(colLocal) else null,
                    mediaType = if (colMedia >= 0) cursor.getString(colMedia) else null,
                    lastModified = if (colTime >= 0) cursor.getLong(colTime) else 0L,
                    totalSize = if (colSize >= 0) cursor.getLong(colSize) else 0L
                )
            )
        }
        cursor.close()
        return list.sortedByDescending { it.lastModified }
    }

    private fun statusLabel(item: DownloadItem): String {
        return when (item.status) {
            DownloadManager.STATUS_SUCCESSFUL -> getString(R.string.download_status_done)
            DownloadManager.STATUS_RUNNING,
            DownloadManager.STATUS_PENDING -> getString(R.string.download_status_running)
            else -> getString(R.string.download_status_failed)
        }
    }

    private fun sizeLabel(bytes: Long): String {
        if (bytes <= 0) return ""
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        return if (mb >= 1) String.format(Locale.US, "%.1f MB", mb)
        else String.format(Locale.US, "%.0f KB", kb)
    }

    private fun dateLabel(ms: Long): String {
        if (ms <= 0) return ""
        return SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()).format(Date(ms))
    }

    private fun openItem(item: DownloadItem) {
        if (item.status != DownloadManager.STATUS_SUCCESSFUL) {
            Toast.makeText(this, "Still downloading or failed", Toast.LENGTH_SHORT).show()
            return
        }
        val uriString = item.localUri
        if (uriString.isNullOrBlank()) {
            Toast.makeText(this, "File path not found", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val uri = Uri.parse(uriString)
            val mime = item.mediaType ?: contentResolver.getType(uri) ?: "*/*"

            // Prefer FileProvider for file:// paths
            val openUri: Uri = if (uri.scheme == "file") {
                val path = uri.path
                if (path != null) {
                    val file = File(path)
                    FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
                } else uri
            } else uri

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(openUri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.open_file)))
        } catch (e: Exception) {
            Toast.makeText(this, "Cannot open: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
