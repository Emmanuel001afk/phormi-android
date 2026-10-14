package com.uong.phormi

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.lang.ref.WeakReference
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Async blob/data transfers use an explicit nonce and bounded chunks, not a Promise
 * returned from evaluateJavascript (WebView does not await such Promises).
 * No page can create a native transfer: start() is called only by the browser.
 */
internal class PhormiBlobDownloadBridge(context: Context, webView: WebView) {
    private val context = context.applicationContext
    private val view = WeakReference(webView)
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private data class Transfer(val token: String, val source: String, val disposition: String?, var mime: String?,
        val file: File, val output: FileOutputStream, var bytes: Long = 0, var touched: Long = System.currentTimeMillis())
    private val transfers = mutableMapOf<String, Transfer>()
    private var closed = false
    private val expiry = object : Runnable {
        override fun run() {
            synchronized(this@PhormiBlobDownloadBridge) {
                transfers.values.filter { System.currentTimeMillis() - it.touched > 60_000L }.map { it.token }.forEach { fail(it, "The page stopped providing download data.") }
                if (transfers.isNotEmpty() && !closed) main.postDelayed(this, 10_000L)
            }
        }
    }

    @Synchronized fun start(source: String, disposition: String?, mime: String?) {
        if (closed || transfers.size >= 2) { toast("Finish the current page download first."); return }
        val token = UUID.randomUUID().toString()
        val directory = File(context.cacheDir, "page_downloads").apply { mkdirs() }
        val file = File(directory, "$token.part")
        val transfer = try { Transfer(token, source, disposition, mime, file, FileOutputStream(file)) }
            catch (_: Exception) { toast("Not enough storage to prepare this download."); return }
        transfers[token] = transfer
        main.removeCallbacks(expiry); main.postDelayed(expiry, 10_000L)
        val script = """
            (async function(){
              var token=${JSONObject.quote(token)}, sink=window.__phormiBlobSink;
              try {
                var response=await fetch(${JSONObject.quote(source)});
                if(!response.ok)throw new Error('Page data could not be read');
                var blob=await response.blob();
                if(blob.size>536870912)throw new Error('Page downloads are limited to 512 MB');
                for(var offset=0;offset<blob.size;offset+=49152){
                  var chunk=blob.slice(offset,offset+49152);
                  var encoded=await new Promise(function(resolve,reject){var reader=new FileReader();reader.onload=function(){resolve(String(reader.result).split(',')[1])};reader.onerror=reject;reader.readAsDataURL(chunk)});
                  if(!sink.append(token,encoded))throw new Error('Download was stopped');
                }
                sink.finish(token,blob.type||'');
              } catch(error){sink.fail(token,'This page did not provide a downloadable file. Streaming/DRM media sources cannot be saved as a blob.');}
            })();
        """.trimIndent()
        view.get()?.evaluateJavascript(script, null)
        toast("Preparing page download…")
    }

    @JavascriptInterface @Synchronized fun append(token: String, data: String): Boolean {
        val transfer = transfers[token] ?: return false
        if (closed || data.length > 70_000 || transfer.bytes > 512L * 1024 * 1024) return false
        return try {
            val bytes = Base64.decode(data, Base64.DEFAULT)
            transfer.output.write(bytes); transfer.bytes += bytes.size; transfer.touched = System.currentTimeMillis()
            true
        } catch (_: Exception) { fail(token, "Could not write the page download. Check available storage."); false }
    }

    @JavascriptInterface @Synchronized fun finish(token: String, detectedMime: String) {
        val transfer = transfers.remove(token) ?: return
        runCatching { transfer.output.close() }
        if (detectedMime.isNotBlank()) transfer.mime = detectedMime.take(150)
        worker.execute { publish(transfer) }
    }

    @JavascriptInterface @Synchronized fun fail(token: String, message: String) {
        val transfer = transfers.remove(token) ?: return
        runCatching { transfer.output.close() }; transfer.file.delete()
        toast(message.take(180))
    }

    private fun publish(transfer: Transfer) {
        var outputUri: Uri? = null
        try {
            val info = PhormiDownloadSupport.resolve(transfer.source, transfer.disposition, transfer.mime, null, null, null)
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, info.fileName); put(MediaStore.Downloads.MIME_TYPE, info.mimeType)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS); put(MediaStore.Downloads.IS_PENDING, 1)
                }
                outputUri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw java.io.IOException("No download destination")
                context.contentResolver.openOutputStream(outputUri, "wt")?.use { output -> transfer.file.inputStream().use { it.copyTo(output) } }
                    ?: throw java.io.IOException("Cannot open download destination")
                context.contentResolver.update(outputUri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            } else {
                val directory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).apply { mkdirs() }
                val target = File(directory, info.fileName)
                var file = target; var suffix = 1
                while (!file.createNewFile()) file = File(directory, "${target.nameWithoutExtension} (${suffix++})${target.extension.let { if (it.isBlank()) "" else ".$it" }}")
                outputUri = Uri.fromFile(file)
                transfer.file.inputStream().use { input -> file.outputStream().use { input.copyTo(it) } }
                android.media.MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf(info.mimeType), null)
            }
            PhormiDownloadEngine.recordExternalCompleted(context, transfer.source, info.fileName, info.mimeType,
                info.category, outputUri.toString(), transfer.bytes)
            PhormiNotificationManager.post(context, PhormiNotificationManager.CHANNEL_DOWNLOADS, transfer.token.hashCode(),
                "Download complete", info.fileName, android.content.Intent(context, DownloadsActivity::class.java))
            toast("Download complete: ${info.fileName}")
        } catch (_: Exception) {
            outputUri?.let { uri -> runCatching { if (uri.scheme == "content") context.contentResolver.delete(uri, null, null) else uri.path?.let { File(it).delete() } } }
            toast("Page download failed. Check storage access and available space.")
        } finally { transfer.file.delete() }
    }

    @Synchronized fun close() {
        closed = true; main.removeCallbacks(expiry)
        transfers.values.forEach { runCatching { it.output.close() }; it.file.delete() }
        transfers.clear(); worker.shutdown()
    }
    private fun toast(message: String) { main.post { Toast.makeText(context, message, Toast.LENGTH_LONG).show() } }
}
