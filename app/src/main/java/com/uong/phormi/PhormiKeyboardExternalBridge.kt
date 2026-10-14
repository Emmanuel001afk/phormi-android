package com.uong.phormi

import android.content.Context
import android.net.Uri

/** Picker results are queued by the IME for the originating editor only, with a short expiry. */
object PhormiKeyboardExternalBridge {
    fun commitText(context: Context, text: String): Boolean =
        PhormiKeyboardServiceV2.commitExternalText(context, text)

    fun commitContent(context: Context, uri: Uri): Boolean =
        PhormiKeyboardServiceV2.commitPickedContent(context, uri)
}
