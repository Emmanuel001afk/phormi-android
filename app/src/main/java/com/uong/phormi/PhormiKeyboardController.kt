package com.uong.phormi

import android.content.Context
import android.view.View
import android.view.inputmethod.InputMethodManager

object PhormiKeyboardController {
    fun showKeyboardPicker(context: Context) {
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
    }
    fun showSoftKeyboard(context: Context, target: View) {
        target.requestFocus()
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(target, InputMethodManager.SHOW_IMPLICIT)
    }
}
