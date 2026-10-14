package com.uong.phormi

import android.content.Context
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Offline-first multilingual text engine with learning and system spellchecker fallback. */
object PhormiKeyboardTextEngine {
    private const val PREFS = "phormi_keyboard_text"
    private const val KEY_LEARNED = "learned_words"
    private const val KEY_BIGRAMS = "next_words"
    private const val KEY_CORRECTIONS = "learned_corrections"
    private const val MAX_LEARNED = 2000
    private const val MAX_BIGRAMS = 4000
    private const val MAX_CORRECTIONS = 800

    private val correctionsByLanguage = mapOf(
        "en" to mapOf("teh" to "the", "taht" to "that", "adn" to "and", "hte" to "the", "recieve" to "receive", "seperate" to "separate", "definately" to "definitely", "becuase" to "because", "adress" to "address", "thier" to "their", "wierd" to "weird", "untill" to "until", "tomorow" to "tomorrow", "dont" to "don't", "cant" to "can't", "wont" to "won't", "im" to "I'm", "ive" to "I've", "youre" to "you're", "thats" to "that's"),
        "fr" to mapOf("bonjou" to "bonjour", "merc" to "merci", "commen" to "comment", "beaucou" to "beaucoup", "parceque" to "parce que", "vraimen" to "vraiment", "desole" to "désolé"),
        "es" to mapOf("gracis" to "gracias", "holaa" to "hola", "tambien" to "también", "manana" to "mañana"),
        "pt" to mapOf("obrigao" to "obrigado", "tambem" to "também", "amanha" to "amanhã", "voce" to "você"),
        "de" to mapOf("uber" to "über", "fur" to "für", "schon" to "schön"),
        "it" to mapOf("perche" to "perché", "piu" to "più", "cosi" to "così"),
        "tr" to mapOf("tesekkur" to "teşekkür", "cok" to "çok", "bugun" to "bugün", "yarin" to "yarın"),
        "yo" to mapOf("bawo" to "báwo", "bee ni" to "bẹ́ẹ̀ni", "dada" to "dáadáa", "mo dupe" to "mo dúpẹ́", "ola" to "ọ̀la", "ore" to "ọ̀rẹ́", "ife" to "ìfẹ́", "ayo" to "ayọ̀", "pele" to "pẹ̀lẹ́"),
        "ig" to mapOf("daalu" to "daalụ"),
        "sw" to mapOf("asante" to "asante")
    )

    private val nextSeeds = mapOf(
        "en" to mapOf("good" to listOf("morning", "afternoon", "evening", "luck"), "how" to listOf("are", "is", "was", "do"), "thank" to listOf("you"), "happy" to listOf("birthday", "to", "for"), "i" to listOf("am", "will", "can", "want", "need"), "we" to listOf("are", "can", "will", "should")),
        "fr" to mapOf("bonjour" to listOf("à", "tout", "comment"), "merci" to listOf("beaucoup", "pour"), "je" to listOf("suis", "vais", "peux", "veux")),
        "es" to mapOf("hola" to listOf("a", "todos", "cómo"), "gracias" to listOf("por", "mucho"), "yo" to listOf("soy", "quiero", "puedo")),
        "pt" to mapOf("olá" to listOf("amigo", "a", "todos"), "obrigado" to listOf("por", "muito"), "eu" to listOf("sou", "vou", "posso", "quero")),
        "de" to mapOf("hallo" to listOf("zusammen", "wie", "mein"), "danke" to listOf("dir", "sehr"), "ich" to listOf("bin", "habe", "will", "kann")),
        "it" to mapOf("ciao" to listOf("come", "amico", "a"), "grazie" to listOf("mille", "per"), "io" to listOf("sono", "voglio", "posso")),
        "id" to mapOf("selamat" to listOf("pagi", "siang", "malam"), "terima" to listOf("kasih"), "saya" to listOf("mau", "akan", "bisa")),
        "tr" to mapOf("merhaba" to listOf("nasılsın", "arkadaş"), "teşekkür" to listOf("ederim", "çok"), "ben" to listOf("bir", "de", "çok")),
        "yo" to mapOf("báwo" to listOf("ni", "ni o ṣe"), "mo" to listOf("fẹ́", "wà", "dúpẹ́", "mọ̀"), "ẹ" to listOf("ṣe", "gan")),
        "ig" to mapOf("kedu" to listOf("ka", "ị", "mere"), "daalụ" to listOf("nke ukwuu"), "anyị" to listOf("ga", "nwere", "chọrọ")),
        "ha" to mapOf("sannu" to listOf("da", "lafiya")),
        "sw" to mapOf("habari" to listOf("ya", "za"), "asante" to listOf("sana", "kwa")),
        "ar" to mapOf("مرحبا" to listOf("بكم", "كيف"), "شكرا" to listOf("لك", "جزيلا")),
        "hi" to mapOf("नमस्ते" to listOf("आप", "कैसे"), "धन्यवाद" to listOf("आपको")),
        "bn" to mapOf("হ্যালো" to listOf("আপনি", "কেমন"), "ধন্যবাদ" to listOf("আপনাকে")),
        "ur" to mapOf("سلام" to listOf("آپ", "کیسے"), "شکریہ" to listOf("آپ", "بہت")),
        "zh" to mapOf("你好" to listOf("吗", "朋友"), "谢谢" to listOf("你", "大家")),
        "ja" to mapOf("こんにちは" to listOf("皆さん", "元気"), "ありがとう" to listOf("ございます")),
        "ko" to mapOf("안녕하세요" to listOf("여러분", "어떻게"), "감사합니다" to listOf("정말")),
        "ru" to mapOf("привет" to listOf("всем", "как"), "спасибо" to listOf("вам", "большое"))
    )

    private fun inputClass(info: EditorInfo?): Int = (info?.inputType ?: 0) and InputType.TYPE_MASK_CLASS
    private fun variation(info: EditorInfo?): Int = (info?.inputType ?: 0) and InputType.TYPE_MASK_VARIATION
    fun isPassword(info: EditorInfo?): Boolean = when (inputClass(info)) {
        InputType.TYPE_CLASS_NUMBER -> variation(info) == InputType.TYPE_NUMBER_VARIATION_PASSWORD
        InputType.TYPE_CLASS_TEXT -> variation(info) in setOf(InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)
        else -> false
    }
    fun isUriLike(info: EditorInfo?): Boolean = inputClass(info) == InputType.TYPE_CLASS_TEXT && variation(info) in setOf(
        InputType.TYPE_TEXT_VARIATION_URI, InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS)
    fun isNoPersonalizedLearning(info: EditorInfo?): Boolean = info == null || (info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0
    fun isPrivateEditor(info: EditorInfo?): Boolean = isPassword(info) || isNoPersonalizedLearning(info) ||
        PhormiKeyboardServiceV2.activeInstance?.let { PhormiKeyboardPreferences.incognito(it) } == true
    fun allowsPersonalizedLearning(info: EditorInfo?): Boolean = shouldUsePredictions(info)
    fun allowsAiEmoji(info: EditorInfo?): Boolean = shouldUsePredictions(info)
    fun shouldUsePredictions(info: EditorInfo?): Boolean = info != null && !isPrivateEditor(info) && !isUriLike(info) &&
        (info.inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) == 0 && inputClass(info) == InputType.TYPE_CLASS_TEXT

    fun localeFor(info: EditorInfo?): Locale {
        val service = PhormiKeyboardServiceV2.activeInstance
        val subtype = service?.activeSubtype
        val tag = subtype?.languageTag?.takeIf { it.isNotBlank() }
            ?: subtype?.locale?.replace('_', '-')?.takeIf { it.isNotBlank() }
        return tag?.let(Locale::forLanguageTag)
            ?: info?.hintLocales?.takeIf { !it.isEmpty }?.get(0) ?: Locale.getDefault()
    }

    fun currentWord(ic: InputConnection?): String = PhormiTypingRules.currentWord(ic?.getTextBeforeCursor(128, 0)?.toString().orEmpty())
    fun previousWord(ic: InputConnection?, locale: Locale = Locale.getDefault()): String =
        PhormiTypingRules.previousWord(ic?.getTextBeforeCursor(240, 0)?.toString().orEmpty()).lowercase(locale)
    fun supportedLanguageLabels(): List<String> = PhormiKeyboardLanguageData.supportedLanguages().map { Locale(it).getDisplayLanguage(Locale.getDefault()).ifBlank { it } }
    fun languageName(locale: Locale): String = locale.getDisplayLanguage(Locale.getDefault()).ifBlank { locale.language }

    fun suggestions(context: Context, prefix: String, locale: Locale = activeLocale(context)): List<String> {
        val p = normalize(prefix, locale)
        if (p.isBlank()) return emptyList()
        val learned = learned(context, locale)
        val result = linkedSetOf(prefix) // The literal spelling is always an explicit choice.
        correctionFor(context, prefix, locale)?.let(result::add)
        learned.values.filter { it.count >= 3 && normalize(it.word, locale).startsWith(p) && !it.word.equals(prefix, true) }
            .sortedWith(compareByDescending<LearnedWord> { it.choices }.thenByDescending { it.count }.thenByDescending { it.updated })
            .forEach { result.add(PhormiTypingRules.matchCase(prefix, it.word, locale)) }
        PhormiKeyboardLanguageData.words(locale).filter { it.startsWith(p, true) && !it.equals(prefix, true) }
            .take(4).forEach { result.add(PhormiTypingRules.matchCase(prefix, it, locale)) }
        PhormiKeyboardSystemSpellChecker.cached(p, locale).filter { it.isNotBlank() && !it.equals(prefix, true) }
            .forEach { result.add(PhormiTypingRules.matchCase(prefix, it, locale)) }
        PhormiKeyboardSystemSpellChecker.request(context, p, locale)
        return result.take(3)
    }

    fun nextWordSuggestions(context: Context, previous: String, locale: Locale = activeLocale(context)): List<String> {
        val key = normalize(previous, locale)
        if (key.isBlank()) return emptyList()
        val stored = readObject(context, KEY_BIGRAMS).optJSONArray(locale.toLanguageTag() + "|" + key)
        val result = linkedSetOf<String>()
        if (stored != null) for (i in 0 until stored.length()) result.add(stored.optString(i))
        result.addAll(nextSeeds[locale.language]?.get(key).orEmpty())
        return result.filter { it.isNotBlank() }.take(3)
    }

    fun correctionFor(context: Context, word: String, locale: Locale = activeLocale(context)): String? {
        val key = normalize(word, locale)
        val known = learned(context, locale)[key]
        if ((known?.choices ?: 0) >= 3 || (known?.count ?: 0) >= 3) return null
        val manual = readObject(context, KEY_CORRECTIONS).optString(locale.toLanguageTag() + "|" + key).takeIf { it.isNotBlank() }
        val replacement = manual ?: correctionsByLanguage[locale.language]?.get(key) ?: return null
        return PhormiTypingRules.matchCase(word, replacement, locale).takeIf { it != word }
    }
    fun correctionFor(word: String): String? = correctionsByLanguage[Locale.getDefault().language]?.get(word.lowercase(Locale.getDefault()))

    private data class LearnedWord(val word: String, val count: Int, val choices: Int, val updated: Long)

    /** Words enter personal predictions after three completed uses, never on a partial keystroke. */
    fun learn(context: Context, word: String, info: EditorInfo?) = remember(context, word, info, false)

    /** Called only when the user taps the literal spelling or undoes an automatic correction. */
    fun rememberLiteralChoice(context: Context, word: String, info: EditorInfo?) = remember(context, word, info, true)

    private fun remember(context: Context, word: String, info: EditorInfo?, intentional: Boolean) {
        if (!allowsPersonalizedLearning(info)) return
        val locale = localeFor(info)
        val value = word.trim()
        if (value.length !in 2..64 || value.any { it.isDigit() || it.isWhitespace() }) return
        val root = readObject(context, "personal_dictionary_v2")
        val key = locale.toLanguageTag() + "|" + normalize(value, locale)
        val old = root.optJSONObject(key)
        root.put(key, JSONObject().put("word", value)
            .put("count", ((old?.optInt("count") ?: 0) + 1).coerceAtMost(10000))
            .put("choices", ((old?.optInt("choices") ?: 0) + if (intentional) 1 else 0).coerceAtMost(100))
            .put("updated", System.currentTimeMillis()))
        trim(root, MAX_LEARNED)
        writeObject(context, "personal_dictionary_v2", root)
    }

    fun learnPair(context: Context, previous: String, current: String, info: EditorInfo?) {
        if (!allowsPersonalizedLearning(info) || previous.isBlank() || current.isBlank()) return
        val locale = localeFor(info)
        val root = readObject(context, KEY_BIGRAMS)
        val key = locale.toLanguageTag() + "|" + normalize(previous, locale)
        val old = root.optJSONArray(key)
        val values = mutableListOf(current.take(64))
        if (old != null) for (i in 0 until old.length()) old.optString(i).takeIf { it.isNotBlank() && !it.equals(current, true) }?.let(values::add)
        root.put(key, JSONArray(values.take(10)))
        trim(root, MAX_BIGRAMS)
        writeObject(context, KEY_BIGRAMS, root)
    }

    /** Only manual suggestion acceptance creates correction mappings, not automatic replacements. */
    fun learnCorrection(context: Context, original: String, accepted: String, info: EditorInfo?) {
        if (!allowsPersonalizedLearning(info) || original.isBlank() || accepted.isBlank() || original.equals(accepted, true)) return
        val locale = localeFor(info)
        val root = readObject(context, KEY_CORRECTIONS)
        root.put(locale.toLanguageTag() + "|" + normalize(original, locale), accepted.take(64))
        trim(root, MAX_CORRECTIONS)
        writeObject(context, KEY_CORRECTIONS, root)
    }

    fun forgetPersonalizedSuggestion(context: Context, value: String, previous: String = "", locale: Locale = activeLocale(context)): Boolean {
        val dictionary = readObject(context, "personal_dictionary_v2")
        val key = locale.toLanguageTag() + "|" + normalize(value, locale)
        var removed = dictionary.remove(key) != null
        writeObject(context, "personal_dictionary_v2", dictionary)
        val pairs = readObject(context, KEY_BIGRAMS)
        pairs.keys().asSequence().toList().filter { it.startsWith(locale.toLanguageTag() + "|") }.forEach { pairKey ->
            val old = pairs.optJSONArray(pairKey) ?: return@forEach
            val values = (0 until old.length()).map { old.optString(it) }.filterNot { it.equals(value, true) }
            if (values.size != old.length()) { removed = true; pairs.put(pairKey, JSONArray(values)) }
        }
        writeObject(context, KEY_BIGRAMS, pairs)
        val corrections = readObject(context, KEY_CORRECTIONS)
        if (corrections.remove(key) != null) removed = true
        writeObject(context, KEY_CORRECTIONS, corrections)
        return removed
    }

    fun clearPersonalDictionary(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        learnedCache.clear()
    }

    fun autoCapitalize(ic: InputConnection?, info: EditorInfo?): Boolean {
        if (ic == null || info == null || isPrivateEditor(info) || isUriLike(info) || inputClass(info) != InputType.TYPE_CLASS_TEXT) return false
        val type = info.inputType
        if (type and InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS != 0) return true
        val before = ic.getTextBeforeCursor(128, 0)?.toString().orEmpty()
        if (type and InputType.TYPE_TEXT_FLAG_CAP_WORDS != 0) return before.isEmpty() || before.last().isWhitespace()
        if (type and InputType.TYPE_TEXT_FLAG_CAP_SENTENCES == 0) return false
        return before.isBlank() || before.endsWith('\n') ||
            (before.last().isWhitespace() && before.trimEnd().lastOrNull() in setOf('.', '!', '?'))
    }

    fun contextBeforeCursor(ic: InputConnection?): String {
        val service = PhormiKeyboardServiceV2.activeInstance ?: return ""
        if (!allowsAiEmoji(service.currentInputEditorInfo)) return ""
        return ic?.getTextBeforeCursor(240, 0)?.toString().orEmpty()
    }

    private fun activeLocale(context: Context): Locale = if (context is PhormiKeyboardServiceV2) localeFor(context.currentInputEditorInfo) else Locale.getDefault()
    private fun normalize(word: String, locale: Locale) = java.text.Normalizer.normalize(word, java.text.Normalizer.Form.NFC).lowercase(locale)
    private val learnedCache = mutableMapOf<String, Pair<String, Map<String, LearnedWord>>>()
    private fun learned(context: Context, locale: Locale): Map<String, LearnedWord> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("personal_dictionary_v2", "{}").orEmpty()
        val tag = locale.toLanguageTag()
        learnedCache[tag]?.takeIf { it.first == raw }?.let { return it.second }
        val root = runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
        val prefix = locale.toLanguageTag() + "|"
        val words = buildMap {
            root.keys().forEach { key ->
                if (key.startsWith(prefix)) root.optJSONObject(key)?.let {
                    put(key.removePrefix(prefix), LearnedWord(it.optString("word"), it.optInt("count"), it.optInt("choices"), it.optLong("updated")))
                }
            }
        }
        if (learnedCache.size > 16) learnedCache.clear()
        learnedCache[tag] = raw to words
        return words
    }
    private fun readObject(context: Context, key: String): JSONObject = runCatching {
        JSONObject(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key, "{}") ?: "{}")
    }.getOrDefault(JSONObject())
    private fun writeObject(context: Context, key: String, value: JSONObject) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(key, value.toString()).apply()
    }
    private fun trim(root: JSONObject, max: Int) {
        val keys = root.keys().asSequence().toList()
        if (keys.size > max) keys.sortedBy { root.optJSONObject(it)?.optLong("updated") ?: 0L }.take(keys.size - max).forEach(root::remove)
    }
}
