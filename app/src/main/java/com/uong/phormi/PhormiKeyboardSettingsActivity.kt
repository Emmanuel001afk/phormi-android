package com.uong.phormi

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** Dedicated keyboard settings surface with an explicit enable/select flow. */
class PhormiKeyboardSettingsActivity : AppCompatActivity() {
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render()
    }

    override fun onResume() {
        super.onResume()
        if (::status.isInitialized) updateStatus()
    }

    private fun render() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 28)
            setBackgroundColor(android.graphics.Color.rgb(11, 18, 32))
        }
        root.addView(TextView(this).apply {
            text = "Phormi Keyboard"
            textSize = 24f
            setTextColor(android.graphics.Color.WHITE)
            setPadding(0, 0, 0, 12)
        })
        status = TextView(this).apply {
            setTextColor(android.graphics.Color.rgb(203, 213, 225))
            textSize = 14f
            setPadding(0, 0, 0, 18)
        }
        root.addView(status)

        root.addView(Button(this).apply {
            text = "Enable Phormi Keyboard"
            setOnClickListener {
                runCatching { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
                    .onFailure { ToastCompat.show(this@PhormiKeyboardSettingsActivity, "Keyboard settings are unavailable") }
            }
        })
        root.addView(Button(this).apply {
            text = "Choose Phormi Keyboard"
            setOnClickListener {
                (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showInputMethodPicker()
            }
        })
        root.addView(Button(this).apply {
            text = "Language & subtype settings"
            setOnClickListener {
                runCatching { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SUBTYPE_SETTINGS)) }
                    .onFailure { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
            }
        })

        root.addView(TextView(this).apply {
            text = "Keyboard behavior"
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(android.graphics.Color.rgb(56, 189, 248))
            textSize = 16f
            setPadding(0, 22, 0, 8)
        })
        option(root, "Suggestions", "Show word suggestions when the editor does not provide its own completions.", PhormiKeyboardPreferences.suggestions(this), PhormiKeyboardPreferences.KEY_SUGGESTIONS)
        option(root, "Autocorrect", "Apply conservative local corrections for common typing mistakes.", PhormiKeyboardPreferences.autocorrect(this), PhormiKeyboardPreferences.KEY_AUTOCORRECT)
        option(root, "Auto-capitalization", "Capitalize the beginning of sentences and the first word in a text field.", PhormiKeyboardPreferences.autoCaps(this), PhormiKeyboardPreferences.KEY_AUTO_CAPS)
        option(root, "Key vibration", "Use the device haptic feedback setting for key presses.", PhormiKeyboardPreferences.haptic(this), PhormiKeyboardPreferences.KEY_HAPTIC)
        option(root, "Key sounds", "Play a short key sound where the device allows it.", PhormiKeyboardPreferences.sound(this), PhormiKeyboardPreferences.KEY_SOUND)
        setContentView(root)
        updateStatus()
    }

    private fun updateStatus() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        val serviceId = "$packageName/.PhormiKeyboardService"
        val enabled = imm.enabledInputMethodList.any { it.id == serviceId }
        val selected = imm.currentInputMethodInfo?.id == serviceId
        status.text = when {
            selected -> "Status: enabled and currently selected as the active keyboard."
            enabled -> "Status: enabled, but another keyboard is currently selected."
            else -> "Status: not enabled yet. Tap ‘Enable Phormi Keyboard’, enable it in Android settings, then return here and choose it."
        }
        status.setTextColor(if (enabled) android.graphics.Color.rgb(134, 239, 172) else android.graphics.Color.rgb(248, 196, 113))
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
        row.addView(TextView(this).apply {
            text = summary
            setTextColor(android.graphics.Color.rgb(148, 163, 184))
            textSize = 12f
            setPadding(48, 0, 0, 8)
        })
        root.addView(row)
    }
}

private object ToastCompat {
    fun show(context: android.content.Context, message: String) = android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
}
