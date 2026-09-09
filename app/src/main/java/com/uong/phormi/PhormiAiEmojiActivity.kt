package com.uong.phormi

import android.app.Activity
import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** Local AI emoji/sticker studio. No API key, network request, or cloud service is used. */
class PhormiAiEmojiActivity : Activity() {
    private lateinit var prompt: EditText
    private lateinit var grid: GridLayout
    private var generated = emptyList<java.io.File>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            setBackgroundColor(0xFF111827.toInt())
        }
        root.addView(TextView(this).apply {
            text = "✨ AI Emoji Studio"
            textSize = 22f
            setTextColor(0xFFFFFFFF.toInt())
        })
        root.addView(TextView(this).apply {
            text = "Describe an emoji or sticker. Phormi creates four local variations on-device."
            textSize = 13f
            setTextColor(0xFFCBD5E1.toInt())
            setPadding(0, 8, 0, 12)
        })
        prompt = EditText(this).apply {
            hint = "e.g. happy Nigerian celebration, cool, love, sad..."
            setSingleLine(false)
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF94A3B8.toInt())
        }
        root.addView(prompt, LinearLayout.LayoutParams(-1, 90))
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(Button(this).apply {
            text = "Generate 4"
            setOnClickListener { generate() }
        }, LinearLayout.LayoutParams(0, 52, 1f))
        buttons.addView(Button(this).apply {
            text = "My packs"
            setOnClickListener { showPacks() }
        }, LinearLayout.LayoutParams(0, 52, 1f))
        root.addView(buttons)
        grid = GridLayout(this).apply {
            columnCount = 2
            useDefaultMargins = true
        }
        root.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(Button(this).apply {
            text = "Close"
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(-1, 52))
        setContentView(root)
    }

    private fun generate() {
        val text = prompt.text.toString().trim().ifBlank { "happy friendly emoji" }
        generated = (0 until 4).map { PhormiAiEmojiEngine.generate(this, text, it) }
        grid.removeAllViews()
        generated.forEach { file ->
            val image = ImageView(this).apply {
                setImageBitmap(BitmapFactory.decodeFile(file.absolutePath))
                contentDescription = "Generated AI emoji"
                adjustViewBounds = true
                setPadding(8, 8, 8, 8)
                setOnClickListener { insert(file) }
                setOnLongClickListener { saveToPack(file); true }
            }
            grid.addView(image, GridLayout.LayoutParams().apply {
                width = 0
                height = 230
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                rowSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
            })
        }
        Toast.makeText(this, "Choose one to insert; long-press to save to a sticker pack", Toast.LENGTH_LONG).show()
    }

    private fun insert(file: java.io.File) {
        runCatching {
            PhormiKeyboardService.useSticker(this, PhormiKeyboardStickerStore.contentUri(this, file))
        }.onFailure { Toast.makeText(this, "This editor does not accept sticker content", Toast.LENGTH_SHORT).show() }
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

    private fun showPacks() {
        val packs = PhormiKeyboardStickerPackStore.packs(this)
        if (packs.isEmpty()) {
            Toast.makeText(this, "No saved sticker packs yet", Toast.LENGTH_SHORT).show()
            return
        }
        val names = packs.map { "${it.name} (${it.files.size})" }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Keyboard sticker packs")
            .setItems(names) { _, which ->
                val files = PhormiKeyboardStickerPackStore.files(this, packs[which])
                if (files.isNotEmpty()) insert(files.first())
            }.show()
    }
}
