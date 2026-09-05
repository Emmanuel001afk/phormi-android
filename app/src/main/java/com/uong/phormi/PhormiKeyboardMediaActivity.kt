package com.uong.phormi

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/** Opens the Android document picker for GIFs, stickers, or general media and returns rich content to the IME. */
class PhormiKeyboardMediaActivity : Activity() {
    companion object {
        const val EXTRA_MODE = "mode"
        private const val REQUEST_PICK = 4401
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mode = intent?.getStringExtra(EXTRA_MODE).orEmpty().lowercase()
        val mime = if (mode == "gif") "image/gif" else "image/*"
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = mime
                putExtra(Intent.EXTRA_MIME_TYPES, if (mode == "gif") arrayOf("image/gif") else arrayOf("image/*"))
            },
            REQUEST_PICK
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_PICK && resultCode == RESULT_OK) {
            data?.data?.let { uri ->
                runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                PhormiKeyboardService.commitPickedContent(this, uri)
            }
        }
        finish()
    }
}
