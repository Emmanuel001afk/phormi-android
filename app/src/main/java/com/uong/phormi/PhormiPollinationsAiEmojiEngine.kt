package com.uong.phormi

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request

/** Contextual custom-emoji pipeline: local fallback plus authenticated Pollinations generation. */
object PhormiPollinationsAiEmojiEngine {
    private const val IMAGE_ENDPOINT = "https://gen.pollinations.ai/image/"
    private val executor = Executors.newSingleThreadExecutor()
    private val client = OkHttpClient.Builder()
        .retryOnConnectionFailure(true)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(24, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()
    private val main = Handler(Looper.getMainLooper())

    fun generateAsync(context: Context, prompt: String, variant: Int, onComplete: (File?) -> Unit) {
        val safePrompt = buildPrompt(prompt)
        executor.execute {
            val local = runCatching { PhormiAiEmojiEngine.generate(context, safePrompt, variant) }.getOrNull()
            if (local != null) main.post { onComplete(local) }

            val key = PhormiKeyboardPreferences.pollinationsKey(context)
            if (key.isBlank()) return@execute

            val remote = runCatching { download(safePrompt, variant + 1, context, key) }.getOrNull()
            if (remote != null) main.post { onComplete(remote) }
        }
    }

    private fun buildPrompt(context: String): String {
        val sanitized = sanitizeContext(context)
        return "one original custom emoji-style reaction fused from the whole context: " +
            "${sanitized.take(360)}; infer the strongest emotion and important situational concepts " +
            "and combine them into ONE coherent expressive icon; secondary concepts must be visibly " +
            "integrated into the face or main symbol, never placed as separate emoji; create a distinctive " +
            "new emoji design, not a copy of any standard Unicode or platform emoji; centered isolated " +
            "subject, simple bold high-quality emoji aesthetic, clean uncluttered background, square " +
            "composition, no text, no letters, no words, no UI, no border, no watermark, one unified reaction, " +
            "high readability at tiny size"
    }

    private fun sanitizeContext(value: String): String = value
        .replace(Regex("https?://\\S+", RegexOption.IGNORE_CASE), " [link] ")
        .replace(Regex("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", RegexOption.IGNORE_CASE), " [email] ")
        .replace(Regex("\\b(?:\\d[ -]?){7,18}\\b"), " [number] ")
        .replace(Regex("(?i)\\b(?:api[_ -]?key|access[_ -]?token|refresh[_ -]?token|bearer|password|secret)\\s*[:=]?\\s*\\S+"), " [private-value] ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun download(prompt: String, variant: Int, context: Context, key: String): File {
        val encoded = URLEncoder.encode(prompt, Charsets.UTF_8.name()).replace("+", "%20")
        val seed = (prompt.hashCode() and 0x7fffffff) + variant
        val url = "$IMAGE_ENDPOINT$encoded?model=flux&width=512&height=512&safe=true&seed=$seed"
        val request = Request.Builder()
            .url(url)
            .header("Accept", "image/*")
            .header("Authorization", "Bearer $key")
            .header("User-Agent", "Phormi-Keyboard/1.0")
            .build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Pollinations image request failed: ${response.code}" }
            val body = response.body ?: error("Pollinations returned no image")
            val bytes = body.bytes()
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("Invalid generated image")
            val dir = File(context.filesDir, "phormi_stickers").apply { mkdirs() }
            val file = File(dir, "pollinations_aiemoji_${System.currentTimeMillis()}_$variant.png")
            FileOutputStream(file).use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            return file
        }
    }
}
