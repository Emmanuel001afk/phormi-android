package com.uong.phormi

import android.content.Context
import java.util.Locale

/**
 * Per-origin site permission decisions for WebView.
 *
 * The decision logic is plain Kotlin (unit-testable without a device); Android
 * storage is a small adapter. Previously geolocation was auto-granted whenever the
 * app happened to hold ACCESS_FINE_LOCATION, with no per-site allow/deny/one-time UI.
 */
object PhormiSitePermissions {
    const val ASK = "ask"
    const val ALLOW = "allow"
    const val BLOCK = "block"

    interface Store {
        fun read(origin: String): String?
        fun write(origin: String, value: String?)
        fun clear()
    }

    class SharedPreferencesStore(context: Context) : Store {
        private val prefs = context.getSharedPreferences("phormi_site_permissions", Context.MODE_PRIVATE)
        private fun key(origin: String) = "geolocation|$origin"

        override fun read(origin: String): String? =
            if (origin.isBlank()) null else prefs.getString(key(origin), null)

        override fun write(origin: String, value: String?) {
            if (origin.isBlank()) return
            val editor = prefs.edit()
            if (value == null) editor.remove(key(origin)) else editor.putString(key(origin), value)
            editor.apply()
        }

        override fun clear() = prefs.edit().clear().apply()
    }

    /** WebView supplies origins such as `https://example.com`; normalise to scheme + authority. */
    fun normalizeOrigin(raw: String?): String {
        val value = raw?.trim().orEmpty()
        if (value.isBlank()) return ""
        val scheme = if (value.contains("://"))
            value.substringBefore("://").lowercase(Locale.ROOT).ifBlank { "https" }
        else "https"
        val withoutScheme = if (value.contains("://")) value.substringAfter("://") else value
        val authority = withoutScheme
            .substringBefore('/').substringBefore('?').substringBefore('#')
            .trimEnd('/').lowercase(Locale.ROOT)
        return if (authority.isBlank()) value.trimEnd('/').lowercase(Locale.ROOT) else "$scheme://$authority"
    }

    /** null = ask the user, true = allow, false = block. */
    fun decision(stored: String?): Boolean? = when (stored?.trim()?.lowercase(Locale.ROOT)) {
        ALLOW -> true
        BLOCK -> false
        else -> null
    }

    /**
     * Choice index from the site permission dialog.
     * Returns the value to persist (empty = do not persist) and the WebView callback
     * arguments (allow, retain). Retain is always false: this store is the single
     * source of truth, so "Reset site permissions" can actually revoke a decision
     * instead of leaving a hidden grant in WebView's own database.
     */
    fun grantFor(choice: Int): Pair<String, Pair<Boolean, Boolean>> = when (choice) {
        0 -> "" to (true to false)          // Allow once
        1 -> ALLOW to (true to false)       // Always allow on this site (persisted here)
        else -> BLOCK to (false to false)   // Block
    }

    fun forget(origin: String, store: Store) = store.write(origin, null)
}
