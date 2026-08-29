package com.uong.phormi

import android.app.Activity
import android.content.Context
import android.os.Build
import android.widget.Toast
import java.util.concurrent.Executor

/**
 * Optional Phormi app lock.
 *
 * This protects access to the browser UI when the app returns to the foreground.
 * It deliberately does not upload or expose website cookies/passwords.
 */
class BrowserLockManager(
    private val activity: Activity,
    private val executor: Executor
) {
    companion object {
        const val PREF_KEY = "browser_lock_enabled"
    }

    fun isEnabled(prefs: android.content.SharedPreferences): Boolean =
        prefs.getBoolean(PREF_KEY, false)

    fun authenticate(onSuccess: () -> Unit, onFailure: () -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            Toast.makeText(activity, "Browser Lock requires Android 9 or newer.", Toast.LENGTH_LONG).show()
            onFailure()
            return
        }

        val prompt = android.hardware.biometrics.BiometricPrompt.Builder(activity)
            .setTitle("Unlock Phormi")
            .setSubtitle("Protect your tabs and browser data")
            .setDescription("Authenticate to continue using Phormi.")
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
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    Toast.makeText(activity, "Phormi remains locked", Toast.LENGTH_SHORT).show()
                    onFailure()
                }
            }
        )
    }
}
