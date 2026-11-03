package com.uong.phormi

import android.net.Uri
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.BufferedReader
import java.io.InputStreamReader

/** Read-only local code/text viewer used for files opened from downloads or ZIP archives. */
class PhormiCodeViewerActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent.getParcelableExtra<Uri>("uri") ?: run { finish(); return }
        val title = intent.getStringExtra("title").orEmpty().ifBlank { PhormiFileOpener.displayName(this, uri) }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(PhormiUiColors.background(this@PhormiCodeViewerActivity)) }
        val bar = LinearLayout(this).apply { setPadding(12, 8, 12, 8); gravity = android.view.Gravity.CENTER_VERTICAL; setBackgroundColor(PhormiUiColors.surface(this@PhormiCodeViewerActivity)) }
        val close = TextView(this).apply { text = "‹"; textSize = 30f; gravity = android.view.Gravity.CENTER; setTextColor(PhormiUiColors.text(this@PhormiCodeViewerActivity)); setOnClickListener { finish() } }
        bar.addView(close, LinearLayout.LayoutParams(48, 48))
        bar.addView(TextView(this).apply { text = title; textSize = 16f; setTextColor(PhormiUiColors.text(this@PhormiCodeViewerActivity)); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, 48, 1f))
        root.addView(bar)
        val codeView = TextView(this).apply { setTextColor(PhormiUiColors.text(this@PhormiCodeViewerActivity)); textSize = 13f; typeface = android.graphics.Typeface.MONOSPACE; setPadding(16, 16, 16, 24); setTextIsSelectable(true) }
        val scroll = ScrollView(this)
        scroll.addView(codeView)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        Thread {
            val result = runCatching { contentResolver.openInputStream(uri)?.use { input -> BufferedReader(InputStreamReader(input, Charsets.UTF_8)).readText() } ?: error("Unable to read file") }
            runOnUiThread { result.onSuccess { codeView.text = it }.onFailure { codeView.text = "Unable to display this text/code file.\n\n" + (it.message ?: "Unknown error") } }
        }.start()
    }
}