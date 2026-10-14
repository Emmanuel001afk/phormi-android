package com.uong.phormi

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File

/** Generates one integrated reaction image for the live Phormi emoji rail. */
class PhormiKeyboardAiEmojiController(
    private val context: Context,
    private val onChanged: (List<File>, Boolean, String) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    private var generationId = 0
    private var request: PhormiPollinationsAiEmojiEngine.Generation? = null

    fun generate(prompt: String) {
        request?.cancel()
        val id = ++generationId
        main.post { onChanged(emptyList(), true, "Creating emoji…") }
        request = PhormiPollinationsAiEmojiEngine.generateAsync(context, prompt, 0) { result ->
            if (id != generationId) return@generateAsync
            onChanged(result.file?.let(::listOf).orEmpty(), false, result.status)
        }
    }
    fun cancel() { generationId++; request?.cancel(); request = null }
}
