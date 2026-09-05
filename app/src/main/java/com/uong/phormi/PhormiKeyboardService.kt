package com.uong.phormi

import android.content.ClipDescription
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.inputmethodservice.InputMethodService
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputContentInfo
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast

class PhormiKeyboardService : InputMethodService() {
    companion object {
        const val ACTION_COMMIT_TEXT = "com.uong.phormi.keyboard.COMMIT_TEXT"
        const val EXTRA_TEXT = "text"
        private var instance: PhormiKeyboardService? = null

        fun commitPickedContent(context: Context, uri: Uri): Boolean {
            val service = instance
            return service?.commitContent(uri) ?: run {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newRawUri("Phormi media", uri))
                Toast.makeText(context, "Media copied. Return to the text field and paste it.", Toast.LENGTH_LONG).show()
                false
            }
        }
    }

    private var caps = false
    private var symbols = false
    private var emojiMode = false
    private var emojiCategory = 0

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onCreateInputView(): View = buildKeyboard()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_COMMIT_TEXT) {
            intent.getStringExtra(EXTRA_TEXT)?.takeIf { it.isNotEmpty() }?.let { commitRemembered(it) }
        }
        return START_NOT_STICKY
    }

    private fun buildKeyboard(): View {
        if (emojiMode) return buildEmojiKeyboard()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(4, 4, 4, 4)
            setBackgroundColor(Color.rgb(17, 24, 39))
        }
        val toolbar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        addKey(toolbar, "😀") { emojiMode = true; setInputView(buildKeyboard()) }
        addKey(toolbar, "GIF") { openMedia("gif") }
        addKey(toolbar, "Sticker") { openMedia("sticker") }
        addKey(toolbar, "📋") { showClipboardHistory(root) }
        addKey(toolbar, "⌫") { currentInputConnection?.deleteSurroundingText(1, 0) }
        root.addView(toolbar, LinearLayout.LayoutParams(-1, 44))

        val rows = if (symbols) {
            listOf("1234567890-=", "!@#\$%^&*()_+", "[]{};:'\"<>", "?/,\\|`~")
        } else listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
        rows.forEach { root.addView(row(it)) }

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        addKey(actions, if (symbols) "ABC" else "123") { symbols = !symbols; emojiMode = false; setInputView(buildKeyboard()) }
        addKey(actions, "⇧") { caps = !caps; setInputView(buildKeyboard()) }
        addKey(actions, "Space", 4f) { commitSpaceAndRemember() }
        addKey(actions, "⌫") { currentInputConnection?.deleteSurroundingText(1, 0) }
        addKey(actions, "↵") { sendEnterAndRemember() }
        addKey(actions, "🎙") { startActivity(Intent(this, PhormiKeyboardVoiceActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        root.addView(actions, LinearLayout.LayoutParams(-1, 50))
        return root
    }

    private fun buildEmojiKeyboard(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(6, 6, 6, 6)
            setBackgroundColor(Color.rgb(17, 24, 39))
        }
        val nav = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val categories = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val keys = PhormiKeyboardEmoji.categories.keys.toList()
        keys.forEachIndexed { index, icon -> addKey(categories, icon) { emojiCategory = index; redrawEmoji(root) } }
        addKey(categories, "ABC") { emojiMode = false; setInputView(buildKeyboard()) }
        nav.addView(categories)
        root.addView(nav, LinearLayout.LayoutParams(-1, 48))
        redrawEmoji(root)
        return root
    }

    private fun redrawEmoji(root: LinearLayout) {
        while (root.childCount > 1) root.removeViewAt(1)
        val scroll = ScrollView(this)
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val items = PhormiKeyboardEmoji.categories.values.elementAtOrNull(emojiCategory).orEmpty()
        items.chunked(8).forEach { group ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            group.forEach { emoji -> addKey(row, emoji) { commitRemembered(emoji) } }
            grid.addView(row, LinearLayout.LayoutParams(-1, 48))
        }
        scroll.addView(grid)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun showClipboardHistory(root: LinearLayout) {
        while (root.childCount > 0) root.removeViewAt(root.childCount - 1)
        val scroll = ScrollView(this)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        addKey(box, "← Keyboard") { emojiMode = false; setInputView(buildKeyboard()) }
        val items = PhormiKeyboardClipboardStore.list(this)
        if (items.isEmpty()) {
            box.addView(android.widget.TextView(this).apply { text = "No clipboard history yet."; setTextColor(Color.WHITE); setPadding(12, 18, 12, 18) })
        } else {
            items.forEach { item -> addKey(box, item.take(50)) { commitRemembered(item) } }
            addKey(box, "Clear history") { PhormiKeyboardClipboardStore.clear(this); setInputView(buildKeyboard()) }
        }
        scroll.addView(box)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun openMedia(mode: String) {
        startActivity(Intent(this, PhormiKeyboardMediaActivity::class.java).putExtra(PhormiKeyboardMediaActivity.EXTRA_MODE, mode).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun row(chars: String): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        chars.forEach { c ->
            val s = if (c.isLetter() && caps) c.uppercaseChar().toString() else c.toString()
            addKey(row, s) { currentInputConnection?.commitText(s, 1) }
        }
        return row
    }

    private fun addKey(row: LinearLayout, label: String, weight: Float = 1f, action: () -> Unit) {
        val b = Button(this).apply {
            text = label
            minWidth = 0
            minHeight = 0
            setPadding(4, 0, 4, 0)
            setOnClickListener { action() }
        }
        row.addView(b, LinearLayout.LayoutParams(0, 48, weight).apply { setMargins(2, 2, 2, 2) })
    }

    private fun commitRemembered(text: String) {
        currentInputConnection?.commitText(text, 1)
        if (text.length >= 2 && !text.contains('\n')) PhormiKeyboardClipboardStore.add(this, text)
    }

    private fun commitSpaceAndRemember() {
        val word = currentInputConnection?.getTextBeforeCursor(80, 0)?.toString()?.trim()?.split(Regex("\\s+"))?.lastOrNull().orEmpty()
        if (word.length >= 2) PhormiKeyboardClipboardStore.add(this, word)
        currentInputConnection?.commitText(" ", 1)
    }

    private fun sendEnterAndRemember() {
        val before = currentInputConnection?.getTextBeforeCursor(80, 0)?.toString()?.trim()?.split(Regex("\\s+"))?.lastOrNull().orEmpty()
        if (before.length >= 2) PhormiKeyboardClipboardStore.add(this, before)
        val event = android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_ENTER)
        currentInputConnection?.sendKeyEvent(event)
        currentInputConnection?.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_ENTER))
    }

    private fun commitContent(uri: Uri): Boolean {
        val connection = currentInputConnection ?: return false
        if (android.os.Build.VERSION.SDK_INT < 25) return false
        val requested = currentInputEditorInfo?.contentMimeTypes?.toList().orEmpty()
        val preferred = when {
            requested.any { it.equals("image/gif", true) } -> "image/gif"
            requested.any { it.startsWith("image/") } -> "image/png"
            else -> "image/*"
        }
        val description = ClipDescription("Phormi media", arrayOf(preferred))
        val contentInfo = InputContentInfo(uri, description, null)
        val opts = Bundle().apply { putBoolean("androidx.core.view.extra.INPUT_CONTENT_INFO", true) }
        val accepted = requested.isEmpty() || requested.any { ClipDescription.compareMimeTypes(preferred, it) }
        if (accepted && connection.commitContent(contentInfo, 0, opts)) {
            return true
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newRawUri("Phormi media", uri))
        Toast.makeText(this, "This field does not accept rich media. The media URI was copied for paste.", Toast.LENGTH_LONG).show()
        return false
    }

    private fun sendEnter() {
        val e = android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_ENTER)
        currentInputConnection?.sendKeyEvent(e)
        currentInputConnection?.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_ENTER))
    }
}
