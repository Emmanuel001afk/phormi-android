package com.uong.phormi

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.WebView
import java.io.ByteArrayOutputStream

/** Small, bounded WebView capture helper used by Central Hub browser inspection. */
object PhormiViewportCapture {
    private val mainHandler = Handler(Looper.getMainLooper())

    fun toBase64Jpeg(webView: WebView, maxWidth: Int = 1280, quality: Int = 62): String {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw IllegalStateException("WebView capture must run on the main thread")
        }

        val width = webView.width.coerceAtLeast(1)
        val height = webView.height.coerceAtLeast(1)
        val scale = (maxWidth.toFloat() / width.toFloat()).coerceAtMost(1f)
        val outWidth = (width * scale).toInt().coerceAtLeast(1)
        val outHeight = (height * scale).toInt().coerceAtLeast(1)

        val bitmap = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.scale(scale, scale)
        webView.draw(canvas)

        return try {
            ByteArrayOutputStream().use { output ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(20, 95), output)
                Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
            }
        } finally {
            bitmap.recycle()
        }
    }

    fun captureAsync(webView: WebView, maxWidth: Int = 1280, quality: Int = 62, callback: (String?) -> Unit) {
        mainHandler.post {
            callback(runCatching { toBase64Jpeg(webView, maxWidth, quality) }.getOrNull())
        }
    }
}
