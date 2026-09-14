package com.uong.phormi

import android.content.Context
import android.graphics.BitmapFactory
import java.io.File
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.util.concurrent.Executors

object PhormiContextEmojiEngine {
    private val executor = Executors.newSingleThreadExecutor()
    private val cache = mutableMapOf<String, File>()
    fun expressive(text: String): List<String> {
        val t=text.lowercase()
        return when {
            listOf("furious","enraged","very angry","so angry","hate","mad").any(t::contains)->listOf("😡🔥","🤬🔥💢","😤💥🔥","😡❤️‍🔥")
            listOf("angry","annoyed","irritated").any(t::contains)->listOf("😡💢","😤🔥","🤬💥")
            listOf("extremely happy","so happy","very happy","overjoyed").any(t::contains)->listOf("🥳✨🔥","😄💥✨","😍💫❤️","😁🔥🎉")
            listOf("happy","glad","joy","excited","awesome").any(t::contains)->listOf("😊✨","😄🌟","🥰💖","🤩✨")
            listOf("heartbroken","broken heart").any(t::contains)->listOf("😭💔💧","🥺💔🌧️","😢❤️‍🩹💧")
            listOf("sad","cry","crying","tears","hurt","sorry").any(t::contains)->listOf("😭💧","😢💦💔","🥺💧🌧️","😞💧")
            listOf("laugh","laughing","funny","lol").any(t::contains)->listOf("😂💥","🤣🔥","😆✨","💀😂")
            listOf("love","in love","crush").any(t::contains)->listOf("🥰❤️✨","😍💖","😘💋❤️","❤️‍🔥🥰")
            listOf("wow","amazing","shocked","surprised").any(t::contains)->listOf("🤯💥","😱⚡","😮✨","🤩💫")
            listOf("tired","sleepy","exhausted").any(t::contains)->listOf("🥱💤","😴💤","🫠💧")
            listOf("cool","nice","style").any(t::contains)->listOf("😎🔥","😎✨","🤌🔥")
            t.endsWith("!")->listOf("😊✨","🔥😄","🤩💫")
            t.endsWith("?")->listOf("🤔❓","👀❓","😅🤔")
            else->emptyList()
        }.distinct()
    }
    fun generate(context: Context,text:String,onReady:(File?)->Unit){
        val key=PhormiKeyboardPreferences.pollinationsKey(context).trim(); if(key.isBlank()){onReady(null);return}
        val normalized=text.trim().take(180);if(normalized.isBlank()){onReady(null);return}
        val hash=normalized.lowercase().hashCode().toString();cache[hash]?.takeIf{it.exists()&&it.length()>1000}?.let{onReady(it);return}
        executor.execute{
            val file=runCatching{
                val prompt="Create one unique expressive emoji sticker based on this text: $normalized. Exaggerate the emotion. Emoji-style character, bold readable face, simple clean composition, isolated single sticker, no words, no letters, no watermark, square."
                val encoded=URLEncoder.encode(prompt,"UTF-8");val url=URL("https://gen.pollinations.ai/image/$encoded?model=flux")
                val conn=url.openConnection() as HttpURLConnection;conn.connectTimeout=12000;conn.readTimeout=30000;conn.requestMethod="GET";conn.setRequestProperty("Authorization","Bearer $key")
                if(conn.responseCode !in 200..299)throw IllegalStateException("Pollinations HTTP ${conn.responseCode}")
                val out=File(context.cacheDir,"phormi_ai_emoji_$hash.png");conn.inputStream.use{input->out.outputStream().use{output->input.copyTo(output)}};conn.disconnect()
                if(BitmapFactory.decodeFile(out.absolutePath)==null)throw IllegalStateException("Invalid image");cache[hash]=out;out
            }.getOrNull()
            android.os.Handler(android.os.Looper.getMainLooper()).post{onReady(file)}
        }
    }
}
