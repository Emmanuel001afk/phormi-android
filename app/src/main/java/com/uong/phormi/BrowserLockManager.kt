package com.uong.phormi

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.widget.Toast
import java.util.concurrent.Executor

class BrowserLockManager(
    private val activity: Activity,
    private val executor: Executor
) {
    companion object {
        const val PREF_KEY = "browser_lock_enabled"
    }

    fun isEnabled(prefs: android.content.SharedPreferences): Boolean =
        prefs.getBoolean(PREF_KEY, false)

    fun deviceHasUsableCredential(): Boolean {
        val keyguardManager = activity.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val hasDeviceCredential = keyguardManager?.isDeviceSecure == true

        val hasBiometric = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val biometricManager = activity.getSystemService(Context.BIOMETRIC_SERVICE) as? BiometricManager
            biometricManager?.canAuthenticate() == BiometricManager.BIOMETRIC_SUCCESS
        } else false

        return hasDeviceCredential || hasBiometric
    }

    fun trySetEnabled(prefs: android.content.SharedPreferences, enabled: Boolean): Boolean {
        if (enabled && !deviceHasUsableCredential()) {
            Toast.makeText(
                activity,
                "Set up a fingerprint, face unlock, or screen PIN/pattern in your phone's Settings first — Browser Lock needs one of these to work safely.",
                Toast.LENGTH_LONG
            ).show()
            return false
        }
        prefs.edit().putBoolean(PREF_KEY, enabled).apply()
        return true
    }

    fun authenticate(onSuccess: () -> Unit, onCancelled: () -> Unit, onFailure: () -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            Toast.makeText(activity, "Browser Lock requires Android 9 or newer.", Toast.LENGTH_LONG).show()
            onFailure()
            return
        }

        if (!deviceHasUsableCredential()) {
            Toast.makeText(
                activity,
                "No screen lock or biometric is set up on this device — Browser Lock has been disabled automatically.",
                Toast.LENGTH_LONG
            ).show()
            activity.getSharedPreferences("phormi_prefs", Context.MODE_PRIVATE)
                .edit().putBoolean(PREF_KEY, false).apply()
            onSuccess()
            return
        }

        val prompt = BiometricPrompt.Builder(activity)
            .setTitle("Unlock Phormi")
            .setSubtitle("Protect your tabs and browser data")
            .setDescription("Authenticate to continue using Phormi.")
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setAllowedAuthenticators(
                        BiometricManager.Authenticators.BIOMETRIC_STRONG or
                            BiometricManager.Authenticators.DEVICE_CREDENTIAL
                    )
                } else {
                    @Suppress("DEPRECATION")
                    setDeviceCredentialAllowed(true)
                }
            }
            .build()

        prompt.authenticate(
            CancellationSignal(),
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(
                    result: BiometricPrompt.AuthenticationResult
                ) {
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    // Codes 10 (BIOMETRIC_ERROR_USER_CANCELED) and 13
                    // (BIOMETRIC_ERROR_NEGATIVE_BUTTON) mean the user chose
                    // to back out, not that authentication actually failed.
                    if (errorCode == 10 || errorCode == 13) {
                        onCancelled()
                    } else {
                        Toast.makeText(activity, "Phormi remains locked: $errString", Toast.LENGTH_SHORT).show()
                        onFailure()
                    }
                }

                override fun onAuthenticationFailed() {
                    // A single wrong attempt — the prompt stays open for
                    // another try, so this is not treated as a hard failure.
                }
            }
        )
    }
}
