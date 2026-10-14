package com.uong.phormi

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/** Generated search pages need their own durable address/content; WebView history is not their HTML. */
internal object PhormiSearchPages {
    const val BASE = "https://phormi.local/"
    private val worker = Executors.newSingleThreadExecutor()
    private val lock = Any()
    fun address(query: String): String = "phormi://search?q=" + java.net.URLEncoder.encode(query.take(2000), "UTF-8")
    fun query(address: String?): String? = runCatching {
        val uri = java.net.URI(address ?: return@runCatching null)
        if (!uri.scheme.equals("phormi", true) || !uri.host.equals("search", true)) return@runCatching null
        val value = uri.rawQuery?.split('&')?.firstOrNull { it.startsWith("q=") }?.substringAfter('=') ?: return@runCatching null
        java.net.URLDecoder.decode(value, "UTF-8").takeIf { it.isNotBlank() }?.take(2000)
    }.getOrNull()
    fun generated(address: String?): Boolean = address?.startsWith(BASE, true) == true
    private fun file(context: Context, id: Int): AtomicFile = AtomicFile(File(File(context.filesDir, "search_pages").apply { mkdirs() }, "$id.json"))
    fun read(context: Context, id: Int, address: String): String? = synchronized(lock) {
        runCatching {
            val file = file(context, id)
            if (file.baseFile.length() > 1_000_000) return@runCatching null
            val json = JSONObject(file.openRead().bufferedReader().use { it.readText() })
            if (json.optString("address") != address) return@runCatching null
            json.optString("html").takeIf { it.isNotBlank() }
        }.getOrNull()
    }
    fun save(context: Context, id: Int, address: String, html: String): java.util.concurrent.Future<*> = worker.submit {
        if (html.length > 200_000) return@submit
        synchronized(lock) {
            val file = file(context.applicationContext, id)
            var output: java.io.FileOutputStream? = null
            try {
                output = file.startWrite()
                output.write(JSONObject().put("address", address).put("html", html).toString().toByteArray(Charsets.UTF_8))
                file.finishWrite(output)
            } catch (_: Exception) { file.failWrite(output) }
        }
    }
    fun delete(context: Context, id: Int) { worker.execute { synchronized(lock) { file(context.applicationContext, id).delete() } } }
}
