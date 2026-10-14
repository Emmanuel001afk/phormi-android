package com.uong.phormi

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.textservice.SpellCheckerSession
import android.view.textservice.SuggestionsInfo
import android.view.textservice.TextInfo
import android.view.textservice.TextServicesManager
import java.util.Locale

/** Debounced device dictionaries, with per-request IDs so late replies cannot corrupt another word. */
object PhormiKeyboardSystemSpellChecker {
    private val cache = linkedMapOf<String, List<String>>()
    private val requests = mutableMapOf<Int, String>()
    private val main = Handler(Looper.getMainLooper())
    private var session: SpellCheckerSession? = null
    private var sessionLocale: Locale? = null
    private var sequence = 0
    private var pending: Runnable? = null

    fun cached(word: String, locale: Locale): List<String> = cache[key(word, locale)].orEmpty()

    fun request(context: Context, word: String, locale: Locale) {
        if (word.length !in 2..64 || cache.containsKey(key(word, locale))) return
        pending?.let(main::removeCallbacks)
        pending = Runnable { requestNow(context.applicationContext, word, locale) }.also { main.postDelayed(it, 180L) }
    }

    private fun requestNow(context: Context, word: String, locale: Locale) {
        val manager = context.getSystemService(Context.TEXT_SERVICES_MANAGER_SERVICE) as? TextServicesManager ?: return
        if (sessionLocale != locale || session?.isSessionDisconnected == true) close()
        if (session == null) {
            sessionLocale = locale
            session = runCatching { manager.newSpellCheckerSession(null, locale, listener, false) }.getOrNull()
        }
        val id = ++sequence
        requests[id] = key(word, locale)
        if (requests.size > 30) requests.remove(requests.keys.minOrNull())
        runCatching { session?.getSuggestions(TextInfo(word, 0x5048, id), 4) }
    }

    private val listener = object : SpellCheckerSession.SpellCheckerSessionListener {
        override fun onGetSuggestions(results: Array<out SuggestionsInfo>?) {
            results?.forEach { result ->
                val requestedKey = requests.remove(result.sequence) ?: return@forEach
                val values = (0 until result.suggestionsCount.coerceAtLeast(0)).mapNotNull { result.getSuggestionAt(it) }.distinct()
                cache[requestedKey] = values
                if (cache.size > 200) cache.remove(cache.keys.first())
                main.post { PhormiKeyboardServiceV2.activeInstance?.onSpellcheckUpdated(requestedKey) }
            }
        }
        override fun onGetSentenceSuggestions(results: Array<out android.view.textservice.SentenceSuggestionsInfo>?) = Unit
    }

    fun close() {
        pending?.let(main::removeCallbacks); pending = null
        runCatching { session?.close() }
        session = null; sessionLocale = null; requests.clear()
    }

    internal fun key(word: String, locale: Locale) = locale.toLanguageTag() + "|" + word.lowercase(locale)
}
