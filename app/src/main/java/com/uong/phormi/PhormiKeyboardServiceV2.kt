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

/** Phormi's real Android system IME. */
class PhormiKeyboardServiceV2 : InputMethodService() {
    companion object {
        private const val PREFS = "phormi_keyboard_pending"
        private const val KEY_PENDING_URI = "pending_uri"
        private const val KEY_PENDING_TEXT = "pending_text"
        private var instance: PhormiKeyboardServiceV2? = null
        fun commitPickedContent(context: Context, uri: Uri): Boolean {
            val service = instance
            if (service?.currentInputConnection != null) return service.commitContent(uri)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_PENDING_URI, uri.toString()).apply()
            return false
        }
        fun commitExternalText(context: Context, text: String): Boolean {
            if (text.isBlank()) return false
            val service = instance
            if (service?.currentInputConnection != null) return service.commitText(text)
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
    private val aiController by lazy { PhormiKeyboardAiEmojiController(this) { files, loading -> aiFiles = files; aiGenerating = loading; if (panel == Panel.EMOJI || panel == Panel.AI_EMOJI) setInputView(render()) } }

    override fun onCreate() { super.onCreate(); instance = this; val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager; clipboardListener = ClipboardManager.OnPrimaryClipChangedListener { PhormiKeyboardClipboardStore.capturePrimaryClipboard(this) }; clipboardListener?.let { cm?.addPrimaryClipChangedListener(it) } }
    override fun onDestroy() { val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager; clipboardListener?.let { cm?.removePrimaryClipChangedListener(it) }; clipboardListener = null; stopRepeat(); stopVoice(); aiController.cancel(); if (instance === this) instance = null; super.onDestroy() }
    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) { super.onStartInput(attribute, restarting); editorInfo = attribute; selectionStart = 0; selectionEnd = 0; symbols = false; shift = false; capsLock = false; panel = Panel.KEYBOARD; completions = emptyList(); lastSuggestions = emptyList(); aiFiles = emptyList(); aiGenerating = false; stopVoice(); aiController.cancel() }
    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) { super.onStartInputView(info, restarting); editorInfo = info ?: editorInfo; panel = Panel.KEYBOARD; setInputView(render()); applyPendingInput(); refreshPredictions() }
    override fun onFinishInput() { stopRepeat(); stopVoice(); editorInfo = null; completions = emptyList(); lastSuggestions = emptyList(); aiController.cancel(); super.onFinishInput() }
    override fun onUnbindInput() { stopRepeat(); stopVoice(); editorInfo = null; aiController.cancel(); super.onUnbindInput() }
    override fun onFinishInputView(finishingInput: Boolean) { stopRepeat(); super.onFinishInputView(finishingInput) }
    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) { super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd); selectionStart = newSelStart; selectionEnd = newSelEnd; if (panel == Panel.KEYBOARD) refreshPredictions(false) }
    override fun onDisplayCompletions(values: Array<out CompletionInfo>?) { super.onDisplayCompletions(values); completions = values?.filter { !it.text.isNullOrBlank() }?.take(5).orEmpty(); refreshPredictions(false) }
    override fun onEvaluateFullscreenMode() = false
    override fun onCreateInputView(): View = render()
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean { if (keyCode == KeyEvent.KEYCODE_BACK && panel != Panel.KEYBOARD) { panel = Panel.KEYBOARD; setInputView(render()); return true }; return super.onKeyDown(keyCode, event) }

    private fun scale(value: Int) = (value * PhormiKeyboardPreferences.heightScale(this)).toInt().coerceAtLeast(1)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt().coerceAtLeast(1)
    private fun root() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(6), dp(4), dp(6), dp(4)); setBackgroundColor(Color.rgb(13, 18, 30)); isFocusable = true }
    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = radius.toFloat() }
    private fun feedback(view: View) { if (PhormiKeyboardPreferences.haptic(this)) view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP); if (PhormiKeyboardPreferences.sound(this)) view.playSoundEffect(android.view.SoundEffectConstants.CLICK) }
    private fun pill(label: String, action: () -> Unit) = Button(this).apply { text = label; textSize = 13f; setTextColor(Color.rgb(229, 231, 235)); typeface = Typeface.DEFAULT_BOLD; minWidth = 0; minHeight = 0; isAllCaps = false; stateListAnimator = null; setPadding(dp(7), 0, dp(7), 0); background = rounded(Color.rgb(31, 41, 55), dp(13)); contentDescription = label; setOnClickListener { feedback(this); action() } }
    private fun keyButton(label: String, weight: Float = 1f, action: () -> Unit) = Button(this).apply { text = label; textSize = if (label.length == 1) 20f else 12f; setTextColor(Color.WHITE); minWidth = 0; minHeight = 0; isAllCaps = false; typeface = Typeface.DEFAULT; stateListAnimator = null; setPadding(dp(2), 0, dp(2), 0); background = rounded(Color.rgb(39, 48, 64), dp(9)); contentDescription = label; setOnClickListener { feedback(this); action() }; layoutParams = LinearLayout.LayoutParams(0, scale(46), weight).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) } }

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
            val values = (if (completions.isNotEmpty()) completions.mapNotNull { it.text?.toString() } else lastSuggestions).distinct().take(4)
            if (values.isNotEmpty()) { val s = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }; val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }; values.forEach { v -> r.addView(pill(v) { acceptSuggestion(v) }, LinearLayout.LayoutParams(-2, scale(34)).apply { setMargins(dp(2), dp(1), dp(2), dp(1)) }) }; s.addView(r); root.addView(s, LinearLayout.LayoutParams(-1, scale(36))) }
        }
    }

    private fun buildKeyboard(): View {
        val root = root(); val type = editorInfo?.inputType ?: InputType.TYPE_CLASS_TEXT; val clazz = type and InputType.TYPE_MASK_CLASS; val variation = type and InputType.TYPE_MASK_VARIATION
        val number = clazz == InputType.TYPE_CLASS_NUMBER; val phone = clazz == InputType.TYPE_CLASS_PHONE; val dateTime = clazz == InputType.TYPE_CLASS_DATETIME; val email = variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS || variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS; val uri = variation == InputType.TYPE_TEXT_VARIATION_URI
        shift = shouldCapitalize(); toolbar(root)
        when { phone -> addRows(root, listOf("1234567890", "*#+-", "().")); number -> addRows(root, listOf("1234567890", "789456123", "0.,+-")); dateTime -> addRows(root, listOf("1234567890", "4567891230", ":/-")); symbols -> addRows(root, listOf("1234567890", "-=[]\\;',./", "!@#\$%^&*()", "_+{}|:\"<>?")); else -> addRows(root, listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")) }
        if (email || uri) { val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }; (if (email) listOf("@", ".com", ".net", ".org") else listOf("/", ".com", "https://", ".org")).forEach { token -> r.addView(keyButton(token) { commitText(token); refreshPredictions() }) }; root.addView(r, LinearLayout.LayoutParams(-1, scale(50))) }
        val bottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        if (!number && !phone && !dateTime) { bottom.addView(keyButton(if (symbols) "ABC" else "123") { symbols = !symbols; setInputView(render()) }); bottom.addView(keyButton(if (capsLock) "⇧·" else "⇧") { toggleShift(); setInputView(render()) }) }
        bottom.addView(keyButton("Sel") { selectAll() }); bottom.addView(keyButton("Copy") { copySelection() }); bottom.addView(keyButton("Paste") { pasteClipboard() })
        val space = keyButton("Space", if (number || phone) 2f else 2.5f) { commitSpace() }
        space.setOnTouchListener { _, e -> when (e.actionMasked) { MotionEvent.ACTION_DOWN -> { spaceDownX = e.x; spaceMoved = false }; MotionEvent.ACTION_MOVE -> if (!spaceMoved && kotlin.math.abs(e.x - spaceDownX) > dp(28)) { spaceMoved = true; moveCursor(if (e.x > spaceDownX) 1 else -1) }; MotionEvent.ACTION_UP -> if (!spaceMoved) commitSpace(); MotionEvent.ACTION_CANCEL -> spaceMoved = false }; true }
        bottom.addView(space); bottom.addView(keyButton("←") { moveCursor(-1) }); bottom.addView(keyButton("→") { moveCursor(1) }); val back = keyButton("⌫") { deleteBackward() }; installRepeat(back) { deleteBackward() }; bottom.addView(back); bottom.addView(keyButton(actionLabel()) { sendEditorAction() }); if (!number && !phone && !dateTime) bottom.addView(keyButton(if (listening) "■" else "🎙") { if (listening) stopVoice() else startVoice(); setInputView(render()) })
        root.addView(bottom, LinearLayout.LayoutParams(-1, scale(54))); return root
    }
    private fun addRows(root: LinearLayout, rows: List<String>) { rows.forEach { chars -> val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }; chars.forEach { c -> val label = if (c.isLetter() && (shift || capsLock)) c.uppercaseChar().toString() else c.toString(); row.addView(keyButton(label) { commitText(label); if (shift && !capsLock) shift = false; refreshPredictions(); setInputView(render()) }) }; root.addView(row, LinearLayout.LayoutParams(-1, scale(50))) } }

    private fun buildEmoji(): View {
        val root = root(); val nav = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }; val categories = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        PhormiKeyboardEmoji.categories.keys.forEachIndexed { index, icon -> categories.addView(pill(icon) { emojiCategory = index; setInputView(render()) }, LinearLayout.LayoutParams(dp(48), scale(36)).apply { setMargins(dp(2), 0, dp(2), 0) }) }
        if (!isPrivateEditor() && PhormiKeyboardPreferences.aiEmoji(this)) categories.addView(pill("✨") { prepareAiContext(); panel = Panel.AI_EMOJI; setInputView(render()) }, LinearLayout.LayoutParams(dp(48), scale(36)))
        categories.addView(pill("ABC") { panel = Panel.KEYBOARD; setInputView(render()) }, LinearLayout.LayoutParams(dp(58), scale(36))); nav.addView(categories); root.addView(nav, LinearLayout.LayoutParams(-1, scale(40)))
        val scroll = ScrollView(this); val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (!isPrivateEditor()) { val moods = PhormiKeyboardAiContext.suggestions(PhormiKeyboardTextEngine.contextBeforeCursor(currentInputConnection)); if (moods.isNotEmpty()) { val moodRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }; moods.take(3).forEach { m -> moodRow.addView(keyButton(m.emoji) { commitText(m.emoji) }, LinearLayout.LayoutParams(0, scale(50), 1f)) }; grid.addView(moodRow, LinearLayout.LayoutParams(-1, scale(54))) } }
        if (aiFiles.isNotEmpty() && PhormiKeyboardPreferences.aiEmoji(this)) { val file = aiFiles.first(); val aiRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }; aiRow.addView(emojiImageButton(file, "Generated reaction") { if (commitContent(PhormiKeyboardStickerStore.contentUri(this@PhormiKeyboardServiceV2, file))) { aiFiles = emptyList(); setInputView(render()) } }, LinearLayout.LayoutParams(0, scale(50), 1f)); grid.addView(aiRow, LinearLayout.LayoutParams(-1, scale(54))) }
        PhormiKeyboardEmoji.categories.values.elementAtOrNull(emojiCategory).orEmpty().chunked(8).forEach { group -> val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }; group.forEach { emoji -> row.addView(keyButton(emoji) { commitText(emoji) }, LinearLayout.LayoutParams(0, scale(50), 1f)) }; repeat(8 - group.size) { row.addView(View(this), LinearLayout.LayoutParams(0, scale(50), 1f)) }; grid.addView(row, LinearLayout.LayoutParams(-1, scale(50))) }
        scroll.addView(grid); root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f)); return root
    }
    private fun emojiImageButton(file: File, description: String, action: () -> Unit): View = ImageView(this).apply { setImageBitmap(android.graphics.BitmapFactory.decodeFile(file.absolutePath)); contentDescription = description; scaleType = ImageView.ScaleType.CENTER_INSIDE; setPadding(dp(6), dp(5), dp(6), dp(5)); background = rounded(Color.rgb(39, 48, 64), dp(9)); setOnClickListener { feedback(this); action() } }

    private fun buildClipboard(): View {
        val root = root(); val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }; header.addView(pill("← Keyboard") { panel = Panel.KEYBOARD; setInputView(render()) }); header.addView(pill("Clear") { PhormiKeyboardClipboardStore.clearUnpinned(this); setInputView(render()) }); root.addView(header, LinearLayout.LayoutParams(-1, scale(40)))
        val scroll = ScrollView(this); val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }; val items = PhormiKeyboardClipboardStore.list(this); if (items.isEmpty()) list.addView(TextView(this).apply { text = "Copy something to build your Phormi clipboard history."; setTextColor(Color.LTGRAY); setPadding(dp(12), dp(20), dp(12), dp(20)) })
        items.forEach { item -> val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; background = rounded(Color.rgb(27, 35, 49), dp(10)); setPadding(dp(8), dp(2), dp(8), dp(2)) }; row.addView(TextView(this).apply { text = item.text; setTextColor(Color.WHITE); textSize = 13f; maxLines = 3 }, LinearLayout.LayoutParams(0, scale(54), 1f)); row.addView(pill(if (item.pinned) "📌" else "○") { PhormiKeyboardClipboardStore.togglePinned(this@PhormiKeyboardServiceV2, item.text); setInputView(render()) }, LinearLayout.LayoutParams(dp(50), scale(40))); row.setOnClickListener { commitText(item.text) }; row.setOnLongClickListener { PhormiKeyboardClipboardStore.remove(this, item.text); setInputView(render()); true }; list.addView(row, LinearLayout.LayoutParams(-1, scale(62)).apply { setMargins(0, dp(3), 0, dp(3)) }) }
        scroll.addView(list); root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f)); return root
    }

    private fun buildTools(): View {
        val root = root(); root.addView(TextView(this).apply { text = "Phormi Keyboard Tools"; textSize = 18f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE); setPadding(dp(8), dp(6), dp(8), dp(8)) }, LinearLayout.LayoutParams(-1, scale(42)))
        val scroll = ScrollView(this); val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun tool(label: String, action: () -> Unit) { list.addView(keyButton(label, 1f, action), LinearLayout.LayoutParams(-1, scale(50)).apply { setMargins(0, dp(3), 0, dp(3)) }) }
        tool("⚙ Settings") { startActivity(Intent(this, PhormiKeyboardSettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        tool("✨ AI Emoji: ${if (PhormiKeyboardPreferences.aiEmoji(this)) "ON" else "OFF"}") { PhormiKeyboardPreferences.set(this, PhormiKeyboardPreferences.KEY_AI_EMOJI, !PhormiKeyboardPreferences.aiEmoji(this)); setInputView(render()) }
        tool("Height −") { PhormiKeyboardPreferences.setHeight(this, PhormiKeyboardPreferences.height(this) - 1); setInputView(render()) }
        tool("Height +") { PhormiKeyboardPreferences.setHeight(this, PhormiKeyboardPreferences.height(this) + 1); setInputView(render()) }
        tool(if (oneHanded()) "Full width" else "One-hand") { setOneHanded(!oneHanded()); setInputView(render()) }
        tool("😀 Emoji") { panel = Panel.EMOJI; prepareAiContext(); setInputView(render()) }
        tool("📋 Clipboard") { panel = Panel.CLIPBOARD; setInputView(render()) }
        tool("🎙 Voice") { panel = Panel.KEYBOARD; startVoice(); setInputView(render()) }
        tool("ABC Keyboard") { panel = Panel.KEYBOARD; setInputView(render()) }
        scroll.addView(list); root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f)); root.addView(pill("← Back to keyboard") { panel = Panel.KEYBOARD; setInputView(render()) }, LinearLayout.LayoutParams(-1, scale(40))); return root
    }

    private fun buildMedia(): View {
        val root = root(); root.addView(TextView(this).apply { text = "GIF & Stickers"; textSize = 18f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE); gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-1, scale(42)))
        val packs = PhormiKeyboardStickerPackStore.packs(this); val files = packs.flatMap { PhormiKeyboardStickerPackStore.files(this, it) }.take(8)
        if (files.isNotEmpty()) { val scroll = ScrollView(this); val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }; files.forEach { file -> list.addView(emojiImageButton(file, "Media") { commitContent(PhormiKeyboardStickerStore.contentUri(this@PhormiKeyboardServiceV2, file)) }, LinearLayout.LayoutParams(-1, scale(58))) }; scroll.addView(list); root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f)) } else root.addView(TextView(this).apply { text = "Imported stickers and generated emoji will appear here."; setTextColor(Color.LTGRAY); gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(pill("Import GIF / image") { startActivity(Intent(this, PhormiKeyboardMediaActivity::class.java).putExtra(PhormiKeyboardMediaActivity.EXTRA_MODE, "gif").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }, LinearLayout.LayoutParams(-1, scale(42)))
        root.addView(pill("Import sticker") { startActivity(Intent(this, PhormiKeyboardMediaActivity::class.java).putExtra(PhormiKeyboardMediaActivity.EXTRA_MODE, "sticker").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }, LinearLayout.LayoutParams(-1, scale(42)))
        root.addView(pill("✨ Create AI Emoji") { panel = Panel.AI_EMOJI; prepareAiContext(); setInputView(render()) }, LinearLayout.LayoutParams(-1, scale(42))); root.addView(pill("← Back") { panel = Panel.KEYBOARD; setInputView(render()) }, LinearLayout.LayoutParams(-1, scale(40))); return root
    }

    private fun buildAiEmoji(): View {
        if (isPrivateEditor() || !PhormiKeyboardPreferences.aiEmoji(this)) { panel = Panel.KEYBOARD; return buildKeyboard() }
        val root = root(); root.addView(pill("← Emoji") { panel = Panel.EMOJI; setInputView(render()) }, LinearLayout.LayoutParams(-1, scale(40)))
        root.addView(TextView(this).apply { text = if (aiGenerating) "Creating one integrated reaction…" else "Phormi reaction"; textSize = 17f; setTextColor(Color.WHITE); gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-1, scale(42)))
        if (aiFiles.isNotEmpty()) { val file = aiFiles.first(); root.addView(emojiImageButton(file, "Generated reaction") { if (commitContent(PhormiKeyboardStickerStore.contentUri(this@PhormiKeyboardServiceV2, file))) { aiFiles = aiFiles.drop(1); setInputView(render()) } }, LinearLayout.LayoutParams(-1, scale(90))) }
        root.addView(pill(if (aiGenerating) "Generating…" else "Generate reaction") { val text = PhormiKeyboardTextEngine.contextBeforeCursor(currentInputConnection).trim(); aiController.generate(text.ifBlank { "happy positive celebration" }) }, LinearLayout.LayoutParams(-1, scale(42))); return root
    }

    private fun render(): View = when (panel) { Panel.KEYBOARD -> buildKeyboard(); Panel.EMOJI -> buildEmoji(); Panel.CLIPBOARD -> buildClipboard(); Panel.AI_EMOJI -> buildAiEmoji(); Panel.TOOLS -> buildTools(); Panel.MEDIA -> buildMedia() }
    private fun isPrivateEditor(): Boolean { val info = editorInfo ?: return true; return PhormiKeyboardTextEngine.isPrivateEditor(info) || (info.inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0 }
    private fun actionLabel() = when ((editorInfo?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION) { EditorInfo.IME_ACTION_GO -> "Go"; EditorInfo.IME_ACTION_SEARCH -> "Search"; EditorInfo.IME_ACTION_NEXT -> "Next"; EditorInfo.IME_ACTION_DONE -> "Done"; EditorInfo.IME_ACTION_SEND -> "Send"; EditorInfo.IME_ACTION_PREVIOUS -> "Prev"; else -> "↵" }
    private fun sendEditorAction() { val ic = currentInputConnection ?: return; val action = (editorInfo?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION; runCatching { if (action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) { if (!ic.performEditorAction(action)) sendEnter(ic) } else sendEnter(ic) } }
    private fun sendEnter(ic: InputConnection) { ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)); ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER)) }
    private fun commitSpace() { val ic = currentInputConnection ?: return; if (!PhormiKeyboardPreferences.autocorrect(this)) { ic.commitText(" ", 1); refreshPredictions(); return }; val word = PhormiKeyboardTextEngine.currentWord(ic); runCatching { ic.beginBatchEdit(); if (PhormiKeyboardTextEngine.shouldUsePredictions(editorInfo) && word.isNotBlank()) { PhormiKeyboardTextEngine.correctionFor(word)?.let { c -> ic.deleteSurroundingText(word.length, 0); ic.commitText(matchCase(c, word), 1) }; PhormiKeyboardTextEngine.learn(this, word, editorInfo) }; val before = ic.getTextBeforeCursor(2, 0)?.toString().orEmpty(); if (before.endsWith("  ")) { ic.deleteSurroundingText(2, 0); ic.commitText(". ", 1) } else ic.commitText(" ", 1) }.also { runCatching { ic.endBatchEdit() } }; refreshPredictions() }
    private fun deleteBackward() { val ic = currentInputConnection ?: return; runCatching { ic.beginBatchEdit(); if (!ic.getSelectedText(0).isNullOrEmpty()) { ic.commitText("", 1); return@runCatching }; val before = ic.getTextBeforeCursor(128, 0)?.toString().orEmpty(); if (before.isEmpty()) return@runCatching; val iterator = android.icu.text.BreakIterator.getCharacterInstance().apply { setText(before) }; val boundary = iterator.preceding(before.length); val count = if (boundary >= 0) before.length - boundary else 1; ic.deleteSurroundingText(count, 0) }.also { runCatching { ic.endBatchEdit() } }; refreshPredictions() }
    private fun installRepeat(view: View, action: () -> Unit) { view.setOnLongClickListener { stopRepeat(); val r = object : Runnable { override fun run() { if (currentInputConnection == null) { stopRepeat(); return }; action(); repeatHandler.postDelayed(this, 55) } }; repeatRunnable = r; repeatHandler.postDelayed(r, 280); true }; view.setOnTouchListener { _, e -> if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) stopRepeat(); false } }
    private fun stopRepeat() { repeatRunnable?.let { repeatHandler.removeCallbacks(it) }; repeatRunnable = null }
    private fun moveCursor(delta: Int) { val ic = currentInputConnection ?: return; val target = (selectionStart + delta).coerceAtLeast(0); if (ic.setSelection(target, target)) { selectionStart = target; selectionEnd = target } }
    private fun selectAll() { currentInputConnection?.performContextMenuAction(android.R.id.selectAll) }
    private fun copySelection() { currentInputConnection?.performContextMenuAction(android.R.id.copy) }
    private fun pasteClipboard() { currentInputConnection?.performContextMenuAction(android.R.id.paste) }
    private fun refreshPredictions(redraw: Boolean = true) { val ic = currentInputConnection ?: return; if (!PhormiKeyboardTextEngine.shouldUsePredictions(editorInfo) || !PhormiKeyboardPreferences.suggestions(this)) { lastSuggestions = emptyList(); return }; lastSuggestions = PhormiKeyboardTextEngine.suggestions(this, PhormiKeyboardTextEngine.currentWord(ic)); if (redraw && panel == Panel.KEYBOARD) setInputView(render()) }
    private fun acceptSuggestion(value: String) { val ic = currentInputConnection ?: return; val word = PhormiKeyboardTextEngine.currentWord(ic); if (word.isNotBlank()) ic.deleteSurroundingText(word.length, 0); ic.commitText(value, 1); refreshPredictions() }
    private fun matchCase(value: String, original: String) = if (original.all { !it.isLetter() || it.isUpperCase() }) value.uppercase(Locale.getDefault()) else if (original.firstOrNull()?.isUpperCase() == true) value.replaceFirstChar { it.uppercase() } else value
    private fun shouldCapitalize() = PhormiKeyboardPreferences.autoCaps(this) && PhormiKeyboardTextEngine.autoCapitalize(currentInputConnection, editorInfo)
    private fun toggleShift() { if (capsLock) { capsLock = false; shift = false } else if (shift) capsLock = true else shift = true }

    private fun prepareAiContext() { if (isPrivateEditor() || !PhormiKeyboardPreferences.aiEmoji(this)) return; aiController.cancel(); val text = PhormiKeyboardTextEngine.contextBeforeCursor(currentInputConnection).trim(); if (text.length >= 3) aiController.generate(text) }
    private fun startVoice() { if (listening || isPrivateEditor()) return; if (!SpeechRecognizer.isRecognitionAvailable(this)) { showToast("Speech recognition is not available on this device"); return }; stopVoice(); speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this); speechRecognizer?.setRecognitionListener(object : RecognitionListener { override fun onReadyForSpeech(params: Bundle?) { listening = true; setInputView(render()) }; override fun onBeginningOfSpeech() {}; override fun onRmsChanged(rmsdB: Float) {}; override fun onBufferReceived(buffer: ByteArray?) {}; override fun onEndOfSpeech() {}; override fun onError(error: Int) { listening = false; setInputView(render()) }; override fun onResults(results: Bundle?) { val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull(); if (!text.isNullOrBlank()) commitText(text); listening = false; setInputView(render()) }; override fun onPartialResults(partialResults: Bundle?) {}; override fun onEvent(eventType: Int, params: Bundle?) {} }); val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply { putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM); putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag()) }; runCatching { speechRecognizer?.startListening(intent) }.onFailure { stopVoice(); showToast("Unable to start voice input") } }
    private fun stopVoice() { speechRecognizer?.let { runCatching { it.stopListening() }; it.cancel(); it.destroy() }; speechRecognizer = null; listening = false }
    private fun applyPendingInput() { val p = getSharedPreferences(PREFS, MODE_PRIVATE); p.getString(KEY_PENDING_TEXT, null)?.let { p.edit().remove(KEY_PENDING_TEXT).apply(); commitText(it) }; p.getString(KEY_PENDING_URI, null)?.let { p.edit().remove(KEY_PENDING_URI).apply(); commitContent(Uri.parse(it)) } }
    private fun commitText(text: String): Boolean { val ok = currentInputConnection?.commitText(text, 1) ?: false; if (ok) refreshPredictions(false); return ok }
    private fun commitContent(uri: Uri): Boolean { if (Build.VERSION.SDK_INT < 25) return false; val ic = currentInputConnection ?: return false; val mime = contentResolver.getType(uri) ?: "image/*"; val info = InputContentInfo(uri, ClipDescription("Phormi media", arrayOf(mime))); return runCatching { ic.commitContent(info, InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null) }.getOrDefault(false) }
    private fun oneHanded(): Boolean = getSharedPreferences("phormi_keyboard_layout", MODE_PRIVATE).getBoolean("one_handed", false)
    private fun setOneHanded(enabled: Boolean) { getSharedPreferences("phormi_keyboard_layout", MODE_PRIVATE).edit().putBoolean("one_handed", enabled).apply() }
    private fun showToast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
}
