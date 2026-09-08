from pathlib import Path
root=Path(__file__).resolve().parents[1]
p=root/'app/src/main/java/com/uong/phormi/PhormiSitePermissionStore.kt'
p.write_text('''package com.uong.phormi

import android.content.Context
import java.net.URI

object PhormiSitePermissionStore {
    private const val PREFS = "phormi_site_permissions"
    private const val KEY = "decisions"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun origin(raw: String): String = runCatching {
        val uri = URI(raw)
        val scheme = uri.scheme?.lowercase() ?: ""
        val host = uri.host?.lowercase() ?: ""
        val port = if (uri.port > 0) ":${uri.port}" else ""
        if (scheme.isBlank() || host.isBlank()) "" else "$scheme://$host$port"
    }.getOrDefault("")

    fun get(context: Context, rawUrl: String, resource: String): Boolean? {
        val o = origin(rawUrl)
        if (o.isBlank()) return null
        val set = prefs(context).getStringSet(KEY, emptySet()).orEmpty()
        return when {
            "$o|$resource|1" in set -> true
            "$o|$resource|0" in set -> false
            else -> null
        }
    }

    fun set(context: Context, rawUrl: String, resource: String, allow: Boolean) {
        val o = origin(rawUrl)
        if (o.isBlank()) return
        val set = prefs(context).getStringSet(KEY, emptySet()).orEmpty().toMutableSet()
        set.removeIf { it.startsWith("$o|$resource|") }
        set += "$o|$resource|${if (allow) 1 else 0}"
        prefs(context).edit().putStringSet(KEY, set).apply()
    }

    fun clear(context: Context) = prefs(context).edit().remove(KEY).apply()
}
''')
print('Round 7 site permission parser replaced')
