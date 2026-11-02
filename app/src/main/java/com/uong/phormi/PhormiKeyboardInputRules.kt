package com.uong.phormi

/** Pure text rules shared by the IME and regression tests. Offsets are UTF-16 offsets. */
object PhormiKeyboardInputRules {
    fun isWordPart(c: Char): Boolean = c.isLetter() || c == '\'' || c == '’' ||
        Character.getType(c) in setOf(Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt())

    fun currentWord(before: String): String = before.takeLastWhile(::isWordPart).takeLast(96)

    /** Delete one extended emoji/marked-letter cluster, not half a surrogate pair. */
    fun deletionLength(before: String): Int {
        if (before.isEmpty()) return 0
        fun previous(index: Int) = index - Character.charCount(before.codePointBefore(index))
        fun extender(cp: Int) = Character.getType(cp) in setOf(Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt()) ||
            cp in 0x1F3FB..0x1F3FF || cp in 0xE0020..0xE007F || cp in 0xFE00..0xFE0F || cp in 0xE0100..0xE01EF
        var start = previous(before.length)
        val last = before.codePointAt(start)
        if (last == 10 && start > 0 && before[start - 1] == '\r') return 2
        if (last in 0x1F1E6..0x1F1FF) {
            var count = 1
            var i = start
            while (i > 0 && before.codePointBefore(i) in 0x1F1E6..0x1F1FF) { count++; i = previous(i) }
            return if (count % 2 == 0) before.length - previous(start) else before.length - start
        }
        while (start > 0 && extender(before.codePointAt(start))) start = previous(start)
        while (start > 0 && before.codePointBefore(start) == 0x200D) {
            start = previous(start)
            if (start > 0) start = previous(start)
            while (start > 0 && extender(before.codePointAt(start))) start = previous(start)
        }
        return before.length - start
    }
}
