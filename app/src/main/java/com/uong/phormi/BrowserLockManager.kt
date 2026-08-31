package com.uong.phormi

import android.app.Activity
import android.os.Build
import android.widget.Toast
import java.security.MessageDigest
import java.util.concurrent.Executor

/**
 * Device lock for Phormi (biometrics/device credential or a local Phormi PIN).
 * The PIN is stored only as a SHA-256 hash in SharedPreferences.
 */
class BrowserLockManager(
    private val activity: Activity,
    private val executor: Executor
) {
    companion object {
        const val PREF_KEY = "browser_lock_enabled"

        const val PREF_METHOD = "browser_lock_method"
        const val METHOD_DEVICE = "device"
        const val METHOD_PIN = "pin"

        private const val PREF_PIN_HASH = "browser_lock_pin_hash"
    }

    private var promptInProgress = false

    fun isEnabled(prefs: android.content.SharedPreferences): Boolean =
        prefs.getBoolean(PREF_KEY, false)

    fun isPromptInProgress(): Boolean = promptInProgress

    fun method(prefs: android.content.SharedPreferences): String {
        return prefs.getString(PREF_METHOD, METHOD_DEVICE) ?: METHOD_DEVICE
    }

    fun isPinConfigured(prefs: android.content.SharedPreferences): Boolean {
        return !prefs.getString(PREF_PIN_HASH, null).isNullOrBlank()
    }

    fun setPin(prefs: android.content.SharedPreferences, pin: String): Boolean {
        val normalized = pin.trim()
        if (normalized.length < 4 || normalized.any { !it.isDigit() }) return false

        prefs.edit()
            .putString(PREF_METHOD, METHOD_PIN)
            .putString(PREF_PIN_HASH, sha256(normalized))
            .apply()
        return true
    }

    fun verifyPin(prefs: android.content.SharedPreferences, pin: String): Boolean {
        val stored = prefs.getString(PREF_PIN_HASH, null) ?: return false
        return MessageDigest.isEqual(
            stored.toByteArray(Charsets.UTF_8),
            sha256(pin.trim()).toByteArray(Charsets.UTF_8)
        )
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun authenticate(onSuccess: () -> Unit, onFailure: () -> Unit) {
        if (promptInProgress) return

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            Toast.makeText(
                activity,
                "Browser Lock requires Android 9 or newer.",
                Toast.LENGTH_LONG
            ).show()
            onFailure()
            return
        }

        promptInProgress = true
        try {
            val prompt = android.hardware.biometrics.BiometricPrompt.Builder(activity)
                .setTitle("Unlock Phormi")
                .setSubtitle("Browser Lock")
                .setDescription("Use your fingerprint, face, or device screen lock to continue.")
                .apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        setAllowedAuthenticators(
                            android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG or
                                android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        setDeviceCredentialAllowed(true)
                    }
                }
                .build()

            prompt.authenticate(
                android.os.CancellationSignal(),
                executor,
                object : android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(
                        result: android.hardware.biometrics.BiometricPrompt.AuthenticationResult
                    ) {
                        promptInProgress = false
                        onSuccess()
                    }

                    override fun onAuthenticationError(
                        errorCode: Int,
                        errString: CharSequence
                    ) {
                        promptInProgress = false
                        Toast.makeText(
                            activity,
                            "Phormi remains locked",
                            Toast.LENGTH_SHORT
                        ).show()
                        onFailure()
                    }

                    override fun onAuthenticationFailed() {
                        super.onAuthenticationFailed()
                    }
                }
            )
        } catch (_: Exception) {
            promptInProgress = false
            Toast.makeText(
                activity,
                "Unable to open device authentication",
                Toast.LENGTH_LONG
            ).show()
            onFailure()
        }
    }
}
