package com.uong.phormi

import android.view.View

/**
 * Compatibility render dispatcher for the rebuilt IME.
 *
 * The V2 service keeps its panel builders private. This extension restores the
 * render() call site used by the service while keeping those builders private.
 */
internal fun PhormiKeyboardServiceV2.render(): View {
    val panel = runCatching {
        val field = PhormiKeyboardServiceV2::class.java.getDeclaredField("panel").apply { isAccessible = true }
        field.get(this)?.toString().orEmpty()
    }.getOrDefault("KEYBOARD")
    val methodName = when {
        panel.endsWith("EMOJI") && panel != "AI_EMOJI" -> "buildEmoji"
        panel == "AI_EMOJI" -> "buildAiEmoji"
        panel == "CLIPBOARD" -> "buildClipboard"
        else -> "buildKeyboard"
    }
    val method = generateSequence(PhormiKeyboardServiceV2::class.java) { it.superclass }
        .flatMap { it.declaredMethods.asSequence() }
        .firstOrNull { it.name == methodName && it.parameterTypes.isEmpty() }
        ?: error("Missing Phormi keyboard panel builder: $methodName")
    method.isAccessible = true
    return method.invoke(this) as View
}
