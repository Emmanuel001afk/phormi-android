package com.uong.phormi

import android.content.Context
import android.os.Handler
import android.os.Looper

/** Small compatibility bridge for activities that need to hand text back to the active IME. */
object PhormiKeyboardExternalBridge {
    private const val PREFS = "phormi_keyboard_pending_text"
    private const val KEY_TEXT = "pending_text"
    private const val MAX_PENDING = 4000
    private val handler = Handler(Looper.getMainLooper())

    fun commitText(context: Context, text: String): Boolean {
        val value = text.trim()
        if (value.isBlank()) return false
        if (tryCommit(value)) return true

        val safe = value.take(MAX_PENDING)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_TEXT, safe).apply()
        retryPending(context.applicationContext, 0)
        return false
    }

    private fun retryPending(context: Context, attempt: Int) {
        if (attempt >= 20) return
        handler.postDelayed({
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val pending = prefs.getString(KEY_TEXT, null).orEmpty()
            if (pending.isBlank()) return@postDelayed
            if (tryCommit(pending)) {
                prefs.edit().remove(KEY_TEXT).apply()
            } else {
                retryPending(context, attempt + 1)
            }
        }, 250L)
    }

    private fun tryCommit(text: String): Boolean = runCatching {
        val serviceClass = PhormiKeyboardServiceV2::class.java
        val companion = serviceClass.getDeclaredField("Companion").apply { isAccessible = true }.get(null)
            ?: return false

        var instanceField: java.lang.reflect.Field? = null
        var companionClass: Class<*>? = companion.javaClass
        while (companionClass != null && instanceField == null) {
            instanceField = companionClass.declaredFields.firstOrNull { it.name == "instance" }
            companionClass = companionClass.superclass
        }
        val field = instanceField ?: return false
        field.isAccessible = true
        val service = field.get(companion) ?: return false

        var commitMethod: java.lang.reflect.Method? = null
        var serviceClassCursor: Class<*>? = serviceClass
        while (serviceClassCursor != null && commitMethod == null) {
            commitMethod = serviceClassCursor.declaredMethods.firstOrNull {
                it.name == "commitText" && it.parameterTypes.size == 1
            }
            serviceClassCursor = serviceClassCursor.superclass
        }
        val method = commitMethod ?: return false
        method.isAccessible = true
        (method.invoke(service, text) as? Boolean) ?: true
    }.getOrDefault(false)
}