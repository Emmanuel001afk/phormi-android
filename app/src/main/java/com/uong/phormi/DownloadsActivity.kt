package com.uong.phormi

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/** Polished in-app download manager with real pause/resume/delete controls. */
class DownloadsActivity : AppCompatActivity() {
    private val items = mutableListOf<PhormiDownloadStore.Record>()
    private lateinit var adapter: BaseAdapter
    private lateinit var empty: TextView
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

        adapter = object : BaseAdapter() {
            override fun getCount() = items.size
            override fun getItem(position: Int) = items[position]
            override fun getItemId(position: Int) = items[position].id
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val view = convertView ?: layoutInflater.inflate(R.layout.item_download, parent, false)
                bind(view, items[position])
                return view
            }
        }
        findViewById<ListView>(R.id.downloads_list).adapter = adapter
        PhormiNotificationCenter.ensureChannels(this)
        PhormiDownloadService.resumePending(this)
        loadDownloads()
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(poll)
        handler.post(poll)
    }

    override fun onPause() {
        handler.removeCallbacks(poll)
        super.onPause()
    }

    private fun loadDownloads() {
        items.clear()
        items += PhormiDownloadStore.all(this)
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        adapter.notifyDataSetChanged()
    }

    private fun bind(view: View, item: PhormiDownloadStore.Record) {
        val title = view.findViewById<TextView>(R.id.download_title)
        val status = view.findViewById<TextView>(R.id.download_status)
        val progress = view.findViewById<ProgressBar>(R.id.download_progress)
        val action = view.findViewById<Button>(R.id.download_action)
        val delete = view.findViewById<Button>(R.id.download_delete)
        val open = view.findViewById<Button>(R.id.download_open)

        title.text = item.fileName
        val percent = if (item.totalBytes > 0) ((item.downloadedBytes * 100L) / item.totalBytes).toInt().coerceIn(0, 100) else 0
        progress.progress = percent
        progress.visibility = if (item.state == PhormiDownloadStore.STATE_COMPLETED) View.GONE else View.VISIBLE

        status.text = when (item.state) {
            PhormiDownloadStore.STATE_DOWNLOADING -> if (item.totalBytes > 0) "${item.category} · Downloading · $percent% · ${formatBytes(item.downloadedBytes)} / ${formatBytes(item.totalBytes)}" else "${item.category} · Downloading · ${formatBytes(item.downloadedBytes)}"
            PhormiDownloadStore.STATE_QUEUED -> "${item.category} · Waiting to download"
            PhormiDownloadStore.STATE_PAUSED -> if (item.totalBytes > 0) "${item.category} · Paused · $percent% · ${formatBytes(item.downloadedBytes)} / ${formatBytes(item.totalBytes)}" else "${item.category} · Paused · ${formatBytes(item.downloadedBytes)}"
            PhormiDownloadStore.STATE_COMPLETED -> "${item.category} · Completed · ${formatBytes(item.downloadedBytes)}"
            PhormiDownloadStore.STATE_FAILED -> "${item.category} · Download failed${item.error?.let { " · $it" }.orEmpty()}"
            else -> item.category
        }

        action.visibility = if (item.state == PhormiDownloadStore.STATE_COMPLETED || item.state == PhormiDownloadStore.STATE_FAILED) View.GONE else View.VISIBLE
        action.text = if (item.state == PhormiDownloadStore.STATE_PAUSED) "Resume" else "Pause"
        action.setOnClickListener {
            if (item.state == PhormiDownloadStore.STATE_PAUSED || item.state == PhormiDownloadStore.STATE_FAILED) {
                PhormiDownloadService.resume(this, item.id)
            } else {
                PhormiDownloadService.pause(this, item.id)
            }
            loadDownloads()
        }

        delete.setOnClickListener {
            PhormiDownloadService.delete(this, item.id)
            loadDownloads()
            Toast.makeText(this, "Download deleted", Toast.LENGTH_SHORT).show()
        }

        open.visibility = if (item.state == PhormiDownloadStore.STATE_COMPLETED && !item.localUri.isNullOrBlank()) View.VISIBLE else View.GONE
        open.setOnClickListener {
            val uri = item.localUri?.let(Uri::parse) ?: return@setOnClickListener
            if (!PhormiFileOpener.open(this, uri, item.mimeType)) {
                Toast.makeText(this, "No installed app can open ${item.fileName}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun formatBytes(value: Long): String = when {
        value < 1024 -> "$value B"
        value < 1024 * 1024 -> "${value / 1024} KB"
        value < 1024 * 1024 * 1024 -> "${value / (1024 * 1024)} MB"
        else -> "${value / (1024 * 1024 * 1024)} GB"
    }
}
