package com.uong.phormi

import org.junit.Assert.*
import org.junit.Test

class PhormiPinSecurityTest {
    @Test fun `new PIN uses a unique salted slow verifier`() {
        assertFalse(PhormiPinVerifier.isValidNewPin("1234"))
        assertFalse(PhormiPinVerifier.isValidNewPin("1234567"))
        assertFalse(PhormiPinVerifier.isValidNewPin("1234abcd"))
        assertFalse(PhormiPinVerifier.isValidNewPin("1".repeat(PhormiPinVerifier.MAX_PIN_LENGTH + 1)))
        assertTrue(PhormiPinVerifier.isValidNewPin("12345678"))

        val first = PhormiPinVerifier.encode("12345678")
        val second = PhormiPinVerifier.encode("12345678")
        assertNotEquals(first, second)
        assertTrue(PhormiPinVerifier.verify(first, "12345678").valid)
        assertFalse(PhormiPinVerifier.verify(first, "87654321").valid)
        assertFalse(first.startsWith(PhormiPinVerifier.legacySha256("12345678")))
    }

    @Test fun `successful legacy verification is marked for migration`() {
        val legacy = PhormiPinVerifier.legacySha256("1234")
        assertEquals(PhormiPinVerifier.Verification(valid = true, needsUpgrade = true),
            PhormiPinVerifier.verify(legacy, "1234"))
        assertFalse(PhormiPinVerifier.verify(legacy, "9999").valid)
    }

    @Test fun `repeated PIN failures apply increasing local delays`() {
        var state = PhormiPinAttemptPolicy.State()
        val now = 10_000L
        repeat(PhormiPinAttemptPolicy.MAX_UNTHROTTLED_FAILURES) {
            state = PhormiPinAttemptPolicy.afterFailure(state, now)
            assertEquals(0L, PhormiPinAttemptPolicy.remainingMs(state, now))
        }
        state = PhormiPinAttemptPolicy.afterFailure(state, now)
        assertEquals(PhormiPinAttemptPolicy.BASE_LOCKOUT_MS,
            PhormiPinAttemptPolicy.remainingMs(state, now))

        val afterFirstLockout = PhormiPinAttemptPolicy.afterFailure(state, now + PhormiPinAttemptPolicy.BASE_LOCKOUT_MS)
        assertEquals(PhormiPinAttemptPolicy.BASE_LOCKOUT_MS * 2,
            PhormiPinAttemptPolicy.remainingMs(afterFirstLockout, now + PhormiPinAttemptPolicy.BASE_LOCKOUT_MS))
        assertEquals(PhormiPinAttemptPolicy.State(), PhormiPinAttemptPolicy.afterSuccess())
    }
}
