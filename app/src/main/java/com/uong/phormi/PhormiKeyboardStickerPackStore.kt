package com.uong.phormi

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Persistent local sticker-pack index. The actual media stays in Phormi's private files. */
object PhormiKeyboardStickerPackStore {
    private const val PREFS = "phormi_sticker_packs"
    private const val KEY = "packs"

    data class Pack(val name: String, val files: List<String>)

    fun add(context: Context, packName: String, file: File) {
        val name = packName.trim().ifBlank { "My Stickers" }
        val packs = read(context).toMutableList()
        val index = packs.indexOfFirst { it.name.equals(name, ignoreCase = true) }
        val updated = if (index >= 0) packs[index].copy(files = (packs[index].files + file.absolutePath).distinct())
        else Pack(name, listOf(file.absolutePath))
        if (index >= 0) packs[index] = updated else packs += updated
        save(context, packs)
    }

    fun packs(context: Context): List<Pack> = read(context)

    fun files(context: Context, pack: Pack): List<File> = pack.files.map(::File).filter { it.exists() && it.length() > 0 }

    private fun read(context: Context): List<Pack> {
        val arr = runCatching { JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]")) }.getOrElse { JSONArray() }
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val files = buildList {
                    val a = o.optJSONArray("files") ?: JSONArray()
                    for (j in 0 until a.length()) a.optString(j).takeIf { it.isNotBlank() }?.let(::add)
                }
                if (files.isNotEmpty()) add(Pack(o.optString("name", "My Stickers"), files))
            }
        }
    }

    private fun save(context: Context, packs: List<Pack>) {
        val arr = JSONArray()
        packs.forEach { pack ->
            arr.put(JSONObject().put("name", pack.name).put("files", JSONArray(pack.files.distinct())))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }
}
