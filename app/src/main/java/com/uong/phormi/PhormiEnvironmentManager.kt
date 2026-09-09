package com.uong.phormi

import android.content.Context
import android.webkit.WebView
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/** Owns named browser environments; each is an isolated WebView browsing session. */
object PhormiEnvironmentManager {
    const val DEFAULT_ENVIRONMENT = "Default"
    const val GHOST_ENVIRONMENT = "Ghost"
    private const val META_PREFS = "phormi_environment_meta"
    private const val LAST_USED = "last_used"
    private const val CLEANUP_CHECKED = "cleanup_checked"
    private const val EXPIRY_DAYS = 30L

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
            touch(webView.context, normalized)
            true
        }.getOrDefault(false)
    }

    fun touch(context: Context?, name: String) {
        val normalized = normalize(name)
        if (context == null || normalized == DEFAULT_ENVIRONMENT || normalized == GHOST_ENVIRONMENT) return
        val prefs = context.getSharedPreferences(META_PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(LAST_USED, "{}") ?: "{}"
        val obj = runCatching { org.json.JSONObject(raw) }.getOrElse { org.json.JSONObject() }
        obj.put(normalized, System.currentTimeMillis())
        prefs.edit().putString(LAST_USED, obj.toString()).apply()
    }

    /**
     * Deletes named environments that have not been used for 30 days. Active profiles
     * are protected because WebView profile deletion must not race live WebViews.
     */
    fun cleanupExpired(context: Context, activeProfiles: Set<String> = emptySet()) {
        if (!isSupported()) return
        val prefs = context.getSharedPreferences(META_PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val lastCheck = prefs.getLong(CLEANUP_CHECKED, 0L)
        if (now - lastCheck < 60_000L) return
        prefs.edit().putLong(CLEANUP_CHECKED, now).apply()

        val cutoff = now - EXPIRY_DAYS * 24L * 60L * 60L * 1000L
        val lastUsed = runCatching { org.json.JSONObject(prefs.getString(LAST_USED, "{}") ?: "{}") }.getOrElse { org.json.JSONObject() }
        val protectedNames = activeProfiles.map(::normalize).toSet() + DEFAULT_ENVIRONMENT + GHOST_ENVIRONMENT
        val expired = list().filter { name ->
            !protectedNames.contains(name) && lastUsed.optLong(name, 0L) in 1 until cutoff
        }
        expired.forEach { name -> if (delete(name)) lastUsed.remove(name) }
        prefs.edit().putString(LAST_USED, lastUsed.toString()).apply()
    }

    fun delete(name: String): Boolean {
        val normalized = normalize(name)
        if (normalized == DEFAULT_ENVIRONMENT || normalized == GHOST_ENVIRONMENT || !isSupported()) return false
        return runCatching { ProfileStore.getInstance().deleteProfile(normalized) }.getOrDefault(false)
    }
}
