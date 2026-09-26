package com.uong.phormi

import android.app.Activity
import android.content.Context
import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.EditText
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast

/** Pollinations AI Emoji Studio. Automatic mode uses the current typed context. */
class PhormiAiEmojiActivity : Activity() {
    companion object {
        const val EXTRA_CONTEXT = "ai_emoji_context"
        const val EXTRA_AUTOMATIC = "ai_emoji_automatic"
        const val EXTRA_CONFIG_ONLY = "ai_emoji_config_only"
    }
    private lateinit var prompt: EditText
    private lateinit var grid: GridLayout
    private val generated = mutableListOf<java.io.File>()
    private var pending = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render()
        if (intent.getBooleanExtra(EXTRA_CONFIG_ONLY, false)) {
            showKeyDialog()
            return
        }
        val automatic = intent.getBooleanExtra(EXTRA_AUTOMATIC, false)
        val context = intent.getStringExtra(EXTRA_CONTEXT).orEmpty().trim()
        if (automatic && context.isNotBlank()) {
            prompt.setText(context)
            prompt.setSelection(prompt.text.length)
            generate(true)
        }
    }

    private fun render() {
        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
        }
        root.addView(TextView(this).apply { text = "Phormi AI Emoji"; textSize = 22f })
        root.addView(TextView(this).apply {
            text = "Automatic mode uses what you are typing as the context. The result is one original fused emoji-style reaction, not a normal Unicode emoji."
            setPadding(0, 8, 0, 12)
        })
        prompt = EditText(this).apply { hint = "Optional manual context"; minLines = 2 }
        root.addView(prompt)
        prompt.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                prompt.postDelayed({
                    (getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                        .showSoftInput(prompt, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                }, 180L)
            }
        }
        root.addView(android.widget.Button(this).apply {
            text = "Generate 4"
            setOnClickListener { generate(false) }
        })
        root.addView(android.widget.Button(this).apply {
            text = if (PhormiKeyboardPreferences.pollinationsKey(this@PhormiAiEmojiActivity).isBlank()) "Set Pollinations AI key" else "Pollinations AI key ✓"
            setOnClickListener { showKeyDialog() }
        })
        grid = GridLayout(this).apply { columnCount = 2 }
        root.addView(grid, android.widget.LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun showKeyDialog() {
        val input = EditText(this).apply {
            hint = "pk_…"
            setSingleLine(true)
            setText(PhormiKeyboardPreferences.pollinationsKey(this@PhormiAiEmojiActivity))
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Pollinations AI key")
            .setMessage("Use your Pollinations publishable key. It stays in this device's private app preferences.")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                PhormiKeyboardPreferences.setPollinationsKey(this@PhormiAiEmojiActivity, input.text.toString())
            }
            .setNeutralButton("Clear") { _, _ ->
                PhormiKeyboardPreferences.setPollinationsKey(this@PhormiAiEmojiActivity, "")
            }
            .show()
    }

    private fun generate(automatic: Boolean) {
        val text = prompt.text.toString().trim()
        if (text.isBlank()) {
            Toast.makeText(this, "Type something first", Toast.LENGTH_SHORT).show()
            return
        }
        generated.clear()
        grid.removeAllViews()
        val count = if (automatic) 1 else 4
        pending = count
        Toast.makeText(this, if (automatic) "Creating your contextual emoji…" else "Generating contextual emojis…", Toast.LENGTH_SHORT).show()
        repeat(count) { index ->
            PhormiPollinationsAiEmojiEngine.generateAsync(this, text, index) { file ->
                pending--
                if (file != null) generated.add(file)
                if (pending == 0) showGenerated()
            }
        }
    }

    private fun showGenerated() {
        grid.removeAllViews()
        generated.forEach { file ->
            val image = ImageView(this).apply {
                setImageBitmap(BitmapFactory.decodeFile(file.absolutePath))
                contentDescription = "Phormi generated custom emoji"
                adjustViewBounds = true
                setPadding(8, 8, 8, 8)
                setOnClickListener { insert(file) }
                setOnLongClickListener { saveToPack(file); true }
            }
            grid.addView(image, GridLayout.LayoutParams().apply {
                width = 0; height = 230
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                rowSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
            })
        }
        Toast.makeText(this, "Tap an emoji to insert; long-press to save", Toast.LENGTH_LONG).show()
    }

    private fun insert(file: java.io.File) {
        val ok = runCatching { PhormiKeyboardServiceV2.commitPickedContent(this, PhormiKeyboardStickerStore.contentUri(this, file)) }.getOrDefault(false)
        if (!ok) Toast.makeText(this, "The current editor does not accept image content", Toast.LENGTH_SHORT).show()
    }

    private fun saveToPack(file: java.io.File) {
        val input = EditText(this).apply { hint = "Sticker pack name"; setSingleLine(true); setText("My AI Emojis") }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Save to keyboard")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                PhormiKeyboardStickerPackStore.add(this, input.text.toString(), file)
                Toast.makeText(this, "Saved to your Phormi keyboard sticker pack", Toast.LENGTH_SHORT).show()
            }
            .show()
    }
}
