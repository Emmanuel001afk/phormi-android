package com.uong.phormi

import android.webkit.WebView
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/** Owns named browser environments; groups never become account/session containers. */
object PhormiEnvironmentManager {
    const val DEFAULT_ENVIRONMENT = "Default"
    const val GHOST_ENVIRONMENT = "Ghost"

    fun isSupported(): Boolean = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)

    fun normalize(name: String?): String = name?.trim()?.takeIf { it.isNotBlank() } ?: DEFAULT_ENVIRONMENT

    fun list(): List<String> {
        if (!isSupported()) return listOf(DEFAULT_ENVIRONMENT)
        return runCatching {
            ProfileStore.getInstance().getAllProfileNames()
                .map(::normalize)
                .filter { it != GHOST_ENVIRONMENT }
                .distinct()
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it })
                .ifEmpty { listOf(DEFAULT_ENVIRONMENT) }
        }.getOrDefault(listOf(DEFAULT_ENVIRONMENT))
    }

    fun ensure(name: String): Boolean {
        val normalized = normalize(name)
        if (normalized == DEFAULT_ENVIRONMENT) return true
        if (!isSupported()) return false
        return runCatching {
            ProfileStore.getInstance().getOrCreateProfile(normalized)
            true
        }.getOrDefault(false)
    }

    fun apply(webView: WebView, name: String): Boolean {
        val normalized = normalize(name)
        if (normalized == DEFAULT_ENVIRONMENT) return true
        if (!ensure(normalized)) return false
        return runCatching {
            WebViewCompat.setProfile(webView, normalized)
            true
        }.getOrDefault(false)
    }

    fun delete(name: String): Boolean {
        val normalized = normalize(name)
        if (normalized == DEFAULT_ENVIRONMENT || normalized == GHOST_ENVIRONMENT || !isSupported()) return false
        return runCatching { ProfileStore.getInstance().deleteProfile(normalized) }.getOrDefault(false)
    }
}
