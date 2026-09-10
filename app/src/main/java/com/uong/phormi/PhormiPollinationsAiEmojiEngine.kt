package com.uong.phormi

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder
import java.util.concurrent.Executors
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Pollinations-backed AI Emoji image generation.
 *
 * No API key is embedded in Phormi. We use Pollinations' public image URL form and
 * keep the existing local renderer as a fallback when anonymous generation is
 * unavailable, rate-limited, or offline.
 */
object PhormiPollinationsAiEmojiEngine {
    private const val LEGACY_IMAGE_ENDPOINT = "https://image.pollinations.ai/prompt/"
    private val executor = Executors.newCachedThreadPool()
    private val client = OkHttpClient.Builder().retryOnConnectionFailure(true).build()

    fun generateAsync(
        context: Context,
        prompt: String,
        variant: Int,
        onComplete: (File?) -> Unit
    ) {
        val safePrompt = prompt.trim().ifBlank { "expressive emoji sticker" }
        executor.execute {
            val result = runCatching { download(safePrompt, variant, context) }.getOrNull()
                ?: runCatching { PhormiAiEmojiEngine.generate(context, safePrompt, variant) }.getOrNull()
            android.os.Handler(android.os.Looper.getMainLooper()).post { onComplete(result) }
        }
    }

    private fun download(prompt: String, variant: Int, context: Context): File {
        val encoded = URLEncoder.encode(prompt, Charsets.UTF_8.name()).replace("+", "%20")
        val url = "$LEGACY_IMAGE_ENDPOINT$encoded?model=flux&width=512&height=512&nologo=true&safe=true&seed=${variant + (prompt.hashCode() and 0x7fffffff)}"
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
            FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
            bitmap.recycle()
            return file
        }
    }
}
