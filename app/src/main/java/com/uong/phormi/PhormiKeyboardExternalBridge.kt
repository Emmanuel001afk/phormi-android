package com.uong.phormi

import android.content.Context

/** Small compatibility bridge for activities that need to hand text back to the active IME. */
object PhormiKeyboardExternalBridge {
    fun commitText(context: Context, text: String): Boolean {
        if (text.isBlank()) return false
        return runCatching {
            val serviceClass = PhormiKeyboardServiceV2::class.java
            val companion = serviceClass.getDeclaredField("Companion").apply { isAccessible = true }.get(null)
            val instanceField = generateSequence(companion.javaClass) { it.superclass }
                .flatMap { it.declaredFields.asSequence() }
                .first { it.name == "instance" }
                .apply { isAccessible = true }
            val service = instanceField.get(companion) ?: return false
            val method = generateSequence(serviceClass) { it.superclass }
                .flatMap { it.declaredMethods.asSequence() }
                .first { it.name == "commitText" && it.parameterTypes.size == 1 }
                .apply { isAccessible = true }
            (method.invoke(service, text) as? Boolean) ?: true
        }.getOrDefault(false)
    }
}
