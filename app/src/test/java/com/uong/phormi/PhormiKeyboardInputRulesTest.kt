package com.uong.phormi

import org.junit.Assert.*
import org.junit.Test

class PhormiKeyboardInputRulesTest {
    @Test fun ordinaryCharactersAndEmptyEditor() {
        assertEquals(0, PhormiKeyboardInputRules.deletionLength(""))
        assertEquals(1, PhormiKeyboardInputRules.deletionLength("hello"))
        assertEquals(2, PhormiKeyboardInputRules.deletionLength("hi\r\n"))
    }
    @Test fun emojiAndCombiningSequencesAreNotSplit() {
        for (cluster in listOf("😀", "👍🏽", "👨‍👩‍👧‍👦", "🇳🇬", "1️⃣", "ọ\u0301", "a\u0301\u0323")) {
            assertEquals(cluster, cluster.length, PhormiKeyboardInputRules.deletionLength("prefix $cluster"))
        }
    }
    @Test fun regionalIndicatorsDeleteOneFlagAtATime() {
        assertEquals(4, PhormiKeyboardInputRules.deletionLength("🇳🇬🇬🇧"))
        assertEquals(2, PhormiKeyboardInputRules.deletionLength("🇳"))
    }
    @Test fun wordExtractionPreservesCombiningMarks() {
        assertEquals("dúpẹ\u0301", PhormiKeyboardInputRules.currentWord("mo dúpẹ\u0301"))
        assertEquals("don't", PhormiKeyboardInputRules.currentWord("don't"))
        assertEquals("", PhormiKeyboardInputRules.currentWord("hello "))
    }
    @Test fun layoutsContainActualCharacters() {
        assertEquals("azertyuiop", PhormiKeyboardLayouts.rows("fr").first())
        assertTrue(PhormiKeyboardLayouts.rows("ar").joinToString("").contains('ض'))
        assertTrue(PhormiKeyboardLayouts.rows("ru").joinToString("").contains('я'))
        assertTrue(PhormiKeyboardLayouts.alternatives("o", "yo").contains("ọ"))
        assertFalse(PhormiKeyboardLayouts.supported.contains("ja"))
    }
}
