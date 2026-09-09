package com.uong.phormi

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Small persistent command queue used to bridge nested browser surfaces back to MainActivity. */
object PhormiCommandBus {
    private const val PREFS = "phormi_command_bus"
    private const val KEY = "queue"

    data class Command(val action: String, val extras: Map<String, String>)

    @Synchronized
    fun enqueue(context: Context, action: String, extras: Map<String, String> = emptyMap()) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val arr = runCatching { JSONArray(prefs.getString(KEY, "[]") ?: "[]") }.getOrElse { JSONArray() }
        arr.put(JSONObject().put("action", action).apply {
            extras.forEach { (k, v) -> put(k, v) }
        })
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    @Synchronized
    fun drain(context: Context): List<Command> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val arr = runCatching { JSONArray(prefs.getString(KEY, "[]") ?: "[]") }.getOrElse { JSONArray() }
        prefs.edit().remove(KEY).apply()
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val action = o.optString("action").trim()
                if (action.isBlank()) continue
                val extras = mutableMapOf<String, String>()
                o.keys().forEach { key ->
                    if (key != "action") extras[key] = o.optString(key)
                }
                add(Command(action, extras))
            }
        }
    }
}
