package com.uong.phormi

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.inputmethodservice.InputMethodService
import android.net.Uri
import android.os.Bundle
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
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * System-wide Phormi IME.
 *
 * The service deliberately treats InputConnection as ephemeral: every editor
 * operation obtains the current connection and safely no-ops when the target
 * editor has gone away. This is important when an app, WebView, dialog or
 * activity is being replaced while the keyboard is still visible.
 */
class PhormiKeyboardService : InputMethodService() {
    companion object {
        const val ACTION_COMMIT_TEXT = "com.uong.phormi.keyboard.COMMIT_TEXT"
        const val EXTRA_TEXT = "text"
        private var instance: PhormiKeyboardService? = null

        fun commitPickedContent(context: Context, uri: Uri): Boolean = instance?.commitContent(uri) ?: false
    }

    private var shift = false
    private var capsLock = false
    private var symbols = false
    private var emojiCategory = 0
    private var panel = Panel.KEYBOARD
    private var editorInfo: EditorInfo? = null
    private var selectionStart = 0
    private var selectionEnd = 0
    private var completions: List<CompletionInfo> = emptyList()
    private var clipboardListener: ClipboardManager.OnPrimaryClipChangedListener? = null
    private var repeatRunnable: Runnable? = null
    private val repeatHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var lastSpaceDownX = 0f
    private var lastSpaceDownAt = 0L
    private var suppressSpaceCommit = false
    private var spaceSwipeMoved = false

    private enum class Panel { KEYBOARD, CLIPBOARD, EMOJI }

    override fun onCreate() {
        super.onCreate()
        instance = this
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
            PhormiKeyboardClipboardStore.capturePrimaryClipboard(this)
        }
        cm.addPrimaryClipChangedListener(clipboardListener)
    }

    override fun onDestroy() {
        stopRepeat()
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboardListener?.let(cm::removePrimaryClipChangedListener)
        clipboardListener = null
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        editorInfo = attribute
        selectionStart = 0
        selectionEnd = 0
        shift = false
        capsLock = false
        symbols = false
        panel = Panel.KEYBOARD
        completions = emptyList()
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        editorInfo = info ?: editorInfo
        stopRepeat()
        panel = Panel.KEYBOARD
        setInputView(render())
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        stopRepeat()
        super.onFinishInputView(finishingInput)
    }

    override fun onUnbindInput() {
        stopRepeat()
        editorInfo = null
        completions = emptyList()
        super.onUnbindInput()
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        selectionStart = newSelStart
        selectionEnd = newSelEnd
    }

    override fun onDisplayCompletions(completions: Array<out CompletionInfo>?) {
        super.onDisplayCompletions(completions)
        this.completions = completions?.filter { !it.text.isNullOrBlank() }?.take(5).orEmpty()
        if (panel == Panel.KEYBOARD && inputView != null) setInputView(render())
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onInitializeInterface() {
        super.onInitializeInterface()
        // InputMethodService can keep the IME instance alive across orientation,
        // density, and window-size changes. Rebuild the view against the new metrics.
        stopRepeat()
    }

    override fun onFinishInput() {
        stopRepeat()
        editorInfo = null
        completions = emptyList()
        super.onFinishInput()
    }

    override fun onCreateInputView(): View = render()

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (panel != Panel.KEYBOARD) {
                panel = Panel.KEYBOARD
                setInputView(render())
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_COMMIT_TEXT) intent.getStringExtra(EXTRA_TEXT)?.let(::commitText)
        return START_NOT_STICKY
    }

    private fun render(): View = when (panel) {
        Panel.KEYBOARD -> buildKeyboard()
        Panel.CLIPBOARD -> buildClipboard()
        Panel.EMOJI -> buildEmoji()
    }

    private fun baseRoot(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(4, 4, 4, 4)
        setBackgroundColor(Color.rgb(17, 24, 39))
        isFocusable = true
        isFocusableInTouchMode = true
    }

    private fun toolbar(root: LinearLayout) {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        key(bar, "😀", action = { panel = Panel.EMOJI; setInputView(render()) })
        key(bar, "GIF", action = { openMedia("gif") })
        key(bar, "Sticker", action = { openMedia("sticker") })
        key(bar, "📋", action = {
            PhormiKeyboardClipboardStore.capturePrimaryClipboard(this)
            panel = Panel.CLIPBOARD
            setInputView(render())
        })
        key(bar, "⌨", action = { panel = Panel.KEYBOARD; setInputView(render()) })
        key(bar, "⇄", action = {
            runCatching { switchToNextInputMethod(false) }
                .onFailure { Toast.makeText(this, "No alternate keyboard available", Toast.LENGTH_SHORT).show() }
        })
        key(bar, "↵", action = { sendEditorAction() })
        root.addView(bar, LinearLayout.LayoutParams(-1, 46))
        if (!isPasswordField()) addCompletions(root)
    }

    private fun addCompletions(root: LinearLayout) {
        if (completions.isEmpty()) return
        val row = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val inner = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        completions.forEach { completion ->
            val text = completion.text?.toString().orEmpty()
            if (text.isNotBlank()) key(inner, text) { commitText(text) }
        }
        row.addView(inner)
        root.addView(row, LinearLayout.LayoutParams(-1, 42))
    }

    private fun buildKeyboard(): View {
        val root = baseRoot()
        val type = editorInfo?.inputType ?: InputType.TYPE_CLASS_TEXT
        val clazz = type and InputType.TYPE_MASK_CLASS
        val variation = type and InputType.TYPE_MASK_VARIATION
        val numeric = clazz == InputType.TYPE_CLASS_NUMBER || clazz == InputType.TYPE_CLASS_PHONE
        val datetime = clazz == InputType.TYPE_CLASS_DATETIME
        val email = variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
        val uri = variation == InputType.TYPE_TEXT_VARIATION_URI ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT
        val password = variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD

        // Password fields intentionally expose no completion/suggestion strip.
        if (password) completions = emptyList()
        toolbar(root)

        if (numeric) {
            listOf("123", "456", "789", "0.,+-").forEach { root.addView(charRow(it)) }
        } else if (datetime) {
            listOf("1234567890", ":-/", "AMPM").forEach { root.addView(charRow(it)) }
        } else if (symbols) {
            listOf("1234567890", "-=[]\\;',./", "!@#\$%^&*()", "_+{}|:\"<>?").forEach { root.addView(charRow(it)) }
        } else {
            listOf("qwertyuiop", "asdfghjkl", "zxcvbnm").forEach { root.addView(charRow(it)) }
        }

        val contextual = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        when {
            email -> listOf("@", ".com", ".net").forEach { token -> key(contextual, token) { commitText(token) } }
            uri -> listOf("/", ".com", "https://").forEach { token -> key(contextual, token) { commitText(token) } }
            numeric || datetime -> listOf(".", ",", "-").forEach { token -> key(contextual, token) { commitText(token) } }
        }
        if (contextual.childCount > 0) root.addView(contextual, LinearLayout.LayoutParams(-1, 48))

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        if (!numeric) {
            key(actions, if (symbols) "ABC" else "123") {
                symbols = !symbols
                setInputView(render())
            }
            key(actions, if (capsLock) "⇧·" else "⇧") {
                if (shift && !capsLock) capsLock = true else shift = !shift
                if (!shift && !capsLock) shift = true
                if (capsLock) shift = false
                setInputView(render())
            }
        }

        // Standard editor operations remain available even when an app's own
        // selection toolbar is difficult to reach on a small screen.
        key(actions, "Sel") { selectAll() }
        key(actions, "Copy") { copySelection() }
        key(actions, "Paste") { pasteClipboard() }

        val space = key(actions, "Space", if (numeric) 2.2f else 3.0f) { commitSpace() }
        space.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastSpaceDownX = event.x
                    lastSpaceDownAt = System.currentTimeMillis()
                    suppressSpaceCommit = false
                    spaceSwipeMoved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - lastSpaceDownX
                    if (!spaceSwipeMoved && kotlin.math.abs(dx) > 45f) {
                        suppressSpaceCommit = true
                        spaceSwipeMoved = true
                        moveCursor(if (dx > 0) 1 else -1)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (!suppressSpaceCommit && System.currentTimeMillis() - lastSpaceDownAt < 650L) commitSpace()
                    suppressSpaceCommit = false
                    spaceSwipeMoved = false
                }
                MotionEvent.ACTION_CANCEL -> {
                    suppressSpaceCommit = false
                    spaceSwipeMoved = false
                }
            }
            true
        }

        key(actions, "←") { moveCursor(-1) }
        key(actions, "→") { moveCursor(1) }
        val backspace = key(actions, "⌫") { deleteBackward() }
        installRepeat(backspace) { deleteBackward() }
        key(actions, "↵") { sendEditorAction() }
        key(actions, "🎙") {
            startActivity(Intent(this, PhormiKeyboardVoiceActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        root.addView(actions, LinearLayout.LayoutParams(-1, 54))
        return root
    }

    private fun charRow(chars: String): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        chars.forEach { c ->
            val text = if (c.isLetter() && (shift || capsLock)) c.uppercaseChar().toString() else c.toString()
            key(row, text) {
                commitText(text)
                if (shift && !capsLock) {
                    shift = false
                    setInputView(render())
                }
            }
        }
        return row
    }

    private fun buildEmoji(): View {
        val root = baseRoot()
        val nav = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val cats = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        PhormiKeyboardEmoji.categories.keys.forEachIndexed { index, icon ->
            key(cats, icon) { emojiCategory = index; setInputView(render()) }
        }
        key(cats, "ABC") { panel = Panel.KEYBOARD; setInputView(render()) }
        nav.addView(cats)
        root.addView(nav, LinearLayout.LayoutParams(-1, 48))
        val scroll = ScrollView(this)
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        PhormiKeyboardEmoji.categories.values.elementAtOrNull(emojiCategory).orEmpty().chunked(8).forEach { group ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            group.forEach { emoji -> key(row, emoji) { commitText(emoji) } }
            grid.addView(row, LinearLayout.LayoutParams(-1, 50))
        }
        scroll.addView(grid)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        return root
    }

    private fun buildClipboard(): View {
        val root = baseRoot()
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        key(header, "← Keyboard") { panel = Panel.KEYBOARD; setInputView(render()) }
        key(header, "Clear") { PhormiKeyboardClipboardStore.clearUnpinned(this); setInputView(render()) }
        root.addView(header, LinearLayout.LayoutParams(-1, 48))
        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val items = PhormiKeyboardClipboardStore.list(this)
        if (items.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "No clipboard history yet. Copy text normally to add it."
                setTextColor(Color.WHITE)
                setPadding(12, 20, 12, 20)
            })
        }
        items.forEach { item ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val preview = TextView(this).apply {
                text = item.text
                setTextColor(Color.WHITE)
                textSize = 13f
                maxLines = 3
                setPadding(12, 10, 8, 10)
                contentDescription = item.text.take(120)
            }
            row.addView(preview, LinearLayout.LayoutParams(0, -2, 1f))
            val pin = Button(this).apply {
                text = if (item.pinned) "📌" else "○"
                contentDescription = if (item.pinned) "Unpin clipboard item" else "Pin clipboard item"
                setOnClickListener {
                    PhormiKeyboardClipboardStore.togglePinned(this@PhormiKeyboardService, item.text)
                    setInputView(render())
                }
            }
            row.addView(pin, LinearLayout.LayoutParams(52, 52))
            row.setOnClickListener { commitText(item.text) }
            row.setOnLongClickListener {
                PhormiKeyboardClipboardStore.remove(this, item.text)
                setInputView(render())
                true
            }
            list.addView(row)
        }
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        return root
    }

    private fun key(
        row: LinearLayout,
        label: String,
        weight: Float = 1f,
        action: () -> Unit
    ): Button = Button(this).apply {
        text = label
        minWidth = 0
        minHeight = 0
        setPadding(2, 0, 2, 0)
        isAllCaps = false
        contentDescription = label
        setOnClickListener { action() }
        row.addView(this, LinearLayout.LayoutParams(0, 50, weight).apply { setMargins(2, 2, 2, 2) })
    }

    private fun installRepeat(button: View, action: () -> Unit) {
        button.setOnLongClickListener {
            stopRepeat()
            val runnable = object : Runnable {
                override fun run() {
                    if (currentInputConnection == null) { stopRepeat(); return }
                    action()
                    repeatHandler.postDelayed(this, 65L)
                }
            }
            repeatRunnable = runnable
            repeatHandler.postDelayed(runnable, 280L)
            true
        }
        button.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) stopRepeat()
            false
        }
    }

    private fun stopRepeat() {
        repeatRunnable?.let(repeatHandler::removeCallbacks)
        repeatRunnable = null
    }

    private fun commitText(text: String) {
        val ic = currentInputConnection ?: return
        runCatching { ic.commitText(text, 1) }
    }

    private fun commitSpace() {
        val ic = currentInputConnection ?: return
        runCatching {
            ic.beginBatchEdit()
            val before = ic.getTextBeforeCursor(2, 0)?.toString().orEmpty()
            if (before.endsWith("  ")) {
                ic.deleteSurroundingText(2, 0)
                ic.commitText(". ", 1)
            } else {
                ic.commitText(" ", 1)
            }
        }.also { runCatching { ic.endBatchEdit() } }
    }

    private fun deleteBackward() {
        val ic = currentInputConnection ?: return
        runCatching {
            ic.beginBatchEdit()
            val selected = ic.getSelectedText(0)
            if (!selected.isNullOrEmpty()) {
                ic.commitText("", 1)
                return
            }
            if (android.os.Build.VERSION.SDK_INT >= 24) {
                val before = ic.getTextBeforeCursor(2, 0)?.toString().orEmpty()
                if (before.isNotEmpty()) {
                    val cp = before.codePointBefore(before.length)
                    ic.deleteSurroundingTextInCodePoints(1, 0)
                    return
                }
            }
            if (!deletePreviousCodePoint(ic)) ic.deleteSurroundingText(1, 0)
        }.also { runCatching { ic.endBatchEdit() } }
    }

    private fun deletePreviousCodePoint(ic: InputConnection): Boolean {
        val before = ic.getTextBeforeCursor(2, 0)?.toString().orEmpty()
        if (before.isEmpty()) return false
        val cp = before.codePointBefore(before.length)
        val chars = Character.charCount(cp)
        ic.deleteSurroundingText(chars, 0)
        return true
    }

    private fun deleteWordBackward() {
        val ic = currentInputConnection ?: return
        runCatching {
            if (!ic.getSelectedText(0).isNullOrEmpty()) {
                ic.commitText("", 1)
                return
            }
            val before = ic.getTextBeforeCursor(256, 0)?.toString().orEmpty()
            if (before.isEmpty()) return
            var end = before.length
            while (end > 0 && before[end - 1].isWhitespace()) end--
            while (end > 0 && !before[end - 1].isWhitespace()) end--
            val count = before.length - end
            if (count > 0) ic.deleteSurroundingText(count, 0)
        }
    }

    private fun deleteWordForward() {
        val ic = currentInputConnection ?: return
        runCatching {
            val after = ic.getTextAfterCursor(256, 0)?.toString().orEmpty()
            if (after.isEmpty()) return
            var end = 0
            while (end < after.length && after[end].isWhitespace()) end++
            while (end < after.length && !after[end].isWhitespace()) end++
            if (end > 0) ic.deleteSurroundingText(0, end)
        }
    }

    private fun moveCursor(delta: Int) {
        val ic = currentInputConnection ?: return
        runCatching {
            val before = ic.getTextBeforeCursor(2048, 0)?.toString().orEmpty()
            val after = ic.getTextAfterCursor(2048, 0)?.toString().orEmpty()
            if (selectionStart != selectionEnd) {
                val collapse = if (delta < 0) minOf(selectionStart, selectionEnd) else maxOf(selectionStart, selectionEnd)
                if (ic.setSelection(collapse, collapse)) {
                    selectionStart = collapse
                    selectionEnd = collapse
                    return
                }
            }
            val base = selectionStart.coerceAtLeast(0)
            val target = if (delta < 0) (base - 1).coerceAtLeast(0) else (base + 1).coerceAtMost(base + after.length)
            if (!ic.setSelection(target, target)) {
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, if (delta < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT))
            }
            selectionStart = target
            selectionEnd = target
        }
    }

    private fun sendEditorAction() {
        val ic = currentInputConnection ?: return
        val info = editorInfo ?: currentInputEditorInfo
        val action = info?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_NONE
        runCatching {
            if (action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
                if (!ic.performEditorAction(action)) ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            } else {
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
            }
        }
    }

    private fun isPasswordField(): Boolean {
        val type = editorInfo?.inputType ?: 0
        val variation = type and InputType.TYPE_MASK_VARIATION
        return variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
    }

    private fun selectAll() {
        val ic = currentInputConnection ?: return
        runCatching {
            if (!ic.performContextMenuAction(android.R.id.selectAll)) {
                val text = ic.getTextBeforeCursor(100000, 0)?.length ?: 0
                val after = ic.getTextAfterCursor(100000, 0)?.length ?: 0
                ic.setSelection(0, text + after)
            }
        }
    }

    private fun copySelection() {
        val ic = currentInputConnection ?: return
        runCatching { ic.performContextMenuAction(android.R.id.copy) }
    }

    private fun pasteClipboard() {
        val ic = currentInputConnection ?: return
        runCatching { ic.performContextMenuAction(android.R.id.paste) }
    }

    private fun openMedia(mode: String) = startActivity(
        Intent(this, PhormiKeyboardMediaActivity::class.java)
            .putExtra(PhormiKeyboardMediaActivity.EXTRA_MODE, mode)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )

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
            val info = InputContentInfo(uri, ClipDescription("Phormi media", arrayOf(mime)), null)
            val flags = InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION
            if (runCatching { ic.commitContent(info, flags, Bundle()) }.getOrDefault(false)) return true
        }
        runCatching {
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(
                android.content.ClipData.newRawUri("Phormi media", uri)
            )
        }
        Toast.makeText(this, "Media copied to the system clipboard for paste.", Toast.LENGTH_LONG).show()
        return false
    }
}
