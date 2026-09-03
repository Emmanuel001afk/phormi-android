package com.uong.phormi

import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/** Browser-owned wrapper for optional AndroidX WebView multi-profile environments. */
object PhormiEnvironmentManager {
    const val DEFAULT_ENVIRONMENT = "Default"

    fun isSupported(): Boolean =
        WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)

    fun list(): List<String> {
        if (!isSupported()) return listOf(DEFAULT_ENVIRONMENT)
        return runCatching {
            ProfileStore.getInstance().getAllProfileNames()
                .filter { it.isNotBlank() && !it.equals("Ghost", ignoreCase = true) }.distinct()
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it })
                .ifEmpty { listOf(DEFAULT_ENVIRONMENT) }
        }.getOrDefault(listOf(DEFAULT_ENVIRONMENT))
    }

    fun delete(name: String): Boolean {
        if (!isSupported() || name.isBlank() || name == DEFAULT_ENVIRONMENT) return false
        return runCatching { ProfileStore.getInstance().deleteProfile(name) }.getOrDefault(false)
    }

    fun ensure(name: String) {
        if (!isSupported() || name.isBlank() || name == DEFAULT_ENVIRONMENT) return
        ProfileStore.getInstance().getOrCreateProfile(name)
    }

    fun apply(webView: android.webkit.WebView, name: String): Boolean {
        if (name.isBlank() || name == DEFAULT_ENVIRONMENT) return true
        if (!isSupported()) return false
        return runCatching { WebViewCompat.setProfile(webView, name); true }.getOrDefault(false)
    }
}
