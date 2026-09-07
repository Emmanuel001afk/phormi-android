package com.uong.phormi

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** Dedicated keyboard settings surface; it does not depend on the browser UI. */
class PhormiKeyboardSettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            setBackgroundColor(android.graphics.Color.rgb(11, 18, 32))
        }
        root.addView(TextView(this).apply {
            text = "Phormi Keyboard"
            textSize = 24f
            setTextColor(android.graphics.Color.WHITE)
            setPadding(0, 0, 0, 18)
        })
        root.addView(TextView(this).apply {
            text = "All settings are stored locally on this device."
            setTextColor(android.graphics.Color.rgb(148, 163, 184))
            setPadding(0, 0, 0, 18)
        })
        option(root, "Suggestions", "Show word suggestions when the editor does not provide its own completions.", PhormiKeyboardPreferences.suggestions(this), PhormiKeyboardPreferences.KEY_SUGGESTIONS)
        option(root, "Autocorrect", "Apply conservative local corrections for common typing mistakes.", PhormiKeyboardPreferences.autocorrect(this), PhormiKeyboardPreferences.KEY_AUTOCORRECT)
        option(root, "Auto-capitalization", "Capitalize the beginning of sentences and the first word in a text field.", PhormiKeyboardPreferences.autoCaps(this), PhormiKeyboardPreferences.KEY_AUTO_CAPS)
        option(root, "Key vibration", "Use the device haptic feedback setting for key presses.", PhormiKeyboardPreferences.haptic(this), PhormiKeyboardPreferences.KEY_HAPTIC)
        option(root, "Key sounds", "Play a short key sound where the device allows it.", PhormiKeyboardPreferences.sound(this), PhormiKeyboardPreferences.KEY_SOUND)
        val language = TextView(this).apply {
            text = "Language & subtype settings"
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(android.graphics.Color.rgb(56, 189, 248))
            textSize = 16f
            setPadding(0, 24, 0, 24)
            setOnClickListener {
                runCatching { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SUBTYPE_SETTINGS)) }
                    .onFailure { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
            }
        }
        root.addView(language)
        val keyboard = TextView(this).apply {
            text = "Choose active keyboard"
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(android.graphics.Color.rgb(56, 189, 248))
            textSize = 16f
            setPadding(0, 18, 0, 18)
            setOnClickListener {
                (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showInputMethodPicker()
            }
        }
        root.addView(keyboard)
        setContentView(root)
    }

    private fun option(root: LinearLayout, title: String, summary: String, checked: Boolean, key: String) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 8, 0, 8) }
        val check = CheckBox(this).apply {
            text = title
            isChecked = checked
            setTextColor(android.graphics.Color.WHITE)
            textSize = 16f
            setOnCheckedChangeListener { _, value -> PhormiKeyboardPreferences.set(this@PhormiKeyboardSettingsActivity, key, value) }
        }
        row.addView(check)
        row.addView(TextView(this).apply { text = summary; setTextColor(android.graphics.Color.rgb(148, 163, 184)); textSize = 12f; setPadding(48, 0, 0, 8) })
        root.addView(row)
    }
}
