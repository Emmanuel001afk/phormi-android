package com.uong.phormi

import android.content.Context

/** Small compatibility bridge for activities that need to hand text back to the active IME. */
object PhormiKeyboardExternalBridge {
    fun commitText(context: Context, text: String): Boolean {
        if (text.isBlank()) return false
        return runCatching {
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
}
