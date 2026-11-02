package com.uong.phormi

import java.util.Locale
import kotlin.math.abs

/**
 * Real keyboard layout, accent and glide data used by the *active* IME.
 *
 * This replaces `PhormiKeyboardRenderCompat.kt`, which declared a top-level
 * `PhormiKeyboardServiceV2.render()` extension that was permanently shadowed by the
 * service's private `render()` member. Nothing in that file ever ran, and it used
 * reflection into private service fields, so enabling R8 would have broken it anyway.
 *
 * Everything here is plain Kotlin with no Android or reflection dependency, which makes
 * it unit-testable and safe under minification.
 */
object PhormiKeyboardLocales {

    private val QWERTY = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
    private val AZERTY = listOf("azertyuiop", "qsdfghjklm", "wxcvbn")
    private val QWERTZ = listOf("qwertzuiop", "asdfghjkl", "yxcvbnm")

    /**
     * Only Latin-script languages are advertised as IME subtypes. Declaring a subtype
     * for Arabic, Devanagari, CJK, Cyrillic, Greek or Hebrew while rendering one QWERTY
     * layout is misleading: a word list is not a keyboard layout.
     */
    val latinLanguages: Set<String> = setOf(
        "en", "fr", "es", "pt", "de", "it", "id", "tr",
        "yo", "ig", "ha", "sw", "vi", "pl", "nl", "sv",
        "no", "da", "fi", "cs", "ro", "hu"
    )

    fun isLatinScript(language: String): Boolean =
        language.lowercase(Locale.ROOT) in latinLanguages

    fun letterRows(language: String): List<String> = when (language.lowercase(Locale.ROOT)) {
        "fr" -> AZERTY
        "de", "cs" -> QWERTZ
        else -> QWERTY
    }

    fun layoutName(language: String): String = when (language.lowercase(Locale.ROOT)) {
        "fr" -> "AZERTY"
        "de", "cs" -> "QWERTZ"
        else -> "QWERTY"
    }

    /** Long-press alternatives for the active locale. Empty means no accent popup. */
    fun accents(language: String): Map<String, String> = when (language.lowercase(Locale.ROOT)) {
        "yo" -> mapOf("a" to "àáā", "e" to "ẹéè", "i" to "íì", "o" to "ọóò", "s" to "ṣśš", "n" to "ńñ")
        "ig" -> mapOf("a" to "áà", "e" to "ẹéè", "i" to "ịíì", "o" to "ọóò", "u" to "ụúù", "n" to "ṅñ")
        "fr" -> mapOf("a" to "àâäæ", "c" to "ç", "e" to "éèêë", "i" to "îï", "o" to "ôöœ", "u" to "ùûü", "y" to "ÿ")
        "es" -> mapOf("a" to "áà", "e" to "éè", "i" to "íì", "o" to "óò", "u" to "úü", "n" to "ñ")
        "pt" -> mapOf("a" to "áàâãä", "c" to "ç", "e" to "éêë", "i" to "í", "o" to "óôõö", "u" to "úü")
        "de" -> mapOf("a" to "äá", "o" to "öó", "u" to "üú", "s" to "ß")
        "it" -> mapOf("a" to "àá", "e" to "èé", "i" to "ìí", "o" to "òó", "u" to "ùú")
        "tr" -> mapOf("c" to "ç", "g" to "ğ", "i" to "ıİ", "o" to "ö", "s" to "ş", "u" to "ü")
        "vi" -> mapOf("a" to "áàảãạâă", "e" to "éèẻẽẹê", "i" to "íìỉĩị", "o" to "óòỏõọôơ", "u" to "úùủũụư", "d" to "đ")
        "pl" -> mapOf("a" to "ąá", "c" to "ć", "e" to "ęé", "l" to "ł", "n" to "ń", "o" to "ó", "s" to "ś", "z" to "źż")
        "cs" -> mapOf("a" to "á", "c" to "č", "d" to "ď", "e" to "éě", "i" to "í", "n" to "ň", "o" to "ó", "r" to "ř", "s" to "š", "t" to "ť", "u" to "úů", "z" to "ž")
        "ro" -> mapOf("a" to "ăâ", "i" to "î", "s" to "ș", "t" to "ț")
        "hu" -> mapOf("a" to "á", "e" to "é", "i" to "í", "o" to "óöő", "u" to "úüű")
        else -> emptyMap()
    }

    /** Collapse a finger trace to distinct letters, ignoring case and punctuation. */
    fun normalizeTrace(trace: String): String = buildString {
        var previous = '\u0000'
        for (character in trace.lowercase(Locale.ROOT)) {
            if (!character.isLetter()) continue
            if (character != previous) append(character)
            previous = character
        }
    }

    /**
     * Resolve a glide trace to the best offline dictionary word.
     *
     * The old (dead) compatibility layer simply concatenated every letter the finger
     * passed over, producing strings like `ghjkhello`. A real glide needs the trace to
     * start and end on the word's first/last letter and to contain almost every letter
     * of the word in order. Longest-common-subsequence scoring tolerates the extra
     * letters a finger inevitably crosses.
     *
     * Returns null when nothing plausible matches; the caller can then fall back to
     * the raw trace. `candidates` should already be narrowed to words starting with
     * the trace's first letter for responsiveness.
     */
    fun glideWord(trace: String, candidates: Iterable<String>): String? {
        val path = normalizeTrace(trace)
        if (path.length < 3) return null
        var best: String? = null
        var bestScore = Double.NEGATIVE_INFINITY
        for (candidate in candidates) {
            val word = candidate.trim().lowercase(Locale.ROOT)
            if (word.length < 3 || word.length > 20 || !word.all(Char::isLetter)) continue
            if (word.first() != path.first() || word.last() != path.last()) continue
            if (word.length > path.length + 2) continue
            val common = longestCommonSubsequence(path, word)
            if (common < word.length - 1) continue
            val coverage = common.toDouble() / word.length
            val precision = common.toDouble() / path.length
            val score = coverage * 3.0 + precision - abs(word.length - path.length) * 0.1
            if (score > bestScore) {
                bestScore = score
                best = candidate.trim()
            }
        }
        return best
    }

    private fun longestCommonSubsequence(a: String, b: String): Int {
        var previous = IntArray(b.length + 1)
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                current[j] = if (a[i - 1] == b[j - 1]) previous[j - 1] + 1
                else maxOf(previous[j], current[j - 1])
            }
            val swap = previous
            previous = current
            current = swap
            current.fill(0)
        }
        return previous[b.length]
    }
}
