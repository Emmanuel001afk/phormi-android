package com.uong.phormi

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.View
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.concurrent.Executors

/** Samples user photos off the typing/UI thread and reuses a small bounded cache. */
internal object PhormiWallpaperLoader {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val cache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val requests = WeakHashMap<View, String>()

    fun load(target: View, uri: String, ready: (View, Bitmap) -> Unit) {
        requests[target] = uri
        cache.get(uri)?.let { ready(target, it); return }
        val reference = WeakReference(target)
        val resolver = target.context.applicationContext.contentResolver
        worker.execute {
            val bitmap = runCatching {
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                resolver.openInputStream(Uri.parse(uri))?.use { BitmapFactory.decodeStream(it, null, options) }
                if (options.outWidth <= 0 || options.outHeight <= 0) return@runCatching null
                var sample = 1
                while (options.outWidth / sample > 1200 || options.outHeight / sample > 1200) sample *= 2
                options.inJustDecodeBounds = false; options.inSampleSize = sample
                resolver.openInputStream(Uri.parse(uri))?.use { BitmapFactory.decodeStream(it, null, options) }
            }.getOrNull() ?: return@execute
            cache.put(uri, bitmap)
            main.post {
                val view = reference.get()
                if (view != null && requests[view] == uri) ready(view, bitmap)
            }
        }
    }

    fun cancel(target: View) { requests.remove(target) }
}
