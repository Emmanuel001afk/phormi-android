package com.uong.phormi

import android.content.Context
import android.net.Uri
import dev.ffmpegkit.llama.Llama
import dev.ffmpegkit.llama.LlamaConfig
import dev.ffmpegkit.llama.LlamaModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Private on-device text generation. No API key or cloud request is required. */
object PhormiLocalTextAi {
    private const val MODEL_DIR = "phormi_models"
    private const val MODEL_NAME = "phormi-text.gguf"
    private const val CONTEXT = 2048

    @Volatile private var loadedPath: String? = null
    @Volatile private var loadedModel: LlamaModel? = null

    fun modelFile(context: Context): File = File(context.filesDir, "$MODEL_DIR/$MODEL_NAME")
    fun hasModel(context: Context): Boolean = modelFile(context).let { it.exists() && it.length() > 10_000_000L }

    suspend fun generate(context: Context, prompt: String, systemPrompt: String = "You are Phormi's private on-device assistant. Be concise, useful and honest."): Result<String> = withContext(Dispatchers.Default) {
        if (prompt.isBlank()) return@withContext Result.failure(IllegalArgumentException("Prompt is empty."))
        val file = modelFile(context)
        if (!hasModel(context)) return@withContext Result.failure(IllegalStateException("No local GGUF model installed."))
        runCatching {
            val model = ensureLoaded(file)
            Llama.complete(model, prompt.take(8000), systemPrompt.take(2000), 256).text.trim()
        }
    }

    suspend fun installFromUri(context: Context, uri: Uri): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(context.filesDir, MODEL_DIR).apply { mkdirs() }
            val target = modelFile(context)
            val temp = File(dir, "$MODEL_NAME.tmp")
            context.contentResolver.openInputStream(uri)?.use { input -> temp.outputStream().use { output -> input.copyTo(output, 1024 * 1024) } } ?: error("Unable to open selected model")
            if (temp.length() < 10_000_000L) error("Selected file is too small to be a GGUF model")
            synchronized(this@PhormiLocalTextAi) { releaseLocked() }
            if (target.exists()) target.delete()
            if (!temp.renameTo(target)) error("Unable to finalize model file")
            target
        }
    }

    fun removeModel(context: Context) { synchronized(this) { releaseLocked(); modelFile(context).delete() } }
    fun release() { synchronized(this) { releaseLocked() } }

    private suspend fun ensureLoaded(file: File): LlamaModel {
        synchronized(this) { loadedModel?.let { if (loadedPath == file.absolutePath) return it }; releaseLocked() }
        val model = Llama.loadModel(file.absolutePath, LlamaConfig(contextSize = CONTEXT, threads = maxOf(2, Runtime.getRuntime().availableProcessors() / 2)))
        synchronized(this) { loadedPath = file.absolutePath; loadedModel = model; return model }
    }

    private fun releaseLocked() {
        loadedModel?.let { runCatching { Llama.releaseModel(it) } }
        loadedModel = null
        loadedPath = null
    }
}
