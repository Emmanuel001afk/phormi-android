package com.uong.phormi

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
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
    private val requestKeys = ConcurrentHashMap<Int, String>()
    private val sequence = java.util.concurrent.atomic.AtomicInteger()
    private var pendingRequest: Runnable? = null

    fun cached(word: String, locale: Locale): List<String> = cache[key(word, locale)].orEmpty()

    @Synchronized fun request(context: Context, word: String, locale: Locale) {
        val clean = word.trim()
        if (clean.length < 2) return
        val k = key(clean, locale)
        if (cache.containsKey(k) && cache[k].orEmpty().isNotEmpty()) return
        // Keyboard input can call this for every character. Debounce the system spell-checker
        // so its session setup/request never sits directly on the typing path.
        pendingRequest?.let(main::removeCallbacks)
        val task = Runnable { requestNow(context.applicationContext, clean, locale) }
        pendingRequest = task
        main.postDelayed(task, 140L)
    }

    @Synchronized
    private fun requestNow(context: Context, clean: String, locale: Locale) {
        val k = key(clean, locale)
        if (cache.containsKey(k) && cache[k].orEmpty().isNotEmpty()) return
        val manager = context.getSystemService(Context.TEXT_SERVICES_MANAGER_SERVICE) as? TextServicesManager ?: return
        if (Build.VERSION.SDK_INT >= 31 && manager.currentSpellCheckerInfo == null) return
        if (session == null || sessionLocale != locale || session?.isSessionDisconnected == true) {
            runCatching { session?.close() }
            session = runCatching { manager.newSpellCheckerSession(null, locale, listener, true) }.getOrNull()
            sessionLocale = locale
        }
        val id = sequence.incrementAndGet()
        requestKeys[id] = k
        runCatching { session?.getSuggestions(TextInfo(clean, 0, id), 5) }
            .onFailure { requestKeys.remove(id) }
    }

    private val listener = object : SpellCheckerSession.SpellCheckerSessionListener {
        override fun onGetSuggestions(results: Array<out SuggestionsInfo>?) {
            val result = results?.firstOrNull() ?: return
            val values = buildList {
                for (i in 0 until result.suggestionsCount.coerceAtLeast(0)) result.getSuggestionAt(i)?.takeIf { it.isNotBlank() }?.let(::add)
            }.distinct().take(5)
            val key = requestKeys.remove(result.sequence) ?: return
            cache[key] = values
            if (values.isNotEmpty()) main.post { refreshKeyboard(key) }
        }
        override fun onGetSentenceSuggestions(results: Array<out android.view.textservice.SentenceSuggestionsInfo>?) = Unit
    }

    private fun refreshKeyboard(requestedKey: String) {
        val service = PhormiKeyboardServiceV2.activeInstance() ?: return
        service.onSpellCheckerSuggestions(requestedKey.substringAfter('|'))
    }

    private fun key(word: String, locale: Locale): String = locale.toLanguageTag() + "|" + word.lowercase(locale)
}