package com.uong.phormi

import java.util.Locale

/** Pure text-boundary rules shared by predictions, correction undo and tests. */
internal object PhormiTypingRules {
    private val word = Regex("[\\p{L}\\p{M}]+(?:['’][\\p{L}\\p{M}]+)*")
    private val current = Regex("[\\p{L}\\p{M}]+(?:['’][\\p{L}\\p{M}]+)*$")

    fun currentWord(before: String): String = current.find(before)?.value.orEmpty().takeLast(64)

    fun previousWord(before: String): String {
        val words = word.findAll(before).map { it.value }.toList()
        return if (currentWord(before).isNotEmpty()) words.dropLast(1).lastOrNull().orEmpty()
        else words.lastOrNull().orEmpty()
    }

    fun matchCase(original: String, replacement: String, locale: Locale): String = when {
        original.length > 1 && original.filter(Char::isLetter).all(Char::isUpperCase) -> replacement.uppercase(locale)
        original.firstOrNull()?.isUpperCase() == true -> replacement.replaceFirstChar { it.titlecase(locale) }
        else -> replacement
    }

    /** UTF-16 length of the final extended emoji/combining sequence (not just one code unit). */
    fun deletionLength(before: String): Int {
        if (before.isEmpty()) return 0
        var start = before.length
        fun previous(): Int = before.codePointBefore(start)
        fun take(): Int { val cp = previous(); start -= Character.charCount(cp); return cp }
        fun extension(cp: Int): Boolean = cp == 0xFE0F || cp == 0xFE0E || cp == 0x20E3 ||
            cp in 0x1F3FB..0x1F3FF || cp in 0xE0020..0xE007F ||
            Character.getType(cp) in setOf(Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt())
        var cp = take()
        if (cp in 0x1F1E6..0x1F1FF && start > 0 && previous() in 0x1F1E6..0x1F1FF) take()
        else {
            while (extension(cp) && start > 0) cp = take()
            while (start > 0 && previous() == 0x200D) {
                take()
                if (start > 0) {
                    cp = take()
                    while (extension(cp) && start > 0) cp = take()
                }
            }
        }
        return before.length - start
    }
}
