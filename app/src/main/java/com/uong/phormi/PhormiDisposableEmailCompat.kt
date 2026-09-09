package com.uong.phormi

import android.widget.TextView

/** Shared UI status helper for the disposable-email activity. */
fun PhormiDisposableEmailActivity.setBusy(text: String) {
    val field = runCatching { javaClass.getDeclaredField("current") }.getOrNull() ?: return
    runCatching {
        field.isAccessible = true
        (field.get(this) as? TextView)?.let { runOnUiThread { it.text = text } }
    }
}
