package com.uong.phormi

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class PhormiPdfViewerActivity : AppCompatActivity() {
    private var descriptor: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(8, 16, 28))
        }
        val bar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12, 8, 12, 8)
            setBackgroundColor(Color.rgb(15, 23, 42))
        }
        val title = TextView(this).apply {
            text = intent.getStringExtra("title").orEmpty().ifBlank { "PDF" }
            setTextColor(Color.WHITE)
            textSize = 16f
            maxLines = 1
        }
        val close = TextView(this).apply {
            text = "×"
            textSize = 28f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            contentDescription = "Close PDF viewer"
            setOnClickListener { finish() }
        }
        bar.addView(title, LinearLayout.LayoutParams(0, 48, 1f))
        bar.addView(close, LinearLayout.LayoutParams(48, 48))
        root.addView(bar)

        val scroll = android.widget.ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(Color.rgb(30, 41, 59))
        }
        val pages = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(8, 12, 8, 24)
        }
        scroll.addView(pages)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        val uri = intent.getParcelableExtra<Uri>("uri")
        if (uri == null) {
            title.text = "PDF unavailable"
            return
        }
        runCatching {
            descriptor = contentResolver.openFileDescriptor(uri, "r")
            renderer = descriptor?.let { PdfRenderer(it) }
            val pdf = renderer ?: error("Cannot open PDF")
            title.text = "${title.text} · ${pdf.pageCount} pages"
            for (index in 0 until pdf.pageCount) {
                val page = pdf.openPage(index)
                try {
                    val width = (resources.displayMetrics.widthPixels - 32).coerceAtLeast(240)
                    val height = (width.toLong() * page.height / page.width.coerceAtLeast(1)).toInt()
                    val bitmap = Bitmap.createBitmap(width, height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    val image = ImageView(this).apply {
                        setImageBitmap(bitmap)
                        adjustViewBounds = true
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        contentDescription = "PDF page ${index + 1}"
                        setBackgroundColor(Color.WHITE)
                    }
                    pages.addView(image, LinearLayout.LayoutParams(width, height).apply { bottomMargin = 12 })
                } finally {
                    page.close()
                }
            }
        }.onFailure {
            title.text = "Cannot open PDF"
            pages.removeAllViews()
            pages.addView(TextView(this).apply {
                text = "Phormi could not render this PDF. Use an installed PDF app instead."
                setTextColor(Color.WHITE)
                textSize = 15f
                setPadding(24, 32, 24, 32)
            })
        }
    }

    override fun onDestroy() {
        renderer?.close()
        renderer = null
        descriptor?.close()
        descriptor = null
        super.onDestroy()
    }
}
