package com.uong.phormi

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder
import java.util.concurrent.Executors
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Anonymous Pollinations-backed image generation for Phormi AI Emoji.
 * No API key is embedded. The local renderer remains the offline fallback.
 */
object PhormiPollinationsAiEmojiEngine {
    private const val LEGACY_IMAGE_ENDPOINT = "https://image.pollinations.ai/prompt/"
    private val executor = Executors.newSingleThreadExecutor()
    private val client = OkHttpClient.Builder()
        .retryOnConnectionFailure(true)
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(12, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()
    private val main = Handler(Looper.getMainLooper())

    fun generateAsync(context: Context, prompt: String, variant: Int, onComplete: (File?) -> Unit) {
        val safePrompt = prompt.trim().ifBlank { "expressive emoji, simple emoji icon, transparent-looking clean background" }
        executor.execute {
            val result = runCatching { download(safePrompt, variant, context) }.getOrNull()
                ?: runCatching { PhormiAiEmojiEngine.generate(context, safePrompt, variant) }.getOrNull()
            main.post { onComplete(result) }
        }
    }

    private fun download(prompt: String, variant: Int, context: Context): File {
        val encoded = URLEncoder.encode(prompt, Charsets.UTF_8.name()).replace("+", "%20")
        val seed = (prompt.hashCode() and 0x7fffffff) + variant
        val url = "$LEGACY_IMAGE_ENDPOINT$encoded?model=flux&width=512&height=512&safe=true&seed=$seed"
        val request = Request.Builder()
            .url(url)
            .header("Accept", "image/*")
            .header("User-Agent", "Phormi-Keyboard/1.0")
            .build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Pollinations image request failed: ${response.code}" }
            val body = response.body ?: error("Pollinations returned no image")
            val bytes = body.bytes()
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("Invalid generated image")
            val dir = File(context.filesDir, "phormi_stickers").apply { mkdirs() }
            val file = File(dir, "pollinations_aiemoji_${System.currentTimeMillis()}_$variant.png")
            FileOutputStream(file).use { out -> bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out) }
            bitmap.recycle()
            return file
        }
    }
}
