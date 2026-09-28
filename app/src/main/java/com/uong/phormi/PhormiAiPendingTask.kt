package com.uong.phormi

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Persistent queue for browser AI work. Up to three independent tasks may be pending/running. */
object PhormiAiPendingTask {
    private const val PREFS = "phormi_ai_pending_task"
    private const val KEY_TASKS = "tasks"
    private const val KEY_LEGACY_TASK = "task"
    private const val MAX_TASKS = 3

    data class Task(
        val id: String,
        val target: String,
        val instruction: String,
        val createdAt: Long
    )

    @Synchronized
    fun enqueue(context: Context, target: String, instruction: String): Task? {
        val clean = instruction.trim()
        if (clean.isBlank()) return null
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val tasks = read(context).toMutableList()
        if (tasks.size >= MAX_TASKS) return null
        val task = Task(UUID.randomUUID().toString(), target.trim(), clean, System.currentTimeMillis())
        tasks += task
        write(prefs, tasks)
        return task
    }

    @Synchronized
    fun takeAll(context: Context): List<Task> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val tasks = read(context)
        write(prefs, emptyList())
        return tasks
    }

    @Synchronized
    fun take(context: Context): Task? = takeAll(context).firstOrNull()

    @Synchronized
    fun pendingCount(context: Context): Int = read(context).size

    fun saveStatus(context: Context, status: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString("last_status", status.take(1000)).apply()
    }

    fun lastStatus(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("last_status", "") ?: ""

    private fun read(context: Context): List<Task> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_TASKS, null)
            ?: prefs.getString(KEY_LEGACY_TASK, null)?.let { legacy ->
                runCatching {
                    val o = JSONObject(legacy)
                    JSONArray().put(JSONObject()
                        .put("id", UUID.randomUUID().toString())
                        .put("target", o.optString("target"))
                        .put("instruction", o.optString("instruction"))
                        .put("createdAt", System.currentTimeMillis()))
                        .toString()
                }.getOrNull()
            }
            ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val instruction = o.optString("instruction").trim()
                    if (instruction.isBlank()) continue
                    add(Task(
                        o.optString("id").ifBlank { UUID.randomUUID().toString() },
                        o.optString("target").trim(),
                        instruction,
                        o.optLong("createdAt", System.currentTimeMillis())
                    ))
                }
            }.take(MAX_TASKS)
        }.getOrDefault(emptyList())
    }

    private fun write(prefs: android.content.SharedPreferences, tasks: List<Task>) {
        val arr = JSONArray()
        tasks.take(MAX_TASKS).forEach {
            arr.put(JSONObject()
                .put("id", it.id)
                .put("target", it.target)
                .put("instruction", it.instruction)
                .put("createdAt", it.createdAt))
        }
        prefs.edit().remove(KEY_LEGACY_TASK).putString(KEY_TASKS, arr.toString()).apply()
    }
}
