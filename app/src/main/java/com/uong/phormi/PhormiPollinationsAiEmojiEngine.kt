package com.uong.phormi

import android.content.Context
import android.graphics.Bitmap
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

/** Pollinations-backed emoji generation with a local fallback and no embedded API key. */
object PhormiPollinationsAiEmojiEngine {
    private const val LEGACY_IMAGE_ENDPOINT = "https://image.pollinations.ai/prompt/"
    private val executor = Executors.newSingleThreadExecutor()
    private val client = OkHttpClient.Builder().retryOnConnectionFailure(true).connectTimeout(12,TimeUnit.SECONDS).readTimeout(25,TimeUnit.SECONDS).writeTimeout(12,TimeUnit.SECONDS).callTimeout(30,TimeUnit.SECONDS).build()
    private val main = Handler(Looper.getMainLooper())

    fun generateAsync(context: Context, prompt: String, variant: Int, onComplete: (File?) -> Unit) {
        val safePrompt = buildPrompt(prompt)
        executor.execute {
            val result = runCatching { download(safePrompt, variant, context) }.getOrNull()
                ?: runCatching { PhormiAiEmojiEngine.generate(context, safePrompt, variant) }.getOrNull()
            main.post { onComplete(result) }
        }
    }

    private fun buildPrompt(context: String): String = "single centered emoji-style icon expressing: ${context.trim().take(180)}; one subject only, simple bold expressive design, clean isolated background, no scene, no text, no letters, no words, no watermark, no border, no frame, no UI, compact icon composition, high contrast, face or symbol-like expression, suitable for display at small emoji size"

    private fun download(prompt: String, variant: Int, context: Context): File {
        val encoded=URLEncoder.encode(prompt,Charsets.UTF_8.name()).replace("+","%20")
        val seed=(prompt.hashCode() and 0x7fffffff)+variant
        val url="$LEGACY_IMAGE_ENDPOINT$encoded?model=flux&width=512&height=512&safe=true&seed=$seed"
        val request=Request.Builder().url(url).header("Accept","image/*").header("User-Agent","Phormi-Keyboard/1.0").build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful){"Pollinations image request failed: ${response.code}"}
            val bytes=response.body?.bytes()?:error("Pollinations returned no image")
            val bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.size)?:error("Invalid generated image")
            val normalized=normalize(bitmap); if(normalized!==bitmap) bitmap.recycle()
            val dir=File(context.filesDir,"phormi_stickers").apply{mkdirs()}
            val file=File(dir,"pollinations_aiemoji_${System.currentTimeMillis()}_$variant.png")
            FileOutputStream(file).use{out->normalized.compress(Bitmap.CompressFormat.PNG,100,out)}
            normalized.recycle(); return file
        }
    }

    /** Keep every result square so the keyboard can render it in the same cell as a normal emoji. */
    private fun normalize(source: Bitmap): Bitmap {
        val size=minOf(source.width,source.height).coerceAtLeast(1)
        val left=(source.width-size)/2; val top=(source.height-size)/2
        return if(source.width==size&&source.height==size) source else Bitmap.createBitmap(source,left,top,size,size)
    }
}
