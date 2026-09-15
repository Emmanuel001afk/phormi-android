package com.uong.phormi

import android.content.Context
import android.net.Uri
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
    private const val KEY_URI = "pending_uri"
    private const val MAX_PENDING = 4000
    private val handler = Handler(Looper.getMainLooper())

    fun commitText(context: Context, text: String): Boolean {
        val value = text.trim()
        if (value.isBlank()) return false
        if (PhormiKeyboardServiceV2.commitExternalText(context, value)) return true
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_TEXT, value.take(MAX_PENDING)).apply()
        retryPendingText(context.applicationContext, 0)
        return false
    }

    fun commitContent(context: Context, uri: Uri): Boolean {
        if (PhormiKeyboardServiceV2.commitPickedContent(context, uri)) return true
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_URI, uri.toString()).apply()
        retryPendingContent(context.applicationContext, 0)
        return false
    }

    private fun retryPendingText(context: Context, attempt: Int) {
        if (attempt >= 20) return
        handler.postDelayed({
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val pending = prefs.getString(KEY_TEXT, null).orEmpty()
            if (pending.isBlank()) return@postDelayed
            if (PhormiKeyboardServiceV2.commitExternalText(context, pending)) {
                prefs.edit().remove(KEY_TEXT).apply()
            } else {
                retryPendingText(context, attempt + 1)
            }
        }, 250L)
    }

    private fun retryPendingContent(context: Context, attempt: Int) {
        if (attempt >= 20) return
        handler.postDelayed({
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val pending = prefs.getString(KEY_URI, null).orEmpty()
            if (pending.isBlank()) return@postDelayed
            val uri = runCatching { Uri.parse(pending) }.getOrNull()
            if (uri != null && PhormiKeyboardServiceV2.commitPickedContent(context, uri)) {
                prefs.edit().remove(KEY_URI).apply()
            } else {
                retryPendingContent(context, attempt + 1)
            }
        }, 300L)
    }
}
