package com.uong.phormi

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
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
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Phormi's real Android system IME. All feature panels share one fixed viewport. */
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

    enum class Panel { KEYBOARD, EMOJI, CLIPBOARD, AI_EMOJI, TOOLS, MEDIA, SETTINGS }
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
    private var clipboardListener: ClipboardManager.OnPrimaryClipChangedListener? = null
    private var resizeStartY = 0f
    private var resizeStartLevel = 3

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
        stopRepeat()
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        editorInfo = attribute
        selectionStart = 0
        selectionEnd = 0
        symbols = false
        capsLock = false
        shift = PhormiKeyboardPreferences.autoCaps(this) && PhormiKeyboardTextEngine.autoCapitalize(currentInputConnection, attribute)
        panel = Panel.KEYBOARD
        completions = emptyList()
        lastSuggestions = emptyList()
        aiFiles = emptyList()
        aiGenerating = false
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        editorInfo = info ?: editorInfo
        panel = Panel.KEYBOARD
        PhormiKeyboardAiBridge.start()
        setInputView(render())
        applyPendingInput()
        refreshPredictions(false)
    }

    override fun onFinishInput() { PhormiKeyboardAiBridge.stop(); stopRepeat(); editorInfo = null; completions = emptyList(); lastSuggestions = emptyList(); super.onFinishInput() }
    override fun onUnbindInput() { PhormiKeyboardAiBridge.stop(); stopRepeat(); editorInfo = null; super.onUnbindInput() }
    override fun onFinishInputView(finishingInput: Boolean) { stopRepeat(); super.onFinishInputView(finishingInput) }
    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        selectionStart = newSelStart
        selectionEnd = newSelEnd
        if (panel == Panel.KEYBOARD) refreshPredictions(false)
    }
    override fun onDisplayCompletions(values: Array<out CompletionInfo>?) { super.onDisplayCompletions(values); completions = values?.filter { !it.text.isNullOrBlank() }?.take(5).orEmpty(); refreshPredictions(false) }
    override fun onEvaluateFullscreenMode() = false
    override fun onCreateInputView(): View = render()

    fun render(): View = when (panel) {
        Panel.KEYBOARD -> buildKeyboard()
        Panel.EMOJI -> buildEmoji()
        Panel.CLIPBOARD -> buildClipboard()
        Panel.AI_EMOJI -> buildAiEmoji()
        Panel.TOOLS -> buildTools()
        Panel.MEDIA -> buildMedia()
        Panel.SETTINGS -> buildSettings()
    }

    private fun baseHeight(): Int = 420
    private fun scale(value: Int): Int = (value * PhormiKeyboardPreferences.heightScale(this)).roundToInt().coerceAtLeast(1)
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt().coerceAtLeast(1)
    private fun theme() = PhormiKeyboardPreferences.theme(this)
    private fun themeBackground() = when (theme()) { 1 -> Color.rgb(20, 24, 29); 2 -> Color.rgb(7, 24, 42); 3 -> Color.rgb(242, 244, 247); else -> Color.rgb(13, 18, 30) }
    private fun themeKey() = when (theme()) { 1 -> Color.rgb(48, 53, 61); 2 -> Color.rgb(18, 52, 79); 3 -> Color.WHITE; else -> Color.rgb(39, 48, 64) }
    private fun themePill() = when (theme()) { 1 -> Color.rgb(37, 42, 49); 2 -> Color.rgb(15, 45, 69); 3 -> Color.rgb(224, 228, 234); else -> Color.rgb(31, 41, 55) }
    private fun themeText() = if (theme() == 3) Color.rgb(20, 27, 36) else Color.WHITE
    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = radius.toFloat() }

    private fun root(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(6), dp(2), dp(6), dp(2))
        setBackgroundColor(themeBackground())
        layoutParams = LinearLayout.LayoutParams(-1, scale(baseHeight()))
        minimumHeight = scale(baseHeight())
        addResizeGrip(this)
        applyWallpaper(this)
    }

    private fun applyWallpaper(root: View) {
        val value = PhormiKeyboardPreferences.wallpaperUri(this) ?: return
        runCatching { contentResolver.openInputStream(Uri.parse(value))?.use { input -> BitmapFactory.decodeStream(input) }?.let { root.background = BitmapDrawable(resources, it).apply { alpha = 70 } } }
    }

    private fun feedback(view: View) {
        if (PhormiKeyboardPreferences.haptic(this)) view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
        if (PhormiKeyboardPreferences.sound(this)) view.playSoundEffect(android.view.SoundEffectConstants.CLICK)
    }

    private fun pill(label: String, action: () -> Unit) = Button(this).apply {
        text = label; textSize = 13f; setTextColor(themeText()); typeface = Typeface.DEFAULT_BOLD
        minWidth = 0; minHeight = 0; isAllCaps = false; stateListAnimator = null; setPadding(dp(7), 0, dp(7), 0)
        background = rounded(themePill(), dp(13)); contentDescription = label
        setOnClickListener { feedback(this); action() }
    }

    private fun keyButton(label: String, weight: Float = 1f, action: () -> Unit) = Button(this).apply {
        text = label; textSize = if (label.length == 1) 20f else 12f; setTextColor(themeText())
        minWidth = 0; minHeight = 0; isAllCaps = false; stateListAnimator = null; setPadding(dp(2), 0, dp(2), 0)
        background = rounded(themeKey(), dp(9)); contentDescription = label
        setOnClickListener { feedback(this); action() }
        layoutParams = LinearLayout.LayoutParams(0, scale(46), weight).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) }
    }

    private fun addResizeGrip(root: LinearLayout) {
        val grip = TextView(this).apply {
            text = "⠿"; textSize = 22f; gravity = Gravity.CENTER; setTextColor(if (theme() == 3) Color.DKGRAY else Color.rgb(148, 163, 184))
            background = rounded(themePill(), dp(10)); contentDescription = "Drag the resize handle up or down to change Phormi Keyboard size"
            setPadding(0, 0, 0, 0)
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { resizeStartY = event.rawY; resizeStartLevel = PhormiKeyboardPreferences.height(this@PhormiKeyboardServiceV2); true }
                    MotionEvent.ACTION_MOVE -> true
                    MotionEvent.ACTION_UP -> {
                        val delta = ((resizeStartY - event.rawY) / (22f * resources.displayMetrics.density)).roundToInt()
                        if (delta != 0) PhormiKeyboardPreferences.setHeight(this@PhormiKeyboardServiceV2, resizeStartLevel + delta)
                        setInputView(render()); true
                    }
                    MotionEvent.ACTION_CANCEL -> true
                    else -> true
                }
            }
        }
        root.addView(grip, 0, LinearLayout.LayoutParams(dp(54), dp(24)).apply { gravity = Gravity.CENTER_HORIZONTAL })
    }

    private fun toolbar(root: LinearLayout) {
        val scroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        fun add(label: String, width: Int = 58, action: () -> Unit) { row.addView(pill(label, action), LinearLayout.LayoutParams(dp(width), scale(36)).apply { setMargins(dp(2), 0, dp(2), 0) }) }
        add("😀") { panel = Panel.EMOJI; prepareAiContext(); setInputView(render()) }
        if (!isPrivateEditor() && PhormiKeyboardPreferences.aiEmoji(this)) add("✨", 52) { panel = Panel.AI_EMOJI; prepareAiContext(); setInputView(render()) }
        add("📋") { panel = Panel.CLIPBOARD; PhormiKeyboardClipboardStore.capturePrimaryClipboard(this); setInputView(render()) }
        add("▦ Tools", 70) { panel = Panel.TOOLS; setInputView(render()) }
        add("GIF", 52) { panel = Panel.MEDIA; setInputView(render()) }
        add("Sticker", 68) { panel = Panel.MEDIA; setInputView(render()) }
        if (shouldOfferSwitchingToNextInputMethod()) add("🌐", 52) { runCatching { switchToNextInputMethod(false) } }
        scroll.addView(row); root.addView(scroll, LinearLayout.LayoutParams(-1, scale(40)))
        if (PhormiKeyboardTextEngine.shouldUsePredictions(editorInfo) && PhormiKeyboardPreferences.suggestions(this)) {
            val prefix = PhormiKeyboardTextEngine.currentWord(currentInputConnection)
            val values = if (prefix.isBlank()) PhormiKeyboardTextEngine.nextWordSuggestions(this, PhormiKeyboardTextEngine.previousWord(currentInputConnection)) else if (completions.isNotEmpty()) completions.mapNotNull { it.text?.toString() } else lastSuggestions
            if (values.isNotEmpty()) {
                val s = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
                val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                values.distinct().take(4).forEach { value -> r.addView(pill(value) { acceptSuggestion(value) }, LinearLayout.LayoutParams(-2, scale(34)).apply { setMargins(dp(2), dp(1), dp(2), dp(1)) }) }
                s.addView(r); root.addView(s, LinearLayout.LayoutParams(-1, scale(36)))
            }
        }
    }

    private fun buildKeyboard(): View {
        val root = root()
        val type = editorInfo?.inputType ?: InputType.TYPE_CLASS_TEXT
        val clazz = type and InputType.TYPE_MASK_CLASS
        val variation = type and InputType.TYPE_MASK_VARIATION
        val number = clazz == InputType.TYPE_CLASS_NUMBER
        val phone = clazz == InputType.TYPE_CLASS_PHONE
        val dateTime = clazz == InputType.TYPE_CLASS_DATETIME
        val email = variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        val uri = variation == InputType.TYPE_TEXT_VARIATION_URI
        toolbar(root)
        when {
            phone -> addRows(root, listOf("1234567890", "*#+-", "()."))
            number -> addRows(root, listOf("1234567890", "789456123", "0.,+-"))
            dateTime -> addRows(root, listOf("1234567890", "4567891230", ":/-"))
            symbols -> addRows(root, listOf("1234567890", "-=[]\\;',./", "!@#\$%^&*()", "_+{}|:\"<>?", "€£₦¥₹₽₩₺₴₫₱₪¢‰§±×÷≤≥≠≈"))
            else -> addRows(root, listOf("qwertyuiop", "asdfghjkl", "zxcvbnm"))
        }
        if (email || uri) {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
            val tokens = if (email) listOf("@", ".com", ".net", ".org") else listOf("/", ".com", "https://", ".org")
            tokens.forEach { token -> r.addView(keyButton(token) { commitTextToEditor(token) }) }
            root.addView(r, LinearLayout.LayoutParams(-1, scale(50)))
        }
        val bottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        if (!number && !phone && !dateTime) {
            bottom.addView(keyButton(if (symbols) "ABC" else "123") { symbols = !symbols; setInputView(render()) })
            bottom.addView(keyButton(if (capsLock) "⇧" else if (shift) "⇧·" else "⇧") { toggleShift(); setInputView(render()) })
        }
        val space = keyButton("Space", 3.0f) { commitSpace() }
        space.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { spaceDownX = event.x; spaceMoved = false }
                MotionEvent.ACTION_MOVE -> if (!spaceMoved && abs(event.x - spaceDownX) > dp(28)) { spaceMoved = true; moveCursor(if (event.x > spaceDownX) 1 else -1) }
                MotionEvent.ACTION_UP -> if (!spaceMoved) commitSpace()
                MotionEvent.ACTION_CANCEL -> spaceMoved = false
            }; true
        }
        bottom.addView(space)
        bottom.addView(keyButton("←") { moveCursor(-1) })
        bottom.addView(keyButton("→") { moveCursor(1) })
        val back = keyButton("⌫") { deleteBackward() }; installRepeat(back) { deleteBackward() }; bottom.addView(back)
        bottom.addView(keyButton(actionLabel()) { sendEditorAction() })
        if (!number && !phone && !dateTime) bottom.addView(keyButton("🎙") { startVoice() })
        root.addView(bottom, LinearLayout.LayoutParams(-1, scale(54)))
        return root
    }

    private fun addRows(root: LinearLayout, rows: List<String>) {
        rows.forEach { chars ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
            chars.forEach { c ->
                val label = if (c.isLetter() && (shift || capsLock)) c.uppercaseChar().toString() else c.toString()
                row.addView(keyButton(label) { commitTextToEditor(label); if (shift && !capsLock) shift = false; refreshPredictions(); setInputView(render()) })
            }
            root.addView(row, LinearLayout.LayoutParams(-1, scale(50)))
        }
    }

    private fun buildEmoji(): View {
        val root = root()
        val nav = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val categories = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        PhormiKeyboardEmoji.categories.keys.forEachIndexed { index, icon -> categories.addView(pill(icon) { emojiCategory = index; setInputView(render()) }, LinearLayout.LayoutParams(dp(48), scale(36)).apply { setMargins(dp(2), 0, dp(2), 0) }) }
        if (!isPrivateEditor() && PhormiKeyboardPreferences.aiEmoji(this)) categories.addView(pill("✨") { panel = Panel.AI_EMOJI; prepareAiContext(); setInputView(render()) })
        categories.addView(pill("ABC") { panel = Panel.KEYBOARD; setInputView(render()) }, LinearLayout.LayoutParams(dp(58), scale(36)))
        nav.addView(categories); root.addView(nav, LinearLayout.LayoutParams(-1, scale(40)))
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (!isPrivateEditor()) {
            PhormiKeyboardAiContext.suggestions(PhormiKeyboardTextEngine.contextBeforeCursor(currentInputConnection)).take(3).let { moods ->
                if (moods.isNotEmpty()) {
                    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
                    moods.forEach { m -> row.addView(keyButton(m.emoji) { commitTextToEditor(m.emoji) }, LinearLayout.LayoutParams(0, scale(50), 1f)) }
                    grid.addView(row, LinearLayout.LayoutParams(-1, scale(54)))
                }
            }
        }
        if (aiFiles.isNotEmpty() && PhormiKeyboardPreferences.aiEmoji(this)) {
            val file = aiFiles.first(); val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
            row.addView(emojiImageButton(file, "Phormi custom reaction") { if (commitContentToEditor(PhormiKeyboardStickerStore.contentUri(this@PhormiKeyboardServiceV2, file))) { aiFiles = emptyList(); setInputView(render()) } }, LinearLayout.LayoutParams(0, scale(50), 1f))
            grid.addView(row, LinearLayout.LayoutParams(-1, scale(54)))
        }
        PhormiKeyboardEmoji.categories.values.elementAtOrNull(emojiCategory).orEmpty().chunked(8).forEach { group ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
            group.forEach { emoji -> row.addView(keyButton(emoji) { commitTextToEditor(emoji) }, LinearLayout.LayoutParams(0, scale(50), 1f)) }
            repeat(8 - group.size) { row.addView(View(this), LinearLayout.LayoutParams(0, scale(50), 1f)) }
            grid.addView(row, LinearLayout.LayoutParams(-1, scale(50)))
        }
        scroll.addView(grid); root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f)); return root
    }

    private fun emojiImageButton(file: File, description: String, action: () -> Unit): View = ImageView(this).apply {
        setImageBitmap(BitmapFactory.decodeFile(file.absolutePath)); contentDescription = description; scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(dp(6), dp(5), dp(6), dp(5)); background = rounded(themeKey(), dp(9)); setOnClickListener { feedback(this); action() }
    }

    private fun buildClipboard(): View {
        val root = root(); val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(pill("← Keyboard") { panel = Panel.KEYBOARD; setInputView(render()) })
        header.addView(pill("Clear") { PhormiKeyboardClipboardStore.clearUnpinned(this); setInputView(render()) })
        root.addView(header, LinearLayout.LayoutParams(-1, scale(40)))
        val scroll = ScrollView(this); val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val items = PhormiKeyboardClipboardStore.list(this)
        if (items.isEmpty()) list.addView(TextView(this).apply { text = "Copy text or images to build your Phormi clipboard history."; setTextColor(themeText()); textSize = 14f; setPadding(dp(12), dp(18), dp(12), dp(18)) })
        items.forEach { item ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; background = rounded(themePill(), dp(10)); setPadding(dp(8), dp(2), dp(8), dp(2)) }
            row.addView(TextView(this).apply { text = item.text; setTextColor(themeText()); textSize = 13f; maxLines = 3 }, LinearLayout.LayoutParams(0, scale(54), 1f))
            row.addView(pill(if (item.pinned) "📌" else "○") { PhormiKeyboardClipboardStore.togglePinned(this@PhormiKeyboardServiceV2, item.text); setInputView(render()) }, LinearLayout.LayoutParams(dp(50), scale(40)))
            row.setOnClickListener { commitTextToEditor(item.text) }; row.setOnLongClickListener { PhormiKeyboardClipboardStore.remove(this, item.text); setInputView(render()); true }
            list.addView(row, LinearLayout.LayoutParams(-1, scale(62)).apply { setMargins(0, dp(3), 0, dp(3)) })
        }
        scroll.addView(list); root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f)); return root
    }

    private fun buildTools(): View {
        val root = root(); root.addView(TextView(this).apply { text = "Phormi Keyboard Tools"; textSize = 18f; typeface = Typeface.DEFAULT_BOLD; setTextColor(themeText()); gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), 0, dp(8), 0) }, LinearLayout.LayoutParams(-1, scale(42)))
        val scroll = ScrollView(this); val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun tool(label: String, action: () -> Unit) { list.addView(keyButton(label, 1f, action), LinearLayout.LayoutParams(-1, scale(48)).apply { setMargins(0, dp(3), 0, dp(3)) }) }
        tool("⚙  Keyboard Settings") { panel = Panel.SETTINGS; setInputView(render()) }
        tool("✂  Select all") { selectAll() }
        tool("⧉  Copy selection") { copySelection() }
        tool("📋  Paste") { pasteClipboard() }
        tool("↔  Cursor movement") { Toast.makeText(this, "Use ← / → or slide on Space", Toast.LENGTH_SHORT).show() }
        tool("GIF & Stickers") { panel = Panel.MEDIA; setInputView(render()) }
        tool("😀  Emoji") { panel = Panel.EMOJI; prepareAiContext(); setInputView(render()) }
        tool("🎙  Voice typing") { startVoice() }
        tool("🌐  Switch keyboard") { runCatching { switchToNextInputMethod(false) } }
        tool("ABC Keyboard") { panel = Panel.KEYBOARD; setInputView(render()) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f)); root.addView(pill("← Back to keyboard") { panel = Panel.KEYBOARD; setInputView(render()) }, LinearLayout.LayoutParams(-1, scale(40))); return root
    }

    private fun buildSettings(): View {
        val root = root(); val scroll = ScrollView(this); val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        list.addView(TextView(this).apply { text = "Phormi Keyboard Settings"; textSize = 19f; typeface = Typeface.DEFAULT_BOLD; setTextColor(themeText()); setPadding(dp(8), dp(4), dp(8), dp(8)) })
        list.addView(TextView(this).apply { text = "These controls belong to the keyboard. They do not change Phormi Browser settings."; textSize = 12f; setTextColor(Color.rgb(148, 163, 184)); setPadding(dp(8), 0, dp(8), dp(8)) })
        val heightLabel = TextView(this).apply { textSize = 13f; setTextColor(themeText()); setPadding(dp(8), dp(4), dp(8), 0) }
        fun heightName(p: Int) = arrayOf("Extra short", "Short", "Compact", "Normal", "Tall", "Extra tall", "Maximum")[p.coerceIn(0, 6)]
        heightLabel.text = "Keyboard size: ${heightName(PhormiKeyboardPreferences.height(this))}"
        list.addView(heightLabel)
        list.addView(SeekBar(this).apply { max = 6; progress = PhormiKeyboardPreferences.height(this@PhormiKeyboardServiceV2); contentDescription = "Keyboard size"; setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { heightLabel.text = "Keyboard size: ${heightName(p)}" }
            override fun onStartTrackingTouch(s: SeekBar?) = Unit
            override fun onStopTrackingTouch(s: SeekBar?) { PhormiKeyboardPreferences.setHeight(this@PhormiKeyboardServiceV2, s?.progress ?: 3); setInputView(render()) }
        }) }, LinearLayout.LayoutParams(-1, scale(48)))
        list.addView(TextView(this).apply { text = "Appearance"; textSize = 16f; setTextColor(themeText()); setPadding(dp(8), dp(8), dp(8), dp(4)) })
        val themes = listOf("Midnight", "Graphite", "Ocean", "Light")
        themes.forEachIndexed { index, name -> list.addView(pill(name) { PhormiKeyboardPreferences.setTheme(this@PhormiKeyboardServiceV2, index); setInputView(render()) }, LinearLayout.LayoutParams(-1, scale(40)).apply { setMargins(dp(4), dp(2), dp(4), dp(2)) }) }
        list.addView(pill("🖼  Keyboard wallpaper…") { startActivity(Intent(this, PhormiKeyboardMediaActivity::class.java).putExtra(PhormiKeyboardMediaActivity.EXTRA_MODE, "wallpaper").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }, LinearLayout.LayoutParams(-1, scale(40)).apply { setMargins(dp(4), dp(2), dp(4), dp(2)) })
        option(list, "Suggestions", "Use local suggestions when an editor does not supply completions.", PhormiKeyboardPreferences.suggestions(this), PhormiKeyboardPreferences.KEY_SUGGESTIONS)
        option(list, "Autocorrect", "Correct conservative common typos locally when Space is pressed.", PhormiKeyboardPreferences.autocorrect(this), PhormiKeyboardPreferences.KEY_AUTOCORRECT)
        option(list, "Auto-capitalization", "Capitalize sentence starts without overriding a manual Caps Lock choice.", PhormiKeyboardPreferences.autoCaps(this), PhormiKeyboardPreferences.KEY_AUTO_CAPS)
        option(list, "AI Emoji", "Create one integrated context-aware reaction from typed text.", PhormiKeyboardPreferences.aiEmoji(this), PhormiKeyboardPreferences.KEY_AI_EMOJI)
        option(list, "Key vibration", "Use device haptic feedback for key presses.", PhormiKeyboardPreferences.haptic(this), PhormiKeyboardPreferences.KEY_HAPTIC)
        option(list, "Key sounds", "Play a short key sound where supported.", PhormiKeyboardPreferences.sound(this), PhormiKeyboardPreferences.KEY_SOUND)
        list.addView(pill("← Back to keyboard") { panel = Panel.KEYBOARD; setInputView(render()) }, LinearLayout.LayoutParams(-1, scale(40)).apply { setMargins(dp(4), dp(6), dp(4), dp(2)) })
        scroll.addView(list); root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f)); return root
    }

    private fun option(list: LinearLayout, title: String, summary: String, checked: Boolean, key: String) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(4), dp(3), dp(4), dp(3)) }
        row.addView(android.widget.CheckBox(this).apply { text = title; isChecked = checked; setTextColor(themeText()); textSize = 15f; setOnCheckedChangeListener { _, value -> PhormiKeyboardPreferences.set(this@PhormiKeyboardServiceV2, key, value) } })
        row.addView(TextView(this).apply { text = summary; setTextColor(Color.rgb(148, 163, 184)); textSize = 11f; setPadding(dp(40), 0, dp(8), dp(3)) })
        list.addView(row)
    }

    private fun buildMedia(): View {
        val root = root(); root.addView(TextView(this).apply { text = "GIF & Stickers"; textSize = 18f; typeface = Typeface.DEFAULT_BOLD; setTextColor(themeText()); gravity = Gravity.CENTER_VERTICAL }, LinearLayout.LayoutParams(-1, scale(42)))
        val scroll = ScrollView(this); val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val packs = PhormiKeyboardStickerPackStore.packs(this); val files = packs.flatMap { PhormiKeyboardStickerPackStore.files(this, it) }.take(12)
        if (files.isEmpty()) list.addView(TextView(this).apply { text = "No imported stickers yet. Use the import buttons below."; setTextColor(themeText()); setPadding(dp(12), dp(14), dp(12), dp(14)) })
        files.forEach { file -> list.addView(emojiImageButton(file, "Sticker") { commitContentToEditor(PhormiKeyboardStickerStore.contentUri(this@PhormiKeyboardServiceV2, file)) }, LinearLayout.LayoutParams(-1, scale(58))) }
        scroll.addView(list); root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(pill("Import GIF / image") { startActivity(Intent(this, PhormiKeyboardMediaActivity::class.java).putExtra(PhormiKeyboardMediaActivity.EXTRA_MODE, "gif").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }, LinearLayout.LayoutParams(-1, scale(40)))
        root.addView(pill("Import sticker") { startActivity(Intent(this, PhormiKeyboardMediaActivity::class.java).putExtra(PhormiKeyboardMediaActivity.EXTRA_MODE, "sticker").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }, LinearLayout.LayoutParams(-1, scale(40)))
        root.addView(pill("✨ Create AI Emoji") { panel = Panel.AI_EMOJI; prepareAiContext(); setInputView(render()) }, LinearLayout.LayoutParams(-1, scale(40)))
        root.addView(pill("← Back") { panel = Panel.KEYBOARD; setInputView(render()) }, LinearLayout.LayoutParams(-1, scale(38)))
        return root
    }

    private fun buildAiEmoji(): View {
        if (isPrivateEditor() || !PhormiKeyboardPreferences.aiEmoji(this)) { panel = Panel.KEYBOARD; return buildKeyboard() }
        val root = root(); root.addView(pill("← Emoji") { panel = Panel.EMOJI; setInputView(render()) }, LinearLayout.LayoutParams(-1, scale(38)))
        root.addView(TextView(this).apply { text = if (aiGenerating) "Creating one integrated reaction…" else "Phormi custom reaction"; textSize = 17f; setTextColor(themeText()); gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-1, scale(42)))
        if (aiFiles.isNotEmpty()) { val file = aiFiles.first(); root.addView(emojiImageButton(file, "Phormi custom reaction") { if (commitContentToEditor(PhormiKeyboardStickerStore.contentUri(this@PhormiKeyboardServiceV2, file))) { aiFiles = aiFiles.drop(1); setInputView(render()) } }, LinearLayout.LayoutParams(-1, scale(100))) }
        val contextText = PhormiKeyboardTextEngine.contextBeforeCursor(currentInputConnection).trim()
        root.addView(pill(if (aiGenerating) "Generating…" else "Generate reaction from typed context") { generateAiEmoji(contextText.ifBlank { "happy positive celebration" }) }, LinearLayout.LayoutParams(-1, scale(42)))
        return root
    }

    private fun generateAiEmoji(text: String) {
        if (aiGenerating) return
        aiGenerating = true; setInputView(render())
        PhormiPollinationsAiEmojiEngine.generateAsync(this, text, 0) { file ->
            aiGenerating = false
            if (file != null) aiFiles = listOf(file) + aiFiles
            setInputView(render())
        }
    }

    private fun actionLabel() = when ((editorInfo?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION) {
        EditorInfo.IME_ACTION_GO -> "Go"; EditorInfo.IME_ACTION_SEARCH -> "Search"; EditorInfo.IME_ACTION_NEXT -> "Next"; EditorInfo.IME_ACTION_DONE -> "Done"; EditorInfo.IME_ACTION_SEND -> "Send"; EditorInfo.IME_ACTION_PREVIOUS -> "Prev"; else -> "↵"
    }

    private fun sendEditorAction() {
        val ic = currentInputConnection ?: return; val action = (editorInfo?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
        runCatching { if (action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) { if (!ic.performEditorAction(action)) sendEnter(ic) } else sendEnter(ic) }
    }
    private fun sendEnter(ic: InputConnection) { ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)); ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER)) }

    private fun commitSpace() {
        val ic = currentInputConnection ?: return
        if (!PhormiKeyboardPreferences.autocorrect(this)) { ic.commitText(" ", 1); updateAutoCaps(); refreshPredictions(); return }
        val rawWord = PhormiKeyboardTextEngine.currentWord(ic)
        runCatching {
            ic.beginBatchEdit()
            if (PhormiKeyboardTextEngine.shouldUsePredictions(editorInfo) && rawWord.isNotBlank()) {
                val locale = PhormiKeyboardTextEngine.localeFor(editorInfo); val corrected = PhormiKeyboardTextEngine.correctionFor(this, rawWord, locale)
                val finalWord = corrected?.let { matchCase(it, rawWord) } ?: rawWord
                if (corrected != null) { ic.deleteSurroundingText(rawWord.length, 0); ic.commitText(finalWord, 1) }
                PhormiKeyboardTextEngine.learn(this, finalWord, editorInfo)
                PhormiKeyboardTextEngine.learnPair(this, PhormiKeyboardTextEngine.previousWord(ic), finalWord, editorInfo)
            }
            val before = ic.getTextBeforeCursor(2, 0)?.toString().orEmpty(); if (before.endsWith("  ")) { ic.deleteSurroundingText(2, 0); ic.commitText(". ", 1) } else ic.commitText(" ", 1)
        }.also { runCatching { ic.endBatchEdit() } }
        updateAutoCaps(); refreshPredictions()
    }

    private fun updateAutoCaps() { if (!capsLock) shift = PhormiKeyboardPreferences.autoCaps(this) && PhormiKeyboardTextEngine.autoCapitalize(currentInputConnection, editorInfo) }
    private fun deleteBackward() {
        val ic = currentInputConnection ?: return
        runCatching {
            ic.beginBatchEdit()
            if (!ic.getSelectedText(0).isNullOrEmpty()) { ic.commitText("", 1); return@runCatching }
            val before = ic.getTextBeforeCursor(128, 0)?.toString().orEmpty(); if (before.isEmpty()) return@runCatching
            val iterator = android.icu.text.BreakIterator.getCharacterInstance().apply { setText(before) }; val boundary = iterator.preceding(before.length); val count = if (boundary >= 0) before.length - boundary else 1; ic.deleteSurroundingText(count, 0)
        }.also { runCatching { ic.endBatchEdit() } }
        refreshPredictions()
    }
    private fun installRepeat(view: View, action: () -> Unit) { view.setOnLongClickListener { stopRepeat(); val r = object : Runnable { override fun run() { if (currentInputConnection == null) { stopRepeat(); return }; action(); repeatHandler.postDelayed(this, 55) } }; repeatRunnable = r; repeatHandler.postDelayed(r, 280); true }; view.setOnTouchListener { _, e -> if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) stopRepeat(); false } }
    private fun stopRepeat() { repeatRunnable?.let { repeatHandler.removeCallbacks(it) }; repeatRunnable = null }
    private fun moveCursor(delta: Int) { val ic = currentInputConnection ?: return; val target = (selectionStart + delta).coerceAtLeast(0); if (ic.setSelection(target, target)) { selectionStart = target; selectionEnd = target } }
    private fun selectAll() { currentInputConnection?.performContextMenuAction(android.R.id.selectAll) }
    private fun copySelection() { currentInputConnection?.performContextMenuAction(android.R.id.copy) }
    private fun pasteClipboard() { currentInputConnection?.performContextMenuAction(android.R.id.paste) }
    private fun refreshPredictions(redraw: Boolean = true) {
        val ic = currentInputConnection ?: return
        if (!PhormiKeyboardTextEngine.shouldUsePredictions(editorInfo) || !PhormiKeyboardPreferences.suggestions(this)) { lastSuggestions = emptyList(); return }
        val prefix = PhormiKeyboardTextEngine.currentWord(ic); lastSuggestions = if (prefix.isBlank()) PhormiKeyboardTextEngine.nextWordSuggestions(this, PhormiKeyboardTextEngine.previousWord(ic)) else PhormiKeyboardTextEngine.suggestions(this, prefix)
        if (redraw && panel == Panel.KEYBOARD) setInputView(render())
    }
    private fun acceptSuggestion(value: String) { val ic = currentInputConnection ?: return; val previous = PhormiKeyboardTextEngine.previousWord(ic); val word = PhormiKeyboardTextEngine.currentWord(ic); if (word.isNotBlank()) ic.deleteSurroundingText(word.length, 0); ic.commitText(value, 1); PhormiKeyboardTextEngine.learn(this, value, editorInfo); PhormiKeyboardTextEngine.learnPair(this, previous, value, editorInfo); updateAutoCaps(); refreshPredictions() }
    private fun matchCase(value: String, original: String) = if (original.all { !it.isLetter() || it.isUpperCase() }) value.uppercase(Locale.getDefault()) else if (original.firstOrNull()?.isUpperCase() == true) value.replaceFirstChar { it.uppercase() } else value
    private fun toggleShift() { if (capsLock) { capsLock = false; shift = false } else if (shift) capsLock = true else shift = true }
    private fun shouldCapitalize() = PhormiKeyboardPreferences.autoCaps(this) && PhormiKeyboardTextEngine.autoCapitalize(currentInputConnection, editorInfo)
    private fun prepareAiContext() { if (!isPrivateEditor() && PhormiKeyboardPreferences.aiEmoji(this)) { val text = PhormiKeyboardTextEngine.contextBeforeCursor(currentInputConnection).trim(); if (text.length >= 3 && !aiGenerating) generateAiEmoji(text) } }
    private fun startVoice() {
        if (isPrivateEditor()) return
        val locale = PhormiKeyboardTextEngine.localeFor(editorInfo).toLanguageTag()
        runCatching { startActivity(Intent(this, PhormiKeyboardVoiceActivity::class.java).putExtra(PhormiKeyboardVoiceActivity.EXTRA_LOCALE, locale).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.onFailure { showToast("Voice input is unavailable") }
    }
    private fun applyPendingInput() { val p = getSharedPreferences(PREFS, MODE_PRIVATE); p.getString(KEY_PENDING_TEXT, null)?.let { p.edit().remove(KEY_PENDING_TEXT).apply(); commitTextToEditor(it) }; p.getString(KEY_PENDING_URI, null)?.let { p.edit().remove(KEY_PENDING_URI).apply(); commitContentToEditor(Uri.parse(it)) } }
    private fun commitTextToEditor(text: String): Boolean { val ok = currentInputConnection?.commitText(text, 1) ?: false; if (ok) refreshPredictions(false); return ok }
    private fun commitContentToEditor(uri: Uri): Boolean { if (Build.VERSION.SDK_INT < 25) return false; val ic = currentInputConnection ?: return false; val mime = contentResolver.getType(uri) ?: "image/*"; val info = InputContentInfo(uri, ClipDescription("Phormi media", arrayOf(mime))); return runCatching { ic.commitContent(info, InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null) }.getOrDefault(false) }
    private fun isPrivateEditor(): Boolean { val info = editorInfo ?: return true; return PhormiKeyboardTextEngine.isPrivateEditor(info) || (info.inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0 }
    private fun showToast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && panel != Panel.KEYBOARD) { panel = Panel.KEYBOARD; setInputView(render()); return true }
        return super.onKeyDown(keyCode, event)
    }
}
