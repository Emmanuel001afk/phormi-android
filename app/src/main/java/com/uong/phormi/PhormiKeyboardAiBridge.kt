package com.uong.phormi

import android.text.InputType
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.SoundEffectConstants
import android.view.View
import android.view.inputmethod.CompletionInfo
import android.widget.Button
import android.os.Handler
import android.os.Looper
import kotlin.math.sqrt

/** Optional enhancement layer for the Phormi system IME. All processing stays local. */
object PhormiKeyboardAiBridge {
    private const val TAG_GLIDE = 0x50484731
    private const val TAG_FEEDBACK = 0x50484631
    private val handler = Handler(Looper.getMainLooper())
    private var started = false
    private var lastText = ""
    private var lastSuggestions = emptyList<String>()

    /** Starts the local enhancement loop without requiring a network service or API. */
    fun start() {
        if (started) return
        started = true
        handler.post(object : Runnable {
            override fun run() { runCatching { update() }; if (started) handler.postDelayed(this, 650L) }
        })
    }

    /** Compatibility overload for older callers. The context is intentionally unused. */
    fun start(@Suppress("UNUSED_PARAMETER") context: android.content.Context) = start()

    fun stop() {
        started = false
        lastText = ""
        lastSuggestions = emptyList()
        handler.removeCallbacksAndMessages(null)
    }

    private fun update() {
        val service = currentService() ?: return
        val info = service.currentInputEditorInfo ?: return
        if (PhormiKeyboardTextEngine.isPassword(info) || PhormiKeyboardTextEngine.isUriLike(info)) return
        val text = service.currentInputConnection?.getTextBeforeCursor(180, 0)?.toString().orEmpty()
        applyAutocorrect(service, text)
        applyAutoCaps(service)
        installViewEnhancements(service)
        if (text == lastText) return
        lastText = text
        val suggestions = if (PhormiKeyboardPreferences.suggestions(service)) {
            (PhormiEmojiSuggester.suggest(text) + PhormiLocalPredictionEngine.suggest(text)).distinct().take(8)
        } else emptyList()
        if (suggestions == lastSuggestions) return
        lastSuggestions = suggestions
        val completionField = findField(service.javaClass, "completions") ?: return
        completionField.isAccessible = true
        @Suppress("UNCHECKED_CAST") val old = (completionField.get(service) as? List<CompletionInfo>).orEmpty().filterNot { it.id <= -9000L }
        completionField.set(service, (old + suggestions.mapIndexed { index, value -> CompletionInfo(-9000L - index, index, value) }).take(8))
        rerender(service)
    }

    private fun applyAutocorrect(service: PhormiKeyboardServiceV2, text: String) {
        if (!PhormiKeyboardPreferences.autocorrect(service) || !text.endsWith(" ")) return
        val word = text.removeSuffix(" ").split(Regex("\\s+")).lastOrNull().orEmpty()
        PhormiKeyboardTextEngine.correctionFor(word)?.let { right ->
            service.currentInputConnection?.let { ic ->
                ic.beginBatchEdit()
                runCatching { ic.deleteSurroundingText(word.length + 1, 0); ic.commitText("$right ", 1) }
                ic.endBatchEdit()
            }
        }
    }

    private fun applyAutoCaps(service: PhormiKeyboardServiceV2) {
        if (!PhormiKeyboardPreferences.autoCaps(service)) return
        val shouldCap = PhormiKeyboardTextEngine.autoCapitalize(service.currentInputConnection, service.currentInputEditorInfo)
        if (!shouldCap) return
        val shift = findField(service.javaClass, "shift") ?: return
        val caps = findField(service.javaClass, "capsLock") ?: return
        shift.isAccessible = true; caps.isAccessible = true
        if (!(caps.get(service) as? Boolean ?: false) && !(shift.get(service) as? Boolean ?: false)) {
            shift.set(service, true)
            rerender(service)
        }
    }

    private fun installViewEnhancements(service: PhormiKeyboardServiceV2) {
        val root = service.getInputView() ?: return
        val buttons = mutableListOf<Button>(); collectButtons(root, buttons)
        val letterButtons = buttons.filter { it.text.toString().matches(Regex("[A-Za-z]")) }
        letterButtons.forEach { button ->
            if (button.getTag(TAG_GLIDE) == true) return@forEach
            button.setTag(TAG_GLIDE, true)
            val state = GlideState()
            button.setOnTouchListener { view, event ->
                feedback(view, event.actionMasked)
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { state.active = true; state.sequence.clear(); state.sequence.add(button.text.toString().lowercase()); state.last = button; true }
                    MotionEvent.ACTION_MOVE -> { val hit = nearestLetter(letterButtons, event.rawX, event.rawY); if (state.active && hit != null && hit !== state.last) { state.sequence.add(hit.text.toString().lowercase()); state.last = hit }; true }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (event.actionMasked == MotionEvent.ACTION_UP && state.active) {
                            if (state.sequence.size >= 2) service.currentInputConnection?.commitText(PhormiGlideEngine.resolve(state.sequence.joinToString("")), 1)
                            else view.performClick()
                        }
                        state.active = false; true
                    }
                    else -> true
                }
            }
        }
        buttons.filterNot { letterButtons.contains(it) || it.text.toString() == "Space" }.forEach { button ->
            if (button.getTag(TAG_FEEDBACK) == true) return@forEach
            button.setTag(TAG_FEEDBACK, true); button.setOnTouchListener { view, event -> feedback(view, event.actionMasked); false }
        }
    }

    private fun feedback(view: View, action: Int) {
        if (action == MotionEvent.ACTION_DOWN) {
            if (PhormiKeyboardPreferences.haptic(view.context)) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            if (PhormiKeyboardPreferences.sound(view.context)) view.playSoundEffect(SoundEffectConstants.CLICK)
        }
    }

    private class GlideState { var active = false; var last: Button? = null; val sequence = mutableListOf<String>() }
    private fun nearestLetter(buttons: List<Button>, x: Float, y: Float): Button? { var best: Button? = null; var bestDistance = Float.MAX_VALUE; buttons.forEach { b -> val loc = IntArray(2); b.getLocationOnScreen(loc); val dx = x - (loc[0] + b.width / 2f); val dy = y - (loc[1] + b.height / 2f); val d = sqrt(dx * dx + dy * dy); if (d < maxOf(b.width, b.height) * 1.15f && d < bestDistance) { best = b; bestDistance = d } }; return best }
    private fun collectButtons(view: View, out: MutableList<Button>) { if (view is Button) out += view; if (view is android.view.ViewGroup) for (i in 0 until view.childCount) collectButtons(view.getChildAt(i), out) }
    private fun rerender(service: PhormiKeyboardServiceV2) { findMethod(service.javaClass, "render")?.let { m -> runCatching { m.isAccessible = true; service.setInputView(m.invoke(service) as View) } } }
    private fun currentService(): PhormiKeyboardServiceV2? { val outer = PhormiKeyboardServiceV2::class.java; val companion = runCatching { outer.getDeclaredField("Companion").apply { isAccessible = true }.get(null) }.getOrNull() ?: return null; val field = findField(companion.javaClass, "instance") ?: return null; field.isAccessible = true; return field.get(companion) as? PhormiKeyboardServiceV2 }
    private fun findField(type: Class<*>, name: String) = generateSequence(type) { it.superclass }.flatMap { it.declaredFields.asSequence() }.firstOrNull { it.name == name }
    private fun findMethod(type: Class<*>, name: String) = generateSequence(type) { it.superclass }.flatMap { it.declaredMethods.asSequence() }.firstOrNull { it.name == name && it.parameterTypes.isEmpty() }
}

object PhormiEmojiSuggester {
    private val rules = listOf(
        listOf("love","heart","crush") to listOf("❤️","🥰","😍","😘"), listOf("happy","great","good","awesome") to listOf("😊","😄","🥳","✨"), listOf("sad","sorry","hurt","cry") to listOf("😢","😭","🥺","💔"), listOf("angry","mad","hate","furious") to listOf("😡","🤬","💢","🔥"), listOf("laugh","funny","joke","lol") to listOf("😂","🤣","😆","💀"), listOf("wow","amazing","shock","surprise") to listOf("😮","🤯","😱","✨"), listOf("cool","style","nice") to listOf("😎","🔥","💯","✨"), listOf("tired","sleep","sleepy") to listOf("😴","🥱","😪","🫠"), listOf("confused","what","why") to listOf("🤔","😕","🧐","❓"), listOf("party","birthday","celebrate","congrats") to listOf("🎉","🥳","🎂","🎊"))
    fun suggest(text: String): List<String> { val lower = text.lowercase(); return rules.firstOrNull { (words, _) -> words.any { lower.contains(it) } }?.second ?: when { lower.endsWith("!") -> listOf("😊","😄","🔥","✨"); lower.endsWith("?") -> listOf("🤔","❓","😅","👀"); else -> emptyList() } }
}

object PhormiLocalPredictionEngine {
    private val words = listOf("the","and","you","your","that","this","with","have","for","are","what","when","where","why","how","can","will","would","could","should","please","thanks","hello","hey","good","great","today","tomorrow","now","later","because","about","from","just","really","very","love","like","want","need","know","think","make","going","come","home","work","friend","family","message","send","open","close","search","download","share","favorite","bookmark","history","keyboard","browser","testing","test","project")
    fun suggest(text: String): List<String> { val token = text.trimEnd().split(Regex("\\s+")).lastOrNull().orEmpty().lowercase().filter { it.isLetter() }; if (token.length < 2) return emptyList(); return words.filter { it.startsWith(token) && it != token }.take(4) }
}

object PhormiGlideEngine {
    private val dictionary = ("hello help hey hi how what why where when thanks thankyou please sorry love lovely happy happiness sad friend friends family home work good great awesome amazing cool nice okay yes no maybe today tomorrow yesterday morning night now later soon welcome congratulations congrats birthday party celebrate food hungry coffee water music movie phone keyboard browser internet website google youtube github nigeria lagos phormi create emoji sticker download upload share search find open close save favorite bookmark history tab tabs group private ghost settings security password account message messages typing type write writing example testing test android iphone apple computer school student business project time day week month year money free local ai image photo video camera voice call chat whatsapp tiktok instagram facebook twitter").split(" ").toSet()
    fun resolve(path: String): String { val clean = path.lowercase().filter { it in 'a'..'z' }; if (clean.isBlank()) return clean; dictionary.minByOrNull { distance(clean, it) }?.let { best -> if (distance(clean, best) <= maxOf(1, clean.length / 3)) return best }; return clean }
    private fun distance(a: String, b: String): Int { val dp = Array(a.length + 1) { IntArray(b.length + 1) }; for (i in 0..a.length) dp[i][0] = i; for (j in 0..b.length) dp[0][j] = j; for (i in 1..a.length) for (j in 1..b.length) dp[i][j] = minOf(dp[i - 1][j] + 1, dp[i][j - 1] + 1, dp[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1); return dp[a.length][b.length] }
}
