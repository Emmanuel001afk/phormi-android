package com.uong.phormi

import android.view.View

/** Compatibility accessor for InputMethodService's private input view across Android versions. */
fun PhormiKeyboardService.getInputView(): View? {
    var type: Class<*>? = javaClass
    while (type != null) {
        val field = type.declaredFields.firstOrNull { it.name == "mInputView" || it.name == "inputView" }
        if (field != null) {
            return runCatching { field.isAccessible = true; field.get(this) as? View }.getOrNull()
        }
        type = type.superclass
    }
    return null
}
