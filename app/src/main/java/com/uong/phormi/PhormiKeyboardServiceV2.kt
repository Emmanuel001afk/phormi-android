package com.uong.phormi

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.inputmethodservice.InputMethodService
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.CompletionInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputContentInfo
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.graphics.drawable.GradientDrawable
import java.io.File
import java.util.Locale

/**
 * Phormi's system-wide Android IME.
 *
 * This service is intentionally independent of the browser. The browser is just one
 * InputConnection client; the same IME can be selected and used in other Android apps.
 */
class PhormiKeyboardServiceV2 : InputMethodService() {
    companion object {
        private const val PREFS = "phormi_keyboard_pending"
        private const val KEY_PENDING_URI = "pending_uri"
        private var instance: PhormiKeyboardServiceV2? = null

        fun commitPickedContent(context: Context, uri: Uri): Boolean {
            val service = instance
            if (service != null && service.currentInputConnection != null) return service.commitContent(uri)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_PENDING_URI, uri.toString()).apply()
            return false
        }
    }

    private enum class Panel { KEYBOARD, EMOJI, CLIPBOARD, AI_EMOJI }

    private var panel = Panel.KEYBOARD
    private var symbols = false
    private var shift = false
    private var capsLock = false
    private var emojiCategory = 0
    private var editorInfo: EditorInfo? = null
    private var selectionStart = 0
    private var selectionEnd = 0
    private var completions: List<CompletionInfo> = emptyList()
    private var lastSuggestions = emptyList<String>()
    private var repeatRunnable: Runnable? = null
    private val repeatHandler = Handler(Looper.getMainLooper())
    private var spaceDownX = 0f
    private var spaceMoved = false
    private var aiPrompt = ""
    private val aiFiles = mutableListOf<File>()

    override fun onCreate() {
        super.onCreate()
        instance = this
        (getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.addPrimaryClipChangedListener {
            PhormiKeyboardClipboardStore.capturePrimaryClipboard(this)
        }
    }

    override fun onDestroy() {
        stopRepeat()
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onInitializeInterface() {
        super.onInitializeInterface()
        stopRepeat()
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        editorInfo = attribute
        selectionStart = 0
        selectionEnd = 0
        symbols = false
        shift = false
        capsLock = false
        panel = Panel.KEYBOARD
        completions = emptyList()
        lastSuggestions = emptyList()
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        editorInfo = info ?: editorInfo
        panel = Panel.KEYBOARD
        applyPendingContent()
        setInputView(render())
        refreshPredictions()
    }

    override fun onFinishInput() {
        stopRepeat()
        editorInfo = null
        completions = emptyList()
        lastSuggestions = emptyList()
        super.onFinishInput()
    }

    override fun onUnbindInput() {
        stopRepeat()
        editorInfo = null
        super.onUnbindInput()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        stopRepeat()
        super.onFinishInputView(finishingInput)
    }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        selectionStart = newSelStart
        selectionEnd = newSelEnd
        if (panel == Panel.KEYBOARD) refreshPredictions(false)
    }

    override fun onDisplayCompletions(completions: Array<out CompletionInfo>?) {
        super.onDisplayCompletions(completions)
        this.completions = completions?.filter { !it.text.isNullOrBlank() }?.take(5).orEmpty()
        refreshPredictions(false)
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && panel != Panel.KEYBOARD) {
            panel = Panel.KEYBOARD
            setInputView(render())
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onCreateInputView(): View = render()

    private fun render(): View = when (panel) {
        Panel.KEYBOARD -> buildKeyboard()
        Panel.EMOJI -> buildEmoji()
        Panel.CLIPBOARD -> buildClipboard()
        Panel.AI_EMOJI -> buildAiEmoji()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun root(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(6), dp(6), dp(6), dp(6))
        setBackgroundColor(Color.rgb(13, 18, 30))
        isFocusable = true
        isFocusableInTouchMode = true
    }

    private fun pill(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        textSize = 13f
        setTextColor(Color.rgb(229, 231, 235))
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        minWidth = 0
        minHeight = 0
        isAllCaps = false
        setPadding(dp(8), 0, dp(8), 0)
        background = rounded(Color.rgb(31, 41, 55), dp(14))
        contentDescription = label
        setOnClickListener { action() }
    }

    private fun keyButton(label: String, weight: Float = 1f, action: () -> Unit): Button = Button(this).apply {
        text = label
        textSize = if (label.length == 1) 20f else 13f
        setTextColor(Color.WHITE)
        minWidth = 0
        minHeight = 0
        isAllCaps = false
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        stateListAnimator = null
        setPadding(dp(2), 0, dp(2), 0)
        background = rounded(Color.rgb(39, 48, 64), dp(9))
        contentDescription = label
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(0, dp(48), weight).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) }
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.toFloat()
    }

    private fun toolbar(root: LinearLayout) {
        val scroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        fun add(label: String, action: () -> Unit) {
            row.addView(pill(label, action), LinearLayout.LayoutParams(dp(58), dp(40)).apply { setMargins(dp(2), 0, dp(2), 0) })
        }
        add("😀") { panel = Panel.EMOJI; setInputView(render()) }
        add("✨ AI") { prepareAiContext(); panel = Panel.AI_EMOJI; setInputView(render()) }
        add("📋") { PhormiKeyboardClipboardStore.capturePrimaryClipboard(this); panel = Panel.CLIPBOARD; setInputView(render()) }
        add("GIF") { openMedia("gif") }
        add("Sticker") { openMedia("sticker") }
        if (shouldOfferSwitchingToNextInputMethod()) add("🌐") { switchToNextInputMethod(false) }
        scroll.addView(row)
        root.addView(scroll, LinearLayout.LayoutParams(-1, dp(42)))

        val suggestionRow = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val suggestions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val values = if (completions.isNotEmpty()) completions.mapNotNull { it.text?.toString() } else lastSuggestions
        values.take(5).forEach { value ->
            suggestions.addView(pill(value) { acceptSuggestion(value) }, LinearLayout.LayoutParams(-2, dp(38)).apply { setMargins(dp(2), dp(1), dp(2), dp(1)) })
        }
        if (suggestions.childCount > 0) {
            suggestionRow.addView(suggestions)
            root.addView(suggestionRow, LinearLayout.LayoutParams(-1, dp(40)))
        }
    }

    private fun buildKeyboard(): View {
        val root = root()
        toolbar(root)
        val type = editorInfo?.inputType ?: InputType.TYPE_CLASS_TEXT
        val clazz = type and InputType.TYPE_MASK_CLASS
        val variation = type and InputType.TYPE_MASK_VARIATION
        val number = clazz == InputType.TYPE_CLASS_NUMBER
        val phone = clazz == InputType.TYPE_CLASS_PHONE
        val dateTime = clazz == InputType.TYPE_CLASS_DATETIME
        val email = variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS || variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
        val uri = variation == InputType.TYPE_TEXT_VARIATION_URI || variation == InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT

        when {
            phone -> addRows(root, listOf("1234567890", "*#+-", "()."))
            number -> addRows(root, listOf("1234567890", "789456123", "0.,+-"))
            dateTime -> addRows(root, listOf("1234567890", "1234567890", ":/-"))
            symbols -> addRows(root, listOf("1234567890", "-=[]\\;',./", "!@#\$%^&*()", "_+{}|:\"<>?"))
            else -> addRows(root, listOf("qwertyuiop", "asdfghjkl", "zxcvbnm"))
        }

        if (email || uri) {
            val contextRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
            val tokens = if (email) listOf("@", ".com", ".net", ".org") else listOf("/", ".com", "https://", ".org")
            tokens.forEach { token -> contextRow.addView(keyButton(token) { commitText(token); refreshPredictions() }) }
            root.addView(contextRow, LinearLayout.LayoutParams(-1, dp(52)))
        }

        val bottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        if (!number && !phone && !dateTime) {
            bottom.addView(keyButton(if (symbols) "ABC" else "123") { symbols = !symbols; setInputView(render()) })
            bottom.addView(keyButton(if (capsLock) "⇧" else "⇧") { toggleShift(); setInputView(render()) })
        }
        bottom.addView(keyButton("Sel") { selectAll() })
        bottom.addView(keyButton("Copy") { copySelection() })
        bottom.addView(keyButton("Paste") { pasteClipboard() })

        val space = keyButton("Space", if (number || phone) 2f else 2.9f) { commitSpace() }
        space.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { spaceDownX = event.x; spaceMoved = false }
                MotionEvent.ACTION_MOVE -> {
                    if (!spaceMoved && kotlin.math.abs(event.x - spaceDownX) > dp(30)) {
                        spaceMoved = true
                        moveCursor(if (event.x > spaceDownX) 1 else -1)
                    }
                }
                MotionEvent.ACTION_UP -> if (!spaceMoved) commitSpace()
                MotionEvent.ACTION_CANCEL -> spaceMoved = false
            }
            true
        }
        bottom.addView(space)
        bottom.addView(keyButton("←") { moveCursor(-1) })
        bottom.addView(keyButton("→") { moveCursor(1) })
        val backspace = keyButton("⌫") { deleteBackward() }
        installRepeat(backspace) { deleteBackward() }
        bottom.addView(backspace)
        bottom.addView(keyButton(actionLabel()) { sendEditorAction() })
        root.addView(bottom, LinearLayout.LayoutParams(-1, dp(56)))
        return root
    }

    private fun addRows(root: LinearLayout, rows: List<String>) {
        rows.forEach { chars ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
            chars.forEach { c ->
                val label = if (c.isLetter() && (shift || capsLock)) c.uppercaseChar().toString() else c.toString()
                row.addView(keyButton(label) {
                    val text = if (c.isLetter() && shouldCapitalize()) label else label
                    commitText(text)
                    if (shift && !capsLock) shift = false
                    refreshPredictions()
                    setInputView(render())
                })
            }
            root.addView(row, LinearLayout.LayoutParams(-1, dp(52)))
        }
    }

    private fun buildEmoji(): View {
        val root = root()
        val nav = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val categories = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        PhormiKeyboardEmoji.categories.keys.forEachIndexed { index, icon ->
            categories.addView(pill(icon) { emojiCategory = index; setInputView(render()) }, LinearLayout.LayoutParams(dp(52), dp(40)).apply { setMargins(dp(2), 0, dp(2), 0) })
        }
        categories.addView(pill("✨ AI") { panel = Panel.AI_EMOJI; prepareAiContext(); setInputView(render()) }, LinearLayout.LayoutParams(dp(70), dp(40)))
        categories.addView(pill("ABC") { panel = Panel.KEYBOARD; setInputView(render()) }, LinearLayout.LayoutParams(dp(58), dp(40)))
        nav.addView(categories)
        root.addView(nav, LinearLayout.LayoutParams(-1, dp(44)))

        val scroll = ScrollView(this)
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        PhormiKeyboardEmoji.categories.values.elementAtOrNull(emojiCategory).orEmpty().chunked(8).forEach { group ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            group.forEach { emoji ->
                row.addView(keyButton(emoji) { commitText(emoji) })
            }
            grid.addView(row, LinearLayout.LayoutParams(-1, dp(52)))
        }
        scroll.addView(grid)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        return root
    }

    private fun buildClipboard(): View {
        val root = root()
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(pill("← Keyboard") { panel = Panel.KEYBOARD; setInputView(render()) })
        header.addView(pill("Clear") { PhormiKeyboardClipboardStore.clearUnpinned(this); setInputView(render()) })
        root.addView(header, LinearLayout.LayoutParams(-1, dp(44)))
        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val items = PhormiKeyboardClipboardStore.list(this)
        if (items.isEmpty()) list.addView(TextView(this).apply { text = "Copy something to build your Phormi clipboard history."; setTextColor(Color.LTGRAY); setPadding(dp(12), dp(20), dp(12), dp(20)) })
        items.forEach { item ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; background = rounded(Color.rgb(27, 35, 49), dp(10)); setPadding(dp(8), dp(4), dp(8), dp(4)) }
            val preview = TextView(this).apply { text = item.text; setTextColor(Color.WHITE); textSize = 13f; maxLines = 3 }
            row.addView(preview, LinearLayout.LayoutParams(0, dp(58), 1f))
            row.addView(pill(if (item.pinned) "📌" else "○") { PhormiKeyboardClipboardStore.togglePinned(this@PhormiKeyboardServiceV2, item.text); setInputView(render()) }, LinearLayout.LayoutParams(dp(52), dp(44)))
            row.setOnClickListener { commitText(item.text) }
            row.setOnLongClickListener { PhormiKeyboardClipboardStore.remove(this, item.text); setInputView(render()); true }
            list.addView(row, LinearLayout.LayoutParams(-1, dp(66)).apply { setMargins(0, dp(3), 0, dp(3)) })
        }
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        return root
    }

    private fun buildAiEmoji(): View {
        val root = root()
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(pill("← Keyboard") { panel = Panel.KEYBOARD; setInputView(render()) })
        header.addView(TextView(this).apply { text = "Phormi AI Emoji"; setTextColor(Color.WHITE); textSize = 17f; typeface = Typeface.DEFAULT_BOLD; setPadding(dp(8), 0, 0, 0) })
        root.addView(header, LinearLayout.LayoutParams(-1, dp(46)))

        val contextText = PhormiKeyboardTextEngine.contextBeforeCursor(currentInputConnection)
        val moods = PhormiKeyboardAiContext.suggestions(contextText)
        val moodRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        moods.forEach { (emoji, label) -> moodRow.addView(pill("$emoji $label") { commitText(emoji) }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { setMargins(dp(2), 0, dp(2), 0) }) }
        root.addView(moodRow, LinearLayout.LayoutParams(-1, dp(46)))

        val prompt = TextView(this).apply {
            text = if (aiPrompt.isBlank()) "Describe an emoji/sticker to create locally, or use the mood shortcuts above." else "Prompt: $aiPrompt"
            setTextColor(Color.rgb(203, 213, 225)); textSize = 13f; setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        root.addView(prompt, LinearLayout.LayoutParams(-1, dp(52)))
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        actions.addView(pill("Generate") { generateAiEmoji() }, LinearLayout.LayoutParams(0, dp(44), 1f))
        actions.addView(pill("Use text mood") { aiPrompt = PhormiKeyboardTextEngine.contextBeforeCursor(currentInputConnection).takeLast(140); generateAiEmoji() }, LinearLayout.LayoutParams(0, dp(44), 1f))
        root.addView(actions, LinearLayout.LayoutParams(-1, dp(48)))

        val scroll = ScrollView(this)
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        aiFiles.chunked(2).forEach { pair ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEach { file ->
                val image = ImageView(this).apply {
                    setImageBitmap(android.graphics.BitmapFactory.decodeFile(file.absolutePath))
                    contentDescription = "Phormi generated emoji"
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    setPadding(dp(6), dp(6), dp(6), dp(6))
                    setOnClickListener { commitContent(PhormiKeyboardStickerStore.contentUri(this@PhormiKeyboardServiceV2, file)) }
                }
                row.addView(image, LinearLayout.LayoutParams(0, dp(180), 1f))
            }
            grid.addView(row)
        }
        scroll.addView(grid)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        return root
    }

    private fun prepareAiContext() {
        val context = PhormiKeyboardTextEngine.contextBeforeCursor(currentInputConnection)
        aiPrompt = context.takeLast(140)
    }

    private fun generateAiEmoji() {
        val prompt = aiPrompt.ifBlank { PhormiKeyboardTextEngine.contextBeforeCursor(currentInputConnection).takeLast(140) }.ifBlank { "happy expressive emoji" }
        aiPrompt = prompt
        aiFiles.clear()
        repeat(4) { index -> runCatching { aiFiles += PhormiAiEmojiEngine.generate(this, prompt, index) } }
        setInputView(render())
    }

    private fun acceptSuggestion(value: String) {
        val ic = currentInputConnection ?: return
        val word = PhormiKeyboardTextEngine.currentWord(ic)
        if (word.isBlank()) return commitText(value)
        runCatching {
            ic.beginBatchEdit()
            ic.deleteSurroundingText(word.length, 0)
            ic.commitText(matchCase(value, word) + " ", 1)
        }.also { runCatching { ic.endBatchEdit() } }
        PhormiKeyboardTextEngine.learn(this, value)
        refreshPredictions()
    }

    private fun matchCase(value: String, source: String): String = when {
        source.all { !it.isLetter() || it.isUpperCase() } -> value.uppercase(Locale.US)
        source.firstOrNull()?.isUpperCase() == true -> value.replaceFirstChar { it.titlecase(Locale.US) }
        else -> value
    }

    private fun toggleShift() {
        if (capsLock) { capsLock = false; shift = false }
        else if (shift) { capsLock = true; shift = false }
        else shift = true
    }

    private fun shouldCapitalize(): Boolean = shift || capsLock || PhormiKeyboardTextEngine.autoCapitalize(currentInputConnection)

    private fun refreshPredictions(redraw: Boolean = true) {
        if (!PhormiKeyboardTextEngine.shouldUsePredictions(editorInfo)) {
            lastSuggestions = emptyList()
        } else {
            val prefix = PhormiKeyboardTextEngine.currentWord(currentInputConnection)
            lastSuggestions = PhormiKeyboardTextEngine.suggestions(this, prefix)
        }
        if (redraw && panel == Panel.KEYBOARD && getInputView() != null) setInputView(render())
    }

    private fun commitText(text: String) {
        val ic = currentInputConnection ?: return
        runCatching {
            if ((editorInfo?.inputType ?: 0) == InputType.TYPE_NULL) {
                text.forEach { char ->
                    ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.keyCodeFromString("KEYCODE_${char.uppercaseChar()}")))
                    ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.keyCodeFromString("KEYCODE_${char.uppercaseChar()}")))
                }
            } else ic.commitText(text, 1)
        }
    }

    private fun commitSpace() {
        val ic = currentInputConnection ?: return
        val word = PhormiKeyboardTextEngine.currentWord(ic)
        runCatching {
            ic.beginBatchEdit()
            if (PhormiKeyboardTextEngine.shouldUsePredictions(editorInfo) && word.isNotBlank()) {
                PhormiKeyboardTextEngine.correctionFor(word.lowercase(Locale.US))?.let { correction ->
                    ic.deleteSurroundingText(word.length, 0)
                    ic.commitText(matchCase(correction, word), 1)
                }
                PhormiKeyboardTextEngine.learn(this, word)
            }
            val before = ic.getTextBeforeCursor(2, 0)?.toString().orEmpty()
            if (before.endsWith("  ")) {
                ic.deleteSurroundingText(2, 0)
                ic.commitText(". ", 1)
            } else ic.commitText(" ", 1)
        }.also { runCatching { ic.endBatchEdit() } }
        refreshPredictions()
    }

    private fun deleteBackward() {
        val ic = currentInputConnection ?: return
        runCatching {
            ic.beginBatchEdit()
            if (!ic.getSelectedText(0).isNullOrEmpty()) {
                ic.commitText("", 1)
                return@runCatching
            }
            val before = ic.getTextBeforeCursor(128, 0)?.toString().orEmpty()
            if (before.isEmpty()) return@runCatching
            val boundary = android.icu.text.BreakIterator.getCharacterInstance().apply { setText(before) }.preceding(before.length)
            val count = if (boundary >= 0) before.length - boundary else 1
            if (!ic.deleteSurroundingText(count, 0)) ic.deleteSurroundingTextInCodePoints(1, 0)
        }.also { runCatching { ic.endBatchEdit() } }
        refreshPredictions()
    }

    private fun installRepeat(view: View, action: () -> Unit) {
        view.setOnLongClickListener {
            stopRepeat()
            val runnable = object : Runnable {
                override fun run() {
                    if (currentInputConnection == null) return
                    action()
                    repeatHandler.postDelayed(this, 55L)
                }
            }
            repeatRunnable = runnable
            repeatHandler.postDelayed(runnable, 280L)
            true
        }
        view.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) stopRepeat()
            false
        }
    }

    private fun stopRepeat() {
        repeatRunnable?.let(repeatHandler::removeCallbacks)
        repeatRunnable = null
    }

    private fun moveCursor(delta: Int) {
        val ic = currentInputConnection ?: return
        runCatching {
            if (selectionStart != selectionEnd) {
                val collapse = if (delta < 0) minOf(selectionStart, selectionEnd) else maxOf(selectionStart, selectionEnd)
                ic.setSelection(collapse, collapse)
                selectionStart = collapse; selectionEnd = collapse
                return@runCatching
            }
            val before = ic.getTextBeforeCursor(256, 0)?.toString().orEmpty()
            val after = ic.getTextAfterCursor(256, 0)?.toString().orEmpty()
            val current = selectionStart.coerceAtLeast(before.length - 1).coerceAtLeast(0)
            val target = if (delta < 0) {
                val iterator = android.icu.text.BreakIterator.getCharacterInstance().apply { setText(before) }
                iterator.preceding(before.length).let { boundary -> (selectionStart - (before.length - boundary)).coerceAtLeast(0) }
            } else {
                val iterator = android.icu.text.BreakIterator.getCharacterInstance().apply { setText(after) }
                val next = iterator.following(0).let { if (it < 0) after.length else it }
                (selectionStart + next).coerceAtMost(selectionStart + after.length)
            }
            if (!ic.setSelection(target, target)) {
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, if (delta < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT))
            }
            selectionStart = target; selectionEnd = target
        }
    }

    private fun selectAll() = currentInputConnection?.performContextMenuAction(android.R.id.selectAll)
    private fun copySelection() = currentInputConnection?.performContextMenuAction(android.R.id.copy)
    private fun pasteClipboard() = currentInputConnection?.performContextMenuAction(android.R.id.paste)

    private fun deleteWordBackward() {
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(256, 0)?.toString().orEmpty()
        if (before.isBlank()) return
        val end = before.length
        var start = end
        while (start > 0 && before[start - 1].isWhitespace()) start--
        while (start > 0 && !before[start - 1].isWhitespace()) start--
        ic.deleteSurroundingText(end - start, 0)
    }

    private fun actionLabel(): String {
        val action = (editorInfo?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
        return when (action) {
            EditorInfo.IME_ACTION_GO -> "Go"
            EditorInfo.IME_ACTION_SEARCH -> "Search"
            EditorInfo.IME_ACTION_NEXT -> "Next"
            EditorInfo.IME_ACTION_DONE -> "Done"
            EditorInfo.IME_ACTION_SEND -> "Send"
            else -> "↵"
        }
    }

    private fun sendEditorAction() {
        val ic = currentInputConnection ?: return
        val action = (editorInfo?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
        runCatching {
            if (action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
                if (!ic.performEditorAction(action)) sendEnter(ic)
            } else sendEnter(ic)
        }
    }

    private fun sendEnter(ic: InputConnection) {
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
    }

    private fun openMedia(mode: String) {
        startActivity(Intent(this, PhormiKeyboardMediaActivity::class.java).putExtra(PhormiKeyboardMediaActivity.EXTRA_MODE, mode).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun commitContent(uri: Uri): Boolean {
        val ic = currentInputConnection ?: return false
        if (android.os.Build.VERSION.SDK_INT < 25) return false
        val requested = editorInfo?.contentMimeTypes?.toList().orEmpty()
        val mime = when {
            requested.any { it == "image/gif" } -> "image/gif"
            requested.any { it.startsWith("image/") } -> "image/png"
            requested.any { it.startsWith("video/") } -> "video/mp4"
            else -> "image/*"
        }
        if (requested.isEmpty() || requested.any { ClipDescription.compareMimeTypes(mime, it) }) {
            val content = InputContentInfo(uri, ClipDescription("Phormi media", arrayOf(mime)), null)
            val flags = InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION
            if (runCatching { ic.commitContent(content, flags, Bundle()) }.getOrDefault(false)) return true
        }
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(android.content.ClipData.newRawUri("Phormi media", uri))
        Toast.makeText(this, "This field does not accept media directly; it is ready to paste.", Toast.LENGTH_LONG).show()
        return false
    }

    private fun applyPendingContent() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val raw = prefs.getString(KEY_PENDING_URI, null) ?: return
        prefs.edit().remove(KEY_PENDING_URI).apply()
        runCatching { commitContent(Uri.parse(raw)) }
    }
}
