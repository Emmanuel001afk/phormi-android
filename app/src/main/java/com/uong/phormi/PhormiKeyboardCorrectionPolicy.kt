package com.uong.phormi

/** Separates deliberate spelling choices from previously auto-learned typo history. */
internal object PhormiKeyboardCorrectionPolicy {
    fun skip(ignored: Boolean, hasExplicitCorrection: Boolean, learned: Boolean, knownWord: Boolean): Boolean =
        ignored || (!hasExplicitCorrection && (learned || knownWord))
}
