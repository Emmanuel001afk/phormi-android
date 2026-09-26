package com.uong.phormi

import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

/** ZIP explorer: inspect archive entries and open individual files with Phormi viewers. */
class PhormiZipViewerActivity : AppCompatActivity() {
    private val maxExtractBytes = 20L * 1024L * 1024L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent.getParcelableExtra<Uri>("uri") ?: run { finish(); return }
        val title = intent.getStringExtra("title").orEmpty().ifBlank { PhormiFileOpener.displayName(this, uri) }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xFF0B1220.toInt()) }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(12, 10, 12, 10) }
        val back = TextView(this).apply { text = "‹"; textSize = 32f; setTextColor(0xFFF8FAFC.toInt()); gravity = Gravity.CENTER; setOnClickListener { finish() } }
        header.addView(back, LinearLayout.LayoutParams(52, 56))
        header.addView(TextView(this).apply { text = title; textSize = 18f; setTextColor(0xFFF8FAFC.toInt()); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, 56, 1f))
        val external = TextView(this).apply {
            text = "Open with…"; textSize = 13f; gravity = Gravity.CENTER; setTextColor(0xFFE2E8F0.toInt()); setPadding(14, 0, 14, 0); setBackgroundColor(0xFF182338.toInt())
            setOnClickListener { if (!PhormiFileOpener.openExternal(this@PhormiZipViewerActivity, uri, "application/zip")) Toast.makeText(this@PhormiZipViewerActivity, "No installed archive app can open this ZIP", Toast.LENGTH_SHORT).show() }
        }
        header.addView(external, LinearLayout.LayoutParams(110, 42))
        root.addView(header)
        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(14, 8, 14, 20) }
        val status = TextView(this).apply { text = "Reading archive…"; textSize = 14f; setTextColor(0xFF94A3B8.toInt()); setPadding(0, 12, 0, 12) }
        list.addView(status); scroll.addView(list); root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f)); setContentView(root)
        Thread {
            val entries = runCatching {
                val result = mutableListOf<Pair<String, Long>>()
                contentResolver.openInputStream(uri)?.use { input -> ZipInputStream(input.buffered()).use { zip ->
                    var entry = zip.nextEntry; var count = 0
                    while (entry != null && count < 5000) { result += entry.name to entry.size; count++; zip.closeEntry(); entry = zip.nextEntry }
                } } ?: error("Could not read ZIP")
                result
            }.getOrElse {
                runOnUiThread { status.text = "Could not read this ZIP archive: " + (it.message ?: "unknown error") }; return@Thread
            }
            runOnUiThread {
                status.text = entries.size.toString() + " item" + if (entries.size == 1) "" else "s"
                entries.forEach { (name, size) ->
                    val row = TextView(this).apply {
                        text = (if (name.endsWith("/")) "📁" else fileIcon(name)) + "  " + name + if (size >= 0) "\n" + formatBytes(size) else ""
                        textSize = 14f; setTextColor(0xFFE2E8F0.toInt()); setPadding(8, 10, 8, 10)
                        if (!name.endsWith("/")) { isClickable = true; setOnClickListener { openEntry(uri, name, status) } }
                    }
                    list.addView(row)
                }
            }
        }.start()
    }

    private fun fileIcon(name: String): String = when (name.substringAfterLast(".", "").lowercase()) {
        "pdf" -> "📕", "zip","rar","7z" -> "🗜️", "mp4","mkv","webm","mov","avi" -> "🎬",
        "mp3","wav","m4a","flac","ogg" -> "🎵", "png","jpg","jpeg","gif","webp" -> "🖼️",
        "kt","java","js","ts","tsx","jsx","py","c","cpp","h","hpp","cs","go","rs","swift","dart","php","rb","sh","html","css","xml","json","yaml","yml","sql","md","txt" -> "💻",
        else -> "📄"
    }

    private fun openEntry(archive: Uri, entryName: String, status: TextView) {
        Thread {
            val extracted = runCatching { extractEntry(archive, entryName) }
            runOnUiThread {
                extracted.onSuccess { file ->
                    val outUri = FileProvider.getUriForFile(this, packageName + ".fileprovider", file)
                    val mime = PhormiFileOpener.resolveMimeType(this, outUri, null)
                    if (!PhormiFileOpener.open(this, outUri, mime)) Toast.makeText(this, "No viewer could open " + entryName, Toast.LENGTH_LONG).show()
                }.onFailure { status.text = "Could not open " + entryName + ": " + (it.message ?: "unknown error") }
            }
        }.start()
    }

    private fun extractEntry(archive: Uri, entryName: String): File {
        val safeName = entryName.replace(Regex("[^A-Za-z0-9._-]"), "_").takeLast(120)
        val dir = File(cacheDir, "zip_entries").apply { mkdirs() }
        val file = File(dir, System.currentTimeMillis().toString() + "_" + safeName)
        var written = 0L
        contentResolver.openInputStream(archive)?.use { input -> ZipInputStream(input.buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == entryName) {
                    FileOutputStream(file).use { out ->
                        val buffer = ByteArray(8192); var n: Int
                        while (zip.read(buffer).also { n = it } > 0) { written += n; if (written > maxExtractBytes) error("File is larger than the safe preview limit"); out.write(buffer, 0, n) }
                    }
                    return file
                }
                zip.closeEntry(); entry = zip.nextEntry
            }
        } } ?: error("Could not read ZIP")
        error("File not found in ZIP")
    }

    private fun formatBytes(value: Long): String = when { value < 0 -> "size unavailable"; value < 1024 -> "$value B"; value < 1024 * 1024 -> String.format("%.1f KB", value / 1024.0); else -> String.format("%.1f MB", value / (1024.0 * 1024.0)) }
}