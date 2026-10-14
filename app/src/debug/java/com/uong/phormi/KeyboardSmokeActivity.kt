package com.uong.phormi

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** Internal debug-only harness. Never included in release builds or the launcher. */
class KeyboardSmokeActivity : Activity() {
    lateinit var editor: EditText
        private set
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24); setBackgroundColor(0xFF0B1220.toInt()) }
        root.addView(TextView(this).apply { text = "Phormi keyboard integration check"; textSize = 20f; setTextColor(-1) })
        editor = EditText(this).apply {
            id = android.R.id.edit
            hint = "Type in Phormi"; setTextColor(-1); setHintTextColor(0xFF94A3B8.toInt())
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            gravity = Gravity.TOP; minLines = 3
        }
        root.addView(editor, LinearLayout.LayoutParams(-1, 220))
        setContentView(root)
        PhormiInsets.apply(this, root)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        editor.requestFocus()
    }
}
