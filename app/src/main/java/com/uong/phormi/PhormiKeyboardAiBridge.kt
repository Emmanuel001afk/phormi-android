package com.uong.phormi

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.SoundEffectConstants
import android.view.inputmethod.CompletionInfo
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import kotlin.math.sqrt

/** Local enhancement layer for the Phormi system IME. */
object PhormiKeyboardAiBridge {
    private const val TAG_GLIDE = 0x50484731
    private const val TAG_FEEDBACK = 0x50484631
    private const val TAG_AI_REACTION = 0x50484145
    private val handler = Handler(Looper.getMainLooper())
    private var started = false
    private var lastText = ""
    private var lastSuggestions = emptyList<String>()
    private var lastAiText = ""
    private var aiController: PhormiKeyboardAiEmojiController? = null

    fun start() {
        if (started) return
        started = true
        handler.post(object : Runnable {
            override fun run() { runCatching { update() }; if (started) handler.postDelayed(this, 650L) }
        })
    }
    fun start(@Suppress("UNUSED_PARAMETER") context: Context) = start()
    fun stop() {
        started = false
        lastText = ""
        lastSuggestions = emptyList()
        lastAiText = ""
        aiController?.cancel()
        aiController = null
        handler.removeCallbacksAndMessages(null)
    }

    private fun update() {
        val service = currentService(); val info = service?.currentInputEditorInfo
        if (service == null || info == null) { stop(); return }
        val inputClass = info.inputType and InputType.TYPE_MASK_CLASS
        if (inputClass != InputType.TYPE_CLASS_TEXT || (info.inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0 || PhormiKeyboardTextEngine.isPrivateEditor(info)) { stop(); return }
        val text = service.currentInputConnection?.let { PhormiKeyboardTextEngine.contextBeforeCursor(it) }.orEmpty()
        applyAutocorrect(service, text)
        applyAutoCaps(service)
        // The system IME renderer already owns touch feedback. Do not replace key
        // touch handlers here: doing so would make normal taps compete with a
        // gesture recognizer. Glide typing will be added at the keyboard-row level.
        if (text != lastText) {
            lastText = text
            updateSuggestions(service, info, text)
            updateAiReaction(service, text)
        }
    }

    private fun updateSuggestions(service: PhormiKeyboardServiceV2, info: android.view.inputmethod.EditorInfo, text: String) {
        if (!PhormiKeyboardPreferences.suggestions(service)) return
        val locale = PhormiKeyboardTextEngine.localeFor(info)
        val suggestions = (PhormiEmojiSuggester.suggest(text) + PhormiLocalPredictionEngine.suggest(text, locale)).distinct().take(8)
        if (suggestions == lastSuggestions) return
        lastSuggestions = suggestions
        val completionField = findField(service.javaClass, "completions") ?: return
        completionField.isAccessible = true
        @Suppress("UNCHECKED_CAST") val old = (completionField.get(service) as? List<CompletionInfo>).orEmpty().filterNot { it.id <= -9000L }
        completionField.set(service, (old + suggestions.mapIndexed { index, value -> CompletionInfo(-9000L - index, index, value) }).take(8))
        rerender(service)
    }

    private fun updateAiReaction(service: PhormiKeyboardServiceV2, text: String) {
        if (!PhormiKeyboardPreferences.aiEmoji(service) || text.trim().length < 3) return
        if (text == lastAiText) return
        lastAiText = text
        if (aiController == null) {
            aiController = PhormiKeyboardAiEmojiController(service) { files, generating ->
                if (started) {
                    val current = currentService()
                    val file = files.firstOrNull()
                    if (current != null && file != null && !generating) installAiReaction(current, file)
                }
            }
        }
        aiController?.generate(text.trim())
    }

    private fun installAiReaction(service: PhormiKeyboardServiceV2, file: java.io.File) {
        val root = service.getInputView() as? ViewGroup ?: return
        val density = service.resources.displayMetrics.density
        val rail: HorizontalScrollView
        val row: LinearLayout
        val candidate = if (root.childCount > 2) root.getChildAt(2) else null
        if (candidate is HorizontalScrollView && candidate.childCount > 0 && candidate.getChildAt(0) is LinearLayout) {
            rail = candidate
            row = candidate.getChildAt(0) as LinearLayout
        } else {
            rail = HorizontalScrollView(service).apply { isHorizontalScrollBarEnabled = false; tag = TAG_AI_REACTION }
            row = LinearLayout(service).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
            rail.addView(row)
            root.addView(rail, minOf(2, root.childCount), LinearLayout.LayoutParams(-1, (36 * density).toInt().coerceAtLeast(1)))
        }
        for (i in row.childCount - 1 downTo 0) if (row.getChildAt(i).getTag(TAG_AI_REACTION) == true) row.removeViewAt(i)
        val image = ImageView(service).apply {
            tag = TAG_AI_REACTION
            setImageBitmap(BitmapFactory.decodeFile(file.absolutePath))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding((4 * density).toInt(), (2 * density).toInt(), (4 * density).toInt(), (2 * density).toInt())
            contentDescription = "Phormi custom reaction"
            background = GradientDrawable().apply { setColor(Color.rgb(31, 41, 55)); cornerRadius = 12 * density }
            setOnClickListener {
                feedback(this)
                PhormiKeyboardServiceV2.commitPickedContent(service, PhormiKeyboardStickerStore.contentUri(service, file))
            }
        }
        row.addView(image, 0, LinearLayout.LayoutParams((42 * density).toInt(), (34 * density).toInt()).apply { setMargins((2 * density).toInt(), 0, (4 * density).toInt(), 0) })
    }

    private fun applyAutocorrect(service: PhormiKeyboardServiceV2, text: String) {
        if (!PhormiKeyboardPreferences.autocorrect(service) || !text.endsWith(" ")) return
        val word = text.removeSuffix(" ").split(Regex("\\s+")).lastOrNull().orEmpty()
        val right = PhormiKeyboardTextEngine.correctionFor(service, word, PhormiKeyboardTextEngine.localeFor(service.currentInputEditorInfo)) ?: return
        service.currentInputConnection?.let { ic -> ic.beginBatchEdit(); runCatching { ic.deleteSurroundingText(word.length + 1, 0); ic.commitText("$right ", 1) }; ic.endBatchEdit() }
    }

    private fun applyAutoCaps(service: PhormiKeyboardServiceV2) {
        if (!PhormiKeyboardPreferences.autoCaps(service)) return
        if (!PhormiKeyboardTextEngine.autoCapitalize(service.currentInputConnection, service.currentInputEditorInfo)) return
        val shift = findField(service.javaClass, "shift") ?: return; val caps = findField(service.javaClass, "capsLock") ?: return
        shift.isAccessible = true; caps.isAccessible = true
        if (!(caps.get(service) as? Boolean ?: false) && !(shift.get(service) as? Boolean ?: false)) { shift.set(service, true); rerender(service) }
    }

    private fun installViewEnhancements(@Suppress("UNUSED_PARAMETER") service: PhormiKeyboardServiceV2) {
        // Intentionally empty. The V2 renderer owns all touch handling so taps,
        // long-press/repeat, space cursor movement, and future gesture input remain
        // coordinated instead of competing for the same MotionEvent stream.
    }

    private fun feedback(view: View, action: Int = MotionEvent.ACTION_DOWN) { if (action == MotionEvent.ACTION_DOWN) { if (PhormiKeyboardPreferences.haptic(view.context)) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); if (PhormiKeyboardPreferences.sound(view.context)) view.playSoundEffect(SoundEffectConstants.CLICK) } }
    private class GlideState { var active = false; var last: Button? = null; val sequence = mutableListOf<String>() }
    private fun nearestLetter(buttons: List<Button>, x: Float, y: Float): Button? { var best: Button? = null; var bestDistance = Float.MAX_VALUE; buttons.forEach { b -> val loc = IntArray(2); b.getLocationOnScreen(loc); val dx = x - (loc[0] + b.width / 2f); val dy = y - (loc[1] + b.height / 2f); val d = sqrt(dx * dx + dy * dy); if (d < maxOf(b.width, b.height) * 1.15f && d < bestDistance) { best = b; bestDistance = d } }; return best }
    private fun collectButtons(view: View, out: MutableList<Button>) { if (view is Button) out += view; if (view is ViewGroup) for (i in 0 until view.childCount) collectButtons(view.getChildAt(i), out) }
    private fun rerender(service: PhormiKeyboardServiceV2) { runCatching { service.setInputView(service.render()) } }
    private fun currentService(): PhormiKeyboardServiceV2? { val outer = PhormiKeyboardServiceV2::class.java; val companion = runCatching { outer.getDeclaredField("Companion").apply { isAccessible = true }.get(null) }.getOrNull() ?: return null; val field = findField(companion.javaClass, "instance") ?: return null; field.isAccessible = true; return field.get(companion) as? PhormiKeyboardServiceV2 }
    private fun findField(type: Class<*>, name: String) = generateSequence(type) { it.superclass }.flatMap { it.declaredFields.asSequence() }.firstOrNull { it.name == name }
}

object PhormiEmojiSuggester {
    private val rules = listOf(listOf("love","heart","crush") to listOf("❤️","🥰","😍","😘"), listOf("happy","great","good","awesome") to listOf("😊","😄","🥳","✨"), listOf("sad","sorry","hurt","cry") to listOf("😢","😭","🥺","💔"), listOf("angry","mad","hate","furious") to listOf("😡","🤬","💢","🔥"), listOf("laugh","funny","joke","lol") to listOf("😂","🤣","😆","💀"), listOf("wow","amazing","shock","surprise") to listOf("😮","🤯","😱","✨"), listOf("cool","style","nice") to listOf("😎","🔥","💯","✨"), listOf("tired","sleep","sleepy") to listOf("😴","🥱","😪","🫠"), listOf("confused","what","why") to listOf("🤔","😕","🧐","❓"), listOf("party","birthday","celebrate","congrats") to listOf("🎉","🥳","🎂","🎊"))
    fun suggest(text: String): List<String> { val lower = text.lowercase(); return rules.firstOrNull { (words, _) -> words.any { lower.contains(it) } }?.second ?: when { lower.endsWith("!") -> listOf("😊","😄","🔥","✨"); lower.endsWith("?") -> listOf("🤔","❓","😅","👀"); else -> emptyList() } }
}

object PhormiLocalPredictionEngine {
    private val words = listOf("the","and","you","your","that","this","with","have","for","are","what","when","where","why","how","can","will","would","could","should","please","thanks","hello","hey","good","great","today","tomorrow","now","later","because","about","from","just","really","very","love","like","want","need","know","think","make","going","come","home","work","friend","family","message","send","open","close","search","download","share","favorite","bookmark","history","keyboard","browser","testing","test","project")
    private val frenchWords = listOf("bonjour","merci","comment","ça","allez","vas","faire","je","suis","vais","peux","veux","pense","aime","nous","sommes","allons","pouvons","devons","avons","vous","êtes","pouvez","avez","voulez","très","bien","heureux","triste","excité","important","bonne","journée","chance","nuit","soirée","demain","bientôt","plus","tard","pour","moi","amour","amis")
    fun suggest(text: String, locale: java.util.Locale = java.util.Locale.getDefault()): List<String> { val token = text.trimEnd().split(Regex("\\s+")).lastOrNull().orEmpty().lowercase(locale).filter { it.isLetter() }; if (token.length < 2) return emptyList(); val pool = if (locale.language == java.util.Locale.FRENCH.language) frenchWords else words; return pool.filter { it.startsWith(token) && it != token }.take(4) }
}

object PhormiGlideEngine {
    private val dictionary = ("hello help hey hi how what why where when thanks thankyou please sorry love lovely happy happiness sad friend friends family home work good great awesome amazing cool nice okay yes no maybe today tomorrow yesterday morning night now later soon welcome congratulations congrats birthday party celebrate food hungry coffee water music movie phone keyboard browser internet website google youtube github nigeria lagos phormi create emoji sticker download upload share search find open close save favorite bookmark history tab tabs group private ghost settings security password account message messages typing type write writing example testing test android iphone apple computer school student business project time day week month year money free local ai image photo video camera voice call chat whatsapp tiktok instagram facebook twitter").split(" ").toSet()
    fun resolve(path: String): String { val clean = path.lowercase().filter { it in 'a'..'z' }; if (clean.isBlank()) return clean; dictionary.minByOrNull { distance(clean, it) }?.let { best -> if (distance(clean, best) <= maxOf(1, clean.length / 3)) return best }; return clean }
    private fun distance(a: String, b: String): Int { val dp = Array(a.length + 1) { IntArray(b.length + 1) }; for (i in 0..a.length) dp[i][0] = i; for (j in 0..b.length) dp[0][j] = j; for (i in 1..a.length) for (j in 1..b.length) dp[i][j] = minOf(dp[i - 1][j] + 1, dp[i][j - 1] + 1, dp[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1); return dp[a.length][b.length] }
}
