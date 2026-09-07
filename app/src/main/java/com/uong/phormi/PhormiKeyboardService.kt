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
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputContentInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Full Android IME surface: text, numbers/symbols, actions, emoji, media, voice and clipboard. */
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
    private var emojiMode = false
    private var emojiCategory = 0
    private var panel = Panel.KEYBOARD
    private var clipboardListener: ClipboardManager.OnPrimaryClipChangedListener? = null
    private var lastSpaceDownX = 0f

    private enum class Panel { KEYBOARD, CLIPBOARD, EMOJI }

    override fun onCreate() {
        super.onCreate()
        instance = this
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboardListener = ClipboardManager.OnPrimaryClipChangedListener { PhormiKeyboardClipboardStore.capturePrimaryClipboard(this) }
        cm.addPrimaryClipChangedListener(clipboardListener)
    }

    override fun onDestroy() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboardListener?.let(cm::removePrimaryClipChangedListener)
        clipboardListener = null
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onCreateInputView(): View = render()

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
    }

    private fun toolbar(root: LinearLayout) {
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        key(bar, "😀") { panel = Panel.EMOJI; setInputView(render()) }
        key(bar, "GIF") { openMedia("gif") }
        key(bar, "Sticker") { openMedia("sticker") }
        key(bar, "📋") { PhormiKeyboardClipboardStore.capturePrimaryClipboard(this); panel = Panel.CLIPBOARD; setInputView(render()) }
        key(bar, "⌨") { panel = Panel.KEYBOARD; setInputView(render()) }
        root.addView(bar, LinearLayout.LayoutParams(-1, 46))
    }

    private fun buildKeyboard(): View {
        val root = baseRoot(); toolbar(root)
        if (symbols) {
            listOf("1234567890", "-=[]\\;',./", "!@#\$%^&*()", "_+{}|:\"<>?").forEach { root.addView(charRow(it)) }
        } else {
            listOf("qwertyuiop", "asdfghjkl", "zxcvbnm").forEach { root.addView(charRow(it)) }
        }
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        key(actions, if (symbols) "ABC" else "123") { symbols = !symbols; setInputView(render()) }
        key(actions, if (capsLock) "⇧·" else "⇧") { shift = !shift; setInputView(render()) }
        val space = key(actions, "Space", 3.8f) { commitText(" ") }
        space.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> lastSpaceDownX = event.x
                MotionEvent.ACTION_UP -> {
                    val dx = event.x - lastSpaceDownX
                    if (kotlin.math.abs(dx) > 35f) moveCursor(if (dx > 0) 1 else -1)
                }
            }
            false
        }
        key(actions, "⌫") { deleteBackward() }
        key(actions, "↵") { sendEditorAction() }
        key(actions, "🎙") { startActivity(Intent(this, PhormiKeyboardVoiceActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        root.addView(actions, LinearLayout.LayoutParams(-1, 52))
        return root
    }

    private fun charRow(chars: String): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        chars.forEach { c ->
            val text = if (c.isLetter() && (shift || capsLock)) c.uppercaseChar().toString() else c.toString()
            key(row, text) { commitText(text); if (shift && !capsLock) { shift = false; setInputView(render()) } }
        }
        return row
    }

    private fun buildEmoji(): View {
        val root = baseRoot()
        val nav = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val cats = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        PhormiKeyboardEmoji.categories.keys.forEachIndexed { index, icon -> key(cats, icon) { emojiCategory = index; setInputView(render()) } }
        key(cats, "ABC") { panel = Panel.KEYBOARD; setInputView(render()) }
        nav.addView(cats); root.addView(nav, LinearLayout.LayoutParams(-1, 48))
        val scroll = ScrollView(this)
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        PhormiKeyboardEmoji.categories.values.elementAtOrNull(emojiCategory).orEmpty().chunked(8).forEach { group ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            group.forEach { emoji -> key(row, emoji) { commitText(emoji) } }
            grid.addView(row, LinearLayout.LayoutParams(-1, 50))
        }
        scroll.addView(grid); root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
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
        if (items.isEmpty()) list.addView(TextView(this).apply { text = "No clipboard history yet. Copy text normally to add it."; setTextColor(Color.WHITE); setPadding(12, 20, 12, 20) })
        items.forEach { item ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val preview = TextView(this).apply { text = item.text; setTextColor(Color.WHITE); textSize = 13f; maxLines = 3; setPadding(12, 10, 8, 10) }
            row.addView(preview, LinearLayout.LayoutParams(0, -2, 1f))
            val pin = Button(this).apply { text = if (item.pinned) "📌" else "○"; setOnClickListener { PhormiKeyboardClipboardStore.togglePinned(this@PhormiKeyboardService, item.text); setInputView(render()) } }
            row.addView(pin, LinearLayout.LayoutParams(52, 52))
            row.setOnClickListener { commitText(item.text) }
            row.setOnLongClickListener { PhormiKeyboardClipboardStore.remove(this, item.text); setInputView(render()); true }
            list.addView(row)
        }
        scroll.addView(list); root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f)); return root
    }

    private fun key(row: LinearLayout, label: String, weight: Float = 1f, action: () -> Unit): Button = Button(this).apply {
        text = label; minWidth = 0; minHeight = 0; setPadding(2, 0, 2, 0); isAllCaps = false; setOnClickListener { action() }
        row.addView(this, LinearLayout.LayoutParams(0, 50, weight).apply { setMargins(2, 2, 2, 2) })
    }

    private fun commitText(text: String) { currentInputConnection?.commitText(text, 1) }
    private fun deleteBackward() {
        val ic = currentInputConnection ?: return
        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) ic.commitText("", 1) else ic.deleteSurroundingText(1, 0)
    }
    private fun moveCursor(delta: Int) {
        val ic = currentInputConnection ?: return
        if (delta > 0) ic.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_RIGHT))
        else ic.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_LEFT))
    }
    private fun sendEditorAction() {
        val info = currentInputEditorInfo ?: return
        val action = info.imeOptions and EditorInfo.IME_MASK_ACTION
        if (action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) currentInputConnection?.performEditorAction(action) else currentInputConnection?.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_ENTER))
    }
    private fun openMedia(mode: String) = startActivity(Intent(this, PhormiKeyboardMediaActivity::class.java).putExtra(PhormiKeyboardMediaActivity.EXTRA_MODE, mode).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    private fun commitContent(uri: Uri): Boolean {
        val ic = currentInputConnection ?: return false
        if (android.os.Build.VERSION.SDK_INT < 25) return false
        val requested = currentInputEditorInfo?.contentMimeTypes?.toList().orEmpty()
        val mime = when { requested.any { it == "image/gif" } -> "image/gif"; requested.any { it.startsWith("image/") } -> "image/png"; else -> "image/*" }
        if (requested.isEmpty() || requested.any { ClipDescription.compareMimeTypes(mime, it) }) {
            val info = InputContentInfo(uri, ClipDescription("Phormi media", arrayOf(mime)), null)
            if (ic.commitContent(info, 0, Bundle())) return true
        }
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(android.content.ClipData.newRawUri("Phormi media", uri))
        Toast.makeText(this, "Media copied to the system clipboard for paste.", Toast.LENGTH_LONG).show(); return false
    }
}
