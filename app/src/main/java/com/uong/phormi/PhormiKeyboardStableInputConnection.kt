package com.uong.phormi

import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodService

/**
 * Helper to keep the IME stable during general sign-in.
 * Web sign-in pages often recreate inputs or fire focus events on each keystroke,
 * which makes InputMethodService receive restarting=true and rebuild the keyboard.
 * We keep rendering stable by not resetting page/panel on restarting inputs.
 */
object PhormiKeyboardStableInputConnection {
    fun shouldKeepPanelOnRestart(restarting: Boolean): Boolean = restarting
}
