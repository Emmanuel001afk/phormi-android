package com.uong.phormi

import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/** Central security controls: whole-browser lock plus per-site timed locks. */
class PhormiSecurityCenterActivity : AppCompatActivity() {
    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var browserLock: BrowserLockManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("phormi_tabs", MODE_PRIVATE)
        browserLock = BrowserLockManager(this, mainExecutor)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(18,18,18,24); setBackgroundColor(0xFF08101C.toInt()) }
        val title = TextView(this).apply { text = "Phormi Security"; textSize = 22f; setTextColor(0xFFF8FAFC.toInt()); setPadding(0,0,0,16) }
        root.addView(title)
        val whole = Switch(this).apply {
            text = "Whole-browser Lock"
            setTextColor(0xFFE2E8F0.toInt())
            isChecked = browserLock.isEnabled(prefs)
            setOnCheckedChangeListener { _, checked ->
                if (checked) {
                    prefs.edit().putBoolean(BrowserLockManager.PREF_KEY, true).apply()
                    if (browserLock.method(prefs) == BrowserLockManager.METHOD_PIN && browserLock.isPinConfigured(prefs)) {
                        Toast.makeText(this@PhormiSecurityCenterActivity, "Browser Lock enabled", Toast.LENGTH_SHORT).show()
                    } else chooseMethod()
                } else prefs.edit().putBoolean(BrowserLockManager.PREF_KEY, false).apply()
            }
        }
        root.addView(whole)
        addButton(root, "Change / create Phormi PIN") { promptPin() }
        addButton(root, "Clear all per-site locks") {
            prefs.edit().remove("phormi_site_locks").apply(); Toast.makeText(this, "Per-site locks cleared", Toast.LENGTH_SHORT).show()
        }
        val note = TextView(this).apply {
            text = "Whole-browser Lock protects Phormi when you leave and return. Per-site locks are enforced when a protected site tab becomes active. A timed lock expires automatically."
            setTextColor(0xFF94A3B8.toInt()); textSize = 13f; setPadding(0,18,0,0)
        }
        root.addView(note)
        val back = Button(this).apply { text = "Close"; setOnClickListener { finish() } }
        root.addView(back)
        setContentView(root)
    }

    private fun chooseMethod() {
        AlertDialog.Builder(this).setTitle("Browser Lock method")
            .setItems(arrayOf("Device security / biometrics", "Phormi PIN")) { _, which ->
                if (which == 0) prefs.edit().putString(BrowserLockManager.PREF_METHOD, BrowserLockManager.METHOD_DEVICE).apply()
                else promptPin()
            }.setOnCancelListener { prefs.edit().putBoolean(BrowserLockManager.PREF_KEY, false).apply() }.show()
    }

    private fun promptPin() {
        val input = EditText(this).apply { inputType = 2; hint = "4+ digit PIN" }
        AlertDialog.Builder(this).setTitle("Set Phormi PIN").setView(input)
            .setPositiveButton("Save") { _, _ ->
                if (!browserLock.setPin(prefs, input.text.toString())) Toast.makeText(this, "PIN must be at least 4 digits", Toast.LENGTH_LONG).show()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun addButton(root: LinearLayout, label: String, action: () -> Unit) {
        root.addView(Button(this).apply { text = label; setOnClickListener { action() } })
    }

    private val mainExecutor = java.util.concurrent.Executor { it.run() }
}
