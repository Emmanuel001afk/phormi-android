package com.uong.phormi

import android.content.Context
import android.net.Uri
import java.util.Locale

/** Persistent host-level timed protection used by the browser navigation layer. */
object PhormiSiteLockManager {
    private const val PREFS = "phormi_site_locks"

    data class LockState(val host: String, val expiryAt: Long, val scopeType: String = SCOPE_TAB, val scopeId: String = "")

    const val SCOPE_TAB = "tab"
    const val SCOPE_GROUP = "group"
    const val SCOPE_ENVIRONMENT = "environment"

    fun normalizeHost(url: String?): String? {
        val raw = url?.trim().orEmpty()
        if (!raw.startsWith("http://") && !raw.startsWith("https://")) return null
        return runCatching { Uri.parse(raw).host?.trim()?.lowercase(Locale.US) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
    }

    fun lock(context: Context, host: String, expiryAt: Long, scopeType: String = SCOPE_TAB, scopeId: String = "") {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong(key(host, scopeType, scopeId), expiryAt).apply()
    }

    fun unlock(context: Context, host: String, scopeType: String = SCOPE_TAB, scopeId: String = "") {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(key(host, scopeType, scopeId)).apply()
    }

    fun get(context: Context, host: String, scopeType: String = SCOPE_TAB, scopeId: String = ""): LockState? {
        val storageKey = key(host, scopeType, scopeId)
        val expiry = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(storageKey, 0L)
        if (expiry <= 0L) return null
        if (expiry != Long.MAX_VALUE && expiry <= System.currentTimeMillis()) {
            unlock(context, host, scopeType, scopeId)
            return null
        }
        return LockState(host.lowercase(Locale.US), expiry, scopeType, scopeId)
    }

    fun clearAll(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    fun list(context: Context): List<LockState> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val out = mutableListOf<LockState>()
        prefs.all.forEach { (host, value) ->
            val expiry = value as? Long ?: return@forEach
            if (expiry > 0L && (expiry == Long.MAX_VALUE || expiry > System.currentTimeMillis())) {
                val parts = host.split("|", limit = 3)
                if (parts.size == 3) out += LockState(parts[2], expiry, parts[0], parts[1])
            }
        }
        return out.sortedWith(compareBy<LockState> { it.host }.thenBy { it.scopeType }.thenBy { it.scopeId })
    }

    fun isLocked(context: Context, url: String?, scopeType: String = SCOPE_TAB, scopeId: String = ""): Boolean {
        val host = normalizeHost(url) ?: return false
        return get(context, host, scopeType, scopeId) != null
    }

    private fun key(host: String, scopeType: String, scopeId: String): String =
        scopeType.lowercase(Locale.US) + "|" + scopeId.lowercase(Locale.US) + "|" + host.lowercase(Locale.US)
}
