package com.uong.phormi

import java.util.Locale

/** Shared, fail-closed rules for fields exposed to or filled by browser AI. */
internal object PhormiSensitiveFieldPolicy {
    private const val SENSITIVE_PATTERN =
        "(?:^|[^a-z0-9])(?:password|passcode|passphrase|one[\\s_-]*time[\\s_-]*code|otp|pin|" +
            "credit[\\s_-]*card|cc[\\s_-]*(number|exp|csc|cvc)|cvv|cvc|" +
            "security[\\s_-]*code|card[\\s_-]*(number|code)|" +
            "account[\\s_-]*number|routing[\\s_-]*number|iban|secret|token|" +
            "api[\\s_-]*key|access[\\s_-]*key|private[\\s_-]*key|" +
            "credential|csrf|xsrf|authentication|authorization|auth[\\s_-]*(token|code))" +
            "(?:\$|[^a-z0-9])"

    private val sensitiveHint = Regex(SENSITIVE_PATTERN, RegexOption.IGNORE_CASE)
    private val likelyPaymentCardNumber = Regex("""(?<!\d)(?:\d[ -]?){11,18}\d(?!\d)""")
    private val explicitPasswordType = setOf("password", "current-password", "new-password")

    /** Pattern source kept in lock-step with the JavaScript DOM checks in MainActivity. */
    val javascriptPattern: String get() = SENSITIVE_PATTERN

    fun redactLikelyPaymentCardNumbers(value: String): String =
        likelyPaymentCardNumber.replace(value, "[private number]")

    fun isSensitiveHtmlField(
        type: String? = null,
        name: String? = null,
        autocomplete: String? = null,
        placeholder: String? = null,
        ariaLabel: String? = null,
        id: String? = null,
        inputMode: String? = null
    ): Boolean {
        val normalizedType = type?.trim()?.lowercase(Locale.ROOT)
        if (normalizedType != null && normalizedType in explicitPasswordType) return true
        val hints = listOfNotNull(type, name, autocomplete, placeholder, ariaLabel, id, inputMode)
            .joinToString(" ")
        return sensitiveHint.containsMatchIn(normalizeHints(hints))
    }

    /**
     * Android accessibility password metadata is authoritative even for numeric PIN
     * fields whose input class is not TYPE_CLASS_TEXT.
     */
    fun isSensitiveAccessibilityInput(
        editable: Boolean,
        isPassword: Boolean,
        inputType: Int,
        clues: String?
    ): Boolean {
        if (isPassword || isNumericPasswordType(inputType)) return true
        if (sensitiveHint.containsMatchIn(normalizeHints(clues.orEmpty()))) return true
        return editable && containsTextPasswordVariation(inputType)
    }

    private fun normalizeHints(value: String): String =
        value.replace(Regex("([A-Z]+)([A-Z][a-z])"), "\$1 \$2")
            .replace(Regex("([a-z])([A-Z])"), "\$1 \$2")

    private fun isNumericPasswordType(inputType: Int): Boolean =
        (inputType and TYPE_MASK_CLASS) == TYPE_CLASS_NUMBER &&
            (inputType and TYPE_NUMBER_VARIATION_PASSWORD) == TYPE_NUMBER_VARIATION_PASSWORD

    private fun containsTextPasswordVariation(inputType: Int): Boolean {
        val inputClass = inputType and TYPE_MASK_CLASS
        val variation = inputType and TYPE_MASK_VARIATION
        return inputClass == TYPE_CLASS_TEXT && variation in TEXT_PASSWORD_VARIATIONS
    }

    private const val TYPE_MASK_CLASS = 0x0000000f
    private const val TYPE_MASK_VARIATION = 0x00000ff0
    private const val TYPE_CLASS_TEXT = 0x00000001
    private const val TYPE_CLASS_NUMBER = 0x00000002
    private const val TYPE_NUMBER_VARIATION_PASSWORD = 0x00000010
    private val TEXT_PASSWORD_VARIATIONS = setOf(0x00000080, 0x00000090, 0x000000e0)
}
