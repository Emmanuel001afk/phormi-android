package com.uong.phormi

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** A small, device-local queue. Scheduled work is a reminder, never unattended WebView automation. */
object PhormiAiPendingTask {
    private const val PREFS = "phormi_ai_pending_task"
    private const val KEY_TASKS = "tasks"
    private const val KEY_LEGACY_TASK = "task"
    private const val MAX_TASKS = 10

    data class Task(
        val id: String,
        val target: String,
        val instruction: String,
        val createdAt: Long,
        val dueAt: Long = createdAt,
        val scheduled: Boolean = false
    )

    @Synchronized
    fun enqueue(context: Context, target: String, instruction: String, dueAt: Long = System.currentTimeMillis()): Task? {
        val clean = instruction.trim().take(2000)
        if (clean.isBlank()) return null
        val tasks = read(context).toMutableList()
        if (tasks.size >= MAX_TASKS) return null
        val now = System.currentTimeMillis()
        val isScheduled = dueAt > now
        val task = Task(UUID.randomUUID().toString(), target.trim().take(180), clean, now,
            if (isScheduled) dueAt else now, isScheduled)
        tasks += task
        write(context, tasks)
        if (isScheduled) setReminder(context, task)
        return task
    }

    /** Only explicitly submitted immediate work may run on returning to the browser. */
    @Synchronized
    fun takeImmediate(context: Context, limit: Int = 3): List<Task> {
        val tasks = read(context)
        val ready = tasks.filterNot { it.scheduled }.take(limit.coerceAtLeast(1))
        if (ready.isNotEmpty()) write(context, tasks.filterNot { task -> ready.any { it.id == task.id } })
        return ready
    }

    @Synchronized
    fun dueForReview(context: Context): List<Task> =
        read(context).filter { it.scheduled && it.dueAt <= System.currentTimeMillis() }

    @Synchronized
    fun takeScheduled(context: Context, id: String): Task? {
        val tasks = read(context)
        val task = tasks.firstOrNull { it.id == id && it.scheduled && it.dueAt <= System.currentTimeMillis() } ?: return null
        write(context, tasks.filterNot { it.id == id })
        cancelReminder(context, id)
        return task
    }

    @Synchronized
    fun restoreReminders(context: Context) {
        val now = System.currentTimeMillis()
        read(context).filter { it.scheduled }.forEach { task ->
            if (task.dueAt > now) setReminder(context, task)
            else PhormiNotificationManager.post(context, PhormiNotificationManager.CHANNEL_AI,
                task.id.hashCode(), "Phormi AI task is ready",
                "Open Phormi to review: ${task.instruction.take(70)}",
                Intent(context, AiActivity::class.java))
        }
    }

    @Synchronized
    fun cancel(context: Context, id: String) {
        write(context, read(context).filterNot { it.id == id })
        cancelReminder(context, id)
    }

    @Synchronized
    fun list(context: Context): List<Task> = read(context).sortedBy { it.dueAt }

    @Synchronized
    fun pendingCount(context: Context): Int = read(context).size

    fun saveStatus(context: Context, status: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString("last_status", status.take(1000)).apply()
    }

    fun lastStatus(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("last_status", "") ?: ""

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
                        .put("createdAt", System.currentTimeMillis())).toString()
                }.getOrNull()
            } ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val instruction = o.optString("instruction").trim()
                    if (instruction.isBlank()) continue
                    val created = o.optLong("createdAt", System.currentTimeMillis())
                    add(Task(o.optString("id").ifBlank { UUID.randomUUID().toString() },
                        o.optString("target").trim(), instruction.take(2000), created,
                        o.optLong("dueAt", created), o.optBoolean("scheduled", false)))
                }
            }.take(MAX_TASKS)
        }.getOrDefault(emptyList())
    }

    private fun write(context: Context, tasks: List<Task>) {
        val arr = JSONArray()
        tasks.take(MAX_TASKS).forEach { task ->
            arr.put(JSONObject().put("id", task.id).put("target", task.target)
                .put("instruction", task.instruction).put("createdAt", task.createdAt)
                .put("dueAt", task.dueAt).put("scheduled", task.scheduled))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_LEGACY_TASK).putString(KEY_TASKS, arr.toString()).apply()
    }

    private fun reminderIntent(context: Context, id: String): PendingIntent = PendingIntent.getBroadcast(
        context, id.hashCode(), Intent(context, PhormiAiReminderReceiver::class.java).putExtra("task_id", id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun setReminder(context: Context, task: Task) {
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        // Inexact alarms need no special permission. Android may defer delivery to save battery.
        runCatching {
            if (Build.VERSION.SDK_INT >= 23) alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, task.dueAt, reminderIntent(context, task.id))
            else alarm.set(AlarmManager.RTC_WAKEUP, task.dueAt, reminderIntent(context, task.id))
        }
    }

    private fun cancelReminder(context: Context, id: String) {
        (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(reminderIntent(context, id))
    }
}
