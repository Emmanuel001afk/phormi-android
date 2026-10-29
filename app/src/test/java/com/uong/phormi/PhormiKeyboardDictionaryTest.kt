package com.uong.phormi

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class PhormiKeyboardDictionaryTest {
    private val ranks = listOf("help", "hello", "test", "today", "tomorrow")
        .withIndex().associate { it.value to it.index }

    @Test fun repeatedLetterBeatsAnUnrelatedHighFrequencyWord() {
        assertEquals("hello", PhormiKeyboardDictionary.candidateFor("helo", ranks, Locale.ENGLISH))
    }

    @Test fun swapsCanBeCorrectedButValidOrDistantWordsStayUntouched() {
        assertEquals("test", PhormiKeyboardDictionary.candidateFor("tset", ranks, Locale.ENGLISH))
        assertNull(PhormiKeyboardDictionary.candidateFor("test", ranks, Locale.ENGLISH))
        assertNull(PhormiKeyboardDictionary.candidateFor("phormi", ranks, Locale.ENGLISH))
    }
}
