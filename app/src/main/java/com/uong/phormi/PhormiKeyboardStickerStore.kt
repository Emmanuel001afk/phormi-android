package com.uong.phormi

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** Local sticker library. Imported/generated media stays inside Phormi until shared by the user. */
object PhormiKeyboardStickerStore {
    private const val DIR = "phormi_stickers"

    private fun dir(context: Context): File = File(context.filesDir, DIR).apply { mkdirs() }

    fun list(context: Context): List<File> = dir(context).listFiles()
        ?.filter { it.isFile && it.length() > 0 }
        ?.sortedByDescending { it.lastModified() }
        .orEmpty()

    fun import(context: Context, uri: Uri, prefix: String = "sticker"): File? = runCatching {
        val mime = context.contentResolver.getType(uri).orEmpty()
        val ext = when {
            mime.contains("gif") -> "gif"
            mime.contains("webp") -> "webp"
            mime.contains("jpeg") || mime.contains("jpg") -> "jpg"
            else -> "png"
        }
        val out = File(dir(context), "${prefix}_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}.$ext")
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(out).use { output -> input.copyTo(output) }
        } ?: return null
        out
    }.getOrNull()

    fun contentUri(context: Context, file: File): Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    fun delete(context: Context, file: File): Boolean = runCatching { file.delete() }.getOrDefault(false)
}
