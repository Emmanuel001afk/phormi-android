package com.uong.phormi

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/** Keyboard media surface: normal picker, local AI emoji creation, and saved sticker packs. */
class PhormiKeyboardMediaActivity : Activity() {
    companion object {
        const val EXTRA_MODE = "mode"
        private const val REQUEST_PICK = 4401
    }
    private var mode = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mode = intent?.getStringExtra(EXTRA_MODE).orEmpty().lowercase()
        if (mode == "sticker") {
            showStickerChooser()
        } else openPicker()
    }

    private fun showStickerChooser() {
        val packs = PhormiKeyboardStickerPackStore.packs(this)
        val choices = mutableListOf("✨ Create AI emoji", "📁 Import sticker")
        choices += packs.map { "📦 ${it.name} (${it.files.size})" }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Phormi Stickers")
            .setItems(choices.toTypedArray()) { _, which ->
                when {
                    which == 0 -> startActivity(Intent(this, PhormiAiEmojiActivity::class.java))
                    which == 1 -> openPicker()
                    else -> {
                        val pack = packs[which - 2]
                        val files = PhormiKeyboardStickerPackStore.files(this, pack)
                        if (files.isEmpty()) Toast.makeText(this, "This pack is empty", Toast.LENGTH_SHORT).show()
                        else PhormiKeyboardService.useSticker(this, PhormiKeyboardStickerStore.contentUri(this, files.first()))
                        finish()
                    }
                }
            }
            .setNegativeButton("Cancel") { _, _ -> finish() }
            .show()
    }

    private fun openPicker() {
        val mime = if (mode == "gif") "image/gif" else "image/*"
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = mime
                putExtra(Intent.EXTRA_MIME_TYPES, if (mode == "gif") arrayOf("image/gif") else arrayOf("image/*"))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            },
            REQUEST_PICK
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_PICK && resultCode == RESULT_OK) {
            data?.data?.let { uri ->
                runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                if (mode == "sticker") {
                    PhormiKeyboardStickerStore.import(this, uri, "imported")?.let { file ->
                        PhormiKeyboardStickerPackStore.add(this, "Imported", file)
                        PhormiKeyboardService.useSticker(this, PhormiKeyboardStickerStore.contentUri(this, file))
                    }
                } else PhormiKeyboardService.commitPickedContent(this, uri)
            }
        }
        finish()
    }
}
