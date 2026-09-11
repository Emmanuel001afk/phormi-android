package com.uong.phormi

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.concurrent.Executors

/** Generates one integrated reaction image for the live Phormi emoji rail. */
class PhormiKeyboardAiEmojiController(
    private val context: Context,
    private val onChanged: (List<File>, Boolean) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    private val localExecutor = Executors.newSingleThreadExecutor()
    private var generationId = 0

    fun generate(prompt: String) {
        generationId++
        val id = generationId
        main.post { onChanged(emptyList(), true) }
        localExecutor.execute {
            val local = runCatching { PhormiAiEmojiEngine.generate(context, prompt, 0) }.getOrNull()
            if (id == generationId && local != null) main.post { if (id == generationId) onChanged(listOf(local), true) }
        }
        PhormiPollinationsAiEmojiEngine.generateAsync(context, prompt, 0) { file ->
            if (id != generationId) return@generateAsync
            onChanged(file?.let { listOf(it) }.orEmpty(), false)
        }
    }

    fun cancel() {
        generationId++
        onChanged(emptyList(), false)
    }
}
