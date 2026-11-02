package com.uong.phormi

/** Unicode Hangul syllable composition for the standard Korean two-set keyboard.
 * Replaying the short key buffer makes backspace reverse vowel/final-consonant splits exactly. */
object PhormiHangulComposer {
    const val INITIALS = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ"
    const val VOWELS = "ㅏㅐㅑㅒㅓㅔㅕㅖㅗㅘㅙㅚㅛㅜㅝㅞㅟㅠㅡㅢㅣ"
    const val FINALS = " ㄱㄲㄳㄴㄵㄶㄷㄹㄺㄻㄼㄽㄾㄿㅀㅁㅂㅄㅅㅆㅇㅈㅊㅋㅌㅍㅎ"
    private val compoundVowels = mapOf("ㅗㅏ" to 'ㅘ', "ㅗㅐ" to 'ㅙ', "ㅗㅣ" to 'ㅚ', "ㅜㅓ" to 'ㅝ', "ㅜㅔ" to 'ㅞ', "ㅜㅣ" to 'ㅟ', "ㅡㅣ" to 'ㅢ', "ㅘㅣ" to 'ㅙ', "ㅝㅣ" to 'ㅞ')
    private val compoundFinals = mapOf("ㄱㅅ" to 'ㄳ', "ㄴㅈ" to 'ㄵ', "ㄴㅎ" to 'ㄶ', "ㄹㄱ" to 'ㄺ', "ㄹㅁ" to 'ㄻ', "ㄹㅂ" to 'ㄼ', "ㄹㅅ" to 'ㄽ', "ㄹㅌ" to 'ㄾ', "ㄹㅍ" to 'ㄿ', "ㄹㅎ" to 'ㅀ', "ㅂㅅ" to 'ㅄ')
    private val splitFinals = compoundFinals.entries.associate { it.value to it.key }
    private val shifted = mapOf('ㅂ' to 'ㅃ', 'ㅈ' to 'ㅉ', 'ㄷ' to 'ㄸ', 'ㄱ' to 'ㄲ', 'ㅅ' to 'ㅆ', 'ㅐ' to 'ㅒ', 'ㅔ' to 'ㅖ')
    fun shifted(letter: String): String = letter.map { shifted[it] ?: it }.joinToString("")
    fun accepts(text: String): Boolean = text.isNotEmpty() && text.all { it in INITIALS || it in VOWELS }
    fun compose(keys: String): String {
        val output = StringBuilder()
        var initial = -1; var vowel = -1; var final = 0
        fun flush() {
            when {
                initial >= 0 && vowel >= 0 -> output.append((0xAC00 + (initial * 21 + vowel) * 28 + final).toChar())
                initial >= 0 -> output.append(INITIALS[initial])
                vowel >= 0 -> output.append(VOWELS[vowel])
            }
            initial = -1; vowel = -1; final = 0
        }
        for (key in keys) {
            val nextVowel = VOWELS.indexOf(key)
            val nextInitial = INITIALS.indexOf(key)
            when {
                nextVowel >= 0 -> {
                    when {
                        vowel < 0 -> vowel = nextVowel
                        final > 0 -> {
                            val oldFinal = FINALS[final]
                            val split = splitFinals[oldFinal]
                            final = if (split != null) FINALS.indexOf(split[0]) else 0
                            flush()
                            initial = INITIALS.indexOf(split?.get(1) ?: oldFinal)
                            vowel = nextVowel
                        }
                        else -> {
                            val combined = compoundVowels["${VOWELS[vowel]}$key"]
                            if (combined != null) vowel = VOWELS.indexOf(combined)
                            else { flush(); vowel = nextVowel }
                        }
                    }
                }
                nextInitial >= 0 -> {
                    val nextFinal = FINALS.indexOf(key)
                    val combined = if (final > 0) compoundFinals["${FINALS[final]}$key"] else null
                    when {
                        initial >= 0 && vowel >= 0 && final == 0 && nextFinal > 0 -> final = nextFinal
                        initial >= 0 && vowel >= 0 && combined != null -> final = FINALS.indexOf(combined)
                        else -> { flush(); initial = nextInitial }
                    }
                }
                else -> { flush(); output.append(key) }
            }
        }
        flush()
        return output.toString()
    }
}
