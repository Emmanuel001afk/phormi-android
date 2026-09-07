package com.uong.phormi

import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/** Privacy/security controls for browser engine behavior. */
class PhormiSecurityCenterActivity : AppCompatActivity() {
    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var browserLock: BrowserLockManager

    private val mainExecutor = java.util.concurrent.Executor { it.run() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("phormi_tabs", MODE_PRIVATE)
        browserLock = BrowserLockManager(this, mainExecutor)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 18, 18, 24)
            setBackgroundColor(0xFF08101C.toInt())
        }
        root.addView(TextView(this).apply {
            text = "Phormi Security & Privacy"
            textSize = 22f
            setTextColor(0xFFF8FAFC.toInt())
            setPadding(0, 0, 0, 16)
        })

        addSwitch(root, "Whole-browser Lock", browserLock.isEnabled(prefs)) { checked ->
            if (checked) {
                prefs.edit().putBoolean(BrowserLockManager.PREF_KEY, true).apply()
                if (browserLock.method(prefs) == BrowserLockManager.METHOD_PIN && browserLock.isPinConfigured(prefs)) {
                    Toast.makeText(this, "Browser Lock enabled", Toast.LENGTH_SHORT).show()
                } else chooseMethod()
            } else prefs.edit().putBoolean(BrowserLockManager.PREF_KEY, false).apply()
        }
        addSwitch(root, "JavaScript", prefs.getBoolean("security_javascript", true)) { checked ->
            prefs.edit().putBoolean("security_javascript", checked).apply()
            Toast.makeText(this, "Applies to newly configured WebViews and reloads", Toast.LENGTH_SHORT).show()
        }
        addSwitch(root, "Third-party cookies", prefs.getBoolean("security_third_party_cookies", false)) { checked ->
            prefs.edit().putBoolean("security_third_party_cookies", checked).apply()
        }
        addSwitch(root, "Mixed HTTP content", prefs.getBoolean("security_mixed_content", false)) { checked ->
            prefs.edit().putBoolean("security_mixed_content", checked).apply()
        }

        addButton(root, "Change / create Phormi PIN") { promptPin() }
        addButton(root, "Clear all per-site locks") {
            PhormiSiteLockManager.clearAll(this)
            Toast.makeText(this, "Per-site locks cleared", Toast.LENGTH_SHORT).show()
        }
        addButton(root, "Clear browser site data") { confirmClearSiteData() }
        addButton(root, "Android WebView settings") {
            runCatching { startActivity(android.content.Intent("android.settings.WEBVIEW_SETTINGS")) }
                .onFailure { Toast.makeText(this, "WebView settings are not available on this device", Toast.LENGTH_SHORT).show() }
        }
        root.addView(TextView(this).apply {
            text = "Safe Browsing and TLS certificate verification remain enforced by the WebView. Mixed content is blocked by default. JavaScript and third-party cookies can be enabled when a site genuinely requires them."
            setTextColor(0xFF94A3B8.toInt())
            textSize = 13f
            setPadding(0, 18, 0, 0)
        })
        root.addView(Button(this).apply { text = "Close"; setOnClickListener { finish() } })
        setContentView(root)
    }

    private fun addSwitch(root: LinearLayout, label: String, checked: Boolean, action: (Boolean) -> Unit) {
        root.addView(Switch(this).apply {
            text = label
            setTextColor(0xFFE2E8F0.toInt())
            isChecked = checked
            setOnCheckedChangeListener { _, value -> action(value) }
        })
    }

    private fun chooseMethod() {
        AlertDialog.Builder(this).setTitle("Browser Lock method")
            .setItems(arrayOf("Device security / biometrics", "Phormi PIN")) { _, which ->
                if (which == 0) prefs.edit().putString(BrowserLockManager.PREF_METHOD, BrowserLockManager.METHOD_DEVICE).apply()
                else promptPin()
            }
            .setOnCancelListener { prefs.edit().putBoolean(BrowserLockManager.PREF_KEY, false).apply() }
            .show()
    }

    private fun promptPin() {
        val input = EditText(this).apply { inputType = 2; hint = "4+ digit PIN" }
        AlertDialog.Builder(this).setTitle("Set Phormi PIN").setView(input)
            .setPositiveButton("Save") { _, _ ->
                if (!browserLock.setPin(prefs, input.text.toString())) Toast.makeText(this, "PIN must be at least 4 digits", Toast.LENGTH_LONG).show()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun confirmClearSiteData() {
        AlertDialog.Builder(this)
            .setTitle("Clear browser site data?")
            .setMessage("This removes cookies, Web Storage, HTTP authentication data, and WebView cache from the local browser. Websites may sign you out.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Clear") { _, _ ->
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
                WebStorage.getInstance().deleteAllData()
                runCatching { WebView(this).apply { clearCache(true); destroy() } }
                Toast.makeText(this, "Browser site data cleared", Toast.LENGTH_LONG).show()
            }.show()
    }

    private fun addButton(root: LinearLayout, label: String, action: () -> Unit) {
        root.addView(Button(this).apply { text = label; setOnClickListener { action() } })
    }
}
