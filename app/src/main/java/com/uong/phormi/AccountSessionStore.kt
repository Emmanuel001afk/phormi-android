package com.uong.phormi

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Stores only account-provider session labels/timestamps; website credentials remain in WebView cookies. */
object AccountSessionStore {
    private const val PREFS = "phormi_account_sessions"
    private const val KEY = "sessions"

    data class Session(val providerId: String, val label: String, val lastUsed: Long)

    fun touch(context: Context, providerId: String, label: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val arr = read(prefs.getString(KEY, "[]"))
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("providerId") != providerId) out.put(o)
        }
        out.put(JSONObject().put("providerId", providerId).put("label", label).put("lastUsed", System.currentTimeMillis()))
        prefs.edit().putString(KEY, out.toString()).apply()
    }

    fun remove(context: Context, providerId: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val arr = read(prefs.getString(KEY, "[]"))
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("providerId") != providerId) out.put(o)
        }
        prefs.edit().putString(KEY, out.toString()).apply()
    }

    fun list(context: Context): List<Session> {
        val arr = read(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]"))
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                add(Session(o.optString("providerId"), o.optString("label"), o.optLong("lastUsed")))
            }
        }.sortedByDescending { it.lastUsed }
    }

    private fun read(raw: String?): JSONArray = runCatching { JSONArray(raw ?: "[]") }.getOrElse { JSONArray() }
}
