package com.uong.phormi

import android.content.Context
import org.json.JSONObject

/** Persistent hand-off from the AI setup screen to the foreground browser. */
object PhormiAiPendingTask {
    private const val PREFS = "phormi_ai_pending_task"
    private const val KEY_TASK = "task"

    data class Task(val target: String, val instruction: String)

    @Synchronized
    fun enqueue(context: Context, target: String, instruction: String) {
        val json = JSONObject()
            .put("target", target.trim())
            .put("instruction", instruction.trim())
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_TASK, json.toString()).apply()
    }

    @Synchronized
    fun take(context: Context): Task? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_TASK, null) ?: return null
        prefs.edit().remove(KEY_TASK).apply()
        return runCatching {
            val json = JSONObject(raw)
            val instruction = json.optString("instruction").trim()
            if (instruction.isBlank()) null else Task(json.optString("target").trim(), instruction)
        }.getOrNull()
    }

    fun saveStatus(context: Context, status: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString("last_status", status.take(1000)).apply()
    }

    fun lastStatus(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("last_status", "") ?: ""
}
