package com.uong.phormi

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.util.zip.ZipInputStream

/** Lightweight in-browser ZIP viewer. It lists archive contents and still offers external opening. */
class PhormiZipViewerActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent.getParcelableExtra<Uri>("uri") ?: run { finish(); return }
        val title = intent.getStringExtra("title").orEmpty().ifBlank { PhormiFileOpener.displayName(this, uri) }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0B1220.toInt())
        }
        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12, 10, 12, 10)
        }
        val back = TextView(this).apply {
            text = "‹"
            textSize = 32f
            setTextColor(0xFFF8FAFC.toInt())
            gravity = Gravity.CENTER
            setOnClickListener { finish() }
        }
        header.addView(back, LinearLayout.LayoutParams(52, 56))
        header.addView(TextView(this).apply {
            text = title
            textSize = 18f
            setTextColor(0xFFF8FAFC.toInt())
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, 56, 1f))
        val external = TextView(this).apply {
            text = "Open with…"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(0xFFE2E8F0.toInt())
            setPadding(14, 0, 14, 0)
            setBackgroundColor(0xFF182338.toInt())
            setOnClickListener {
                if (!PhormiFileOpener.openExternal(this@PhormiZipViewerActivity, uri, "application/zip")) {
                    Toast.makeText(this@PhormiZipViewerActivity, "No installed archive app can open this ZIP", Toast.LENGTH_SHORT).show()
                }
            }
        }
        header.addView(external, LinearLayout.LayoutParams(110, 42))
        root.addView(header)

        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(14, 8, 14, 20)
        }
        val status = TextView(this).apply {
            text = "Reading archive…"
            textSize = 14f
            setTextColor(0xFF94A3B8.toInt())
            setPadding(0, 12, 0, 12)
        }
        list.addView(status)
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        Thread {
            val entries = runCatching {
                val result = mutableListOf<Pair<String, Long>>()
                contentResolver.openInputStream(uri)?.use { input ->
                    ZipInputStream(input.buffered()).use { zip ->
                        var entry = zip.nextEntry
                        var count = 0
                        while (entry != null && count < 5000) {
                            result += entry.name to entry.size
                            count++
                            zip.closeEntry()
                            entry = zip.nextEntry
                        }
                    }
                } ?: error("Could not read ZIP")
                result
            }.getOrElse {
                runOnUiThread { status.text = "Could not read this ZIP archive: ${it.message ?: "unknown error"}" }
                return@Thread
            }
            runOnUiThread {
                status.text = "${entries.size} item${if (entries.size == 1) "" else "s"}"
                entries.forEach { (name, size) ->
                    list.addView(TextView(this).apply {
                        text = if (size >= 0) "${if (name.endsWith("/")) "📁" else "📄"}  $name\n${formatBytes(size)}" else "${if (name.endsWith("/")) "📁" else "📄"}  $name"
                        textSize = 14f
                        setTextColor(0xFFE2E8F0.toInt())
                        setPadding(8, 10, 8, 10)
                    })
                }
            }
        }.start()
    }

    private fun formatBytes(value: Long): String = when {
        value < 0 -> "size unavailable"
        value < 1024 -> "$value B"
        value < 1024 * 1024 -> "${value / 1024} KB"
        else -> "${value / (1024 * 1024)} MB"
    }
}
