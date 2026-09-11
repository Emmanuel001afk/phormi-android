package com.uong.phormi

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.SoundEffectConstants
import android.view.inputmethod.CompletionInfo
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView

/** Lightweight live enhancement bridge for the Phormi system IME. */
object PhormiKeyboardAiBridge {
    private const val TAG_AI_REACTION = 0x50484145
    private const val TAG_AI_ROW = 0x50484152
    private const val POLL_MS = 650L
    private const val AI_DEBOUNCE_MS = 1200L
    private val handler = Handler(Looper.getMainLooper())
    private var started = false
    private var lastText = ""
    private var lastSuggestions = emptyList<String>()
    private var lastAiText = ""
    private var lastAiFile: java.io.File? = null
    private var aiController: PhormiKeyboardAiEmojiController? = null
    private var aiPending: Runnable? = null

    fun start() {
        if (started) return
        started = true
        handler.post(object : Runnable {
            override fun run() {
                if (!started) return
                runCatching { update() }
                if (started) handler.postDelayed(this, POLL_MS)
            }
        })
    }

    fun start(@Suppress("UNUSED_PARAMETER") context: Context) = start()

    fun stop() {
        started = false
        lastText = ""
        lastSuggestions = emptyList()
        lastAiText = ""
        lastAiFile = null
        aiPending?.let(handler::removeCallbacks)
        aiPending = null
        aiController?.cancel()
        aiController = null
        handler.removeCallbacksAndMessages(null)
    }

    private fun update() {
        val service = currentService()
        if (service == null) {
            stop()
            return
        }
        val info = service.currentInputEditorInfo ?: return
        val inputClass = info.inputType and InputType.TYPE_MASK_CLASS
        if (inputClass != InputType.TYPE_CLASS_TEXT ||
            (info.inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0 ||
            PhormiKeyboardTextEngine.isPrivateEditor(info)
        ) return
        val text = service.currentInputConnection?.let { PhormiKeyboardTextEngine.contextBeforeCursor(it) }.orEmpty()
        applyAutoCaps(service)
        if (text != lastText) {
            lastText = text
            updateSuggestions(service, info, text)
            scheduleAiReaction(service, text)
        }
        lastAiFile?.let { installAiReaction(service, it) }
    }

    private fun updateSuggestions(service: PhormiKeyboardServiceV2, info: android.view.inputmethod.EditorInfo, text: String) {
        if (!PhormiKeyboardPreferences.suggestions(service)) return
        val locale = PhormiKeyboardTextEngine.localeFor(info)
        val suggestions = (PhormiEmojiSuggester.suggest(text) + PhormiLocalPredictionEngine.suggest(text, locale)).distinct().take(4)
        if (suggestions == lastSuggestions) return
        lastSuggestions = suggestions
        val field = findField(service.javaClass, "completions") ?: return
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val old = (field.get(service) as? List<CompletionInfo>).orEmpty().filterNot { it.id <= -9000L }
        field.set(service, (old + suggestions.mapIndexed { index, value -> CompletionInfo(-9000L - index, index, value) }).take(4))
        rerender(service)
    }

    private fun scheduleAiReaction(service: PhormiKeyboardServiceV2, text: String) {
        if (!PhormiKeyboardPreferences.aiEmoji(service) || text.trim().length < 3) return
        aiPending?.let(handler::removeCallbacks)
        val snapshot = text.trim().takeLast(240)
        aiPending = Runnable {
            if (!started) return@Runnable
            val current = currentService() ?: return@Runnable
            val info = current.currentInputEditorInfo ?: return@Runnable
            if (PhormiKeyboardTextEngine.isPrivateEditor(info) || !PhormiKeyboardPreferences.aiEmoji(current)) return@Runnable
            if (snapshot == lastAiText) return@Runnable
            lastAiText = snapshot
            if (aiController == null) {
                aiController = PhormiKeyboardAiEmojiController(current) { files, generating ->
                    if (started && !generating) {
                        currentService()?.let { active ->
                            lastAiFile = files.firstOrNull()
                            installAiReaction(active, lastAiFile)
                        }
                    }
                }
            }
            aiController?.generate(snapshot)
        }
        handler.postDelayed(aiPending!!, AI_DEBOUNCE_MS)
    }

    /** Put one generated reaction into the same bounded emoji grid area as normal emoji. */
    private fun installAiReaction(service: PhormiKeyboardServiceV2, file: java.io.File?) {
        if (file == null || !file.exists() || !PhormiKeyboardPreferences.aiEmoji(service)) return
        val root = service.getInputView() as? ViewGroup ?: return
        val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return
        val panel = findField(service.javaClass, "panel")?.let { field ->
            field.isAccessible = true
            field.get(service)?.toString()?.substringAfterLast('.')
        } ?: return
        if (panel != "EMOJI") return
        val scroll = (0 until root.childCount).map { root.getChildAt(it) }.filterIsInstance<ScrollView>().firstOrNull() ?: return
        val grid = scroll.getChildAt(0) as? LinearLayout ?: return

        for (i in grid.childCount - 1 downTo 0) {
            val child = grid.getChildAt(i)
            if (child.tag == TAG_AI_ROW || (child is LinearLayout && child.childCount == 3)) grid.removeViewAt(i)
        }

        val row = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            tag = TAG_AI_ROW
        }
        addReactionCell(service, row, bitmap, file)
        lastSuggestions.take(3).forEach { emoji ->
            val button = android.widget.Button(service).apply {
                text = emoji
                textSize = 20f
                setTextColor(Color.WHITE)
                minWidth = 0
                minHeight = 0
                isAllCaps = false
                stateListAnimator = null
                setPadding(2, 0, 2, 0)
                background = rounded(Color.rgb(39, 48, 64), 9)
                contentDescription = emoji
                setOnClickListener {
                    feedback(this)
                    PhormiKeyboardServiceV2.commitExternalText(service, emoji)
                }
            }
            row.addView(button, LinearLayout.LayoutParams(0, scaled(service, 50), 1f).apply { setMargins(2, 2, 2, 2) })
        }
        repeat((8 - row.childCount).coerceAtLeast(0)) {
            row.addView(View(service), LinearLayout.LayoutParams(0, scaled(service, 50), 1f))
        }
        grid.addView(row, 0, LinearLayout.LayoutParams(-1, scaled(service, 50)))
    }

    private fun addReactionCell(service: PhormiKeyboardServiceV2, row: LinearLayout, bitmap: android.graphics.Bitmap, file: java.io.File) {
        val image = ImageView(service).apply {
            tag = TAG_AI_REACTION
            setImageBitmap(bitmap)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(4, 2, 4, 2)
            contentDescription = "Phormi custom reaction"
            background = rounded(Color.rgb(39, 48, 64), 9)
            setOnClickListener {
                feedback(this)
                if (PhormiKeyboardServiceV2.commitPickedContent(service, PhormiKeyboardStickerStore.contentUri(service, file))) {
                    lastAiText = ""
                    lastAiFile = null
                }
            }
        }
        row.addView(image, LinearLayout.LayoutParams(0, scaled(service, 50), 1f).apply { setMargins(2, 2, 2, 2) })
    }

    private fun scaled(service: PhormiKeyboardServiceV2, dp: Int): Int = (dp * service.resources.displayMetrics.density * PhormiKeyboardPreferences.heightScale(service)).toInt().coerceAtLeast(1)
    private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply { setColor(color); cornerRadius = radiusDp * 1f }

    private fun applyAutoCaps(service: PhormiKeyboardServiceV2) {
        if (!PhormiKeyboardPreferences.autoCaps(service) || !PhormiKeyboardTextEngine.autoCapitalize(service.currentInputConnection, service.currentInputEditorInfo)) return
        val shift = findField(service.javaClass, "shift") ?: return
        val caps = findField(service.javaClass, "capsLock") ?: return
        shift.isAccessible = true; caps.isAccessible = true
        if (!(caps.get(service) as? Boolean ?: false) && !(shift.get(service) as? Boolean ?: false)) {
            shift.set(service, true)
            rerender(service)
        }
    }

    private fun feedback(view: View) {
        if (PhormiKeyboardPreferences.haptic(view.context)) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        if (PhormiKeyboardPreferences.sound(view.context)) view.playSoundEffect(SoundEffectConstants.CLICK)
    }

    private fun rerender(service: PhormiKeyboardServiceV2) = runCatching { service.setInputView(service.render()) }

    private fun currentService(): PhormiKeyboardServiceV2? {
        val outer = PhormiKeyboardServiceV2::class.java
        val companion = runCatching { outer.getDeclaredField("Companion").apply { isAccessible = true }.get(null) }.getOrNull() ?: return null
        val field = findField(companion.javaClass, "instance") ?: return null
        field.isAccessible = true
        return field.get(companion) as? PhormiKeyboardServiceV2
    }

    private fun findField(type: Class<*>, name: String) = generateSequence(type) { it.superclass }.flatMap { it.declaredFields.asSequence() }.firstOrNull { it.name == name }
}

object PhormiEmojiSuggester {
    private val rules = listOf(
        listOf("love", "heart", "crush") to listOf("❤️", "🥰", "😍", "😘"),
        listOf("happy", "great", "good", "awesome") to listOf("😊", "😄", "🥳", "✨"),
        listOf("sad", "sorry", "hurt", "cry") to listOf("😢", "😭", "🥺", "💔"),
        listOf("angry", "mad", "hate", "furious") to listOf("😡", "🤬", "💢", "🔥"),
        listOf("laugh", "funny", "joke", "lol") to listOf("😂", "🤣", "😆", "💀"),
        listOf("wow", "amazing", "shock", "surprise") to listOf("😮", "🤯", "😱", "✨"),
        listOf("cool", "style", "nice") to listOf("😎", "🔥", "💯", "✨"),
        listOf("tired", "sleep", "sleepy") to listOf("😴", "🥱", "😪", "🫠"),
        listOf("confused", "what", "why") to listOf("🤔", "😕", "🧐", "❓"),
        listOf("party", "birthday", "celebrate", "congrats") to listOf("🎉", "🥳", "🎂", "🎊")
    )
    fun suggest(text: String): List<String> {
        val lower = text.lowercase()
        return rules.firstOrNull { (words, _) -> words.any { lower.contains(it) } }?.second ?: when {
            lower.endsWith("!") -> listOf("😊", "😄", "🔥", "✨")
            lower.endsWith("?") -> listOf("🤔", "❓", "😅", "👀")
            else -> emptyList()
        }
    }
}

object PhormiLocalPredictionEngine {
    private val words = listOf("the", "and", "you", "your", "that", "this", "with", "have", "for", "are", "what", "when", "where", "why", "how", "can", "will", "would", "could", "should", "please", "thanks", "hello", "hey", "good", "great", "today", "tomorrow", "now", "later", "because", "about", "from", "just", "really", "very", "love", "like", "want", "need", "know", "think", "make", "going", "come", "home", "work", "friend", "family", "message", "send", "open", "close", "search", "download", "share", "favorite", "bookmark", "history", "keyboard", "browser", "testing", "test", "project")
    private val frenchWords = listOf("bonjour", "merci", "comment", "ça", "allez", "vas", "faire", "je", "suis", "vais", "peux", "veux", "pense", "aime", "nous", "sommes", "allons", "pouvons", "devons", "avons", "vous", "êtes", "pouvez", "avez", "voulez", "très", "bien", "heureux", "triste", "excité", "important", "bonne", "journée", "chance", "nuit", "soirée", "demain", "bientôt", "plus", "tard", "pour", "moi", "amour", "amis")
    fun suggest(text: String, locale: java.util.Locale = java.util.Locale.getDefault()): List<String> {
        val token = text.trimEnd().split(Regex("\\s+")).lastOrNull().orEmpty().lowercase(locale).filter { it.isLetter() }
        if (token.length < 2) return emptyList()
        val pool = if (locale.language == java.util.Locale.FRENCH.language) frenchWords else words
        return pool.filter { it.startsWith(token) && it != token }.take(4)
    }
}
