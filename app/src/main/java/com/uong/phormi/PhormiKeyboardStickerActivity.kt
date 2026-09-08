package com.uong.phormi

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** AI sticker/image generator using Pollinations' legacy anonymous image endpoint. */
class PhormiKeyboardStickerActivity : Activity() {
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val incoming = intent?.getParcelableExtra<Uri>(android.content.Intent.EXTRA_STREAM)
        if (incoming != null && intent?.action == android.content.Intent.ACTION_SEND) {
            importShared(incoming)
            return
        }
        render()
    }

    private fun render() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(24, 24, 24, 24)
        }
        root.addView(TextView(this).apply { text = "Phormi AI Sticker"; textSize = 22f })
        root.addView(TextView(this).apply {
            text = "Create a square sticker with Pollinations AI. No Phormi API key is stored."
            setPadding(0, 10, 0, 18)
        })
        val prompt = EditText(this).apply {
            hint = "e.g. a funny robot laughing, sticker style"
            minLines = 3
            gravity = Gravity.TOP
        }
        root.addView(prompt, LinearLayout.LayoutParams(-1, 0, 1f))
        status = TextView(this).apply { setPadding(0, 14, 0, 14) }
        root.addView(status)
        root.addView(Button(this).apply {
            text = "Generate & use"
            setOnClickListener {
                val text = prompt.text.toString().trim()
                if (text.isBlank()) { status.text = "Enter a prompt first."; return@setOnClickListener }
                generate(text)
            }
        }, LinearLayout.LayoutParams(-1, 54))
        root.addView(Button(this).apply {
            text = "Choose an existing sticker/image"
            setOnClickListener {
                startActivityForResult(android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(android.content.Intent.CATEGORY_OPENABLE)
                    type = "image/*"
                }, 4402)
            }
        }, LinearLayout.LayoutParams(-1, 54))
        setContentView(root)
    }

    private fun importShared(uri: Uri) {
        val file = PhormiKeyboardStickerStore.import(this, uri, "shared")
        if (file != null) {
            PhormiKeyboardService.useSticker(this, PhormiKeyboardStickerStore.contentUri(this, file))
            Toast.makeText(this, "Sticker saved to Phormi Keyboard", Toast.LENGTH_SHORT).show()
        } else Toast.makeText(this, "Could not import that image", Toast.LENGTH_SHORT).show()
        finish()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 4402 && resultCode == RESULT_OK) data?.data?.let { uri ->
            val file = PhormiKeyboardStickerStore.import(this, uri, "imported")
            if (file != null) {
                PhormiKeyboardService.useSticker(this, PhormiKeyboardStickerStore.contentUri(this, file))
                Toast.makeText(this, "Sticker saved to Phormi Keyboard", Toast.LENGTH_SHORT).show()
            }
        }
        finish()
    }

    private fun generate(prompt: String) {
        status.text = "Generating…"
        val encoded = URLEncoder.encode(prompt, "UTF-8")
        val url = "https://image.pollinations.ai/prompt/$encoded?model=flux&width=512&height=512&safe=true"
        CoroutineScope(Dispatchers.IO).launch {
            val result = runCatching {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 30_000
                    readTimeout = 120_000
                    requestMethod = "GET"
                }
                conn.connect()
                if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
                val bytes = conn.inputStream.use { it.readBytes() }
                val file = java.io.File(this@PhormiKeyboardStickerActivity.filesDir, "phormi_stickers/generated_${System.currentTimeMillis()}.jpg")
                file.parentFile?.mkdirs()
                FileOutputStream(file).use { it.write(bytes) }
                conn.disconnect()
                file
            }
            runOnUiThread {
                result.onSuccess { file ->
                    status.text = "Saved."
                    PhormiKeyboardService.useSticker(this@PhormiKeyboardStickerActivity, PhormiKeyboardStickerStore.contentUri(this@PhormiKeyboardStickerActivity, file))
                    Toast.makeText(this@PhormiKeyboardStickerActivity, "AI sticker created", Toast.LENGTH_SHORT).show()
                    finish()
                }.onFailure { status.text = "Generation failed: ${it.message ?: "unknown error"}" }
            }
        }
    }
}
