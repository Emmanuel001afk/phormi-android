package com.uong.phormi

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** Local AI sticker composer and sticker importer. */
class PhormiKeyboardStickerActivity : Activity() {
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val incoming = intent?.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        if (incoming != null && intent?.action == Intent.ACTION_SEND) { importShared(incoming); return }
        render()
    }

    private fun render() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(24, 24, 24, 24) }
        root.addView(TextView(this).apply { text = "Phormi Local AI Sticker"; textSize = 22f })
        root.addView(TextView(this).apply { text = "Create an on-device emoji/sticker from a description. No external image API is used."; setPadding(0, 10, 0, 18) })
        val prompt = EditText(this).apply { hint = "e.g. a funny robot laughing, sticker style"; minLines = 3; gravity = Gravity.TOP }
        root.addView(prompt, LinearLayout.LayoutParams(-1, 0, 1f))
        status = TextView(this).apply { setPadding(0, 14, 0, 14) }
        root.addView(status)
        root.addView(Button(this).apply {
            text = "Generate & use"
            setOnClickListener { val text = prompt.text.toString().trim(); if (text.isBlank()) status.text = "Enter a prompt first." else generate(text) }
        }, LinearLayout.LayoutParams(-1, 54))
        root.addView(Button(this).apply {
            text = "Choose an existing sticker/image"
            setOnClickListener { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "image/*" }, 4402) }
        }, LinearLayout.LayoutParams(-1, 54))
        setContentView(root)
    }

    private fun importShared(uri: Uri) {
        val file = PhormiKeyboardStickerStore.import(this, uri, "shared")
        if (file != null) {
            PhormiKeyboardServiceV2.commitPickedContent(this, PhormiKeyboardStickerStore.contentUri(this, file))
            Toast.makeText(this, "Sticker saved to Phormi Keyboard", Toast.LENGTH_SHORT).show()
        } else Toast.makeText(this, "Could not import that image", Toast.LENGTH_SHORT).show()
        finish()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 4402 && resultCode == RESULT_OK) data?.data?.let { uri ->
            val file = PhormiKeyboardStickerStore.import(this, uri, "imported")
            if (file != null) {
                PhormiKeyboardServiceV2.commitPickedContent(this, PhormiKeyboardStickerStore.contentUri(this, file))
                Toast.makeText(this, "Sticker saved to Phormi Keyboard", Toast.LENGTH_SHORT).show()
            }
        }
        finish()
    }

    private fun generate(prompt: String) {
        status.text = "Generating locally…"
        runCatching {
            val file = PhormiAiEmojiEngine.generate(this, prompt, (System.currentTimeMillis() % 4).toInt())
            PhormiKeyboardStickerPackStore.add(this, "My AI Stickers", file)
            PhormiKeyboardServiceV2.commitPickedContent(this, PhormiKeyboardStickerStore.contentUri(this, file))
            status.text = "Saved to My AI Stickers."
            Toast.makeText(this, "Local AI sticker created", Toast.LENGTH_SHORT).show()
            finish()
        }.onFailure { status.text = "Generation failed: ${it.message ?: "unknown error"}" }
    }
}
