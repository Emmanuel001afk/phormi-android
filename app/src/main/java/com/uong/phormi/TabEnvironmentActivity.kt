package com.uong.phormi

import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebViewFeature

/**
 * Named browsing environments. Each environment has separate WebView cookies,
 * storage and site sessions. This screen also provides a simple URL launcher
 * for legitimate manual testing inside the selected environment.
 */
class TabEnvironmentActivity : AppCompatActivity() {
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render()
    }

    private fun render() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            setBackgroundColor(0xFF0B1220.toInt())
        }
        root.addView(TextView(this).apply {
            text = "Tab environments"
            textSize = 24f
            setTextColor(0xFFF8FAFC.toInt())
        })
        root.addView(TextView(this).apply {
            text = "Each environment has separate website cookies and storage. The same website can therefore hold different legitimate accounts in different environments."
            textSize = 14f
            setTextColor(0xFF94A3B8.toInt())
            setPadding(0, 10, 0, 10)
        })

        val url = EditText(this).apply {
            hint = "Paste a website or test/referral link"
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        root.addView(url, LinearLayout.LayoutParams(-1, -2))
        root.addView(Button(this).apply {
            text = "Open in selected environment"
            setOnClickListener {
                val raw = url.text.toString().trim()
                if (raw.isBlank()) return@setOnClickListener
                val normalized = if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "https://$raw"
                val selected = selectedEnvironment()
                if (selected != PhormiEnvironmentManager.DEFAULT_ENVIRONMENT) PhormiEnvironmentManager.ensure(selected)
                PhormiEnvironmentManager.touch(this@TabEnvironmentActivity, selected)
                PhormiCommandBus.enqueue(this@TabEnvironmentActivity, "open_url", mapOf("open_url" to normalized))
                Toast.makeText(this@TabEnvironmentActivity, "Opening in $selected", Toast.LENGTH_SHORT).show()
                finish()
            }
        }, LinearLayout.LayoutParams(-1, -2))
        root.addView(TextView(this).apply {
            text = "Inactive named environments are automatically removed after 30 days. Active/open environments are protected."
            textSize = 12f
            setTextColor(0xFF64748B.toInt())
            setPadding(0, 8, 0, 16)
        })

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(Button(this).apply {
            text = "New environment"
            setOnClickListener { promptNewEnvironment() }
        }, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)
        renderProfiles()
    }

    private fun selectedEnvironment(): String =
        getSharedPreferences("phormi_tabs", MODE_PRIVATE)
            .getString("tab_environment", PhormiEnvironmentManager.DEFAULT_ENVIRONMENT)
            ?.trim()?.takeIf { it.isNotBlank() } ?: PhormiEnvironmentManager.DEFAULT_ENVIRONMENT

    private fun renderProfiles() {
        list.removeAllViews()
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            list.addView(TextView(this).apply {
                text = "Separate environments are not supported by this WebView version."
                setTextColor(0xFFFCA5A5.toInt())
            })
            return
        }
        val selected = selectedEnvironment()
        val profiles = PhormiEnvironmentManager.list().toMutableList()
        if (!profiles.contains(PhormiEnvironmentManager.DEFAULT_ENVIRONMENT)) profiles.add(0, PhormiEnvironmentManager.DEFAULT_ENVIRONMENT)
        profiles.forEach { name ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 8, 0, 8)
            }
            row.addView(TextView(this).apply {
                text = if (name == selected) "$name  ✓" else name
                textSize = 17f
                setTextColor(0xFFE2E8F0.toInt())
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            })
            row.setOnClickListener {
                getSharedPreferences("phormi_tabs", MODE_PRIVATE).edit().putString("tab_environment", name).apply()
                PhormiEnvironmentManager.touch(this@TabEnvironmentActivity, name)
                Toast.makeText(this, "New tabs and the URL launcher will use $name", Toast.LENGTH_SHORT).show()
                renderProfiles()
            }
            if (name != PhormiEnvironmentManager.DEFAULT_ENVIRONMENT && name != PhormiEnvironmentManager.GHOST_ENVIRONMENT) {
                row.addView(Button(this).apply {
                    text = "Delete"
                    setOnClickListener {
                        if (PhormiEnvironmentManager.delete(name)) {
                            val prefs = getSharedPreferences("phormi_tabs", MODE_PRIVATE)
                            if (prefs.getString("tab_environment", "") == name) prefs.edit().putString("tab_environment", PhormiEnvironmentManager.DEFAULT_ENVIRONMENT).apply()
                            renderProfiles()
                        } else Toast.makeText(this@TabEnvironmentActivity, "Environment is in use or could not be deleted", Toast.LENGTH_SHORT).show()
                    }
                })
            }
            list.addView(row)
        }
    }

    private fun promptNewEnvironment() {
        val input = EditText(this).apply { hint = "Environment name"; setSingleLine(true) }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Create environment")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isBlank() || name.equals(PhormiEnvironmentManager.DEFAULT_ENVIRONMENT, true) || name.equals(PhormiEnvironmentManager.GHOST_ENVIRONMENT, true)) {
                    Toast.makeText(this, "Choose a different environment name", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (PhormiEnvironmentManager.ensure(name)) {
                    PhormiEnvironmentManager.touch(this, name)
                    getSharedPreferences("phormi_tabs", MODE_PRIVATE).edit().putString("tab_environment", name).apply()
                    renderProfiles()
                } else Toast.makeText(this, "Could not create environment", Toast.LENGTH_SHORT).show()
            }.show()
    }
}
