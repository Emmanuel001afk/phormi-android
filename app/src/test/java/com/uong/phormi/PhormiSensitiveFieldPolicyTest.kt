package com.uong.phormi

import org.junit.Assert.*
import org.junit.Test

class PhormiSensitiveFieldPolicyTest {
    @Test fun `HTML classifier catches OTP and payment fields from every label source`() {
        assertTrue(PhormiSensitiveFieldPolicy.isSensitiveHtmlField(type = "number", name = "login_otp"))
        assertTrue(PhormiSensitiveFieldPolicy.isSensitiveHtmlField(autocomplete = "one-time-code"))
        assertTrue(PhormiSensitiveFieldPolicy.isSensitiveHtmlField(placeholder = "CVV"))
        assertTrue(PhormiSensitiveFieldPolicy.isSensitiveHtmlField(ariaLabel = "Card security code"))
        assertTrue(PhormiSensitiveFieldPolicy.isSensitiveHtmlField(type = "password"))
        assertTrue(PhormiSensitiveFieldPolicy.isSensitiveHtmlField(id = "creditCardNumber"))
        assertTrue(PhormiSensitiveFieldPolicy.isSensitiveHtmlField(name = "apiKey"))
        assertTrue(PhormiSensitiveFieldPolicy.isSensitiveHtmlField(name = "APIKey"))
        assertTrue(PhormiSensitiveFieldPolicy.isSensitiveHtmlField(name = "OTPCode"))
        assertTrue(PhormiSensitiveFieldPolicy.isSensitiveHtmlField(name = "private_key"))
        assertTrue(PhormiSensitiveFieldPolicy.isSensitiveHtmlField(inputMode = "one-time-code"))
    }

    @Test fun `likely payment card numbers are redacted from AI text`() {
        assertEquals("Use [private number] to pay", PhormiSensitiveFieldPolicy
            .redactLikelyPaymentCardNumbers("Use 4111 1111 1111 1111 to pay"))
        assertEquals("Order 1234567", PhormiSensitiveFieldPolicy
            .redactLikelyPaymentCardNumbers("Order 1234567"))
    }

    @Test fun `ordinary fields are not hidden by incidental keyword substrings`() {
        assertFalse(PhormiSensitiveFieldPolicy.isSensitiveHtmlField(
            type = "number", name = "quantity", placeholder = "Shipping quantity"
        ))
        assertFalse(PhormiSensitiveFieldPolicy.isSensitiveHtmlField(type = "search", name = "query"))
    }

    @Test fun `accessibility hides password metadata and numeric PIN variations`() {
        assertTrue(PhormiSensitiveFieldPolicy.isSensitiveAccessibilityInput(
            editable = true, isPassword = true, inputType = 0, clues = null
        ))
        assertTrue(PhormiSensitiveFieldPolicy.isSensitiveAccessibilityInput(
            editable = true, isPassword = false, inputType = 0x00000002 or 0x00000010, clues = null
        ))
        assertTrue(PhormiSensitiveFieldPolicy.isSensitiveAccessibilityInput(
            editable = true, isPassword = false, inputType = 0x00000001 or 0x00000080, clues = null
        ))
        assertTrue(PhormiSensitiveFieldPolicy.isSensitiveAccessibilityInput(
            editable = true, isPassword = false, inputType = 0, clues = "verification OTP"
        ))
    }

    @Test fun `normal accessibility text fields remain available`() {
        assertFalse(PhormiSensitiveFieldPolicy.isSensitiveAccessibilityInput(
            editable = true, isPassword = false, inputType = 0x00000001, clues = "Search the web"
        ))
    }
}
