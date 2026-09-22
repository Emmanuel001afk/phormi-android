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
            val key = PhormiKeyboardPreferences.pollinationsKey(context)
            // One request produces one result. Prefer the remote generator when configured;
            // otherwise (or on failure) use the deterministic local fused-emoji renderer.
            val result = if (key.isNotBlank()) {
                runCatching { download(safePrompt, variant, context, key) }.getOrNull()
                    ?: runCatching { PhormiAiEmojiEngine.generate(context, safePrompt, variant) }.getOrNull()
            } else {
                runCatching { PhormiAiEmojiEngine.generate(context, safePrompt, variant) }.getOrNull()
            }
            main.post { onComplete(result) }
        }
    }

    private fun buildPrompt(context: String): String {
        val sanitized = sanitizeContext(context)
        return "create ONE custom emoji-style reaction from this context: " +
            "${sanitized.take(360)}; keep the familiar visual language of a normal expressive emoji " +
            "(simple face, clear eyes and mouth, bold readable silhouette), infer the strongest emotion, " +
            "then fuse only the most relevant secondary idea into the SAME emoji itself. For example, " +
            "happy + fire should become one happy face with a subtle flame-like glow/crown integrated " +
            "into the head, not a face plus a separate fire emoji. The result should feel immediately " +
            "recognizable as an emoji but have one new expressive twist; do not make a collage, scene, " +
            "sticker sheet, or collection of separate icons. Centered single subject, clean square composition, " +
            "no text, no letters, no words, no UI, no border, no watermark, high readability at tiny size."
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
