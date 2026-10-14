package com.uong.phormi

import java.util.Locale

/** Direct, deterministic layouts. Long-presses preserve locale-specific letters/diacritics. */
internal object PhormiKeyboardLayouts {
    fun rows(locale: Locale): List<String> = when (locale.language) {
        "fr" -> listOf("azertyuiop", "qsdfghjklm", "wxcvbn")
        "de", "cs" -> listOf("qwertzuiop", "asdfghjkl", "yxcvbnm")
        "es" -> listOf("qwertyuiop", "asdfghjklñ", "zxcvbnm")
        "tr" -> listOf("qwertyuıopğü", "asdfghjklşi", "zxcvbnmöç")
        "ru" -> listOf("йцукенгшщзх", "фывапролджэ", "ячсмитьбю")
        "uk" -> listOf("йцукенгшщзх", "фівапролджє", "ячсмитьбюї")
        "ar" -> listOf("ضصثقفغعهخحج", "شسيبلاتنمكط", "ئءؤرلىةوزظ")
        "he" -> listOf("קראטוןםפ", "שדגכעיחלךף", "זסבהנמצתץ")
        "el" -> listOf("ςερτυθιοπ", "ασδφγηξκλ", "ζχψωβνμ")
        else -> listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
    }

    fun alternatives(letter: String, locale: Locale): List<String> {
        val common = mapOf("a" to "àáâäãåāæ", "c" to "çćč", "e" to "èéêëēėę", "i" to "ìíîïīı", "n" to "ñńṅ", "o" to "òóôöõōøœ", "s" to "ßśšṣ", "u" to "ùúûüūụ", "y" to "ýÿ", "z" to "žźż")
        val localized = when (locale.language) {
            "yo" -> mapOf("a" to listOf("à", "á", "ā"), "e" to listOf("ẹ", "ẹ́", "ẹ̀", "é", "è"), "i" to listOf("í", "ì"), "o" to listOf("ọ", "ọ́", "ọ̀", "ó", "ò"), "s" to listOf("ṣ"), "n" to listOf("ń", "ǹ"))
            "ig" -> mapOf("a" to listOf("á", "à"), "i" to listOf("ị", "ị́", "ị̀", "í", "ì"), "o" to listOf("ọ", "ọ́", "ọ̀", "ó", "ò"), "u" to listOf("ụ", "ụ́", "ụ̀", "ú", "ù"), "n" to listOf("ṅ", "ñ"))
            "ha" -> mapOf("b" to listOf("ɓ"), "d" to listOf("ɗ"), "k" to listOf("ƙ"), "y" to listOf("ƴ"))
            "tr" -> mapOf("c" to listOf("ç"), "g" to listOf("ğ"), "i" to listOf("İ", "ı"), "o" to listOf("ö"), "s" to listOf("ş"), "u" to listOf("ü"))
            "ru" -> mapOf("е" to listOf("ё"), "ь" to listOf("ъ"))
            "ar" -> mapOf("ا" to listOf("أ", "إ", "آ", "ً", "َ"), "و" to listOf("ؤ", "ُ", "ٌ"), "ي" to listOf("ئ", "ى", "ِ", "ٍ"), "ل" to listOf("لا", "لأ", "لإ"))
            else -> emptyMap()
        }
        return localized[letter] ?: common[letter]?.map(Char::toString).orEmpty()
    }
}
