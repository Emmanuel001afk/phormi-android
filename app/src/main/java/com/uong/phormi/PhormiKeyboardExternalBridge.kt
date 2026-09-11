package com.uong.phormi

import android.content.Context
import android.os.Handler
import android.os.Looper

/**
 * Bridge used by short-lived activities (voice/media pickers) to return data to the
 * currently active IME. The service owns the real InputConnection; activities only
 * queue work when Android temporarily suspends that connection.
 */
object PhormiKeyboardExternalBridge {
    private const val PREFS = "phormi_keyboard_pending"
    private const val KEY_TEXT = "pending_text"
    private const val MAX_PENDING = 4000
    private val handler = Handler(Looper.getMainLooper())

    fun commitText(context: Context, text: String): Boolean {
        val value = text.trim()
        if (value.isBlank()) return false
        if (PhormiKeyboardServiceV2.commitExternalText(context, value)) return true
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_TEXT, value.take(MAX_PENDING)).apply()
        retryPending(context.applicationContext, 0)
        return false
    }

    private fun retryPending(context: Context, attempt: Int) {
        if (attempt >= 20) return
        handler.postDelayed({
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val pending = prefs.getString(KEY_TEXT, null).orEmpty()
            if (pending.isBlank()) return@postDelayed
            if (PhormiKeyboardServiceV2.commitExternalText(context, pending)) {
                prefs.edit().remove(KEY_TEXT).apply()
            } else {
                retryPending(context, attempt + 1)
            }
        }, 250L)
    }
}
