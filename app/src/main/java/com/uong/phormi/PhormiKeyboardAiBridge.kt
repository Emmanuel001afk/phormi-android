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
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout

/** Lightweight live enhancement bridge for the Phormi system IME. */
object PhormiKeyboardAiBridge {
    private const val TAG_AI_REACTION = 0x50484145
    private const val POLL_MS = 650L
    private const val AI_DEBOUNCE_MS = 1200L
    private val handler = Handler(Looper.getMainLooper())
    private var started = false
    private var lastText = ""
    private var lastSuggestions = emptyList<String>()
    private var lastAiText = ""
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
        aiPending?.let(handler::removeCallbacks)
        aiPending = null
        aiController?.cancel()
        aiController = null
        handler.removeCallbacksAndMessages(null)
    }

    private fun update() {
        val service = currentService()
        if (service == null) {
            // The IME may be torn down while the polling loop is alive. Stop immediately
            // instead of retaining a main-thread polling loop after the system IME dies.
            stop()
            return
        }
        val info = service.currentInputEditorInfo ?: return
        val inputClass = info.inputType and InputType.TYPE_MASK_CLASS
        if (inputClass != InputType.TYPE_CLASS_TEXT ||
            (info.inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0 ||
            PhormiKeyboardTextEngine.isPrivateEditor(info)
        ) return

        val text = service.currentInputConnection?.let {
            PhormiKeyboardTextEngine.contextBeforeCursor(it)
        }.orEmpty()
        applyAutoCaps(service)
        // Autocorrect is owned by V2's commit path. Never perform a second correction here.
        if (text != lastText) {
            lastText = text
            updateSuggestions(service, info, text)
            scheduleAiReaction(service, text)
        }
    }

    private fun updateSuggestions(
        service: PhormiKeyboardServiceV2,
        info: android.view.inputmethod.EditorInfo,
        text: String
    ) {
        if (!PhormiKeyboardPreferences.suggestions(service)) return
        val locale = PhormiKeyboardTextEngine.localeFor(info)
        val suggestions = (
            PhormiEmojiSuggester.suggest(text) +
                PhormiLocalPredictionEngine.suggest(text, locale)
            ).distinct().take(4)
        if (suggestions == lastSuggestions) return
        lastSuggestions = suggestions
        val completionField = findField(service.javaClass, "completions") ?: return
        completionField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val old = (completionField.get(service) as? List<CompletionInfo>)
            .orEmpty()
            .filterNot { it.id <= -9000L }
        completionField.set(
            service,
            (old + suggestions.mapIndexed { index, value ->
                CompletionInfo(-9000L - index, index, value)
            }).take(4)
        )
        rerender(service)
    }

    private fun scheduleAiReaction(service: PhormiKeyboardServiceV2, text: String) {
        if (!PhormiKeyboardPreferences.aiEmoji(service) || text.trim().length < 3) return
        aiPending?.let(handler::removeCallbacks)
        val snapshot = text.trim().takeLast(240)
        aiPending = Runnable {
            if (!started) return@Runnable
            val current = currentService() ?: return@Runnable
            val currentInfo = current.currentInputEditorInfo ?: return@Runnable
            if (PhormiKeyboardTextEngine.isPrivateEditor(currentInfo) ||
                !PhormiKeyboardPreferences.aiEmoji(current)
            ) return@Runnable
            if (snapshot == lastAiText) return@Runnable
            lastAiText = snapshot
            if (aiController == null) {
                aiController = PhormiKeyboardAiEmojiController(current) { files, generating ->
                    if (started && !generating) {
                        val active = currentService()
                        val file = files.firstOrNull()
                        if (active != null && file != null) installAiReaction(active, file)
                    }
                }
            }
            aiController?.generate(snapshot)
        }
        handler.postDelayed(aiPending!!, AI_DEBOUNCE_MS)
    }

    private fun installAiReaction(service: PhormiKeyboardServiceV2, file: java.io.File) {
        val root = service.getInputView() as? ViewGroup ?: return
        val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return
        val density = service.resources.displayMetrics.density
        val rail: HorizontalScrollView
        val row: LinearLayout
        val candidate = if (root.childCount > 2) root.getChildAt(2) else null
        if (candidate is HorizontalScrollView && candidate.childCount > 0 && candidate.getChildAt(0) is LinearLayout) {
            rail = candidate
            row = candidate.getChildAt(0) as LinearLayout
        } else {
            rail = HorizontalScrollView(service).apply {
                isHorizontalScrollBarEnabled = false
                tag = TAG_AI_REACTION
            }
            row = LinearLayout(service).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            rail.addView(row)
            root.addView(
                rail,
                minOf(2, root.childCount),
                LinearLayout.LayoutParams(-1, (36 * density).toInt().coerceAtLeast(1))
            )
        }
        for (i in row.childCount - 1 downTo 0) {
            if (row.getChildAt(i).getTag(TAG_AI_REACTION) == true) row.removeViewAt(i)
        }
        val image = ImageView(service).apply {
            tag = TAG_AI_REACTION
            setImageBitmap(bitmap)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(
                (4 * density).toInt(),
                (2 * density).toInt(),
                (4 * density).toInt(),
                (2 * density).toInt()
            )
            contentDescription = "Phormi custom reaction"
            background = GradientDrawable().apply {
                setColor(Color.rgb(31, 41, 55))
                cornerRadius = 12 * density
            }
            setOnClickListener {
                feedback(this)
                PhormiKeyboardServiceV2.commitPickedContent(
                    service,
                    PhormiKeyboardStickerStore.contentUri(service, file)
                )
            }
        }
        row.addView(
            image,
            0,
            LinearLayout.LayoutParams((42 * density).toInt(), (34 * density).toInt()).apply {
                setMargins((2 * density).toInt(), 0, (4 * density).toInt(), 0)
            }
        )
    }

    private fun applyAutoCaps(service: PhormiKeyboardServiceV2) {
        if (!PhormiKeyboardPreferences.autoCaps(service)) return
        if (!PhormiKeyboardTextEngine.autoCapitalize(
                service.currentInputConnection,
                service.currentInputEditorInfo
            )
        ) return
        val shift = findField(service.javaClass, "shift") ?: return
        val caps = findField(service.javaClass, "capsLock") ?: return
        shift.isAccessible = true
        caps.isAccessible = true
        if (!(caps.get(service) as? Boolean ?: false) && !(shift.get(service) as? Boolean ?: false)) {
            shift.set(service, true)
            rerender(service)
        }
    }

    private fun feedback(view: View, action: Int = MotionEvent.ACTION_DOWN) {
        if (action == MotionEvent.ACTION_DOWN) {
            if (PhormiKeyboardPreferences.haptic(view.context)) {
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            }
            if (PhormiKeyboardPreferences.sound(view.context)) {
                view.playSoundEffect(SoundEffectConstants.CLICK)
            }
        }
    }

    private fun rerender(service: PhormiKeyboardServiceV2) {
        runCatching { service.setInputView(service.render()) }
    }

    private fun currentService(): PhormiKeyboardServiceV2? {
        val outer = PhormiKeyboardServiceV2::class.java
        val companion = runCatching {
            outer.getDeclaredField("Companion").apply { isAccessible = true }.get(null)
        }.getOrNull() ?: return null
        val field = findField(companion.javaClass, "instance") ?: return null
        field.isAccessible = true
        return field.get(companion) as? PhormiKeyboardServiceV2
    }

    private fun findField(type: Class<*>, name: String) =
        generateSequence(type) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }
            .firstOrNull { it.name == name }
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
        return rules.firstOrNull { (words, _) -> words.any { lower.contains(it) } }?.second
            ?: when {
                lower.endsWith("!") -> listOf("😊", "😄", "🔥", "✨")
                lower.endsWith("?") -> listOf("🤔", "❓", "😅", "👀")
                else -> emptyList()
            }
    }
}

object PhormiLocalPredictionEngine {
    private val words = listOf(
        "the", "and", "you", "your", "that", "this", "with", "have", "for", "are", "what", "when", "where", "why", "how", "can", "will", "would", "could", "should", "please", "thanks", "hello", "hey", "good", "great", "today", "tomorrow", "now", "later", "because", "about", "from", "just", "really", "very", "love", "like", "want", "need", "know", "think", "make", "going", "come", "home", "work", "friend", "family", "message", "send", "open", "close", "search", "download", "share", "favorite", "bookmark", "history", "keyboard", "browser", "testing", "test", "project"
    )
    private val frenchWords = listOf(
        "bonjour", "merci", "comment", "ça", "allez", "vas", "faire", "je", "suis", "vais", "peux", "veux", "pense", "aime", "nous", "sommes", "allons", "pouvons", "devons", "avons", "vous", "êtes", "pouvez", "avez", "voulez", "très", "bien", "heureux", "triste", "excité", "important", "bonne", "journée", "chance", "nuit", "soirée", "demain", "bientôt", "plus", "tard", "pour", "moi", "amour", "amis"
    )
    fun suggest(text: String, locale: java.util.Locale = java.util.Locale.getDefault()): List<String> {
        val token = text.trimEnd().split(Regex("\\s+")).lastOrNull().orEmpty().lowercase(locale).filter { it.isLetter() }
        if (token.length < 2) return emptyList()
        val pool = if (locale.language == java.util.Locale.FRENCH.language) frenchWords else words
        return pool.filter { it.startsWith(token) && it != token }.take(4)
    }
}
