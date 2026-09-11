package com.uong.phormi

import android.content.Context
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Offline-first multilingual text engine. Locale comes from the selected IME subtype when available. */
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
        "fr" to mapOf("bonjou" to "bonjour", "merc" to "merci", "commen" to "comment", "beaucou" to "beaucoup", "parceque" to "parce que", "vraimen" to "vraiment", "maintenan" to "maintenant", "demai" to "demain", "desole" to "désolé"),
        "es" to mapOf("gracis" to "gracias", "holaa" to "hola", "buenosdias" to "buenos días", "tambien" to "también", "manana" to "mañana", "felis" to "feliz"),
        "pt" to mapOf("obrigao" to "obrigado", "tambem" to "também", "amanha" to "amanhã", "familia" to "família", "voce" to "você"),
        "de" to mapOf("uber" to "über", "fur" to "für", "schon" to "schön"),
        "it" to mapOf("perche" to "perché", "piu" to "più", "cosi" to "così", "grazzie" to "grazie"),
        "tr" to mapOf("tesekkur" to "teşekkür", "cok" to "çok", "bugun" to "bugün", "yarin" to "yarın", "degil" to "değil"),
        "yo" to mapOf("bawo" to "báwo", "bee ni" to "bẹ́ẹ̀ni", "dada" to "dáadáa", "mo dupe" to "mo dúpẹ́", "ola" to "ọ̀la", "ore" to "ọ̀rẹ́", "ife" to "ìfẹ́", "ayo" to "ayọ̀", "pele" to "pẹ̀lẹ́"),
        "ig" to mapOf("daalu" to "daalụ", "biko" to "biko"),
        "sw" to mapOf("asante" to "asante", "tafadhali" to "tafadhali")
    )

    private val nextSeeds = mapOf(
        "en" to mapOf("good" to listOf("morning", "afternoon", "evening", "luck"), "how" to listOf("are", "is", "was", "do"), "thank" to listOf("you"), "happy" to listOf("birthday", "to", "for"), "i" to listOf("am", "will", "can", "want", "need"), "we" to listOf("are", "can", "will", "should")),
        "fr" to mapOf("bonjour" to listOf("à", "tout", "comment"), "merci" to listOf("beaucoup", "pour"), "je" to listOf("suis", "vais", "peux", "veux"), "nous" to listOf("sommes", "allons", "pouvons")),
        "es" to mapOf("hola" to listOf("a", "todos", "cómo"), "gracias" to listOf("por", "mucho"), "yo" to listOf("soy", "quiero", "puedo")),
        "pt" to mapOf("olá" to listOf("amigo", "a", "todos"), "obrigado" to listOf("por", "muito"), "eu" to listOf("sou", "vou", "posso", "quero")),
        "de" to mapOf("hallo" to listOf("zusammen", "wie", "mein"), "danke" to listOf("dir", "sehr"), "ich" to listOf("bin", "habe", "will", "kann")),
        "it" to mapOf("ciao" to listOf("come", "amico", "a"), "grazie" to listOf("mille", "per"), "io" to listOf("sono", "voglio", "posso")),
        "id" to mapOf("selamat" to listOf("pagi", "siang", "malam"), "terima" to listOf("kasih"), "saya" to listOf("mau", "akan", "bisa")),
        "tr" to mapOf("merhaba" to listOf("nasılsın", "arkadaş"), "teşekkür" to listOf("ederim", "çok"), "ben" to listOf("bir", "de", "çok")),
        "yo" to mapOf("báwo" to listOf("ni", "ni o ṣe"), "mo" to listOf("fẹ́", "wà", "dúpẹ́", "mọ̀"), "ẹ" to listOf("ṣe", "gan")),
        "ig" to mapOf("kedu" to listOf("ka", "ị", "mere"), "daalụ" to listOf("nke ukwuu"), "anyị" to listOf("ga", "nwere", "chọrọ")),
        "ha" to mapOf("sannu" to listOf("da", "lafiya"), "na" to listOf("gode", "son", "ina")),
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

    fun isPassword(info: EditorInfo?): Boolean = variation(info) in setOf(InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)
    fun isUriLike(info: EditorInfo?): Boolean { val v = variation(info); return v == InputType.TYPE_TEXT_VARIATION_URI || v == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS || v == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS }
    fun isNoPersonalizedLearning(info: EditorInfo?): Boolean = info?.imeOptions?.and(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0
    fun isPrivateEditor(info: EditorInfo?): Boolean = isPassword(info) || isNoPersonalizedLearning(info)
    fun allowsPersonalizedLearning(info: EditorInfo?): Boolean = info != null && !isPassword(info) && !isUriLike(info) && !isNoPersonalizedLearning(info)
    fun allowsAiEmoji(info: EditorInfo?): Boolean = info != null && !isPassword(info) && !isUriLike(info) && !isNoPersonalizedLearning(info)
    fun shouldUsePredictions(info: EditorInfo?): Boolean = info != null && !isPassword(info) && !isNoPersonalizedLearning(info) && (info.inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) == 0 && (info.inputType and InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT

    fun localeFor(info: EditorInfo?): Locale = runCatching {
        val companion = PhormiKeyboardServiceV2::class.java.getDeclaredField("Companion").apply { isAccessible = true }.get(null)
        val field = companion.javaClass.declaredFields.firstOrNull { it.name == "instance" }?.apply { isAccessible = true }
        val service = field?.get(companion) as? PhormiKeyboardServiceV2
        val tag = service?.currentInputMethodSubtype?.locale?.replace('_', '-')?.takeIf { it.isNotBlank() }
        if (tag != null) Locale.forLanguageTag(tag) else info?.hintLocales?.get(0) ?: Locale.getDefault()
    }.getOrElse { info?.hintLocales?.get(0) ?: Locale.getDefault() }

    fun currentWord(ic: InputConnection?): String = ic?.getTextBeforeCursor(96, 0)?.toString().orEmpty().trimEnd().split(Regex("[\\s\\n\\r\\t]+"), limit = 0).lastOrNull().orEmpty().takeLastWhile { it.isLetter() || it == '\'' || it == '’' }.take(48)
    fun previousWord(ic: InputConnection?, locale: Locale = Locale.getDefault()): String { val before = ic?.getTextBeforeCursor(180, 0)?.toString().orEmpty().trimEnd(); return before.split(Regex("[\\s\\n\\r\\t]+" )).dropLast(1).lastOrNull().orEmpty().trim(' ', '\'', '"', '.', ',', '!', '?', ':', ';').lowercase(locale) }
    fun supportedLanguageLabels(): List<String> = PhormiKeyboardLanguageData.supportedLanguages().map { Locale(it).getDisplayLanguage(Locale.getDefault()).ifBlank { it } }
    fun languageName(locale: Locale): String = Locale(locale.language).getDisplayLanguage(Locale.getDefault()).ifBlank { locale.language }

    fun suggestions(context: Context, prefix: String, locale: Locale = activeLocale(context)): List<String> {
        val p = prefix.trim().lowercase(locale); if (p.isBlank()) return emptyList()
        val result = LinkedHashSet<String>()
        correctionsByLanguage[locale.language]?.get(p)?.let(result::add)
        loadLearned(context).filter { it.startsWith(p, true) }.forEach(result::add)
        PhormiKeyboardSystemSpellChecker.cached(p, locale).forEach(result::add)
        PhormiKeyboardLanguageData.words(locale).filter { it.startsWith(p, true) && !it.equals(p, true) }.forEach(result::add)
        PhormiKeyboardSystemSpellChecker.request(context, p, locale)
        return result.take(5)
    }

    fun nextWordSuggestions(context: Context, previous: String, locale: Locale = activeLocale(context)): List<String> {
        val key = previous.lowercase(locale).trim(); if (key.isBlank()) return emptyList()
        val result = LinkedHashSet<String>(); nextSeeds[locale.language]?.get(key).orEmpty().forEach(result::add); loadAllNextWords(context)[key].orEmpty().forEach(result::add); return result.take(4)
    }
    fun correctionFor(context: Context, word: String, locale: Locale = activeLocale(context)): String? = correctionsByLanguage[locale.language]?.get(word.lowercase(locale)) ?: loadLearnedCorrections(context)[word.lowercase(locale)]
    fun correctionFor(word: String): String? = correctionsByLanguage[Locale.getDefault().language]?.get(word.lowercase(Locale.getDefault()))

    fun learn(context: Context, word: String, info: EditorInfo?) { if (!allowsPersonalizedLearning(info)) return; val value=word.trim(); if(value.length !in 2..64||value.any(Char::isDigit))return; val all=loadLearned(context).toMutableList(); all.removeAll{it.equals(value,true)}; all.add(0,value); saveLearned(context,all) }
    fun learnPair(context: Context, previous: String, current: String, info: EditorInfo?) { if(!allowsPersonalizedLearning(info)||previous.isBlank()||current.isBlank())return; val key=previous.lowercase(localeFor(info)); val map=loadAllNextWords(context).toMutableMap(); map[key]=(map[key].orEmpty().filterNot{it.equals(current,true)}+current.take(64)).takeLast(10); saveAllNextWords(context,map) }
    fun learnCorrection(context: Context, original: String, accepted: String, info: EditorInfo?) { if(!allowsPersonalizedLearning(info))return; val a=original.trim();val b=accepted.trim();if(a.length !in 2..64||b.isBlank()||a.equals(b,true))return;val map=loadLearnedCorrections(context).toMutableMap();map[a.lowercase(localeFor(info))]=b;saveLearnedCorrections(context,map) }
    fun autoCapitalize(ic: InputConnection?, info: EditorInfo?): Boolean { if(ic==null||info==null||isPrivateEditor(info))return false;val type=info.inputType;if(type and InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS!=0||type and InputType.TYPE_TEXT_FLAG_CAP_WORDS!=0)return true;if(type and InputType.TYPE_TEXT_FLAG_CAP_SENTENCES==0)return false;val before=ic.getTextBeforeCursor(80,0)?.toString().orEmpty().trimEnd();return before.isEmpty()||before.lastOrNull() in setOf('.', '!', '?', ':', ';','\n') }
    fun contextBeforeCursor(ic: InputConnection?): String { val service=currentService()?:return "";if(!allowsAiEmoji(service.currentInputEditorInfo))return "";return ic?.getTextBeforeCursor(240,0)?.toString().orEmpty() }

    private fun activeLocale(context: Context): Locale = if(context is PhormiKeyboardServiceV2) localeFor(context.currentInputEditorInfo) else Locale.getDefault()
    private fun currentService(): PhormiKeyboardServiceV2?=runCatching{val companion=PhormiKeyboardServiceV2::class.java.getDeclaredField("Companion").apply{isAccessible=true}.get(null);val field=companion.javaClass.declaredFields.firstOrNull{it.name=="instance"}?.apply{isAccessible=true};field?.get(companion) as? PhormiKeyboardServiceV2}.getOrNull()
    private fun loadLearned(context:Context):List<String>=runCatching{val a=JSONArray(context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getString(KEY_LEARNED,"[]")?:"[]");buildList{for(i in 0 until a.length())a.optString(i).takeIf{it.isNotBlank()}?.let(::add)}}.getOrDefault(emptyList())
    private fun saveLearned(context:Context,words:List<String>){val a=JSONArray();words.take(MAX_LEARNED).forEach(a::put);context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putString(KEY_LEARNED,a.toString()).apply()}
    private fun loadAllNextWords(context:Context):Map<String,List<String>>=runCatching{val o=JSONObject(context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getString(KEY_BIGRAMS,"{}")?:"{}");buildMap{o.keys().forEach{k->val a=o.optJSONArray(k)?:return@forEach;put(k,buildList{for(i in 0 until a.length())a.optString(i).takeIf{it.isNotBlank()}?.let(::add)})}}}.getOrDefault(emptyMap())
    private fun saveAllNextWords(context:Context,map:Map<String,List<String>>){val o=JSONObject();map.entries.take(MAX_BIGRAMS).forEach{(k,v)->o.put(k,JSONArray(v.take(10)))};context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putString(KEY_BIGRAMS,o.toString()).apply()}
    private fun loadLearnedCorrections(context:Context):Map<String,String>=runCatching{val o=JSONObject(context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getString(KEY_CORRECTIONS,"{}")?:"{}");buildMap{o.keys().forEach{k->o.optString(k).takeIf{it.isNotBlank()}?.let{put(k,it)}}}}.getOrDefault(emptyMap())
    private fun saveLearnedCorrections(context:Context,map:Map<String,String>){val o=JSONObject();map.entries.take(MAX_CORRECTIONS).forEach{(k,v)->o.put(k,v)};context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putString(KEY_CORRECTIONS,o.toString()).apply()}
    private fun variation(info:EditorInfo?):Int=(info?.inputType?:0) and InputType.TYPE_MASK_VARIATION
}
