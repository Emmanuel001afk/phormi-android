package com.uong.phormi

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.inputmethodservice.InputMethodService
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
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
import kotlin.math.roundToInt

/** Phormi's real Android system IME. */
class PhormiKeyboardServiceV2 : InputMethodService() {
    companion object {
        private const val PREFS = "phormi_keyboard_pending"
        private const val KEY_PENDING_URI = "pending_uri"
        private const val KEY_PENDING_TEXT = "pending_text"
        private var instance: PhormiKeyboardServiceV2? = null
        fun commitPickedContent(context: Context, uri: Uri): Boolean {
            val service = instance
            if (service?.currentInputConnection != null) return service.commitContentToEditor(uri)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_PENDING_URI, uri.toString()).apply()
            return false
        }
        fun commitExternalText(context: Context, text: String): Boolean {
            if (text.isBlank()) return false
            val service = instance
            if (service?.currentInputConnection != null) return service.commitTextToEditor(text)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_PENDING_TEXT, text).apply()
            return false
        }
    }

    private enum class Panel { KEYBOARD, EMOJI, CLIPBOARD, AI_EMOJI, TOOLS, MEDIA }
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
    private var aiFiles: List<File> = emptyList()
    private var aiGenerating = false
    private var speechRecognizer: SpeechRecognizer? = null
    private var listening = false
    private var clipboardListener: ClipboardManager.OnPrimaryClipChangedListener? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboardListener = ClipboardManager.OnPrimaryClipChangedListener { PhormiKeyboardClipboardStore.capturePrimaryClipboard(this) }
        clipboardListener?.let { cm?.addPrimaryClipChangedListener(it) }
    }

    override fun onDestroy() {
        PhormiKeyboardAiBridge.stop()
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboardListener?.let { cm?.removePrimaryClipChangedListener(it) }
        clipboardListener = null
        stopRepeat(); stopVoice()
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        editorInfo = attribute
        selectionStart = 0; selectionEnd = 0
        symbols = false; shift = false; capsLock = false
        panel = Panel.KEYBOARD; completions = emptyList(); lastSuggestions = emptyList()
        aiFiles = emptyList(); aiGenerating = false; stopVoice()
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        editorInfo = info ?: editorInfo
        panel = Panel.KEYBOARD
        PhormiKeyboardAiBridge.start()
        setInputView(render())
        applyPendingInput()
        refreshPredictions()
    }

    override fun onFinishInput() { PhormiKeyboardAiBridge.stop(); stopRepeat(); stopVoice(); editorInfo = null; completions = emptyList(); lastSuggestions = emptyList(); super.onFinishInput() }
    override fun onUnbindInput() { PhormiKeyboardAiBridge.stop(); stopRepeat(); stopVoice(); editorInfo = null; super.onUnbindInput() }
    override fun onFinishInputView(finishingInput: Boolean) { stopRepeat(); super.onFinishInputView(finishingInput) }
    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) { super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd); selectionStart = newSelStart; selectionEnd = newSelEnd; if (panel == Panel.KEYBOARD) refreshPredictions(false) }
    override fun onDisplayCompletions(values: Array<out CompletionInfo>?) { super.onDisplayCompletions(values); completions = values?.filter { !it.text.isNullOrBlank() }?.take(5).orEmpty(); refreshPredictions(false) }
    override fun onEvaluateFullscreenMode() = false
    override fun onCreateInputView(): View = render()

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && panel != Panel.KEYBOARD) { panel = Panel.KEYBOARD; setInputView(render()); return true }
        return super.onKeyDown(keyCode, event)
    }

    private fun scale(value: Int): Int = (value * PhormiKeyboardPreferences.heightScale(this)).roundToInt().coerceAtLeast(1)
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt().coerceAtLeast(1)
    private fun root() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(6), dp(2), dp(6), dp(2)); setBackgroundColor(Color.rgb(13, 18, 30)); isFocusable = true; addResizeGrip(this) }
    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = radius.toFloat() }
    private fun feedback(view: View) { if (PhormiKeyboardPreferences.haptic(this)) view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP); if (PhormiKeyboardPreferences.sound(this)) view.playSoundEffect(android.view.SoundEffectConstants.CLICK) }
    private fun pill(label: String, action: () -> Unit) = Button(this).apply { text = label; textSize = 13f; setTextColor(Color.rgb(229, 231, 235)); typeface = Typeface.DEFAULT_BOLD; minWidth = 0; minHeight = 0; isAllCaps = false; stateListAnimator = null; setPadding(dp(7), 0, dp(7), 0); background = rounded(Color.rgb(31, 41, 55), dp(13)); contentDescription = label; setOnClickListener { feedback(this); action() } }
    private fun keyButton(label: String, weight: Float = 1f, action: () -> Unit) = Button(this).apply { text = label; textSize = if (label.length == 1) 20f else 12f; setTextColor(Color.WHITE); minWidth = 0; minHeight = 0; isAllCaps = false; typeface = Typeface.DEFAULT; stateListAnimator = null; setPadding(dp(2), 0, dp(2), 0); background = rounded(Color.rgb(39, 48, 64), dp(9)); contentDescription = label; setOnClickListener { feedback(this); action() }; layoutParams = LinearLayout.LayoutParams(0, scale(46), weight).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) } }

    /** Small persistent grip: drag vertically and release to save a new keyboard height. */
    private fun addResizeGrip(root: LinearLayout) {
        val grip = TextView(this).apply {
            text = "⋮  Phormi Keyboard  ⋮"
            textSize = 9f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(100, 116, 139))
            contentDescription = "Drag up or down to resize Phormi Keyboard"
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { resizeStartY = event.rawY; resizeStartLevel = PhormiKeyboardPreferences.height(this@PhormiKeyboardServiceV2); true }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        val delta = ((resizeStartY - event.rawY) / dp(38f)).roundToInt()
                        if (delta != 0) {
                            PhormiKeyboardPreferences.setHeight(this@PhormiKeyboardServiceV2, resizeStartLevel + delta)
                            setInputView(render())
                        }
                        true
                    }
                    else -> true
                }
            }
        }
        root.addView(grip, 0, LinearLayout.LayoutParams(-1, dp(14)))
    }
    private var resizeStartY = 0f
    private var resizeStartLevel = 3
    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private fun toolbar(root: LinearLayout) {
        val scroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        fun add(label: String, width: Int = 58, action: () -> Unit) { row.addView(pill(label, action), LinearLayout.LayoutParams(dp(width), scale(36)).apply { setMargins(dp(2), 0, dp(2), 0) }) }
        add("😀") { panel = Panel.EMOJI; prepareAiContext(); setInputView(render()) }
        if (!isPrivateEditor() && PhormiKeyboardPreferences.aiEmoji(this)) add("✨", 52) { prepareAiContext(); panel = Panel.AI_EMOJI; setInputView(render()) }
        add("📋") { PhormiKeyboardClipboardStore.capturePrimaryClipboard(this); panel = Panel.CLIPBOARD; setInputView(render()) }
        add("▦ Tools", 70) { panel = Panel.TOOLS; setInputView(render()) }
        add("GIF", 52) { panel = Panel.MEDIA; setInputView(render()) }
        add("Sticker", 68) { panel = Panel.MEDIA; setInputView(render()) }
        if (shouldOfferSwitchingToNextInputMethod()) add("🌐", 52) { runCatching { switchToNextInputMethod(false) } }
        scroll.addView(row); root.addView(scroll, LinearLayout.LayoutParams(-1, scale(40)))
        if (PhormiKeyboardTextEngine.shouldUsePredictions(editorInfo) && PhormiKeyboardPreferences.suggestions(this)) {
            val prefix = PhormiKeyboardTextEngine.currentWord(currentInputConnection)
            val values = if (prefix.isBlank()) PhormiKeyboardTextEngine.nextWordSuggestions(this, PhormiKeyboardTextEngine.previousWord(currentInputConnection)) else (if (completions.isNotEmpty()) completions.mapNotNull { it.text?.toString() } else lastSuggestions)
            if (values.isNotEmpty()) {
                val s = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
                val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                values.distinct().take(4).forEach { v -> r.addView(pill(v) { acceptSuggestion(v) }, LinearLayout.LayoutParams(-2, scale(34)).apply { setMargins(dp(2), dp(1), dp(2), dp(1)) }) }
                s.addView(r); root.addView(s, LinearLayout.LayoutParams(-1, scale(36)))
            }
        }
    }

    private fun buildKeyboard(): View {
        val root = root(); val type = editorInfo?.inputType ?: InputType.TYPE_CLASS_TEXT; val clazz = type and InputType.TYPE_MASK_CLASS; val variation = type and InputType.TYPE_MASK_VARIATION
        val number = clazz == InputType.TYPE_CLASS_NUMBER; val phone = clazz == InputType.TYPE_CLASS_PHONE; val dateTime = clazz == InputType.TYPE_CLASS_DATETIME; val email = variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS; val uri = variation == InputType.TYPE_TEXT_VARIATION_URI
        shift = shouldCapitalize(); toolbar(root)
        when { phone -> addRows(root, listOf("1234567890", "*#+-", "().")); number -> addRows(root, listOf("1234567890", "789456123", "0.,+-")); dateTime -> addRows(root, listOf("1234567890", "4567891230", ":/-")); symbols -> addRows(root, listOf("1234567890", "-=[]\\;',./", "!@#\$%^&*()", "_+{}|:\"<>?")); else -> addRows(root, listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")) }
        if (email || uri) { val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }; (if (email) listOf("@", ".com", ".net", ".org") else listOf("/", ".com", "https://", ".org")).forEach { token -> r.addView(keyButton(token) { commitTextToEditor(token); refreshPredictions() }) }; root.addView(r, LinearLayout.LayoutParams(-1, scale(50))) }
        val bottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        if (!number && !phone && !dateTime) { bottom.addView(keyButton(if (symbols) "ABC" else "123") { symbols = !symbols; setInputView(render()) }); bottom.addView(keyButton(if (capsLock) "⇧·" else "⇧") { toggleShift(); setInputView(render()) }) }
        bottom.addView(keyButton("Sel") { selectAll() }); bottom.addView(keyButton("Copy") { copySelection() }); bottom.addView(keyButton("Paste") { pasteClipboard() })
        val space = keyButton("Space", if (number || phone) 2f else 2.5f) { commitSpace() }
        space.setOnTouchListener { _, e -> when (e.actionMasked) { MotionEvent.ACTION_DOWN -> { spaceDownX = e.x; spaceMoved = false }; MotionEvent.ACTION_MOVE -> if (!spaceMoved && kotlin.math.abs(e.x - spaceDownX) > dp(28)) { spaceMoved = true; moveCursor(if (e.x > spaceDownX) 1 else -1) }; MotionEvent.ACTION_UP -> if (!spaceMoved) commitSpace(); MotionEvent.ACTION_CANCEL -> spaceMoved = false }; true }
        bottom.addView(space); bottom.addView(keyButton("←") { moveCursor(-1) }); bottom.addView(keyButton("→") { moveCursor(1) }); val back = keyButton("⌫") { deleteBackward() }; installRepeat(back) { deleteBackward() }; bottom.addView(back); bottom.addView(keyButton(actionLabel()) { sendEditorAction() }); if (!number && !phone && !dateTime) bottom.addView(keyButton(if (listening) "■" else "🎙") { if (listening) stopVoice() else startVoice(); setInputView(render()) })
        root.addView(bottom, LinearLayout.LayoutParams(-1, scale(54))); return root
    }

    private fun addRows(root: LinearLayout, rows: List<String>) { rows.forEach { chars -> val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }; chars.forEach { c -> val label = if (c.isLetter() && (shift || capsLock)) c.uppercaseChar().toString() else c.toString(); row.addView(keyButton(label) { commitTextToEditor(label); if (shift && !capsLock) shift = false; refreshPredictions(); setInputView(render()) }) }; root.addView(row, LinearLayout.LayoutParams(-1, scale(50))) } }

    private fun buildEmoji(): View {
        val root = root(); val nav = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }; val categories = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        PhormiKeyboardEmoji.categories.keys.forEachIndexed { index, icon -> categories.addView(pill(icon) { emojiCategory = index; setInputView(render()) }, LinearLayout.LayoutParams(dp(48), scale(36)).apply { setMargins(dp(2), 0, dp(2), 0) }) }
        if (!isPrivateEditor() && PhormiKeyboardPreferences.aiEmoji(this)) categories.addView(pill("✨") { prepareAiContext(); panel = Panel.AI_EMOJI; setInputView(render()) }, LinearLayout.LayoutParams(dp(48), scale(36)))
        categories.addView(pill("ABC") { panel = Panel.KEYBOARD; setInputView(render()) }, LinearLayout.LayoutParams(dp(58), scale(36))); nav.addView(categories); root.addView(nav, LinearLayout.LayoutParams(-1, scale(40)))
        val scroll = ScrollView(this).apply { isFillViewport = true }; val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (!isPrivateEditor()) {
            val moods = PhormiKeyboardAiContext.suggestions(PhormiKeyboardTextEngine.contextBeforeCursor(currentInputConnection))
            if (moods.isNotEmpty()) { val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }; moods.take(3).forEach { emoji -> r.addView(keyButton(emoji) { commitTextToEditor(emoji) }) }; grid.addView(r) }
        }
        val values = PhormiKeyboardEmoji.categories.values.elementAtOrNull(emojiCategory).orEmpty()
        values.chunked(8).forEach { chunk -> val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }; chunk.forEach { emoji -> r.addView(keyButton(emoji) { commitTextToEditor(emoji) }, LinearLayout.LayoutParams(0, scale(48), 1f).apply { setMargins(dp(1), dp(1), dp(1), dp(1)) }) }; grid.addView(r) }
        scroll.addView(grid); root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f)); return root
    }

    private fun buildClipboard(): View {
        val root = root(); root.addView(pill("⌨ Keyboard") { panel = Panel.KEYBOARD; setInputView(render()) }, LinearLayout.LayoutParams(-1, scale(38)))
        val scroll = ScrollView(this); val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        PhormiKeyboardClipboardStore.items(this).forEach { item -> list.addView(pill(item) { commitTextToEditor(item) }, LinearLayout.LayoutParams(-1, scale(44)).apply { setMargins(0, dp(2), 0, dp(2)) }) }
        scroll.addView(list); root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f)); return root
    }

    private fun buildTools(): View {
        val root = root(); root.addView(pill("⌨ Keyboard") { panel = Panel.KEYBOARD; setInputView(render()) }, LinearLayout.LayoutParams(-1, scale(38)))
        val scroll = ScrollView(this); val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        list.addView(pill("⚙ Settings") { startActivity(Intent(this, AiActivity::class.java)) }, LinearLayout.LayoutParams(-1, scale(46)))
        list.addView(pill(if (PhormiKeyboardPreferences.aiEmoji(this)) "✨ AI Emoji: On" else "✨ AI Emoji: Off") { PhormiKeyboardPreferences.set(this, PhormiKeyboardPreferences.KEY_AI_EMOJI, !PhormiKeyboardPreferences.aiEmoji(this)); setInputView(render()) }, LinearLayout.LayoutParams(-1, scale(46)))
        list.addView(pill("↕ Resize: drag the top grip") {}, LinearLayout.LayoutParams(-1, scale(46)))
        list.addView(pill("😀 Emoji") { panel = Panel.EMOJI; setInputView(render()) }, LinearLayout.LayoutParams(-1, scale(46)))
        list.addView(pill("📋 Clipboard") { panel = Panel.CLIPBOARD; setInputView(render()) }, LinearLayout.LayoutParams(-1, scale(46)))
        list.addView(pill("🎙 Voice") { startVoice() }, LinearLayout.LayoutParams(-1, scale(46)))
        scroll.addView(list); root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f)); return root
    }

    private fun buildMedia(): View {
        val root = root(); root.addView(pill("⌨ Keyboard") { panel = Panel.KEYBOARD; setInputView(render()) }, LinearLayout.LayoutParams(-1, scale(38)))
        val scroll = ScrollView(this); val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        list.addView(pill("＋ Import GIF / Image") { startActivity(Intent(this, PhormiKeyboardMediaActivity::class.java)) }, LinearLayout.LayoutParams(-1, scale(46)))
        list.addView(pill("＋ Import Sticker") { startActivity(Intent(this, PhormiKeyboardMediaActivity::class.java)) }, LinearLayout.LayoutParams(-1, scale(46)))
        aiFiles = PhormiPollinationsAiEmojiEngine.listGenerated(this)
        aiFiles.forEach { file -> val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }; val image = ImageView(this).apply { setImageURI(Uri.fromFile(file)); adjustViewBounds = true; layoutParams = LinearLayout.LayoutParams(scale(54), scale(54)).apply { setMargins(dp(4), dp(4), dp(8), dp(4)) } }; row.addView(image); row.addView(pill("Insert") { commitContentToEditor(Uri.fromFile(file)); }); list.addView(row) }
        scroll.addView(list); root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f)); return root
    }

    private fun buildAiEmoji(): View {
        val root = root(); root.addView(pill("← Emoji") { panel = Panel.EMOJI; setInputView(render()) }, LinearLayout.LayoutParams(-1, scale(38)))
        val context = PhormiKeyboardTextEngine.contextBeforeCursor(currentInputConnection)
        val rail = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        val moods = PhormiEmojiSuggester.suggest(context).distinct().take(8)
        moods.forEach { emoji -> rail.addView(keyButton(emoji) { commitTextToEditor(emoji) }, LinearLayout.LayoutParams(-1, scale(50))) }
        val generate = pill(if (aiGenerating) "Generating…" else "✨ Generate reaction") { if (!aiGenerating) generateAiEmoji(context) }
        rail.addView(generate, LinearLayout.LayoutParams(-1, scale(44)).apply { setMargins(0, dp(4), 0, dp(4)) })
        root.addView(rail, LinearLayout.LayoutParams(-1, 0, 1f)); return root
    }

    private fun render(): View = when (panel) { Panel.KEYBOARD -> buildKeyboard(); Panel.EMOJI -> buildEmoji(); Panel.CLIPBOARD -> buildClipboard(); Panel.AI_EMOJI -> buildAiEmoji(); Panel.TOOLS -> buildTools(); Panel.MEDIA -> buildMedia() }

    private fun isPrivateEditor(): Boolean = PhormiKeyboardTextEngine.isPrivateEditor(editorInfo)
    private fun prepareAiContext() { PhormiKeyboardAiBridge.update() }
    private fun applyPendingInput() { val p = getSharedPreferences(PREFS, Context.MODE_PRIVATE); p.getString(KEY_PENDING_TEXT, null)?.let { commitTextToEditor(it); p.edit().remove(KEY_PENDING_TEXT).apply() }; p.getString(KEY_PENDING_URI, null)?.let { commitContentToEditor(Uri.parse(it)); p.edit().remove(KEY_PENDING_URI).apply() } }
    private fun commitTextToEditor(text: String) { currentInputConnection?.commitText(text, 1) }
    private fun commitContentToEditor(uri: Uri): Boolean {
        val ic = currentInputConnection ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val description = contentResolver.getType(uri)?.let { ClipDescription("Phormi media", arrayOf(it)) } ?: ClipDescription("Phormi media", arrayOf("image/*"))
            return runCatching { ic.commitContent(InputContentInfo(uri, description, null), InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, Bundle()) }.getOrDefault(false)
        }
        return false
    }
    private fun moveCursor(delta: Int) { val ic = currentInputConnection ?: return; val target = (selectionEnd + delta).coerceAtLeast(0); ic.setSelection(target, target) }
    private fun deleteBackward() { currentInputConnection?.deleteSurroundingText(1, 0) }
    private fun selectAll() { currentInputConnection?.performContextMenuAction(android.R.id.selectAll) }
    private fun copySelection() { currentInputConnection?.performContextMenuAction(android.R.id.copy) }
    private fun pasteClipboard() { currentInputConnection?.performContextMenuAction(android.R.id.paste) }
    private fun toggleShift() { if (capsLock) capsLock = false else if (shift) capsLock = true else shift = true }
    private fun actionLabel(): String = when (editorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION)) { EditorInfo.IME_ACTION_GO -> "Go"; EditorInfo.IME_ACTION_SEARCH -> "⌕"; EditorInfo.IME_ACTION_SEND -> "Send"; EditorInfo.IME_ACTION_NEXT -> "Next"; EditorInfo.IME_ACTION_DONE -> "Done"; else -> "↵" }
    private fun sendEditorAction() { val ic = currentInputConnection ?: return; val action = editorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_NONE; if (action != EditorInfo.IME_ACTION_NONE) ic.performEditorAction(action) else ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)); }
    private fun shouldCapitalize(): Boolean = PhormiKeyboardPreferences.autoCaps(this) && PhormiKeyboardTextEngine.autoCapitalize(editorInfo, currentInputConnection)
    private fun refreshPredictions(restartBridge: Boolean = true) { val ic = currentInputConnection ?: return; if (!PhormiKeyboardTextEngine.shouldUsePredictions(editorInfo)) { lastSuggestions = emptyList(); return }; val prefix = PhormiKeyboardTextEngine.currentWord(ic); val locale = PhormiKeyboardTextEngine.localeFor(editorInfo); lastSuggestions = PhormiKeyboardTextEngine.suggestions(this, prefix, locale); if (restartBridge) PhormiKeyboardAiBridge.update() }
    private fun acceptSuggestion(value: String) { val ic = currentInputConnection ?: return; val prefix = PhormiKeyboardTextEngine.currentWord(ic); if (prefix.isNotBlank()) ic.deleteSurroundingText(prefix.length, 0); ic.commitText(value, 1); PhormiKeyboardTextEngine.learn(this, value); PhormiKeyboardTextEngine.learnPair(this, PhormiKeyboardTextEngine.previousWord(ic), value); refreshPredictions() }
    private fun commitSpace() { val ic = currentInputConnection ?: return; val prefix = PhormiKeyboardTextEngine.currentWord(ic); if (PhormiKeyboardPreferences.autocorrect(this) && prefix.isNotBlank()) PhormiKeyboardTextEngine.correctionFor(this, prefix, PhormiKeyboardTextEngine.localeFor(editorInfo))?.let { corrected -> ic.deleteSurroundingText(prefix.length, 0); ic.commitText(corrected, 1); PhormiKeyboardTextEngine.learn(this, corrected) }; val previous = PhormiKeyboardTextEngine.previousWord(ic); ic.commitText(" ", 1); if (previous.isNotBlank()) PhormiKeyboardTextEngine.learnPair(this, previous, PhormiKeyboardTextEngine.currentWord(ic)); refreshPredictions() }
    private fun installRepeat(view: View, action: () -> Unit) { view.setOnLongClickListener { repeatRunnable = object : Runnable { override fun run() { action(); repeatHandler.postDelayed(this, 55) } }; repeatHandler.post(repeatRunnable!!); true }; view.setOnTouchListener { _, e -> if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) stopRepeat(); false } }
    private fun stopRepeat() { repeatRunnable?.let { repeatHandler.removeCallbacks(it) }; repeatRunnable = null }
    private fun startVoice() { if (!SpeechRecognizer.isRecognitionAvailable(this)) { Toast.makeText(this, "Voice input is unavailable", Toast.LENGTH_SHORT).show(); return }; if (listening) return; speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this); speechRecognizer?.setRecognitionListener(object : RecognitionListener { override fun onReadyForSpeech(params: Bundle?) {}; override fun onBeginningOfSpeech() {}; override fun onRmsChanged(rmsdB: Float) {}; override fun onBufferReceived(buffer: ByteArray?) {}; override fun onEndOfSpeech() { listening = false }; override fun onError(error: Int) { listening = false; speechRecognizer?.destroy(); speechRecognizer = null }; override fun onResults(results: Bundle?) { val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull(); if (!text.isNullOrBlank()) commitTextToEditor(text + " "); listening = false; speechRecognizer?.destroy(); speechRecognizer = null; refreshPredictions() }; override fun onPartialResults(partialResults: Bundle?) {}; override fun onEvent(eventType: Int, params: Bundle?) {} }); val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply { putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM); putExtra(RecognizerIntent.EXTRA_LANGUAGE, PhormiKeyboardTextEngine.localeFor(editorInfo).toLanguageTag()) }; listening = true; speechRecognizer?.startListening(intent) }
    private fun stopVoice() { speechRecognizer?.cancel(); speechRecognizer?.destroy(); speechRecognizer = null; listening = false }
    private fun generateAiEmoji(context: String) { aiGenerating = true; setInputView(render()); PhormiPollinationsAiEmojiEngine.generate(this, context) { file -> aiGenerating = false; if (file != null) { aiFiles = PhormiPollinationsAiEmojiEngine.listGenerated(this); panel = Panel.EMOJI }; setInputView(render()) } }
}
