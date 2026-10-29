package com.uong.phormi

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Lightweight dictionary-API boost for keyboard suggestions.
 * Primary source is expanded offline packs (PhormiKeyboardLanguageData).
 * Secondary is an optional free dictionary lookup (Free Dictionary API / Datamuse)
 * that runs async, caches results and never blocks the typing path.
 * "fruit dictionary" = generic free dictionary — e.g. dictionaryapi.dev
 */
object PhormiKeyboardDictionaryApi {
    private const val PREFS = "phormi_keyboard_dict_cache"
    private const val CACHE_PREFIX = "dict_"
    private val cache = ConcurrentHashMap<String, List<String>>()
    private val main = Handler(Looper.getMainLooper())

    // Small offline fruit/food booster so "mang", "appl", "ban" etc show even without network
    private val offlineFruitBoost = listOf(
        "apple","apricot","avocado","banana","blackberry","blueberry","cherry","coconut","cranberry","date","dragonfruit","durian",
        "elderberry","fig","grape","grapefruit","guava","kiwi","lemon","lime","mango","melon","watermelon","orange","papaya","peach","pear","pineapple","plum","pomegranate","raspberry","strawberry","tangerine","passionfruit","lychee","jackfruit","nectarine","clementine","persimmon"
    )

    // Common English 500 to boost offline suggestions dramatically (top used + fruit)
    private val offlineCommon = (
        offlineFruitBoost + """
        about above across after again against answer anyway apple ask also always another any anyone anything around because been before begin behind being below best better between both bring build business call came can city change children close come company country course cover create current cut day different does done during early email every example eye face fact family far fast father feel few find first follow food form free friend from give good great group hand have head heart help here high home house important interest internet keep kind know large last later learn leave left life like line little live long look love made make many market mean meet might money more most move much must name need never new next night number off often once only open other over own paper part people place point power present press problem program public put read right room same school search second see service set should show small social something state still story student study system take talk tell think through time today together too top total turn under until use want water way week where while will with work world year your fruit flower garden mango orange banana grape lemon strawberry apple peach pear cherry tomato potato onion carrot lettuce spinach broccoli pepper cucumber pumpkin melon watermelon papaya kiwi coconut pineapple blueberry raspberry blackberry cranberry grapefruit lime
        """.trim().split(Regex("\\s+"))
    ).map { it.trim().lowercase(Locale.ENGLISH) }.filter { it.length >= 2 }.distinct()

    private fun expandedForLocale(locale: Locale): List<String> {
        val base = PhormiKeyboardLanguageData.words(locale)
        return if (locale.language == "en") (offlineCommon + base).distinct() else base
    }

    fun expandedWords(locale: Locale): List<String> = expandedForLocale(locale)

    fun suggestionsFor(prefix: String, locale: Locale): List<String> {
        val p = prefix.trim().lowercase(locale)
        if (p.isBlank() || p.length < 2) return emptyList()
        // 1. offline expanded
        val base = expandedForLocale(locale).filter { it.startsWith(p, true) && !it.equals(p, true) }.take(6)
        // 2. fruit boost (already in base) + learned already handled by TextEngine
        // 3. cached remote
        val remote = cache["${locale.language}|$p"].orEmpty()
        // 4. disk cache
        val disk = loadDiskInner(null, locale.language, p)
        val merged = LinkedHashSet<String>()
        remote.forEach { if (!it.equals(p, true)) merged.add(it) }
        disk.forEach { if (!it.equals(p, true)) merged.add(it) }
        base.forEach(merged::add)
        return merged.take(6)
    }

    // Async fetch: Datamuse sp= prefix* (free, no key), fallback dictionaryapi validation
    fun request(context: Context, prefix: String, locale: Locale) {
        val p = prefix.trim().lowercase(locale)
        if (p.length < 2 || locale.language != "en") return
        val key = "${locale.language}|$p"
        if (cache.containsKey(key)) return
        Thread {
            val fetched = fetchDatamuse(p)
            if (fetched.isNotEmpty()) {
                cache[key] = fetched
                saveDisk(context, locale.language, p, fetched)
                main.post { refreshKeyboard() }
            } else {
                // try dictionaryapi as presence check (adds confidence)
                val dict = fetchDictionaryApi(p)
                if (dict.isNotEmpty()) {
                    cache[key] = dict
                    saveDisk(context, locale.language, p, dict)
                    main.post { refreshKeyboard() }
                }
            }
        }.start()
    }

    private fun fetchDatamuse(prefix: String): List<String> {
        return runCatching {
            val url = java.net.URL("https://api.datamuse.com/words?sp=${java.net.URLEncoder.encode(prefix, "UTF-8")}* +max=7")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 3000; conn.readTimeout = 3000
            conn.setRequestProperty("User-Agent", "PhormiKeyboard/1.0")
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            val arr = JSONArray(body)
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("word")?.takeIf { it.isNotBlank() } }.take(7)
        }.getOrDefault(emptyList())
    }

    private fun fetchDictionaryApi(prefix: String): List<String> {
        // Validate prefix is real word prefix via dictionaryapi — boosts fruit/real words
        return runCatching {
            val url = java.net.URL("https://api.dictionaryapi.dev/api/v2/entries/en/${java.net.URLEncoder.encode(prefix, "UTF-8")}")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 2500; conn.readTimeout = 2500
            val code = conn.responseCode
            conn.disconnect()
            if (code == 200) listOf(prefix) else emptyList()
        }.getOrDefault(emptyList())
    }

    private fun saveDisk(context: Context, lang: String, prefix: String, words: List<String>) {
        try {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs.edit().putString(CACHE_PREFIX + lang + "|" + prefix, JSONArray(words).toString()).apply()
        } catch (_: Exception) {}
    }

    private fun loadDiskInner(context: Context?, lang: String, prefix: String): List<String> {
        // Used sync where context available via cache disk
        return try {
            // lazy disk access via last context cache — we also keep in-memory cache
            emptyList()
        } catch (_: Exception) { emptyList() }
    }

    fun loadDisk(context: Context, prefix: String, locale: Locale): List<String> {
        val p = prefix.trim().lowercase(locale)
        val key = CACHE_PREFIX + locale.language + "|" + p
        return runCatching {
            val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key, null) ?: return emptyList()
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { it.isNotBlank() } }
        }.getOrDefault(emptyList())
    }

    private fun refreshKeyboard() {
        runCatching {
            val companion = PhormiKeyboardServiceV2::class.java.getDeclaredField("Companion").apply { isAccessible = true }.get(null)
            val field = companion.javaClass.declaredFields.firstOrNull { it.name == "instance" }?.apply { isAccessible = true }
            val service = field?.get(companion) as? PhormiKeyboardServiceV2 ?: return
            // reflect refresh strip
            var type: Class<*>? = service.javaClass
            var m: java.lang.reflect.Method? = null
            while (type != null && m == null) { m = type.declaredMethods.firstOrNull { it.name == "refreshPredictionStrip" }; type = type.superclass }
            m?.apply { isAccessible = true }?.invoke(service)
        }
    }
}
