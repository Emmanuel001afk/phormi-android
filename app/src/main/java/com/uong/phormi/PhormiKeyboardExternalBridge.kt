package com.uong.phormi

import android.content.Context
import android.net.Uri

/** Picker results wait for an explicit Insert picked tap, never poll and inject into another editor. */
object PhormiKeyboardExternalBridge {
    fun commitText(context: Context, text: String): Boolean = PhormiKeyboardServiceV2.commitExternalText(context, text)
    fun commitContent(context: Context, uri: Uri): Boolean = PhormiKeyboardServiceV2.commitPickedContent(context, uri)
}
