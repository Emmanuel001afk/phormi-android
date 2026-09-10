package com.uong.phormi

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import dev.ffmpegkit.llama.Llama
import dev.ffmpegkit.llama.LlamaConfig
import java.io.File

/**
 * Optional on-device text generation for Phormi.
 *
 * No API key, cloud endpoint, or per-request service is required. The user supplies a
 * GGUF model once; inference then stays on the device. The model is deliberately not
 * bundled in the APK because even the small recommended model is hundreds of MB.
 */
object PhormiLocalTextAi {
    private const val MODEL_DIR = "phormi_models"
    private const val MODEL_NAME = "phormi-text.gguf"
    private const val CONTEXT = 2048

    @Volatile private var loadedPath: String? = null
    @Volatile private var loadedModel: Any? = null

    fun modelFile(context: Context): File = File(context.filesDir, "$MODEL_DIR/$MODEL_NAME")
    fun hasModel(context: Context): Boolean = modelFile(context).let { it.exists() && it.length() > 10_000_000L }

    suspend fun generate(
        context: Context,
        prompt: String,
        systemPrompt: String = "You are Phormi's private on-device assistant. Be concise, useful and honest."
    ): Result<String> = withContext(Dispatchers.Default) {
        if (prompt.isBlank()) return@withContext Result.failure(IllegalArgumentException("Prompt is empty."))
        val file = modelFile(context)
        if (!hasModel(context)) return@withContext Result.failure(IllegalStateException("No local GGUF model installed."))

        runCatching {
            val model = ensureLoaded(file)
            val result = Llama.complete(
                model = model,
                prompt = prompt.take(8000),
                systemPrompt = systemPrompt.take(2000),
                maxTokens = 256
            )
            result.text.trim()
        }
    }

    suspend fun installFromUri(context: Context, uri: android.net.Uri): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(context.filesDir, MODEL_DIR).apply { mkdirs() }
            val target = modelFile(context)
            val temp = File(dir, "$MODEL_NAME.tmp")
            context.contentResolver.openInputStream(uri)?.use { input ->
                temp.outputStream().use { output -> input.copyTo(output, 1024 * 1024) }
            } ?: error("Unable to open selected model")
            if (temp.length() < 10_000_000L) error("Selected file is too small to be a GGUF model")
            if (target.exists()) target.delete()
            if (!temp.renameTo(target)) error("Unable to finalize model file")
            synchronized(this@PhormiLocalTextAi) {
                if (loadedPath != target.absolutePath) {
                    releaseLocked()
                }
            }
            target
        }
    }

    fun removeModel(context: Context) {
        synchronized(this) { releaseLocked(); modelFile(context).delete() }
    }

    fun release() {
        synchronized(this) { releaseLocked() }
    }

    private fun ensureLoaded(file: File): Any {
        synchronized(this) {
            if (loadedPath == file.absolutePath && loadedModel != null) return loadedModel!!
            releaseLocked()
            val model = Llama.loadModel(
                modelPath = file.absolutePath,
                config = LlamaConfig(contextSize = CONTEXT, threads = maxOf(2, Runtime.getRuntime().availableProcessors() / 2))
            )
            loadedPath = file.absolutePath
            loadedModel = model
            return model
        }
    }

    private fun releaseLocked() {
        val model = loadedModel
        if (model != null) runCatching { Llama.releaseModel(model) }
        loadedModel = null
        loadedPath = null
    }
}
