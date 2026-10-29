package com.uong.phormi

import android.content.Context
import java.util.Locale

/** Small, offline frequency-ordered dictionaries. See docs/third-party/keyboard-dictionaries.md. */
object PhormiKeyboardDictionary {
    private data class Lexicon(val words: List<String>) {
        val rank = words.withIndex().associate { it.value to it.index }
        val byFirst = words.groupBy { it.take(1) }
        val byFirstTwo = words.groupBy { it.take(2) }
    }

    // Only the last few active languages stay in memory. No word list is loaded on app startup.
    private val cache = object : LinkedHashMap<String, Lexicon>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Lexicon>?): Boolean = size > 3
    }

    @Synchronized
    private fun lexicon(context: Context, locale: Locale): Lexicon = cache.getOrPut(locale.language) {
        val fallback = PhormiKeyboardLanguageData.words(locale).map { it.lowercase(locale) }
        val bundled = runCatching {
            context.assets.open("keyboard/words_${locale.language}.txt")
                .bufferedReader(Charsets.UTF_8).useLines { it.filter(String::isNotBlank).toList() }
        }.getOrDefault(emptyList())
        Lexicon((bundled + fallback).distinct())
    }

    fun contains(context: Context, locale: Locale, word: String): Boolean =
        lexicon(context, locale).rank.containsKey(word.lowercase(locale))

    /** A prefix index keeps suggestions fast even on the 20,000-word English list. */
    fun completions(context: Context, locale: Locale, prefix: String, limit: Int = 4): List<String> {
        val clean = prefix.lowercase(locale)
        if (clean.isBlank()) return emptyList()
        val data = lexicon(context, locale)
        val pool = if (clean.length == 1) data.byFirst[clean] else data.byFirstTwo[clean.take(2)]
        return pool.orEmpty().asSequence().filter { it != clean && it.startsWith(clean) }.take(limit).toList()
    }

    /** Conservative one-edit correction of unknown words; real dictionary words are never changed. */
    fun closestWord(context: Context, locale: Locale, input: String): String? {
        return candidateFor(input.lowercase(locale), lexicon(context, locale).rank, locale)
    }

    /** Exposed to package tests without needing Android asset loading. */
    internal fun candidateFor(word: String, ranks: Map<String, Int>, locale: Locale): String? {
        if (word.length !in 4..24 || !word.all(Char::isLetter) || word in ranks) return null
        val candidates = LinkedHashSet<String>()
        fun consider(candidate: String) {
            if (ranks[candidate] != null && candidate != word) candidates.add(candidate)
        }
        for (i in 0 until word.lastIndex) {
            if (word[i] != word[i + 1]) {
                val chars = word.toCharArray()
                chars[i] = word[i + 1]; chars[i + 1] = word[i]
                consider(String(chars))
            }
        }
        for (i in word.indices) consider(word.removeRange(i, i + 1))
        val alphabet = when (locale.language) {
            "fr" -> "abcdefghijklmnopqrstuvwxyzàâçéèêëîïôùûüÿœ"
            "es" -> "abcdefghijklmnñopqrstuvwxyzáéíóúü"
            "pt" -> "abcdefghijklmnopqrstuvwxyzáâãàçéêíóôõú"
            "de" -> "abcdefghijklmnopqrstuvwxyzäöüß"
            "tr" -> "abcçdefgğhıijklmnoöprsştuüvyz"
            else -> "abcdefghijklmnopqrstuvwxyz"
        }
        for (i in word.indices) for (letter in alphabet) {
            if (word[i] != letter) consider(word.replaceRange(i, i + 1, letter.toString()))
        }
        for (i in 0..word.length) for (letter in alphabet) {
            consider(word.substring(0, i) + letter + word.substring(i))
        }
        // Rank is frequency order. Prefer a transposition or missing repeated letter
        // over a more frequent but unrelated substitution (e.g. helo -> hello, not help).
        fun priority(candidate: String): Int = when {
            candidate.length == word.length && (0 until word.lastIndex).any { i ->
                candidate[i] == word[i + 1] && candidate[i + 1] == word[i] &&
                    candidate.removeRange(i, i + 2) == word.removeRange(i, i + 2)
            } -> 0
            candidate.length == word.length + 1 && candidate.indices.any { i ->
                (i > 0 && candidate[i] == candidate[i - 1]) && candidate.removeRange(i, i + 1) == word
            } -> 1
            else -> 2
        }
        return candidates.asSequence().filter { (ranks[it] ?: Int.MAX_VALUE) < 6000 }
            .minWithOrNull(compareBy<String> { priority(it) }.thenBy { ranks[it] ?: Int.MAX_VALUE })
    }
}
