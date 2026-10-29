package com.uong.phormi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restore only scheduled reminders after reboot; never run browser automation here. */
class PhormiAiBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            PhormiAiPendingTask.restoreReminders(context)
        }
    }
}
