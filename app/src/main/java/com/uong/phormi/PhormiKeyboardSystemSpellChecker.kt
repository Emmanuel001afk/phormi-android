package com.uong.phormi

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.inputmethod.CompletionInfo
import android.view.textservice.SuggestionsInfo
import android.view.textservice.SpellCheckerSession
import android.view.textservice.TextInfo
import android.view.textservice.TextServicesManager
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Uses the device's installed spell-checker dictionaries as a multilingual fallback. */
object PhormiKeyboardSystemSpellChecker {
    private val cache = ConcurrentHashMap<String, List<String>>()
    private val main = Handler(Looper.getMainLooper())
    private var session: SpellCheckerSession? = null
    private var sessionLocale: Locale? = null
    private var lastKey = ""

    fun cached(word: String, locale: Locale): List<String> = cache[key(word, locale)].orEmpty()

    @Synchronized fun request(context: Context, word: String, locale: Locale) {
        val clean = word.trim()
        if (clean.length < 2) return
        val k = key(clean, locale)
        if (cache.containsKey(k) && cache[k].orEmpty().isNotEmpty()) return
        val manager = context.getSystemService(Context.TEXT_SERVICES_MANAGER_SERVICE) as? TextServicesManager ?: return
        if (manager.currentSpellCheckerInfo == null) return
        if (session == null || sessionLocale != locale || session?.isSessionDisconnected == true) {
            runCatching { session?.close() }
            session = runCatching { manager.newSpellCheckerSession(null, locale, listener, true) }.getOrNull()
            sessionLocale = locale
        }
        lastKey = k
        runCatching { session?.getSuggestions(TextInfo(clean), 5) }
    }

    private val listener = object : SpellCheckerSession.SpellCheckerSessionListener {
        override fun onGetSuggestions(results: Array<out SuggestionsInfo>?) {
            val result = results?.firstOrNull() ?: return
            val values = buildList {
                for (i in 0 until result.suggestionsCount.coerceAtLeast(0)) result.getSuggestionAt(i)?.takeIf { it.isNotBlank() }?.let(::add)
            }.distinct().take(5)
            if (values.isEmpty()) return
            cache[lastKey] = values
            main.post { refreshKeyboard(values) }
        }
        override fun onGetSentenceSuggestions(results: Array<out android.view.textservice.SentenceSuggestionsInfo>?) = Unit
    }

    private fun refreshKeyboard(values: List<String>) {
        runCatching {
            val companion = PhormiKeyboardServiceV2::class.java.getDeclaredField("Companion").apply { isAccessible = true }.get(null)
            val field = companion.javaClass.declaredFields.firstOrNull { it.name == "instance" }?.apply { isAccessible = true }
            val service = field?.get(companion) as? PhormiKeyboardServiceV2 ?: return
            var type: Class<*>? = service.javaClass
            var completionsField: java.lang.reflect.Field? = null
            while (type != null && completionsField == null) { completionsField = type.declaredFields.firstOrNull { it.name == "completions" }; type = type.superclass }
            completionsField?.apply { isAccessible = true }?.set(service, values.mapIndexed { index, value -> CompletionInfo(-7000L - index, index, value) })
            type = service.javaClass
            var method: java.lang.reflect.Method? = null
            while (type != null && method == null) { method = type.declaredMethods.firstOrNull { it.name == "refreshPredictions" && it.parameterTypes.size == 1 }; type = type.superclass }
            method?.apply { isAccessible = true }?.invoke(service, true)
        }
    }

    private fun key(word: String, locale: Locale): String = locale.toLanguageTag() + "|" + word.lowercase(locale)
}
