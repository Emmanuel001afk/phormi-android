package com.uong.phormi

import android.view.View

/** Renderer bridge for the V2 IME. Uses the V2's own private render builders so there is only one UI implementation. */
internal fun PhormiKeyboardServiceV2.render(): View {
    val panelName = runCatching {
        javaClass.walkHierarchyFields("panel")?.apply { isAccessible = true }?.get(this)?.toString()
    }.getOrDefault("KEYBOARD")
    val builderName = when (panelName) {
        "EMOJI" -> "buildEmoji"
        "CLIPBOARD" -> "buildClipboard"
        "AI_EMOJI" -> "buildAiEmoji"
        else -> "buildKeyboard"
    }
    return runCatching {
        javaClass.walkHierarchyMethods(builderName)?.apply { isAccessible = true }?.invoke(this) as View
    }.getOrElse { throw IllegalStateException("Unable to render Phormi keyboard", it) }
}

private fun Class<*>.walkHierarchyFields(name: String): java.lang.reflect.Field? {
    var type: Class<*>? = this
    while (type != null) {
        type.declaredFields.firstOrNull { it.name == name }?.let { return it }
        type = type.superclass
    }
    return null
}

private fun Class<*>.walkHierarchyMethods(name: String): java.lang.reflect.Method? {
    var type: Class<*>? = this
    while (type != null) {
        type.declaredMethods.firstOrNull { it.name == name && it.parameterTypes.isEmpty() }?.let { return it }
        type = type.superclass
    }
    return null
}
