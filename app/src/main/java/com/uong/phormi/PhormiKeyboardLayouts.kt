package com.uong.phormi

/** Actual character layouts, not language labels masquerading as input implementations.
 * Korean uses a two-set syllable composer; other unimplemented composing methods stay unadvertised. */
object PhormiKeyboardLayouts {
    val supported = setOf("en", "fr", "es", "pt", "de", "it", "id", "tr", "yo", "ig", "ha", "sw", "vi", "ru", "uk", "pl", "nl", "sv", "no", "da", "fi", "cs", "ro", "hu", "el", "he", "ar", "ko")
    fun rows(language: String): List<String> = when (language) {
        "ko" -> listOf("ㅂㅈㄷㄱㅅㅛㅕㅑㅐㅔ", "ㅁㄴㅇㄹㅎㅗㅓㅏㅣ", "ㅋㅌㅊㅍㅠㅜㅡ")
        "fr" -> listOf("azertyuiop", "qsdfghjklm", "wxcvbn")
        "de", "cs" -> listOf("qwertzuiop", "asdfghjkl", "yxcvbnm")
        "tr" -> listOf("qwertyuıopğü", "asdfghjklşi", "zxcvbnmöç")
        "es" -> listOf("qwertyuiop", "asdfghjklñ", "zxcvbnm")
        "ru" -> listOf("йцукенгшщзх", "фывапролджэ", "ячсмитьбю")
        "uk" -> listOf("йцукенгшщзх", "фівапролджє", "ячсмитьбюї")
        "el" -> listOf("ςερτυθιοπ", "ασδφγηξκλ", "ζχψωβνμ")
        "he" -> listOf("קראטוןםפ", "שדגכעיחלךף", "זסבהנמצתץ")
        "ar" -> listOf("ضصثقفغعهخحج", "شسيبلاتنمك", "ئءؤرلاىةوزظ")
        else -> listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
    }

    // Long press uses a window-anchored popup, never an Activity dialog from an IME.
    fun alternatives(letter: String, language: String): List<String> {
        val regional = when (language) {
            "yo" -> mapOf("a" to "àáā", "e" to "ẹéè", "i" to "íì", "o" to "ọóò", "s" to "ṣ", "u" to "úù", "n" to "ńǹ")
            "ig" -> mapOf("a" to "áà", "e" to "éè", "i" to "ịíì", "o" to "ọóò", "u" to "ụúù", "n" to "ṅń")
            "ha" -> mapOf("b" to "ɓ", "d" to "ɗ", "k" to "ƙ", "y" to "ƴ")
            "ru" -> mapOf("е" to "ё", "ь" to "ъ")
            "uk" -> mapOf("г" to "ґ", "ь" to "’")
            "ar" -> mapOf("ا" to "أإآ", "ي" to "ى", "و" to "ؤ", "ه" to "ة")
            "el" -> mapOf("α" to "ά", "ε" to "έ", "η" to "ή", "ι" to "ίϊΐ", "ο" to "ό", "υ" to "ύϋΰ", "ω" to "ώ")
            else -> emptyMap()
        }
        val common = mapOf(
            "a" to "áàâäãåāăąæ", "c" to "çćč", "d" to "ďđ", "e" to "éèêëēėęě",
            "g" to "ğ", "i" to "íìîïīįı", "l" to "ł", "n" to "ñńň", "o" to "óòôöõøōőœ",
            "r" to "ř", "s" to "ßśšşș", "t" to "ťț", "u" to "úùûüūůű", "y" to "ýÿ", "z" to "źżž"
        )
        val base = (regional[letter] ?: common[letter]).orEmpty().map(Char::toString)
        // Combining marks allow composed Yoruba/Igbo/Vietnamese vowels without inventing a transliteration engine.
        return if (language in setOf("yo", "ig", "vi") && letter in setOf("a", "e", "i", "o", "u"))
            (base + listOf("\u0301", "\u0300", "\u0304", "\u0309", "\u0303", "\u0323")).distinct() else base
    }
}
