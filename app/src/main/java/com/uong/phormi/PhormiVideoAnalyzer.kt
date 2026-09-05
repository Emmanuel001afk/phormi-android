package com.uong.phormi

import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Current-page video analysis pipeline.
 * It discovers HTML5 video metadata, samples visible frames where the page permits seeking,
 * and asks the configured AI provider for a grounded summary. No video is uploaded by this
 * class except the bounded JPEG frames explicitly sent to the configured AI provider.
 */
object PhormiVideoAnalyzer {
    data class Capture(val metadata: JSONObject, val frames: List<String>)

    suspend fun capture(webView: WebView): Capture = withContext(Dispatchers.Main.immediate) {
        val metadata = evaluate(webView, """
            (() => {
              const v = document.querySelector('video');
              const text = (document.body?.innerText || '').slice(0, 12000);
              if (!v) return JSON.stringify({found:false,url:location.href,title:document.title,text:text});
              return JSON.stringify({
                found:true,
                url:location.href,
                title:document.title,
                currentTime:Number(v.currentTime||0),
                duration:Number(v.duration||0),
                src:v.currentSrc||v.src||'',
                poster:v.poster||'',
                paused:!!v.paused,
                text:text
              });
            })()
        """)
        val root = runCatching { JSONObject(metadata) }.getOrElse { JSONObject().put("found", false) }
        if (!root.optBoolean("found", false)) return@withContext Capture(root, emptyList())

        val duration = root.optDouble("duration", 0.0)
        val current = root.optDouble("currentTime", 0.0).coerceAtLeast(0.0)
        val targets = if (duration.isFinite() && duration > 1.0) {
            listOf(current.coerceIn(0.0, duration), (duration * 0.5), (duration * 0.85))
        } else listOf(current)

        val frames = mutableListOf<String>()
        targets.distinct().take(3).forEach { time ->
            evaluate(webView, """(()=>{const v=document.querySelector('video'); if(!v)return false; try{v.pause(); v.currentTime=$time; return true}catch(e){return false}})()""")
            delay(350)
            runCatching { PhormiViewportCapture.toBase64Jpeg(webView, 768, 48) }.getOrNull()?.let { frames += it }
        }
        Capture(root, frames)
    }

    private suspend fun evaluate(webView: WebView, script: String): String = withContext(Dispatchers.Main.immediate) {
        val latch = java.util.concurrent.CountDownLatch(1)
        var result = "{}"
        webView.evaluateJavascript(script) {
            result = runCatching { org.json.JSONTokener(it.orEmpty()).nextValue().toString() }.getOrDefault("")
            latch.countDown()
        }
        withContext(Dispatchers.IO) {
            latch.await(4, java.util.concurrent.TimeUnit.SECONDS)
        }
        result
    }
}
