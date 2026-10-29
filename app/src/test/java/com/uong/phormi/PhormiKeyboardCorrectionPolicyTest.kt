package com.uong.phormi

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhormiKeyboardCorrectionPolicyTest {
    @Test fun earlierLearnedTypoMustNotSuppressExplicitCorrection() {
        assertFalse(PhormiKeyboardCorrectionPolicy.skip(
            ignored = false, hasExplicitCorrection = true, learned = true, knownWord = true))
    }

    @Test fun deliberateRetypeOrBackspaceUndoAlwaysWins() {
        assertTrue(PhormiKeyboardCorrectionPolicy.skip(
            ignored = true, hasExplicitCorrection = true, learned = false, knownWord = false))
    }

    @Test fun knownWordsAndAcceptedUnknownSpellingRemainIntact() {
        assertTrue(PhormiKeyboardCorrectionPolicy.skip(false, false, false, true))
        assertTrue(PhormiKeyboardCorrectionPolicy.skip(false, false, true, false))
        assertFalse(PhormiKeyboardCorrectionPolicy.skip(false, false, false, false))
    }
}
