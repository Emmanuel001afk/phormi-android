package com.uong.phormi

import android.content.Context
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import org.json.JSONArray
import java.util.Locale

/** Offline-first text engine for the Phormi IME. */
object PhormiKeyboardTextEngine {
    private const val PREFS = "phormi_keyboard_text"
    private const val KEY_LEARNED = "learned_words"
    private const val KEY_BIGRAMS = "next_words"
    private const val MAX_LEARNED = 1500
    private const val MAX_BIGRAMS = 3000

    private val commonWords = listOf(
        "about","after","again","all","also","always","and","another","any","are","around","because","been","before","being","best","better","but","can","come","could","day","did","different","do","does","done","down","each","even","every","feel","find","first","for","from","get","give","good","great","had","has","have","help","here","how","just","know","like","little","look","love","make","many","more","most","much","myself","need","never","new","next","not","now","only","other","our","out","over","people","please","really","right","same","see","should","some","something","still","take","than","that","their","them","then","there","these","they","thing","think","this","time","today","together","too","try","use","very","want","way","well","were","what","when","where","which","who","why","will","with","without","would","yes","you","your","hello","thanks","thank","sorry","happy","sad","excited","angry","amazing","awesome","beautiful","friend","friends","family","home","work","phone","message","morning","night","welcome","okay","ok","sure","tomorrow"
    )

    private val frenchWords = listOf(
        "à","ai","aime","alors","après","avec","beaucoup","bien","bonjour","bonne","bonsoir","ça","ce","cela","cette","comme","comment","dans","de","demain","des","du","elle","elles","en","encore","est","et","être","fait","faire","famille","faut","fois","fort","grand","grande","heureux","ici","ils","je","j'aime","jour","la","le","les","leur","lui","mais","maintenant","merci","mes","moi","mon","matin","ne","nous","nouveau","nuit","où","parce","pas","petit","peut","plus","pour","pourquoi","quand","que","quel","quelle","qui","quoi","rien","sais","sans","se","ses","si","sont","sur","ta","te","tes","toi","ton","toujours","tous","tout","très","tu","un","une","vais","veut","vous","votre","vraiment","avec","voilà","oui","non","désolé","heureux","triste","excité","amour","amis"
    )

    private val corrections = mapOf(
        "teh" to "the","taht" to "that","adn" to "and","hte" to "the","recieve" to "receive","seperate" to "separate","definately" to "definitely","occured" to "occurred","becuase" to "because","adress" to "address","thier" to "their","wierd" to "weird","untill" to "until","tomorow" to "tomorrow","tommorow" to "tomorrow","remeber" to "remember","alot" to "a lot","writting" to "writing","begining" to "beginning","enviroment" to "environment","goverment" to "government","reciever" to "receiver","dont" to "don't","cant" to "can't","wont" to "won't","isnt" to "isn't","didnt" to "didn't","doesnt" to "doesn't","wasnt" to "wasn't","couldnt" to "couldn't","wouldnt" to "wouldn't","shouldnt" to "shouldn't","im" to "I'm","ive" to "I've","ill" to "I'll","id" to "I'd","youre" to "you're","youve" to "you've","theyre" to "they're","thats" to "that's","whats" to "what's","lets" to "let's","hes" to "he's","shes" to "she's","weve" to "we've"
    )

    private val frenchCorrections = mapOf(
        "bonjou" to "bonjour","merc" to "merci","commen" to "comment","beaucou" to "beaucoup","parceque" to "parce que","vraimen" to "vraiment","maintenan" to "maintenant","demai" to "demain","excite" to "excité","desole" to "désolé"
    )

    private val nextWordSeed = mapOf(
        "good" to listOf("morning","afternoon","evening","luck","job"),
        "how" to listOf("are","is","was","do","did"),
        "thank" to listOf("you"),
        "happy" to listOf("birthday","to","for","with"),
        "see" to listOf("you","the","what"),
        "i" to listOf("am","will","can","want","need","love","think"),
        "we" to listOf("are","can","will","should","need"),
        "please" to listOf("send","help","let","give"),
        "looking" to listOf("for","forward","good"),
        "love" to listOf("you","this","that","it"),
        "very" to listOf("good","happy","excited","important","much")
    )

    private val frenchNextWordSeed = mapOf(
        "bonjour" to listOf("à","tout","comment","monsieur","madame"),
        "merci" to listOf("beaucoup","pour","à","encore"),
        "comment" to listOf("ça","allez","vas","faire"),
        "je" to listOf("suis","vais","peux","veux","pense","aime"),
        "nous" to listOf("sommes","allons","pouvons","devons","avons"),
        "vous" to listOf("êtes","allez","pouvez","avez","voulez"),
        "très" to listOf("bien","heureux","triste","excité","important"),
        "bonne" to listOf("journée","chance","nuit","soirée"),
        "à" to listOf("demain","bientôt","plus","tard"),
        "pour" to listOf("vous","moi","ça","faire","que")
    )

    fun isPassword(info: EditorInfo?): Boolean = variation(info) in setOf(InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)
    fun isUriLike(info: EditorInfo?): Boolean {
        val v = variation(info)
        return v == InputType.TYPE_TEXT_VARIATION_URI || v == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS || v == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
    }
    fun isNoPersonalizedLearning(info: EditorInfo?): Boolean = info?.imeOptions?.and(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0
    fun isPrivateEditor(info: EditorInfo?): Boolean = isPassword(info) || isUriLike(info) || isNoPersonalizedLearning(info)
    fun shouldUsePredictions(info: EditorInfo?): Boolean {
        if (info == null || isPrivateEditor(info)) return false
        if ((info.inputType and InputType.TYPE_MASK_CLASS) != InputType.TYPE_CLASS_TEXT) return false
        return (info.inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) == 0
    }

    fun currentWord(ic: InputConnection?): String {
        val before = ic?.getTextBeforeCursor(96, 0)?.toString().orEmpty()
        return before.split(Regex("[\\s\\n\\r\\t]+"), limit = 0).lastOrNull().orEmpty().takeLastWhile { it.isLetter() || it == '\'' }
    }

    fun previousWord(ic: InputConnection?): String {
        val before = ic?.getTextBeforeCursor(160, 0)?.toString().orEmpty().trimEnd()
        val parts = before.split(Regex("[\\s\\n\\r\\t]+"), limit = 0)
        return parts.dropLast(1).lastOrNull().orEmpty().trim(' ', '\'', '"', '.', ',', '!', '?', ':', ';').lowercase(Locale.US)
    }

    fun suggestions(context: Context, prefix: String, locale: Locale = Locale.getDefault()): List<String> {
        val p = prefix.lowercase(locale)
        if (p.isBlank()) return emptyList()
        val french = locale.language == Locale.FRENCH
        val pool = LinkedHashSet<String>()
        if (french) frenchCorrections[p]?.let(pool::add) else corrections[p]?.let(pool::add)
        loadLearned(context).filter { it.startsWith(p) }.forEach(pool::add)
        (if (french) frenchWords else commonWords).filter { it.startsWith(p) }.forEach(pool::add)
        if (pool.isEmpty()) {
            (if (french) frenchWords else commonWords).filter { editDistance(it.lowercase(locale), p) <= 2 }
                .sortedBy { editDistance(it.lowercase(locale), p) }.forEach(pool::add)
        }
        return pool.take(5)
    }

    fun nextWordSuggestions(context: Context, previous: String, locale: Locale = Locale.getDefault()): List<String> {
        val key = previous.lowercase(locale).trim()
        if (key.isBlank()) return emptyList()
        val pool = LinkedHashSet<String>()
        (if (locale.language == Locale.FRENCH) frenchNextWordSeed[key] else nextWordSeed[key]).orEmpty().forEach(pool::add)
        loadNextWords(context, key).forEach(pool::add)
        return pool.take(4)
    }

    fun correctionFor(word: String): String? = corrections[word.lowercase(Locale.US)]

    fun autoCapitalize(ic: InputConnection?, info: EditorInfo?): Boolean {
        if (ic == null || info == null || isPrivateEditor(info)) return false
        val flags = info.inputType
        if (flags and InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS != 0) return true
        if (flags and InputType.TYPE_TEXT_FLAG_CAP_WORDS != 0) return true
        if (flags and InputType.TYPE_TEXT_FLAG_CAP_SENTENCES == 0) return false
        val trimmed = ic.getTextBeforeCursor(64, 0)?.toString().orEmpty().trimEnd()
        return trimmed.isEmpty() || trimmed.lastOrNull() in setOf('.', '!', '?', ':', ';', '\n')
    }

    fun contextBeforeCursor(ic: InputConnection?): String {
        if (ic == null) return ""
        val info = runCatching {
            val f = PhormiKeyboardServiceV2::class.java.getDeclaredField("instance").apply { isAccessible = true }
            (f.get(null) as? PhormiKeyboardServiceV2)?.currentInputEditorInfo
        }.getOrNull()
        if (isPrivateEditor(info)) return ""
        return ic.getTextBeforeCursor(240, 0)?.toString().orEmpty()
    }

    private fun variation(info: EditorInfo?): Int = (info?.inputType ?: 0) and InputType.TYPE_MASK_VARIATION

    private fun loadLearned(context: Context): List<String> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LEARNED, "[]") ?: "[]"
        return runCatching { val a = JSONArray(raw); buildList { for (i in 0 until a.length()) a.optString(i).takeIf { it.isNotBlank() }?.let(::add) } }.getOrDefault(emptyList())
    }

    private fun saveLearned(context: Context, words: List<String>) {
        val a = JSONArray(); words.take(MAX_LEARNED).forEach(a::put)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_LEARNED, a.toString()).apply()
    }

    private fun loadNextWords(context: Context, previous: String): List<String> = loadAllNextWords(context)[previous].orEmpty()

    private fun loadAllNextWords(context: Context): Map<String, List<String>> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_BIGRAMS, "{}") ?: "{}"
        return runCatching {
            val obj = org.json.JSONObject(raw); buildMap {
                obj.keys().forEach { key -> val arr = obj.optJSONArray(key) ?: return@forEach; put(key, buildList { for (i in 0 until arr.length()) arr.optString(i).takeIf { it.isNotBlank() }?.let(::add) }) }
            }
        }.getOrDefault(emptyMap())
    }

    private fun saveAllNextWords(context: Context, pairs: Map<String, List<String>>) {
        val obj = org.json.JSONObject(); pairs.entries.take(MAX_BIGRAMS).forEach { (key, values) -> obj.put(key, JSONArray(values.take(8))) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_BIGRAMS, obj.toString()).apply()
    }

    fun languageTag(locale: Locale = Locale.getDefault()): String = locale.toLanguageTag()

    private fun editDistance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        for (i in a.indices) {
            val cur = IntArray(b.length + 1); cur[0] = i + 1
            for (j in b.indices) cur[j + 1] = minOf(cur[j] + 1, prev[j + 1] + 1, prev[j] + if (a[i] == b[j]) 0 else 1)
            prev = cur
        }
        return prev[b.length]
    }
}
