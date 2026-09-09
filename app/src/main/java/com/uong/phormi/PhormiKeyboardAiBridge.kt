package com.uong.phormi

import android.content.Context
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.view.inputmethod.CompletionInfo
import android.view.inputmethod.EditorInfo
import java.lang.reflect.Modifier

/** Local, API-free semantic emoji suggestions for the Phormi IME. */
object PhormiKeyboardAiBridge {
    private val handler = Handler(Looper.getMainLooper())
    private var started = false
    private var lastText = ""
    private var lastSuggestions = emptyList<String>()

    fun start(context: Context) {
        if (started) return
        started = true
        handler.post(object : Runnable {
            override fun run() {
                runCatching { update() }
                handler.postDelayed(this, 650L)
            }
        })
    }

    private fun update() {
        val service = currentService() ?: return
        val info = service.currentInputEditorInfo ?: return
        val variation = info.inputType and EditorInfo.TYPE_MASK_VARIATION
        if (variation == EditorInfo.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == EditorInfo.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
            variation == EditorInfo.TYPE_TEXT_VARIATION_WEB_PASSWORD) return
        val text = service.currentInputConnection?.getTextBeforeCursor(180, 0)?.toString().orEmpty()
        if (text == lastText) return
        lastText = text
        val suggestions = PhormiEmojiSuggester.suggest(text)
        if (suggestions == lastSuggestions) return
        lastSuggestions = suggestions
        val clazz = service.javaClass
        val panelField = findField(clazz, "panel") ?: return
        panelField.isAccessible = true
        if (panelField.get(service)?.toString()?.contains("KEYBOARD") != true) return
        val completionField = findField(clazz, "completions") ?: return
        completionField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val old = (completionField.get(service) as? List<CompletionInfo>).orEmpty()
            .filterNot { it.id <= -9000 }
        val ai = suggestions.mapIndexed { index, emoji -> CompletionInfo(-9000 - index, index, emoji) }
        completionField.set(service, (old + ai).take(8))
        findMethod(clazz, "render")?.let { method ->
            method.isAccessible = true
            val view = method.invoke(service)
            service.setInputView(view as android.view.View)
        }
    }

    private fun currentService(): InputMethodService? {
        val outer = PhormiKeyboardService::class.java
        val companion = runCatching { outer.getDeclaredField("Companion").apply { isAccessible = true }.get(null) }.getOrNull() ?: return null
        val field = findField(companion.javaClass, "instance") ?: return null
        field.isAccessible = true
        return field.get(companion) as? InputMethodService
    }

    private fun findField(type: Class<*>, name: String) = generateSequence(type) { it.superclass }
        .flatMap { it.declaredFields.asSequence() }
        .firstOrNull { it.name == name && !Modifier.isStatic(it.modifiers) || it.name == name }

    private fun findMethod(type: Class<*>, name: String) = generateSequence(type) { it.superclass }
        .flatMap { it.declaredMethods.asSequence() }
        .firstOrNull { it.name == name && it.parameterTypes.isEmpty() }
}

object PhormiEmojiSuggester {
    private val rules = listOf(
        listOf("love", "lovely", "romance", "heart", "crush", "miss you") to listOf("❤️", "🥰", "😍", "😘"),
        listOf("happy", "great", "good", "yay", "awesome", "glad") to listOf("😊", "😄", "🥳", "✨"),
        listOf("sad", "sorry", "hurt", "unhappy", "cry", "crying") to listOf("😢", "😭", "🥺", "💔"),
        listOf("angry", "mad", "annoyed", "hate", "furious") to listOf("😡", "🤬", "💢", "🔥"),
        listOf("laugh", "funny", "joke", "lol", "lmao") to listOf("😂", "🤣", "😆", "💀"),
        listOf("wow", "amazing", "shocked", "surprise", "surprised") to listOf("😮", "🤯", "😱", "✨"),
        listOf("cool", "style", "stylish", "nice") to listOf("😎", "🔥", "💯", "✨"),
        listOf("tired", "sleep", "sleepy", "exhausted") to listOf("😴", "🥱", "😪", "🫠"),
        listOf("confused", "confusing", "what", "why") to listOf("🤔", "😕", "🧐", "❓"),
        listOf("party", "birthday", "celebrate", "congrats") to listOf("🎉", "🥳", "🎂", "🎊")
    )

    fun suggest(text: String): List<String> {
        val lower = text.lowercase()
        val matched = rules.firstOrNull { (words, _) -> words.any { lower.contains(it) } }?.second
        return matched ?: when {
            lower.endsWith("!") -> listOf("😊", "😄", "🔥", "✨")
            lower.endsWith("?") -> listOf("🤔", "❓", "😅", "👀")
            else -> emptyList()
        }
    }
}
