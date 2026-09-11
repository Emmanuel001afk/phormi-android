package com.uong.phormi

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File

/** Generates one integrated reaction image for the live Phormi emoji rail. */
class PhormiKeyboardAiEmojiController(
    private val context: Context,
    private val onChanged: (List<File>, Boolean) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    private var generationId = 0

    fun generate(prompt: String) {
        generationId++
        val id = generationId
        main.post { onChanged(emptyList(), true) }
        PhormiPollinationsAiEmojiEngine.generateAsync(context, prompt, 0) { file ->
            if (id != generationId) return@generateAsync
            if (file != null) onChanged(listOf(file), false) else onChanged(emptyList(), false)
        }
    }

    fun cancel() {
        generationId++
        onChanged(emptyList(), false)
    }
}