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

/** Explicit image generation; requests cancel with their UI and never silently impersonate cloud output. */
object PhormiPollinationsAiEmojiEngine {
    private const val IMAGE_ENDPOINT = "https://gen.pollinations.ai/image/"
    private val executor = Executors.newFixedThreadPool(2)
    private val client = OkHttpClient.Builder()
        .retryOnConnectionFailure(true)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(24, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()
    private val main = Handler(Looper.getMainLooper())

    data class Result(val file: File?, val status: String)
    class Generation {
        private val stopped = java.util.concurrent.atomic.AtomicBoolean(false)
        @Volatile internal var call: okhttp3.Call? = null
        @Volatile internal var future: java.util.concurrent.Future<*>? = null
        internal fun cancelled() = stopped.get()
        fun cancel() { stopped.set(true); call?.cancel(); future?.cancel(true) }
    }
    fun generateAsync(context: Context, prompt: String, variant: Int, onComplete: (Result) -> Unit): Generation {
        val generation = Generation()
        val app = context.applicationContext
        val safePrompt = buildPrompt(prompt)
        generation.future = executor.submit {
            val key = PhormiKeyboardPreferences.pollinationsKey(app)
            val result = try {
                if (generation.cancelled()) return@submit
                if (key.isBlank()) Result(PhormiAiEmojiEngine.generate(app, safePrompt, variant), "Local emoji renderer · no network request")
                else Result(download(safePrompt, variant, app, key, generation), "AI image ready · Pollinations")
            } catch (_: Exception) { Result(null, "Image generation failed. Check your image-service key/network, or clear the key to use the local emoji renderer.") }
            main.post { if (!generation.cancelled()) onComplete(result) }
        }
        return generation
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

    private fun download(prompt: String, variant: Int, context: Context, key: String, generation: Generation): File {
        val encoded = URLEncoder.encode(prompt, Charsets.UTF_8.name()).replace("+", "%20")
        val seed = (prompt.hashCode() and 0x7fffffff) + variant
        val url = "$IMAGE_ENDPOINT$encoded?model=flux&width=512&height=512&safe=true&seed=$seed"
        val request = Request.Builder()
            .url(url)
            .header("Accept", "image/*")
            .header("Authorization", "Bearer $key")
            .header("User-Agent", "Phormi-Keyboard/1.0")
            .build()
        val call = client.newCall(request); generation.call = call
        if (generation.cancelled()) { call.cancel(); throw java.io.IOException("Cancelled") }
        call.execute().use { response ->
            check(response.isSuccessful) { "Pollinations image request failed: ${response.code}" }
            val body = response.body ?: error("Pollinations returned no image")
            val limit = 5L * 1024L * 1024L
            check(body.contentLength() <= limit) { "Generated image is too large" }
            val source = body.source(); source.request(limit + 1)
            check(source.buffer.size <= limit) { "Generated image is too large" }
            val bytes = source.readByteArray()
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            check(options.outWidth in 1..2048 && options.outHeight in 1..2048) { "Invalid image dimensions" }
            options.inJustDecodeBounds = false
            options.inSampleSize = if (maxOf(options.outWidth, options.outHeight) > 1024) 2 else 1
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: error("Invalid generated image")
            val dir = File(context.filesDir, "phormi_stickers").apply { mkdirs() }
            val file = File(dir, "pollinations_aiemoji_${System.currentTimeMillis()}_$variant.png")
            FileOutputStream(file).use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            return file
        }
    }
}
