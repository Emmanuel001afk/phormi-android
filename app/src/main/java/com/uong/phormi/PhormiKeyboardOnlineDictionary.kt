package com.uong.phormi

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.inputmethod.EditorInfo
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Optional English completions from Datamuse. The offline dictionary works without this service. */
object PhormiKeyboardOnlineDictionary {
    private const val TTL_MS = 15 * 60 * 1000L
    private const val FAILURE_TTL_MS = 30 * 1000L
    private data class Entry(val words: List<String>, val expiresAt: Long)
    private val cache = object : LinkedHashMap<String, Entry>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean = size > 200
    }
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "Phormi-online-dictionary").apply { isDaemon = true }
    }
    private val sensitiveHint = Regex("(?i)\\b(?:password|passcode|pin|otp|2fa|verification|verify|authentication|auth|sign.?in|sign.?up|log.?in|user.?name|e.?mail|phone|address|card|cvv|security.?code|bank|payment|account|secret|token|api.?key)\\b")
    private var pending: Runnable? = null
    private val requestGeneration = AtomicInteger()
    @Volatile private var lastConnection = "Not checked yet"

    fun status(): String = lastConnection

    /** Even with opt-in, never transmit password, address, email, URL or recognizable sign-in fields. */
    fun allowed(context: Context, locale: Locale, info: EditorInfo?): Boolean {
        if (!PhormiKeyboardPreferences.onlineDictionary(context) || locale.language != "en" ||
            !PhormiKeyboardTextEngine.shouldUsePredictions(info)) return false
        val hints = listOf(info?.hintText, info?.fieldName, info?.privateImeOptions)
            .joinToString(" ") { it?.toString().orEmpty() }
        return !isSensitiveFieldHint(hints)
    }

    internal fun isSensitiveFieldHint(hints: String): Boolean {
        val separated = hints.replace(Regex("([a-z])([A-Z])"), "\$1 \$2").replace('_', ' ')
        return sensitiveHint.containsMatchIn(separated)
    }

    fun cancelPending() {
        synchronized(this) {
            requestGeneration.incrementAndGet()
            pending?.let(main::removeCallbacks)
            pending = null
        }
    }

    fun cached(prefix: String, locale: Locale): List<String> {
        val key = key(prefix, locale)
        return synchronized(cache) { cache[key]?.takeIf { it.expiresAt > System.currentTimeMillis() }?.words }.orEmpty()
    }

    fun request(context: Context, prefix: String, locale: Locale, info: EditorInfo?) {
        if (!allowed(context, locale, info) || !prefix.matches(Regex("[a-zA-Z]{3,24}"))) return
        val normalized = prefix.lowercase(Locale.US)
        val k = key(normalized, locale)
        if (synchronized(cache) { cache[k]?.expiresAt?.let { it > System.currentTimeMillis() } == true }) return
        synchronized(this) {
            pending?.let(main::removeCallbacks)
            val generation = requestGeneration.incrementAndGet()
            val task = Runnable {
                val current = PhormiKeyboardServiceV2.activeInstance()?.currentInputEditorInfo
                if (current != null && info != null && current.packageName == info.packageName &&
                    current.fieldId == info.fieldId && current.fieldName == info.fieldName &&
                    current.inputType == info.inputType && allowed(context, locale, current)) {
                    worker.execute {
                        if (generation == requestGeneration.get() &&
                            PhormiKeyboardPreferences.onlineDictionary(context)) fetch(normalized, k)
                    }
                }
            }
            pending = task
            main.postDelayed(task, 350L)
        }
    }

    private fun fetch(prefix: String, key: String) {
        var connection: HttpURLConnection? = null
        val words = try {
            // Only an English word prefix (never the sentence or surrounding page text)
            // leaves the device. No arbitrary URL or redirect can be supplied by a page.
            connection = (URL("https://api.datamuse.com/sug?s=$prefix&max=8").openConnection() as HttpURLConnection).apply {
                connectTimeout = 1800
                readTimeout = 1800
                instanceFollowRedirects = false
                setRequestProperty("Accept", "application/json")
            }
            if (connection.responseCode !in 200..299) error("Dictionary unavailable")
            val body = connection.inputStream.bufferedReader().use { it.readText().take(16_384) }
            parseWords(body)
        } catch (_: Exception) { null } finally { connection?.disconnect() }
        synchronized(cache) {
            cache[key] = Entry(words.orEmpty(), System.currentTimeMillis() + if (words == null) FAILURE_TTL_MS else TTL_MS)
        }
        lastConnection = if (words == null) "Unavailable · using offline words" else "Connected · Datamuse"
        if (words != null) main.post {
            PhormiKeyboardServiceV2.activeInstance()?.onOnlineDictionarySuggestions(prefix)
        }
    }

    internal fun parseWords(json: String): List<String> {
        val array = JSONArray(json)
        return (0 until array.length()).mapNotNull { i ->
            array.optJSONObject(i)?.optString("word")?.trim()?.takeIf {
                it.matches(Regex("[A-Za-z][A-Za-z'-]{1,31}"))
            }
        }.distinct().take(6)
    }

    private fun key(prefix: String, locale: Locale): String = "${locale.language}|${prefix.lowercase(locale)}"
}
