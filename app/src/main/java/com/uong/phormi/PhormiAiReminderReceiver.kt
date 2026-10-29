package com.uong.phormi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Notifies the user when scheduled browser work can be reviewed and run in the foreground. */
class PhormiAiReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val id = intent?.getStringExtra("task_id") ?: return
        val task = PhormiAiPendingTask.list(context).firstOrNull { it.id == id } ?: return
        if (!task.scheduled || task.dueAt > System.currentTimeMillis()) return
        PhormiNotificationManager.post(context, PhormiNotificationManager.CHANNEL_AI, id.hashCode(),
            "Phormi AI task is ready", "Open Phormi to review: ${task.instruction.take(70)}",
            Intent(context, AiActivity::class.java))
    }
}
