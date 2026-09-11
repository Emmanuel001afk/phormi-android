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
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

/** Production IME surface with a fixed docked viewport. Panels scroll inside that viewport. */
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
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_PENDING_TEXT, text.take(4000)).apply()
            return false
        }
    }

    enum class Panel { KEYBOARD, EMOJI, CLIPBOARD, AI_EMOJI, TOOLS, MEDIA, SETTINGS }

    private var panel = Panel.KEYBOARD
    private var symbols = false
    private var shift = false
    private var capsLock = false
    private var autoShift = false
    private var emojiCategory = 0
    private var editorInfo: EditorInfo? = null
    private var clipboardListener: ClipboardManager.OnPrimaryClipChangedListener? = null
    private var resizeStartY = 0f
    private var resizeStartLevel = 3
    private var resizePreviewLevel = 3
    private var spaceDownX = 0f
    private var spaceMoved = false
    private var aiFiles: List<File> = emptyList()
    private val repeatHandler = Handler(Looper.getMainLooper())
    private var repeatRunnable: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboardListener = ClipboardManager.OnPrimaryClipChangedListener { PhormiKeyboardClipboardStore.capturePrimaryClipboard(this) }
        clipboardListener?.let { cm?.addPrimaryClipChangedListener(it) }
    }

    override fun onDestroy() {
        stopRepeat()
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboardListener?.let { cm?.removePrimaryClipChangedListener(it) }
        clipboardListener = null
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        editorInfo = attribute
        symbols = false
        capsLock = false
        shift = false
        autoShift = PhormiKeyboardPreferences.autoCaps(this) && PhormiKeyboardTextEngine.autoCapitalize(currentInputConnection, attribute)
        panel = Panel.KEYBOARD
        aiFiles = emptyList()
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        editorInfo = info ?: editorInfo
        panel = Panel.KEYBOARD
        setInputView(render())
        applyPendingInput()
    }

    override fun onFinishInput() {
        stopRepeat()
        editorInfo = null
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

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onCreateInputView(): View = render()

    private fun density(): Float = resources.displayMetrics.density
    private fun dp(value: Int): Int = (value * density()).roundToInt().coerceAtLeast(1)
    private fun baseHeight(): Int = 360
    private fun scaled(value: Int, level: Int = PhormiKeyboardPreferences.height(this)): Int = dp((value * PhormiKeyboardPreferences.heightScaleFor(level)).roundToInt())
    private fun theme(): Int = PhormiKeyboardPreferences.theme(this)
    private fun themeBackground(): Int = when (theme()) { 1 -> Color.rgb(20,24,29); 2 -> Color.rgb(7,24,42); 3 -> Color.rgb(242,244,247); else -> Color.rgb(13,18,30) }
    private fun themeKey(): Int = when (theme()) { 1 -> Color.rgb(48,53,61); 2 -> Color.rgb(18,52,79); 3 -> Color.WHITE; else -> Color.rgb(39,48,64) }
    private fun themePill(): Int = when (theme()) { 1 -> Color.rgb(37,42,49); 2 -> Color.rgb(15,45,69); 3 -> Color.rgb(224,228,234); else -> Color.rgb(31,41,55) }
    private fun themeText(): Int = if (theme() == 3) Color.rgb(20,27,36) else Color.WHITE
    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = radius.toFloat() }

    private fun root(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(6), dp(2), dp(6), dp(2))
        setBackgroundColor(themeBackground())
        layoutParams = LinearLayout.LayoutParams(-1, scaled(baseHeight()))
        minimumHeight = scaled(baseHeight())
        applyWallpaper(this)
        addResizeGrip(this)
    }

    private fun applyWallpaper(root: View) {
        val value = PhormiKeyboardPreferences.wallpaperUri(this) ?: return
        runCatching { contentResolver.openInputStream(Uri.parse(value))?.use { BitmapFactory.decodeStream(it) }?.let { root.background = BitmapDrawable(resources, it).apply { alpha = 72 } } }
    }

    private fun feedback(view: View) {
        if (PhormiKeyboardPreferences.haptic(this)) view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
        if (PhormiKeyboardPreferences.sound(this)) view.playSoundEffect(android.view.SoundEffectConstants.CLICK)
    }

    private fun pill(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        textSize = 13f * PhormiKeyboardPreferences.heightScale(this@PhormiKeyboardServiceV2).coerceIn(0.92f, 1.15f)
        setTextColor(themeText())
        typeface = Typeface.DEFAULT_BOLD
        minWidth = 0
        minHeight = 0
        isAllCaps = false
        stateListAnimator = null
        setPadding(dp(7), 0, dp(7), 0)
        background = rounded(themePill(), dp(13))
        contentDescription = label
        setOnClickListener { feedback(this); action() }
    }

    private fun keyButton(label: String, weight: Float = 1f, action: () -> Unit): Button = Button(this).apply {
        text = label
        textSize = (if (label.length == 1) 20f else 12f) * PhormiKeyboardPreferences.heightScale(this@PhormiKeyboardServiceV2).coerceIn(0.92f, 1.16f)
        setTextColor(themeText())
        minWidth = 0
        minHeight = 0
        isAllCaps = false
        stateListAnimator = null
        setPadding(dp(2), 0, dp(2), 0)
        background = rounded(themeKey(), dp(9))
        contentDescription = label
        setOnClickListener { feedback(this); action() }
        layoutParams = LinearLayout.LayoutParams(0, scaled(46), weight).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) }
    }

    private fun addResizeGrip(root: LinearLayout) {
        val grip = TextView(this).apply {
            text = "↕"
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(if (theme() == 3) Color.DKGRAY else Color.rgb(148,163,184))
            background = rounded(themePill(), dp(10))
            contentDescription = "Resize Phormi Keyboard"
            isClickable = true
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        resizeStartY = event.rawY
                        resizeStartLevel = PhormiKeyboardPreferences.height(this@PhormiKeyboardServiceV2)
                        resizePreviewLevel = resizeStartLevel
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val delta = ((resizeStartY - event.rawY) / (26f * density())).roundToInt()
                        resizePreviewLevel = (resizeStartLevel + delta).coerceIn(0,6)
                        root.layoutParams = (root.layoutParams ?: LinearLayout.LayoutParams(-1, scaled(baseHeight(), resizePreviewLevel))).apply { height = scaled(baseHeight(), resizePreviewLevel) }
                        root.minimumHeight = scaled(baseHeight(), resizePreviewLevel)
                        root.requestLayout()
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        PhormiKeyboardPreferences.setHeight(this@PhormiKeyboardServiceV2, resizePreviewLevel)
                        setInputView(render())
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        setInputView(render())
                        true
                    }
                    else -> true
                }
            }
        }
        root.addView(grip, 0, LinearLayout.LayoutParams(dp(54), dp(26)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(2) })
    }

    private fun toolbar(root: LinearLayout) {
        val scroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        fun add(label: String, width: Int = 58, action: () -> Unit) { row.addView(pill(label, action), LinearLayout.LayoutParams(dp(width), scaled(36)).apply { setMargins(dp(2),0,dp(2),0) }) }
        add("😀") { panel = Panel.EMOJI; setInputView(render()) }
        if (PhormiKeyboardTextEngine.allowsAiEmoji(editorInfo) && PhormiKeyboardPreferences.aiEmoji(this)) add("✨",52) { panel = Panel.AI_EMOJI; setInputView(render()) }
        add("📋") { panel = Panel.CLIPBOARD; PhormiKeyboardClipboardStore.capturePrimaryClipboard(this); setInputView(render()) }
        add("▦ Tools",70) { panel = Panel.TOOLS; setInputView(render()) }
        add("GIF",52) { panel = Panel.MEDIA; setInputView(render()) }
        add("Sticker",68) { panel = Panel.MEDIA; setInputView(render()) }
        if (Build.VERSION.SDK_INT >= 28 && shouldOfferSwitchingToNextInputMethod()) add("🌐",52) { runCatching { switchToNextInputMethod(false) } }
        scroll.addView(row)
        root.addView(scroll, LinearLayout.LayoutParams(-1, scaled(40)))
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
        val email = variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS || variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL
        val uri = variation == InputType.TYPE_TEXT_VARIATION_URI
        when {
            phone -> addRows(root, listOf("1234567890", "*#+-", "()."))
            number -> addRows(root, listOf("1234567890", "789456123", "0.,+-"))
            dateTime -> addRows(root, listOf("1234567890", "4567891230", ":/-"))
            symbols -> addRows(root, listOf("1234567890", "-=[]\\;',./", "!@#\$%^&*()", "_+{}|:\"<>?", "€£₦¥₹₽₩₺₴₫₱₪¢‰§±×÷≤≥≠≈"))
            else -> addRows(root, listOf("qwertyuiop", "asdfghjkl", "zxcvbnm"))
        }
        if (email || uri) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
            val tokens = if (email) listOf("@", ".com", ".net", ".org") else listOf("/", ".com", "https://", ".org")
            tokens.forEach { token -> row.addView(keyButton(token) { commitTextToEditor(token) }) }
            root.addView(row, LinearLayout.LayoutParams(-1, scaled(50)))
        }
        val bottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        if (!number && !phone && !dateTime) {
            bottom.addView(keyButton(if (symbols) "ABC" else "123") { symbols = !symbols; setInputView(render()) })
            bottom.addView(keyButton(if (capsLock) "⇧" else if (shift) "⇧·" else if (autoShift) "⇧A" else "⇧") { toggleShift(); setInputView(render()) })
        }
        val space = keyButton("Space", 3.25f) { commitSpace() }
        space.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { spaceDownX = event.x; spaceMoved = false }
                MotionEvent.ACTION_MOVE -> if (!spaceMoved && abs(event.x - spaceDownX) > dp(28)) { spaceMoved = true; moveCursor(if (event.x > spaceDownX) 1 else -1) }
                MotionEvent.ACTION_UP -> if (!spaceMoved) commitSpace()
                MotionEvent.ACTION_CANCEL -> spaceMoved = false
            }
            true
        }
        bottom.addView(space)
        bottom.addView(keyButton("←") { moveCursor(-1) })
        bottom.addView(keyButton("→") { moveCursor(1) })
        val back = keyButton("⌫") { deleteBackward() }
        installRepeat(back) { deleteBackward() }
        bottom.addView(back)
        bottom.addView(keyButton(actionLabel()) { sendEditorAction() })
        root.addView(bottom, LinearLayout.LayoutParams(-1, scaled(52)))
        return root
    }

    private fun addRows(root: LinearLayout, rows: List<String>) {
        rows.forEach { chars ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
            chars.forEach { ch -> row.addView(keyButton(ch.toString()) {
                val out = if (shift || capsLock || autoShift) ch.uppercaseChar().toString() else ch.toString()
                commitTextToEditor(out)
                if (shift && !capsLock) shift = false
                autoShift = false
            }) }
            root.addView(row, LinearLayout.LayoutParams(-1, scaled(50)))
        }
    }

    private fun buildEmoji(): View {
        val root = root()
        val nav = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val categories = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        PhormiKeyboardEmoji.categories.keys.forEachIndexed { index, icon ->
            categories.addView(pill(icon) { emojiCategory = index; setInputView(render()) }, LinearLayout.LayoutParams(dp(48), scaled(36)).apply { setMargins(dp(2),0,dp(2),0) })
        }
        categories.addView(pill("ABC") { panel = Panel.KEYBOARD; setInputView(render()) }, LinearLayout.LayoutParams(dp(58), scaled(36)))
        nav.addView(categories)
        root.addView(nav, LinearLayout.LayoutParams(-1, scaled(40)))
        val scroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = true }
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        PhormiKeyboardEmoji.categories.values.elementAtOrNull(emojiCategory).orEmpty().chunked(8).forEach { group ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
            group.forEach { emoji -> row.addView(keyButton(emoji) { commitTextToEditor(emoji) }, LinearLayout.LayoutParams(0, scaled(46), 1f)) }
            repeat(8 - group.size) { row.addView(View(this), LinearLayout.LayoutParams(0, scaled(46), 1f)) }
            grid.addView(row, LinearLayout.LayoutParams(-1, scaled(48)))
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
        root.addView(header, LinearLayout.LayoutParams(-1, scaled(40)))
        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val items = PhormiKeyboardClipboardStore.list(this)
        if (items.isEmpty()) list.addView(TextView(this).apply { text = "Your copied text, screenshots and images will appear here."; setTextColor(themeText()); textSize = 14f; setPadding(dp(12),dp(18),dp(12),dp(18)) })
        items.forEach { item ->
            val text = item.label
            list.addView(pill(text.take(60)) { if (item.text != null) commitTextToEditor(item.text); else item.uri?.let { commitContentToEditor(Uri.parse(it)) } }, LinearLayout.LayoutParams(-1, scaled(44)))
        }
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        return root
    }

    private fun buildAiEmoji(): View {
        val root = root()
        root.addView(pill("← Keyboard") { panel = Panel.KEYBOARD; setInputView(render()) }, LinearLayout.LayoutParams(-1, scaled(40)))
        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        list.addView(TextView(this).apply { text = "Phormi custom reaction"; textSize = 18f; setTextColor(themeText()); gravity = Gravity.CENTER; setPadding(0,dp(16),0,dp(8)) })
        list.addView(TextView(this).apply { text = "Generate reaction from typed context"; textSize = 14f; setTextColor(themeText()); gravity = Gravity.CENTER; setPadding(dp(12),0,dp(12),dp(12)) })
        list.addView(pill("Generate", action = { aiFiles = PhormiKeyboardMediaActivity.generateAiEmoji(this, currentInputConnection); setInputView(render()) }), LinearLayout.LayoutParams(-1, scaled(44)))
        aiFiles.forEach { file ->
            val iv = ImageView(this).apply { adjustViewBounds = true; setImageURI(Uri.fromFile(file)) }
            list.addView(iv, LinearLayout.LayoutParams(-1, scaled(110)).apply { setMargins(dp(12),dp(6),dp(12),dp(6)) })
            list.addView(pill("Insert", action = { commitContentToEditor(Uri.fromFile(file)) }), LinearLayout.LayoutParams(-1, scaled(40)))
        }
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        return root
    }

    private fun buildMedia(): View {
        val root = root()
        val mode = PhormiKeyboardMediaActivity.EXTRA_MODE
        val back = pill("← Keyboard") { panel = Panel.KEYBOARD; setInputView(render()) }
        root.addView(back, LinearLayout.LayoutParams(-1, scaled(40)))
        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        list.addView(pill("Import GIF / Image") { launchMedia("gif") }, LinearLayout.LayoutParams(-1, scaled(46)))
        list.addView(pill("Import Sticker") { launchMedia("sticker") }, LinearLayout.LayoutParams(-1, scaled(46)))
        list.addView(pill("Create AI Emoji") { panel = Panel.AI_EMOJI; setInputView(render()) }, LinearLayout.LayoutParams(-1, scaled(46)))
        list.addView(TextView(this).apply { text = "Use media-capable text fields to insert images directly."; setTextColor(themeText()); textSize = 13f; setPadding(dp(12),dp(12),dp(12),dp(12)) })
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        return root
    }

    private fun buildTools(): View {
        val root = root()
        root.addView(pill("← Keyboard") { panel = Panel.KEYBOARD; setInputView(render()) }, LinearLayout.LayoutParams(-1, scaled(40)))
        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        list.addView(pill("Settings") { panel = Panel.SETTINGS; setInputView(render()) }, LinearLayout.LayoutParams(-1, scaled(46)))
        list.addView(pill("Voice typing") { launchVoice() }, LinearLayout.LayoutParams(-1, scaled(46)))
        list.addView(pill("Select all") { currentInputConnection?.performContextMenuAction(android.R.id.selectAll) }, LinearLayout.LayoutParams(-1, scaled(46)))
        list.addView(pill("Copy") { currentInputConnection?.performContextMenuAction(android.R.id.copy) }, LinearLayout.LayoutParams(-1, scaled(46)))
        list.addView(pill("Paste") { currentInputConnection?.performContextMenuAction(android.R.id.paste) }, LinearLayout.LayoutParams(-1, scaled(46)))
        list.addView(pill("Share text") { shareSelectedText() }, LinearLayout.LayoutParams(-1, scaled(46)))
        list.addView(TextView(this).apply { text = "Tools work inside the same fixed keyboard viewport."; setTextColor(themeText()); textSize = 13f; setPadding(dp(12),dp(16),dp(12),dp(16)) })
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        return root
    }

    private fun buildSettings(): View {
        val root = root()
        root.addView(pill("← Tools") { panel = Panel.TOOLS; setInputView(render()) }, LinearLayout.LayoutParams(-1, scaled(40)))
        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10),dp(8),dp(10),dp(8)) }
        val current = PhormiKeyboardPreferences.height(this)
        list.addView(TextView(this).apply { text = "Keyboard size"; textSize = 16f; setTextColor(themeText()); setPadding(0,dp(6),0,dp(6)) })
        val labels = listOf("85%", "92%", "97%", "100%", "108%", "117%", "127%")
        labels.forEachIndexed { index, label -> list.addView(pill(if (index == current) "✓ $label" else label) { PhormiKeyboardPreferences.setHeight(this@PhormiKeyboardServiceV2, index); setInputView(render()) }, LinearLayout.LayoutParams(-1, scaled(40)).apply { setMargins(0,dp(2),0,dp(2)) }) }
        val toggle = fun(title: String, enabled: Boolean, action: () -> Unit) { list.addView(pill(if (enabled) "✓ $title" else title, action), LinearLayout.LayoutParams(-1, scaled(42)).apply { setMargins(0,dp(3),0,dp(3)) }) }
        toggle("Suggestions", PhormiKeyboardPreferences.suggestions(this)) { PhormiKeyboardPreferences.set("suggestions", !PhormiKeyboardPreferences.suggestions(this)); setInputView(render()) }
        toggle("Autocorrect", PhormiKeyboardPreferences.autocorrect(this)) { PhormiKeyboardPreferences.set("autocorrect", !PhormiKeyboardPreferences.autocorrect(this)); setInputView(render()) }
        toggle("Haptic feedback", PhormiKeyboardPreferences.haptic(this)) { PhormiKeyboardPreferences.set("haptic", !PhormiKeyboardPreferences.haptic(this)); setInputView(render()) }
        toggle("Key sounds", PhormiKeyboardPreferences.sound(this)) { PhormiKeyboardPreferences.set("sound", !PhormiKeyboardPreferences.sound(this)); setInputView(render()) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        return root
    }

    private fun render(): View = when (panel) {
        Panel.EMOJI -> buildEmoji()
        Panel.CLIPBOARD -> buildClipboard()
        Panel.AI_EMOJI -> buildAiEmoji()
        Panel.TOOLS -> buildTools()
        Panel.MEDIA -> buildMedia()
        Panel.SETTINGS -> buildSettings()
        Panel.KEYBOARD -> buildKeyboard()
    }

    private fun launchMedia(mode: String) {
        startActivity(Intent(this, PhormiKeyboardMediaActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(PhormiKeyboardMediaActivity.EXTRA_MODE, mode)
        })
    }

    private fun launchVoice() {
        startActivity(Intent(this, PhormiKeyboardVoiceActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun shareSelectedText() {
        val text = currentInputConnection?.getSelectedText(0)?.toString().orEmpty()
        if (text.isBlank()) { Toast.makeText(this, "Select text first", Toast.LENGTH_SHORT).show(); return }
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun applyPendingInput() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        prefs.getString(KEY_PENDING_TEXT, null)?.takeIf { it.isNotBlank() }?.let { commitTextToEditor(it) }
        prefs.edit().remove(KEY_PENDING_TEXT).apply()
        prefs.getString(KEY_PENDING_URI, null)?.let { runCatching { commitContentToEditor(Uri.parse(it)) } }
        prefs.edit().remove(KEY_PENDING_URI).apply()
    }

    private fun commitTextToEditor(text: String) {
        val ic = currentInputConnection ?: return
        ic.commitText(text, 1)
    }

    private fun commitSpace() {
        val ic = currentInputConnection ?: return
        ic.commitText(" ", 1)
        if (PhormiKeyboardPreferences.autoCaps(this)) autoShift = PhormiKeyboardTextEngine.autoCapitalize(ic, editorInfo)
    }

    private fun deleteBackward() {
        val ic = currentInputConnection ?: return
        val selected = ic.getSelectedText(0)?.toString()
        if (!selected.isNullOrEmpty()) ic.commitText("", 1) else ic.deleteSurroundingText(1, 0)
    }

    private fun moveCursor(delta: Int) { currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, if (delta < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT)); currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, if (delta < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT)) }

    private fun toggleShift() {
        if (capsLock) { capsLock = false; shift = false; return }
        if (shift) { capsLock = true; shift = false } else shift = true
    }

    private fun actionLabel(): String {
        return when (editorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION)) {
            EditorInfo.IME_ACTION_GO -> "Go"
            EditorInfo.IME_ACTION_SEARCH -> "Search"
            EditorInfo.IME_ACTION_SEND -> "Send"
            EditorInfo.IME_ACTION_NEXT -> "Next"
            EditorInfo.IME_ACTION_DONE -> "Done"
            else -> "Enter"
        }
    }

    private fun sendEditorAction() {
        val ic = currentInputConnection ?: return
        val action = editorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_NONE
        if (action != EditorInfo.IME_ACTION_NONE) ic.performEditorAction(action) else ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)).also { ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER)) }
    }

    private fun installRepeat(view: View, action: () -> Unit) {
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    action()
                    repeatRunnable = object : Runnable { override fun run() { action(); repeatHandler.postDelayed(this, 55) } }
                    repeatHandler.postDelayed(repeatRunnable!!, 320)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { stopRepeat(); true }
                else -> true
            }
        }
    }

    private fun stopRepeat() { repeatRunnable?.let { repeatHandler.removeCallbacks(it) }; repeatRunnable = null }

    private fun commitContentToEditor(uri: Uri): Boolean {
        if (Build.VERSION.SDK_INT < 25) return false
        val ic = currentInputConnection ?: return false
        val mime = contentResolver.getType(uri) ?: "image/*"
        val accepted = editorInfo?.contentMimeTypes.orEmpty()
        if (accepted.none { it == "*/*" || it == mime || (it.endsWith("/*") && mime.substringBefore('/') == it.substringBefore('/')) }) {
            Toast.makeText(this, "This text field does not accept images/GIFs", Toast.LENGTH_SHORT).show()
            return false
        }
        return runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.fold(
            onSuccess = { commitContentInternal(ic, uri, mime) },
            onFailure = { commitContentInternal(ic, uri, mime) }
        )
    }

    private fun commitContentInternal(ic: InputConnection, uri: Uri, mime: String): Boolean {
        return runCatching {
            val description = ClipDescription("Phormi media", arrayOf(mime))
            val info = InputContentInfo(uri, description, null)
            ic.commitContent(info, InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null)
        }.getOrDefault(false)
    }
}
