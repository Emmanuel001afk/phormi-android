package com.uong.phormi

import android.app.Activity
import android.os.Bundle
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout

/** Debug-only editor fixture. Not part of the release APK. */
class KeyboardTestActivity : Activity() {
    lateinit var editor: EditText
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        editor = EditText(this).apply {
            contentDescription = "Typing test field"
            inputType = intent.getIntExtra("input_type", android.text.InputType.TYPE_CLASS_TEXT)
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            setSingleLine(true)
        }
        setContentView(LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(editor) })
        editor.requestFocus()
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) editor.post {
            editor.requestFocus()
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
        }
    }
}
