package com.uong.phormi

import org.junit.Assert.*
import org.junit.Test

class PhormiHangulComposerTest {
    @Test fun writesKoreanWordsAndSplitsFinalBeforeFollowingVowel() {
        assertEquals("한글", PhormiHangulComposer.compose("ㅎㅏㄴㄱㅡㄹ"))
        assertEquals("안녕하세요", PhormiHangulComposer.compose("ㅇㅏㄴㄴㅕㅇㅎㅏㅅㅔㅇㅛ"))
        assertEquals("갑사", PhormiHangulComposer.compose("ㄱㅏㅂㅅㅏ"))
        assertEquals("가나", PhormiHangulComposer.compose("ㄱㅏㄴㅏ"))
    }
    @Test fun backspaceUnwindsCompoundVowelsAndFinals() {
        assertEquals("과", PhormiHangulComposer.compose("ㄱㅗㅏ"))
        assertEquals("고", PhormiHangulComposer.compose("ㄱㅗ"))
        assertEquals("값", PhormiHangulComposer.compose("ㄱㅏㅂㅅ"))
        assertEquals("갑", PhormiHangulComposer.compose("ㄱㅏㅂ"))
    }
    @Test fun allModernInitialVowelCombinationsFollowUnicodeFormula() {
        PhormiHangulComposer.INITIALS.forEachIndexed { l, consonant ->
            PhormiHangulComposer.VOWELS.forEachIndexed { v, vowel ->
                assertEquals((0xAC00 + (l * 21 + v) * 28).toChar().toString(), PhormiHangulComposer.compose("$consonant$vowel"))
            }
        }
    }
    @Test fun doubleConsonantsAreShiftedAndInvalidTextPassesThrough() {
        assertEquals("ㄲ", PhormiHangulComposer.shifted("ㄱ"))
        assertEquals("까", PhormiHangulComposer.compose("ㄲㅏ"))
        assertEquals("한 글!", PhormiHangulComposer.compose("ㅎㅏㄴ ㄱㅡㄹ!"))
    }
}
