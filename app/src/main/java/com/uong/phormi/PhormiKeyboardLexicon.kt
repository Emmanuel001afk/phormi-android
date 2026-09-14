package com.uong.phormi

import android.content.Context
import java.util.Locale
import kotlin.math.min

/** Lightweight on-device learning for personal words, Pidgin, Yoruba and other user vocabulary. */
object PhormiKeyboardLexicon {
    private const val PREFS = "phormi_keyboard_lexicon"
    private const val WORDS = "learned_words"
    private const val SHORTCUTS = "shortcuts"
    private const val MAX_WORDS = 2500
    private const val MAX_SHORTCUTS = 250

    private val corrections = mapOf(
        "teh" to "the", "adn" to "and", "taht" to "that", "thier" to "their", "recieve" to "receive",
        "seperate" to "separate", "definately" to "definitely", "occured" to "occurred", "becuase" to "because",
        "becouse" to "because", "dont" to "don't", "cant" to "can't", "wont" to "won't", "isnt" to "isn't",
        "didnt" to "didn't", "doesnt" to "doesn't", "shouldnt" to "shouldn't", "couldnt" to "couldn't",
        "wouldnt" to "wouldn't", "im" to "I'm", "ive" to "I've", "ill" to "I'll", "id" to "I'd",
        "youre" to "you're", "youve" to "you've", "theyre" to "they're", "thats" to "that's",
        "whats" to "what's", "wheres" to "where's", "lets" to "let's", "alot" to "a lot"
    )
    private val words = (corrections.values + listOf(
        "about","after","again","always","android","another","answer","anything","because","before","browser","build",
        "change","chat","choose","clear","close","computer","could","create","download","everything","feature","first",
        "foundation","from","good","have","help","hello","important","internet","keyboard","language","learn","like",
        "local","message","more","never","new","now","page","phone","please","privacy","project","quick","really",
        "search","security","settings","should","site","something","start","system","text","that","their","there",
        "these","thing","this","through","today","together","understand","update","use","very","want","website",
        "what","when","where","which","while","with","work","would","you","your"
    )).map { it.lowercase() }.distinct().toSet()

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun learnedWords(context: Context): Set<String> = prefs(context).getStringSet(WORDS, emptySet()).orEmpty()

    fun learn(context: Context, raw: String) {
        val word = raw.trim().lowercase(Locale.getDefault())
        if (!isLearnable(word)) return
        val current = learnedWords(context).toMutableSet()
        current.remove(word)
        current.add(word)
        prefs(context).edit().putStringSet(WORDS, current.takeLast(MAX_WORDS).toSet()).apply()
    }

    fun addShortcut(context: Context, shortcut: String, phrase: String) {
        val s = shortcut.trim().lowercase(Locale.getDefault())
        val p = phrase.trim()
        if (!isLearnable(s) || p.isBlank()) return
        val current = prefs(context).getStringSet(SHORTCUTS, emptySet()).orEmpty().toMutableSet()
        current.removeIf { it.startsWith("$s=") }
        current.add("$s=$p")
        prefs(context).edit().putStringSet(SHORTCUTS, current.takeLast(MAX_SHORTCUTS).toSet()).apply()
    }

    fun clear(context: Context) = prefs(context).edit().clear().apply()

    fun correctWord(context: Context, token: String): String? {
        val lower = token.lowercase(Locale.getDefault())
        if (!isLearnable(lower) || learnedWords(context).contains(lower)) return null
        val direct = corrections[lower]
        if (direct != null) return direct
        val all = (words + learnedWords(context)).filter { isLearnable(it) && it != lower }
        val maxDistance = when {
            lower.length <= 4 -> 1
            lower.length <= 8 -> 2
            else -> 2
        }
        return all.asSequence()
            .filter { min(it.length, lower.length) >= 3 }
            .map { it to distance(lower, it.lowercase(Locale.getDefault())) }
            .filter { it.second <= maxDistance }
            .sortedWith(compareBy<Pair<String, Int>> { it.second }.thenBy { it.first.length })
            .firstOrNull()?.first
    }

    fun suggestions(context: Context, prefix: String, limit: Int = 6): List<String> {
        val p = prefix.trim().lowercase(Locale.getDefault())
        if (p.isBlank()) return emptyList()
        val shortcutMatches = prefs(context).getStringSet(SHORTCUTS, emptySet()).orEmpty().mapNotNull { entry ->
            val i = entry.indexOf('=')
            if (i > 0 && entry.substring(0, i).startsWith(p)) entry.substring(i + 1) else null
        }
        return (shortcutMatches + learnedWords(context).filter { it.startsWith(p) } + words.filter { it.startsWith(p) })
            .distinct().filter { it != p }.take(limit)
    }

    private fun isLearnable(word: String): Boolean =
        word.length in 2..40 && word.any { it.isLetter() } && !word.any { it.isDigit() }

    private fun distance(a: String, b: String): Int {
        val previous = IntArray(b.length + 1) { it }
        val current = IntArray(b.length + 1)
        for (i in a.indices) {
            current[0] = i + 1
            for (j in b.indices) {
                val cost = if (a[i] == b[j]) 0 else 1
                current[j + 1] = min(min(current[j] + 1, previous[j + 1] + 1), previous[j] + cost)
            }
            for (j in previous.indices) previous[j] = current[j]
        }
        return previous[b.length]
    }
}
