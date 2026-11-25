package com.uong.phormi

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Salted, deliberately slow verifier for the optional local Browser Lock PIN. */
internal object PhormiPinVerifier {
    const val MIN_PIN_LENGTH = 8
    const val MAX_PIN_LENGTH = 64
    private const val PREFIX = "pbkdf2-sha256"
    private const val ITERATIONS = 150_000
    private const val KEY_BITS = 256
    private const val SALT_BYTES = 16
    private const val MIN_ACCEPTED_ITERATIONS = 100_000
    private const val MAX_ACCEPTED_ITERATIONS = 1_000_000

    data class Verification(val valid: Boolean, val needsUpgrade: Boolean = false)

    fun isValidNewPin(pin: String): Boolean =
        pin.length in MIN_PIN_LENGTH..MAX_PIN_LENGTH && pin.all { it in '0'..'9' }

    fun encode(pin: String, random: SecureRandom = SecureRandom()): String {
        val salt = ByteArray(SALT_BYTES).also { random.nextBytes(it) }
        val derived = derive(pin, salt, ITERATIONS)
        return listOf(
            PREFIX,
            ITERATIONS.toString(),
            Base64.getEncoder().withoutPadding().encodeToString(salt),
            Base64.getEncoder().withoutPadding().encodeToString(derived)
        ).joinToString(":")
    }

    /** Supports the historical unsalted SHA-256 form solely to migrate it on success. */
    fun verify(stored: String, candidate: String): Verification {
        val normalizedCandidate = candidate.trim()
        if (normalizedCandidate.length > MAX_PIN_LENGTH) return Verification(false)
        if (!stored.startsWith("$PREFIX:")) {
            val legacy = sha256Hex(normalizedCandidate)
            val valid = MessageDigest.isEqual(
                stored.toByteArray(Charsets.UTF_8),
                legacy.toByteArray(Charsets.UTF_8)
            )
            return Verification(valid, needsUpgrade = valid)
        }
        val parts = stored.split(':')
        if (parts.size != 4 || parts[0] != PREFIX) return Verification(false)
        val iterations = parts[1].toIntOrNull()
            ?.takeIf { it in MIN_ACCEPTED_ITERATIONS..MAX_ACCEPTED_ITERATIONS }
            ?: return Verification(false)
        val salt = runCatching { Base64.getDecoder().decode(parts[2]) }.getOrNull()
            ?.takeIf { it.size in 16..64 } ?: return Verification(false)
        val expected = runCatching { Base64.getDecoder().decode(parts[3]) }.getOrNull()
            ?.takeIf { it.size == KEY_BITS / 8 } ?: return Verification(false)
        val actual = runCatching { derive(normalizedCandidate, salt, iterations) }.getOrNull()
            ?: return Verification(false)
        return Verification(MessageDigest.isEqual(expected, actual))
    }

    /** Compatibility helper used only by the successful legacy-verifier migration. */
    fun legacySha256(pin: String): String = sha256Hex(pin.trim())

    private fun derive(pin: String, salt: ByteArray, iterations: Int): ByteArray {
        val chars = pin.toCharArray()
        val spec = PBEKeySpec(chars, salt, iterations, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec)
                .encoded
        } finally {
            spec.clearPassword()
            chars.fill('\u0000')
        }
    }

    private fun sha256Hex(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
