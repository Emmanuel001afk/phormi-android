package com.uong.phormi

import org.junit.Assert.*
import org.junit.Test

class PhormiKeyboardAiContextTest {
    @Test fun contextSelectsUnicodeEmojiRatherThanImages() {
        assertEquals("❤️", PhormiKeyboardAiContext.suggestions("I love you").first().emoji)
        assertEquals("🙏", PhormiKeyboardAiContext.suggestions("Thank you so much").first().emoji)
        assertTrue(PhormiKeyboardAiContext.suggestions("Is that okay?").any { it.emoji == "🤔" })
    }

    @Test fun fallbacksRemainAvailableWithoutTypedContext() {
        assertTrue(PhormiKeyboardAiContext.suggestions("").isNotEmpty())
    }
}
