package com.uong.phormi

import android.app.Activity
import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.EditText
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast

/** Pollinations AI Emoji Studio with a local offline fallback. */
class PhormiAiEmojiActivity : Activity() {
    private lateinit var prompt: EditText
    private lateinit var grid: GridLayout
    private val generated = mutableListOf<java.io.File>()
    private var pending = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render()
    }

    private fun render() {
        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
        }
        root.addView(TextView(this).apply { text = "Pollinations AI Emoji Studio"; textSize = 22f })
        root.addView(TextView(this).apply {
            text = "Describe the emoji or sticker you want. Phormi uses Pollinations AI without embedding an API key; if the service is unavailable, it falls back to the built-in offline renderer."
            setPadding(0, 8, 0, 12)
        })
        prompt = EditText(this).apply { hint = "e.g. happy robot celebrating"; minLines = 2 }
        root.addView(prompt)
        root.addView(android.widget.Button(this).apply {
            text = "Generate 4"
            setOnClickListener { generate() }
        })
        grid = GridLayout(this).apply { columnCount = 2 }
        root.addView(grid, android.widget.LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun generate() {
        val text = prompt.text.toString().trim()
        if (text.isBlank()) return Toast.makeText(this, "Describe the emoji first", Toast.LENGTH_SHORT).show()
        generated.clear()
        grid.removeAllViews()
        pending = 4
        Toast.makeText(this, "Generating with Pollinations AI…", Toast.LENGTH_SHORT).show()
        repeat(4) { index ->
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
                contentDescription = "Pollinations AI generated emoji"
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
        Toast.makeText(this, "Tap to insert; long-press to save", Toast.LENGTH_LONG).show()
    }

    private fun insert(file: java.io.File) {
        runCatching { PhormiKeyboardServiceV2.commitPickedContent(this, PhormiKeyboardStickerStore.contentUri(this, file)) }
            .onFailure { Toast.makeText(this, "This editor does not accept image content", Toast.LENGTH_SHORT).show() }
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
            }.show()
    }
}
