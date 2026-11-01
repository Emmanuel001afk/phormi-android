package com.uong.phormi

import org.junit.Assert.*
import org.junit.Test

class PhormiKeyboardLocalesTest {

    @Test fun localeActuallyChangesTheLetterLayout() {
        assertEquals(listOf("qwertyuiop", "asdfghjkl", "zxcvbnm"), PhormiKeyboardLocales.letterRows("en"))
        assertEquals(listOf("azertyuiop", "qsdfghjklm", "wxcvbn"), PhormiKeyboardLocales.letterRows("fr"))
        assertEquals(listOf("qwertzuiop", "asdfghjkl", "yxcvbnm"), PhormiKeyboardLocales.letterRows("de"))
        assertEquals("AZERTY", PhormiKeyboardLocales.layoutName("fr"))
        assertEquals("QWERTZ", PhormiKeyboardLocales.layoutName("cs"))
        assertEquals("QWERTY", PhormiKeyboardLocales.layoutName("yo"))
    }

    @Test fun onlyLatinScriptLanguagesAreAdvertised() {
        assertTrue(PhormiKeyboardLocales.isLatinScript("en"))
        assertTrue(PhormiKeyboardLocales.isLatinScript("yo"))
        assertTrue(PhormiKeyboardLocales.isLatinScript("vi"))
        assertFalse(PhormiKeyboardLocales.isLatinScript("ar"))
        assertFalse(PhormiKeyboardLocales.isLatinScript("hi"))
        assertFalse(PhormiKeyboardLocales.isLatinScript("zh"))
        assertFalse(PhormiKeyboardLocales.isLatinScript("el"))
    }

    @Test fun accentsAreAvailableForLocalesThatNeedThem() {
        assertEquals("àâäæ", PhormiKeyboardLocales.accents("fr")["a"])
        assertTrue(PhormiKeyboardLocales.accents("yo")["o"]!!.contains('ọ'))
        assertTrue(PhormiKeyboardLocales.accents("vi")["a"]!!.contains('ă'))
        assertTrue(PhormiKeyboardLocales.accents("en").isEmpty())
    }

    @Test fun glideTraceResolvesToARealWordInsteadOfConcatenatedLetters() {
        val words = listOf("hello", "help", "hero", "hell", "hold", "hot", "today", "tomorrow", "the")
        assertEquals("hello", PhormiKeyboardLocales.glideWord("hgfjkllello", words))
        assertEquals("today", PhormiKeyboardLocales.glideWord("tyuioplday", words))
        assertNull(PhormiKeyboardLocales.glideWord("qz", words))
    }

    @Test fun glideIgnoresRepeatedAndNonLetterTraceCharacters() {
        // A finger lingering on one key must not add spurious letters to the trace.
        assertEquals("helo", PhormiKeyboardLocales.normalizeTrace("HHeelllloo"))
        assertEquals("helo", PhormiKeyboardLocales.normalizeTrace("h-e-l-l-o 2"))
    }
}
