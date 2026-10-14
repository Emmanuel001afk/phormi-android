package com.uong.phormi

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class PhormiTypingRulesTest {
    @Test fun `a trailing space starts a new word rather than replacing the completed one`() {
        assertEquals("", PhormiTypingRules.currentWord("hello "))
        assertEquals("hello", PhormiTypingRules.previousWord("hello "))
        assertEquals("wo", PhormiTypingRules.currentWord("hello wo"))
        assertEquals("hello", PhormiTypingRules.previousWord("hello wo"))
    }
    @Test fun `combining marks and apostrophes are part of multilingual words`() {
        assertEquals("ọ\u0301rẹ\u0301", PhormiTypingRules.currentWord("mo fẹ́ ọ\u0301rẹ\u0301"))
        assertEquals("don't", PhormiTypingRules.currentWord("please don't"))
        assertEquals("", PhormiTypingRules.currentWord("hello,"))
    }
    @Test fun `correction preserves title and uppercase spelling`() {
        assertEquals("The", PhormiTypingRules.matchCase("Teh", "the", Locale.US))
        assertEquals("THE", PhormiTypingRules.matchCase("TEH", "the", Locale.US))
        assertEquals("the", PhormiTypingRules.matchCase("teh", "the", Locale.US))
    }
    @Test fun `backspace removes complete emoji and accent sequences`() {
        for (sequence in listOf("😀", "👍🏾", "🇳🇬", "👨‍👩‍👧‍👦", "❤️", "1️⃣", "ẹ\u0301")) {
            assertEquals(sequence, sequence.length, PhormiTypingRules.deletionLength("hello $sequence"))
        }
        assertEquals(1, PhormiTypingRules.deletionLength("hello"))
        assertEquals(0, PhormiTypingRules.deletionLength(""))
    }
}
