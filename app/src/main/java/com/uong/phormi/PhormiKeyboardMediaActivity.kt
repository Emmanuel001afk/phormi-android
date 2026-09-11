package com.uong.phormi

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog

/** Picker/import activity used by the keyboard media surface. */
class PhormiKeyboardMediaActivity : Activity() {
    companion object { const val EXTRA_MODE = "mode"; private const val REQUEST_PICK = 4401 }
    private val mode: String get() = intent?.getStringExtra(EXTRA_MODE).orEmpty()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when (mode) {
            "sticker" -> showStickerChooser()
            "wallpaper" -> openWallpaperPicker()
            else -> openPicker()
        }
    }

    private fun showStickerChooser() {
        val packs = PhormiKeyboardStickerPackStore.packs(this)
        val choices = mutableListOf("📁 Import sticker")
        choices += packs.map { "📦 ${it.name} (${it.files.size})" }
        AlertDialog.Builder(this)
            .setTitle("Phormi Stickers")
            .setItems(choices.toTypedArray()) { _, which ->
                if (which == 0) openPicker() else chooseSticker(packs[which - 1])
            }
            .setNegativeButton("Cancel") { _, _ -> finish() }
            .show()
    }

    private fun chooseSticker(pack: PhormiKeyboardStickerPackStore.Pack) {
        val files = PhormiKeyboardStickerPackStore.files(this, pack)
        if (files.isEmpty()) {
            Toast.makeText(this, "This pack is empty", Toast.LENGTH_SHORT).show()
            finish(); return
        }
        val names = files.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(pack.name)
            .setItems(names) { _, which ->
                val file = files.getOrNull(which) ?: return@setItems
                PhormiKeyboardExternalBridge.commitContent(this, PhormiKeyboardStickerStore.contentUri(this, file))
                finish()
            }
            .setNegativeButton("Back") { _, _ -> showStickerChooser() }
            .show()
    }

    private fun openPicker() {
        // Use image/* for discovery because some document providers do not expose a
        // GIF with the expected MIME type. We still advertise GIF as the preferred type.
        val gif = mode == "gif"
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            putExtra(Intent.EXTRA_MIME_TYPES, if (gif) arrayOf("image/gif", "image/webp", "image/*") else arrayOf("image/png", "image/jpeg", "image/webp", "image/gif", "image/*"))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }, REQUEST_PICK)
    }

    private fun openWallpaperPicker() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/png", "image/jpeg", "image/webp", "image/*"))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }, REQUEST_PICK)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_PICK && resultCode == RESULT_OK) {
            data?.data?.let { uri ->
                runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                when (mode) {
                    "wallpaper" -> {
                        PhormiKeyboardPreferences.setWallpaperUri(this, uri.toString())
                        Toast.makeText(this, "Wallpaper imported", Toast.LENGTH_SHORT).show()
                    }
                    "sticker" -> {
                        val file = PhormiKeyboardStickerStore.import(this, uri, "sticker")
                        if (file == null) {
                            Toast.makeText(this, "Could not import this sticker", Toast.LENGTH_SHORT).show()
                        } else {
                            PhormiKeyboardStickerPackStore.add(this, "Imported", file)
                            PhormiKeyboardExternalBridge.commitContent(this, PhormiKeyboardStickerStore.contentUri(this, file))
                        }
                    }
                    else -> {
                        if (!PhormiKeyboardExternalBridge.commitContent(this, uri)) {
                            Toast.makeText(this, "Media queued — return to the keyboard to insert it", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
        finish()
    }
}
