package com.uong.phormi

import android.app.Activity
import android.os.Build
import android.widget.Toast
import java.util.concurrent.Executor

/**
 * Device lock for Phormi (fingerprint / face / PIN / pattern / password).
 * Never finishes the Activity. Failed auth keeps the lock overlay on screen.
 */
class BrowserLockManager(
    private val activity: Activity,
    private val executor: Executor
) {
    companion object {
        const val PREF_KEY = "browser_lock_enabled"
    }

    private var promptInProgress = false

    fun isEnabled(prefs: android.content.SharedPreferences): Boolean =
        prefs.getBoolean(PREF_KEY, false)

    fun isPromptInProgress(): Boolean = promptInProgress

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
                        Toast.makeText(activity, "Phormi remains locked", Toast.LENGTH_SHORT).show()
                        onFailure()
                    }

                    override fun onAuthenticationFailed() {
                        super.onAuthenticationFailed()
                    }
                }
            )
        } catch (_: Exception) {
            promptInProgress = false
            Toast.makeText(activity, "Unable to open device authentication", Toast.LENGTH_LONG).show()
            onFailure()
        }
    }
}
