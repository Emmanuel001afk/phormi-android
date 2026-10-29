package com.uong.phormi

import org.junit.Assert.*
import org.junit.Test

class PhormiBrowserAiSafetyTest {
    @Test fun onlyWebUrlsWithoutEmbeddedCredentialsAreAllowed() {
        assertTrue(PhormiBrowserAiSafety.allowedUrl("https://example.org/path"))
        assertFalse(PhormiBrowserAiSafety.allowedUrl("javascript:alert(1)"))
        assertFalse(PhormiBrowserAiSafety.allowedUrl("file:///sdcard/secret"))
        assertFalse(PhormiBrowserAiSafety.allowedUrl("https://user:pass@example.org/"))
    }

    @Test fun submittingAndSensitiveClicksNeedApproval() {
        assertTrue(PhormiBrowserAiSafety.requiresConfirmation("press_enter", ""))
        assertTrue(PhormiBrowserAiSafety.requiresConfirmation("click", "Send message"))
        assertTrue(PhormiBrowserAiSafety.requiresConfirmation("click", "Delete account"))
        assertFalse(PhormiBrowserAiSafety.requiresConfirmation("scroll", "Send message"))
    }
}
