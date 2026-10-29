package com.uong.phormi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhormiKeyboardOnlineDictionaryTest {
    @Test fun returnsOnlySingleSafeWordsAndDeduplicatesProviderData() {
        val json = """[{"word":"hello","score":900},{"word":"hello"},{"word":"hello world"},
            {"word":"help"},{"word":"<script>"},{"word":"don't"}]"""
        assertEquals(listOf("hello", "help", "don't"), PhormiKeyboardOnlineDictionary.parseWords(json))
    }

    @Test fun commonSignInFieldsAreNeverEligibleForNetworkHints() {
        assertTrue(PhormiKeyboardOnlineDictionary.isSensitiveFieldHint("User name"))
        assertTrue(PhormiKeyboardOnlineDictionary.isSensitiveFieldHint("two factor verification code"))
        assertTrue(PhormiKeyboardOnlineDictionary.isSensitiveFieldHint("email address"))
        assertTrue(PhormiKeyboardOnlineDictionary.isSensitiveFieldHint("loginUsername"))
        assertFalse(PhormiKeyboardOnlineDictionary.isSensitiveFieldHint("Write a comment while typing"))
    }
}
