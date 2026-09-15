package com.uong.phormi

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * Portable, privacy-safe browsing-state format for Phormi.
 * A capsule contains URLs/titles and user notes only; it never contains cookies,
 * WebView databases, POST bodies, passwords, or page contents.
 */
object PhormiCapsuleStore {
    private const val DIRECTORY = "phormi_capsules"
    private const val PREFIX = "capsule_"
    private const val SUFFIX = ".phormicapsule.json"
    private const val VERSION = 1
    private const val MAX_TABS = 50
    private const val MAX_NOTE = 1000
    private const val MAX_TITLE = 300
    private const val MAX_URL = 4096

    data class Tab(val url: String, val title: String)
    data class Capsule(
        val id: String,
        val createdAt: Long,
        val note: String,
        val activeIndex: Int,
        val tabs: List<Tab>
    )

    fun capture(context: Context, note: String = ""): Capsule? {
        val prefs = context.getSharedPreferences("phormi_tabs", Context.MODE_PRIVATE)
        val urls = readStringArray(prefs.getString("tab_urls", "[]"))
        val titles = readStringArray(prefs.getString("tab_titles", "[]"))
        val tabs = urls.mapIndexedNotNull { index, raw ->
            val url = normalizeUrl(raw) ?: return@mapIndexedNotNull null
            Tab(url, titles.getOrNull(index).orEmpty().take(MAX_TITLE))
        }.take(MAX_TABS)
        if (tabs.isEmpty()) return null

        val active = prefs.getInt("active_index", 0).coerceIn(0, tabs.lastIndex)
        val capsule = Capsule(
            id = "${System.currentTimeMillis()}_${tabs.size}",
            createdAt = System.currentTimeMillis(),
            note = note.trim().take(MAX_NOTE),
            activeIndex = active,
            tabs = tabs
        )
        write(context, capsule)
        return capsule
    }

    fun list(context: Context): List<Capsule> = directory(context).listFiles()
        ?.mapNotNull { read(it) }
        ?.sortedByDescending { it.createdAt }
        .orEmpty()

    fun read(file: File): Capsule? = runCatching {
        val root = JSONObject(file.readText())
        if (root.optInt("version", -1) != VERSION) return null
        val array = root.optJSONArray("tabs") ?: return null
        val tabs = buildList {
            for (i in 0 until minOf(array.length(), MAX_TABS)) {
                val item = array.optJSONObject(i) ?: continue
                val url = normalizeUrl(item.optString("url")) ?: continue
                add(Tab(url, item.optString("title").take(MAX_TITLE)))
            }
        }
        if (tabs.isEmpty()) return null
        Capsule(
            id = file.name.removePrefix(PREFIX).removeSuffix(SUFFIX),
            createdAt = root.optLong("createdAt", file.lastModified()),
            note = root.optString("note").take(MAX_NOTE),
            activeIndex = root.optInt("activeIndex", 0).coerceIn(0, tabs.lastIndex),
            tabs = tabs
        )
    }.getOrNull()

    fun exportJson(capsule: Capsule): String = toJson(capsule).toString(2)

    fun importJson(context: Context, json: String): Capsule? = runCatching {
        val root = JSONObject(json)
        if (root.optInt("version", -1) != VERSION) return null
        val array = root.optJSONArray("tabs") ?: return null
        val tabs = buildList {
            for (i in 0 until minOf(array.length(), MAX_TABS)) {
                val item = array.optJSONObject(i) ?: continue
                val url = normalizeUrl(item.optString("url")) ?: continue
                add(Tab(url, item.optString("title").take(MAX_TITLE)))
            }
        }
        if (tabs.isEmpty()) return null
        val capsule = Capsule(
            id = "${System.currentTimeMillis()}_${tabs.size}_imported",
            createdAt = System.currentTimeMillis(),
            note = root.optString("note").take(MAX_NOTE),
            activeIndex = root.optInt("activeIndex", 0).coerceIn(0, tabs.lastIndex),
            tabs = tabs
        )
        write(context, capsule)
        capsule
    }.getOrNull()

    fun delete(context: Context, capsule: Capsule) {
        File(directory(context), PREFIX + capsule.id + SUFFIX).delete()
    }

    fun suggestedFileName(capsule: Capsule): String =
        "phormi-capsule-${capsule.id}.phormicapsule.json"

    private fun write(context: Context, capsule: Capsule) {
        val dir = directory(context)
        File(dir, PREFIX + capsule.id + SUFFIX).writeText(toJson(capsule).toString())
    }

    private fun toJson(capsule: Capsule): JSONObject = JSONObject().apply {
        put("format", "Phormi Capsule")
        put("version", VERSION)
        put("createdAt", capsule.createdAt)
        put("note", capsule.note)
        put("activeIndex", capsule.activeIndex)
        put("tabs", JSONArray().apply {
            capsule.tabs.forEach { tab ->
                put(JSONObject().put("url", tab.url.take(MAX_URL)).put("title", tab.title.take(MAX_TITLE)))
            }
        })
    }

    private fun directory(context: Context): File = File(context.filesDir, DIRECTORY).apply { mkdirs() }

    private fun readStringArray(raw: String?): List<String> = runCatching {
        val array = JSONArray(raw ?: "[]")
        buildList { for (i in 0 until minOf(array.length(), MAX_TABS)) add(array.optString(i)) }
    }.getOrDefault(emptyList())

    private fun normalizeUrl(raw: String): String? {
        val url = raw.trim().take(MAX_URL)
        val lower = url.lowercase(Locale.US)
        return if ((lower.startsWith("https://") || lower.startsWith("http://")) &&
            !lower.contains("\n") && !lower.contains("\r")) url else null
    }
}
