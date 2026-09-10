package com.uong.phormi

import android.content.Context
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import org.json.JSONArray
import java.util.Locale

/** Small offline text engine used by the Phormi IME. */
object PhormiKeyboardTextEngine {
    private const val PREFS = "phormi_keyboard_text"
    private const val KEY_LEARNED = "learned_words"
    private const val MAX_LEARNED = 1500
    private val commonWords = listOf("about","after","again","all","also","always","and","another","any","are","around","because","been","before","being","best","better","but","can","come","could","day","did","different","do","does","done","down","each","even","every","feel","find","first","for","from","get","give","good","great","had","has","have","help","here","how","just","know","like","little","look","love","make","many","more","most","much","myself","need","never","new","next","not","now","only","other","our","out","over","people","please","really","right","same","see","should","some","something","still","take","than","that","their","them","then","there","these","they","thing","think","this","time","today","together","too","try","use","very","want","way","well","were","what","when","where","which","who","why","will","with","without","would","yes","you","your","hello","thanks","thank","sorry","happy","sad","excited","angry","amazing","awesome","beautiful","friend","friends","family","home","work","phone","message","morning","night","welcome","okay","ok","sure")
    private val corrections = mapOf("teh" to "the","taht" to "that","adn" to "and","hte" to "the","recieve" to "receive","seperate" to "separate","definately" to "definitely","occured" to "occurred","becuase" to "because","adress" to "address","thier" to "their","wierd" to "weird","untill" to "until","tomorow" to "tomorrow","dont" to "don't","cant" to "can't","wont" to "won't","isnt" to "isn't","didnt" to "didn't","doesnt" to "doesn't","wasnt" to "wasn't","couldnt" to "couldn't","wouldnt" to "wouldn't")

    fun isPassword(info: EditorInfo?): Boolean = variation(info) in setOf(InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)
    fun isUriLike(info: EditorInfo?): Boolean { val v = variation(info); return v == InputType.TYPE_TEXT_VARIATION_URI || v == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS || v == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS }
    fun isNoPersonalizedLearning(info: EditorInfo?): Boolean = info?.imeOptions?.and(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0
    fun isPrivateEditor(info: EditorInfo?): Boolean = isPassword(info) || isUriLike(info) || isNoPersonalizedLearning(info)
    fun shouldUsePredictions(info: EditorInfo?): Boolean {
        if (info == null || isPrivateEditor(info)) return false
        if ((info.inputType and InputType.TYPE_MASK_CLASS) != InputType.TYPE_CLASS_TEXT) return false
        if ((info.inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0) return false
        PhormiKeyboardAiBridge.start()
        return true
    }
    fun currentWord(ic: InputConnection?): String { val before = ic?.getTextBeforeCursor(64, 0)?.toString().orEmpty(); return before.split(Regex("[\\s\\n\\r\\t]+"), limit = 0).lastOrNull().orEmpty().takeLastWhile { it.isLetter() || it == '\'' } }
    fun suggestions(context: Context, prefix: String): List<String> { val p = prefix.lowercase(Locale.US); if (p.isBlank()) return emptyList(); val pool = LinkedHashSet<String>(); corrections[p]?.let(pool::add); loadLearned(context).filter { it.startsWith(p) }.sortedBy { it.length }.forEach(pool::add); commonWords.filter { it.startsWith(p) }.forEach(pool::add); if (pool.isEmpty()) commonWords.filter { editDistance(it, p) <= 2 }.sortedBy { editDistance(it, p) }.forEach(pool::add); return pool.take(5) }
    fun correctionFor(word: String): String? = corrections[word.lowercase(Locale.US)]
    fun learn(context: Context, word: String, info: EditorInfo? = null) { if (isPrivateEditor(info)) return; val n = word.lowercase(Locale.US).trim(); if (n.length < 2 || n.length > 32 || !n.all { it.isLetter() || it == '\'' }) return; if (n in commonWords || n in corrections.keys) return; val words = loadLearned(context).toMutableList(); words.remove(n); words.add(0, n); saveLearned(context, words.take(MAX_LEARNED)) }
    fun autoCapitalize(ic: InputConnection?, info: EditorInfo?): Boolean { if (ic == null || info == null || isPrivateEditor(info)) return false; val flags = info.inputType; if (flags and InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS != 0) return true; if (flags and InputType.TYPE_TEXT_FLAG_CAP_WORDS != 0) return true; if (flags and InputType.TYPE_TEXT_FLAG_CAP_SENTENCES == 0) return false; val trimmed = ic.getTextBeforeCursor(64, 0)?.toString().orEmpty().trimEnd(); return trimmed.isEmpty() || trimmed.lastOrNull() in setOf('.', '!', '?', ':', ';', '\n') }
    fun autoCapitalize(ic: InputConnection?): Boolean { val before = ic?.getTextBeforeCursor(64, 0)?.toString().orEmpty().trimEnd(); return before.isNotEmpty() && before.lastOrNull() in setOf('.', '!', '?', ':', ';', '\n') }
    fun contextBeforeCursor(ic: InputConnection?): String { if (ic == null) return ""; val info = runCatching { val f = PhormiKeyboardServiceV2::class.java.getDeclaredField("instance").apply { isAccessible = true }; (f.get(null) as? PhormiKeyboardServiceV2)?.currentInputEditorInfo }.getOrNull(); if (isPrivateEditor(info)) return ""; return ic.getTextBeforeCursor(160, 0)?.toString().orEmpty() }
    private fun variation(info: EditorInfo?): Int = (info?.inputType ?: 0) and InputType.TYPE_MASK_VARIATION
    private fun loadLearned(context: Context): List<String> { val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LEARNED, "[]") ?: "[]"; return runCatching { val a = JSONArray(raw); buildList { for (i in 0 until a.length()) a.optString(i).takeIf { it.isNotBlank() }?.let(::add) } }.getOrDefault(emptyList()) }
    private fun saveLearned(context: Context, words: List<String>) { val a = JSONArray(); words.take(MAX_LEARNED).forEach(a::put); context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_LEARNED, a.toString()).apply() }
    private fun editDistance(a: String, b: String): Int { if (a == b) return 0; if (a.isEmpty()) return b.length; if (b.isEmpty()) return a.length; var prev = IntArray(b.length + 1) { it }; for (i in a.indices) { val cur = IntArray(b.length + 1); cur[0] = i + 1; for (j in b.indices) cur[j + 1] = minOf(cur[j] + 1, prev[j + 1] + 1, prev[j] + if (a[i] == b[j]) 0 else 1); prev = cur }; return prev[b.length] }
}
