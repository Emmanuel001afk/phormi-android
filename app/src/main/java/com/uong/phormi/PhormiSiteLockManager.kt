package com.uong.phormi

import android.content.Context
import android.net.Uri
import java.util.Locale

/** Persistent host-level timed protection used by the browser navigation layer. */
object PhormiSiteLockManager {
    private const val PREFS = "phormi_site_locks"

    data class LockState(val host: String, val expiryAt: Long)

    fun normalizeHost(url: String?): String? {
        val raw = url?.trim().orEmpty()
        if (!raw.startsWith("http://") && !raw.startsWith("https://")) return null
        return runCatching { Uri.parse(raw).host?.trim()?.lowercase(Locale.US) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
    }

    fun lock(context: Context, host: String, expiryAt: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong(host.lowercase(Locale.US), expiryAt).apply()
    }

    fun unlock(context: Context, host: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(host.lowercase(Locale.US)).apply()
    }

    fun get(context: Context, host: String): LockState? {
        val key = host.lowercase(Locale.US)
        val expiry = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(key, 0L)
        if (expiry <= 0L) return null
        if (expiry != Long.MAX_VALUE && expiry <= System.currentTimeMillis()) {
            unlock(context, host)
            return null
        }
        return LockState(key, expiry)
    }

    fun isLocked(context: Context, url: String?): Boolean {
        val host = normalizeHost(url) ?: return false
        return get(context, host) != null
    }
}
