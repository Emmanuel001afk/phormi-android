package com.uong.phormi

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Executors

/** Optional online completion. Ordinary typing remains offline unless explicitly enabled. */
object PhormiKeyboardDictionaryApi {
    private val main = Handler(Looper.getMainLooper())
    private val workers = Executors.newFixedThreadPool(2)
    private val pending = mutableSetOf<String>()
    private val cache = object : LinkedHashMap<String, List<String>>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<String>>?) = size > 128
    }
    private val failedUntil = mutableMapOf<String, Long>()
    fun expandedWords(locale: Locale): List<String> = PhormiKeyboardLanguageData.words(locale)

    fun suggestionsFor(prefix: String, locale: Locale): List<String> {
        val p = prefix.trim().lowercase(locale)
        val context = PhormiKeyboardServiceV2.activeInstance()
        val remote = if (context != null && PhormiKeyboardPreferences.onlineSuggestions(context))
            synchronized(cache) { cache["${locale.language}|$p"].orEmpty() } else emptyList()
        return (remote + expandedWords(locale).filter { it.startsWith(p) }).filterNot { it.equals(p, true) }.distinct().take(6)
    }

    fun request(context: Context, prefix: String, locale: Locale) {
        if (!PhormiKeyboardPreferences.onlineSuggestions(context)) return
        val app = context.applicationContext
        val p = prefix.trim().lowercase(locale)
        if (locale.language != "en" || p.length !in 2..32 || !p.all { it.isLetter() || it == '\'' }) return
        val key = "en|$p"
        synchronized(cache) {
            if (cache.containsKey(key) || key in pending || pending.size >= 8 || (failedUntil[key] ?: 0) > System.currentTimeMillis()) return
            val disk = loadDisk(app, p, locale)
            if (disk.isNotEmpty()) { cache[key] = disk; return }
            pending.add(key)
        }
        workers.execute {
            val words = if (!PhormiKeyboardPreferences.onlineSuggestions(app)) emptyList() else fetch(p)
            synchronized(cache) {
                pending.remove(key)
                if (words.isNotEmpty() && PhormiKeyboardPreferences.onlineSuggestions(app)) {
                    cache[key] = words
                    val json = JSONObject()
                    cache.forEach { (k, v) -> json.put(k, JSONArray(v)) }
                    app.getSharedPreferences("phormi_keyboard_dict_cache", Context.MODE_PRIVATE).edit().putString("bounded_cache", json.toString()).apply()
                } else {
                    if (failedUntil.size >= 128) failedUntil.clear()
                    failedUntil[key] = System.currentTimeMillis() + 60_000
                }
            }
            main.post {
                if (PhormiKeyboardPreferences.onlineSuggestions(app)) PhormiKeyboardServiceV2.activeInstance()?.onSpellCheckerSuggestions(p)
            }
        }
    }

    private fun fetch(prefix: String): List<String> {
        var connection: HttpURLConnection? = null
        return try {
            connection = URL("https://api.datamuse.com/words?sp=${URLEncoder.encode(prefix, "UTF-8")}*&max=7").openConnection() as HttpURLConnection
            connection.connectTimeout = 2500; connection.readTimeout = 2500
            connection.setRequestProperty("User-Agent", "PhormiKeyboard/1.0")
            if (connection.responseCode != 200) emptyList() else {
                val text = connection.inputStream.bufferedReader().use { it.readText().take(32_768) }
                val array = JSONArray(text)
                (0 until array.length()).mapNotNull { array.optJSONObject(it)?.optString("word") }
                    .filter { it.length in 2..40 && !it.equals(prefix, true) }.take(7)
            }
        } catch (_: Exception) { emptyList() } finally { connection?.disconnect() }
    }

    fun loadDisk(context: Context, prefix: String, locale: Locale): List<String> = runCatching {
        val raw = context.getSharedPreferences("phormi_keyboard_dict_cache", Context.MODE_PRIVATE).getString("bounded_cache", "{}")
        val array = JSONObject(raw ?: "{}").optJSONArray("${locale.language}|${prefix.lowercase(locale)}") ?: return emptyList()
        (0 until array.length()).map { array.getString(it) }
    }.getOrDefault(emptyList())
}
