package com.uong.phormi

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import java.security.MessageDigest
import java.util.concurrent.Executor

/**
 * Browser Lock for Phormi.
 *
 * Device mode delegates to Android's system authentication UI, including the phone's
 * configured screen lock where the platform allows it. PIN mode is local to Phormi.
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

    fun method(prefs: android.content.SharedPreferences): String =
        prefs.getString(PREF_METHOD, METHOD_DEVICE) ?: METHOD_DEVICE

    fun isPinConfigured(prefs: android.content.SharedPreferences): Boolean =
        !prefs.getString(PREF_PIN_HASH, null).isNullOrBlank()

    fun canUseDeviceAuthentication(): Boolean {
        val keyguard = activity.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        if (keyguard?.isDeviceSecure == true) return true
        return if (Build.VERSION.SDK_INT >= 30) {
            BiometricManager.from(activity).canAuthenticate(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
            ) == BiometricManager.BIOMETRIC_SUCCESS
        } else {
            BiometricManager.from(activity).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
                BiometricManager.BIOMETRIC_SUCCESS
        }
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
        if (!canUseDeviceAuthentication()) {
            Toast.makeText(
                activity,
                "No usable phone lock or biometric authentication is configured. Set a device screen lock or use a Phormi PIN.",
                Toast.LENGTH_LONG
            ).show()
            onFailure()
            return
        }

        promptInProgress = true
        try {
            val authenticators = if (Build.VERSION.SDK_INT >= 30) {
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
            } else {
                BiometricManager.Authenticators.BIOMETRIC_STRONG
            }
            val canAuthenticate = BiometricManager.from(activity).canAuthenticate(authenticators)
            if (canAuthenticate != BiometricManager.BIOMETRIC_SUCCESS) {
                promptInProgress = false
                Toast.makeText(activity, "Device authentication is unavailable. Use a Phormi PIN.", Toast.LENGTH_LONG).show()
                onFailure()
                return
            }

            val promptInfo = BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock Phormi")
                .setSubtitle("Browser Lock")
                .setDescription("Use your fingerprint, face, or phone screen lock to continue.")
                .apply {
                    if (Build.VERSION.SDK_INT >= 30) {
                        setAllowedAuthenticators(authenticators)
                    } else {
                        @Suppress("DEPRECATION")
                        setDeviceCredentialAllowed(true)
                    }
                }
                .build()

            val prompt = BiometricPrompt(
                activity,
                executor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        promptInProgress = false
                        onSuccess()
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        promptInProgress = false
                        Toast.makeText(activity, "Phormi remains locked", Toast.LENGTH_SHORT).show()
                        onFailure()
                    }
                }
            )
            prompt.authenticate(promptInfo)
        } catch (_: Exception) {
            promptInProgress = false
            Toast.makeText(activity, "Unable to open device authentication. Use a Phormi PIN.", Toast.LENGTH_LONG).show()
            onFailure()
        }
    }
}
