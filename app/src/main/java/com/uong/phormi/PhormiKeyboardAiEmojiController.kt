package com.uong.phormi

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File

/**
 * Keeps AI Emoji generation off the IME main thread and preserves emoji order.
 * The IME can treat generated files exactly like ordinary emoji/media results.
 */
class PhormiKeyboardAiEmojiController(
    private val context: Context,
    private val onChanged: (List<File>, Boolean) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    private val results = arrayOfNulls<File>(4)
    private var generationId = 0
    private var pending = 0

    fun generate(prompt: String) {
        generationId++
        val id = generationId
        results.fill(null)
        pending = 4
        main.post { onChanged(emptyList(), true) }
        for (variant in 0 until 4) {
            PhormiPollinationsAiEmojiEngine.generateAsync(context, prompt, variant) { file ->
                if (id != generationId) return@generateAsync
                results[variant] = file
                pending--
                if (pending == 0) onChanged(results.filterNotNull(), false)
            }
        }
    }

    fun cancel() {
        generationId++
        pending = 0
        results.fill(null)
    }
}
