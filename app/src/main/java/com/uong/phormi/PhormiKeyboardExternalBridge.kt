package com.uong.phormi

import android.content.Context

/** Bridge used by voice/media activities while the system IME connection is active. */
object PhormiKeyboardExternalBridge {
    fun commitText(context: Context, text: String): Boolean {
        if (text.isBlank()) return false
        return runCatching { PhormiKeyboardServiceV2.commitExternalText(context, text) }.getOrDefault(false)
    }
}
