package com.uong.phormi

import android.content.Context
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.SoundEffectConstants
import android.view.View
import android.widget.Button
import android.view.inputmethod.CompletionInfo
import java.lang.ref.WeakReference
import kotlin.math.sqrt

/** Local keyboard enhancement bridge: semantic emoji suggestions, autocorrect, haptics/sound and glide typing. */
object PhormiKeyboardAiBridge {
    private const val TAG_GLIDE = 0x50484731
    private const val TAG_FEEDBACK = 0x50484631
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
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        if (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD || variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD) return
        val text = service.currentInputConnection?.getTextBeforeCursor(180, 0)?.toString().orEmpty()
        applyAutocorrect(service, text)
        applyAutoCaps(service, text)
        installViewEnhancements(service)
        if (text == lastText) return
        lastText = text
        val suggestions = if (PhormiKeyboardPreferences.suggestions(service)) PhormiEmojiSuggester.suggest(text) else emptyList()
        if (suggestions == lastSuggestions) return
        lastSuggestions = suggestions
        val panelField = findField(service.javaClass, "panel") ?: return
        panelField.isAccessible = true
        if (panelField.get(service)?.toString()?.contains("KEYBOARD") != true) return
        val completionField = findField(service.javaClass, "completions") ?: return
        completionField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val old = (completionField.get(service) as? List<CompletionInfo>).orEmpty().filterNot { it.id <= -9000 }
        completionField.set(service, (old + suggestions.mapIndexed { index, emoji -> CompletionInfo(-9000 - index, index, emoji) }).take(8))
        rerender(service)
    }

    private fun applyAutocorrect(service: PhormiKeyboardService, text: String) {
        if (!PhormiKeyboardPreferences.autocorrect(service) || !text.endsWith(" ")) return
        val corrections = mapOf("teh" to "the", "adn" to "and", "dont" to "don't", "cant" to "can't", "wont" to "won't", "im" to "I'm", "ive" to "I've", "id" to "I'd", "recieve" to "receive", "becuase" to "because", "seperate" to "separate", "definately" to "definitely", "occured" to "occurred", "alot" to "a lot")
        corrections.entries.firstOrNull { text.removeSuffix(" ").substringAfterLast(" ").equals(it.key, true) }?.let { (wrong, right) ->
            service.currentInputConnection?.let { ic -> ic.deleteSurroundingText(wrong.length + 1, 0); ic.commitText("$right ", 1) }
        }
    }

    private fun applyAutoCaps(service: PhormiKeyboardService, text: String) {
        if (!PhormiKeyboardPreferences.autoCaps(service)) return
        val shouldCap = text.isBlank() || text.endsWith(". ") || text.endsWith("! ") || text.endsWith("? ") || text.endsWith("\n")
        if (!shouldCap) return
        val shift = findField(service.javaClass, "shift") ?: return
        val caps = findField(service.javaClass, "capsLock") ?: return
        shift.isAccessible = true; caps.isAccessible = true
        if (!(caps.get(service) as? Boolean ?: false) && !(shift.get(service) as? Boolean ?: false)) {
            shift.set(service, true)
            rerender(service)
        }
    }

    private fun installViewEnhancements(service: PhormiKeyboardService) {
        val root = service.inputView ?: return
        val buttons = mutableListOf<Button>()
        collectButtons(root, buttons)
        val letterButtons = buttons.filter { it.text.toString().matches(Regex("[A-Za-z]")) }
        letterButtons.forEach { button ->
            if (button.getTag(TAG_GLIDE) == true) return@forEach
            button.setTag(TAG_GLIDE, true)
            val state = GlideState()
            button.setOnTouchListener { view, event ->
                feedback(view, event.actionMasked)
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { state.active = true; state.sequence.clear(); state.sequence.add(button.text.toString().lowercase()); state.last = button; true }
                    MotionEvent.ACTION_MOVE -> {
                        val hit = nearestLetter(letterButtons, event.rawX, event.rawY)
                        if (state.active && hit != null && hit !== state.last) { state.sequence.add(hit.text.toString().lowercase()); state.last = hit }
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (event.actionMasked == MotionEvent.ACTION_UP && state.active) {
                            if (state.sequence.size >= 2) {
                                val word = PhormiGlideEngine.resolve(state.sequence.joinToString(""))
                                val output = if (button.text.toString().first().isUpperCase()) word.replaceFirstChar { it.uppercase() } else word
                                service.currentInputConnection?.commitText(output, 1)
                            } else view.performClick()
                        }
                        state.active = false; true
                    }
                    else -> true
                }
            }
        }
        buttons.filterNot { letterButtons.contains(it) || it.text.toString() == "Space" }.forEach { button ->
            if (button.getTag(TAG_FEEDBACK) == true) return@forEach
            button.setTag(TAG_FEEDBACK, true)
            button.setOnTouchListener { view, event -> feedback(view, event.actionMasked); false }
        }
    }

    private fun feedback(view: View, action: Int) {
        if (action != MotionEvent.ACTION_DOWN) return
        val context = view.context
        if (PhormiKeyboardPreferences.haptic(context)) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        if (PhormiKeyboardPreferences.sound(context)) view.playSoundEffect(SoundEffectConstants.CLICK)
    }

    private class GlideState { var active = false; var last: Button? = null; val sequence = mutableListOf<String>() }

    private fun nearestLetter(buttons: List<Button>, x: Float, y: Float): Button? {
        var best: Button? = null; var bestDistance = Float.MAX_VALUE
        buttons.forEach { button ->
            val loc = IntArray(2); button.getLocationOnScreen(loc)
            val cx = loc[0] + button.width / 2f; val cy = loc[1] + button.height / 2f
            val dx = x - cx; val dy = y - cy; val d = sqrt(dx * dx + dy * dy)
            val radius = maxOf(button.width, button.height).toFloat() * 1.15f
            if (d < radius && d < bestDistance) { best = button; bestDistance = d }
        }
        return best
    }

    private fun collectButtons(view: View, out: MutableList<Button>) {
        if (view is Button) out += view
        if (view is android.view.ViewGroup) for (i in 0 until view.childCount) collectButtons(view.getChildAt(i), out)
    }

    private fun rerender(service: PhormiKeyboardService) {
        findMethod(service.javaClass, "render")?.let { method -> runCatching { method.isAccessible = true; service.setInputView(method.invoke(service) as View) } }
    }

    private fun currentService(): PhormiKeyboardService? {
        val outer = PhormiKeyboardService::class.java
        val companion = runCatching { outer.getDeclaredField("Companion").apply { isAccessible = true }.get(null) }.getOrNull() ?: return null
        val field = findField(companion.javaClass, "instance") ?: return null
        field.isAccessible = true
        return field.get(companion) as? PhormiKeyboardService
    }

    private fun findField(type: Class<*>, name: String) = generateSequence(type) { it.superclass }.flatMap { it.declaredFields.asSequence() }.firstOrNull { it.name == name }
    private fun findMethod(type: Class<*>, name: String) = generateSequence(type) { it.superclass }.flatMap { it.declaredMethods.asSequence() }.firstOrNull { it.name == name && it.parameterTypes.isEmpty() }
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
        return matched ?: when { lower.endsWith("!") -> listOf("😊", "😄", "🔥", "✨"); lower.endsWith("?") -> listOf("🤔", "❓", "😅", "👀"); else -> emptyList() }
    }
}

object PhormiGlideEngine {
    private val dictionary = ("hello help hey hi how what why where when thanks thankyou please sorry love lovely happy happiness sad friend friends family home work good great awesome amazing cool nice okay yes no maybe today tomorrow yesterday morning night now later soon welcome congratulations congrats birthday party celebrate celebration food hungry coffee water music movie phone keyboard browser internet website google youtube github nigeria lagos phormi create emoji sticker download upload share search find open close save favorite bookmark history tab tabs group private ghost settings security password account message messages typing type write writing example testing test android iphone apple computer school student business project time day week month year money free local ai image photo video camera voice call chat whatsapp tiktok instagram facebook twitter").split(" ").toSet()

    fun resolve(path: String): String {
        val clean = path.lowercase().filter { it in 'a'..'z' }
        if (clean.isBlank()) return clean
        dictionary.minByOrNull { distance(clean, it) }?.let { best -> if (distance(clean, best) <= maxOf(1, clean.length / 3)) return best }
        return clean
    }

    private fun distance(a: String, b: String): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length) dp[i][j] = minOf(dp[i - 1][j] + 1, dp[i][j - 1] + 1, dp[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
        return dp[a.length][b.length]
    }
}
