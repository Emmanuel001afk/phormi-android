package com.uong.phormi

import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewFeature

/**
 * Named browsing environments. Each environment is a WebView profile with its own
 * cookies, storage and site sessions, so the same website can be signed into as
 * different accounts in different Phormi tabs.
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
        val title = TextView(this).apply {
            text = "Tab environments"
            textSize = 24f
            setTextColor(0xFFF8FAFC.toInt())
        }
        val help = TextView(this).apply {
            text = "Each environment has separate website cookies and storage. Sign into the same website with different accounts by using different environments."
            textSize = 14f
            setTextColor(0xFF94A3B8.toInt())
            setPadding(0, 10, 0, 18)
        }
        root.addView(title)
        root.addView(help)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        val add = Button(this).apply {
            text = "New environment"
            setOnClickListener { promptNewEnvironment() }
        }
        root.addView(add, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)
        renderProfiles()
    }

    private fun renderProfiles() {
        list.removeAllViews()
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            list.addView(TextView(this).apply {
                text = "Separate environments are not supported by this WebView version."
                setTextColor(0xFFFCA5A5.toInt())
            })
            return
        }
        val selected = getSharedPreferences("phormi_tabs", MODE_PRIVATE)
            .getString("tab_environment", PhormiEnvironmentManager.DEFAULT_ENVIRONMENT)
            ?: PhormiEnvironmentManager.DEFAULT_ENVIRONMENT
        val profiles = PhormiEnvironmentManager.list().toMutableList()
        if (!profiles.contains(PhormiEnvironmentManager.DEFAULT_ENVIRONMENT)) {
            profiles.add(0, PhormiEnvironmentManager.DEFAULT_ENVIRONMENT)
        }
        profiles.forEach { name ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 8, 0, 8)
            }
            val label = TextView(this).apply {
                text = if (name == selected) "$name  ✓" else name
                textSize = 17f
                setTextColor(0xFFE2E8F0.toInt())
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            }
            row.addView(label)
            row.setOnClickListener {
                getSharedPreferences("phormi_tabs", MODE_PRIVATE).edit()
                    .putString("tab_environment", name).apply()
                Toast.makeText(this, "New tabs will use $name", Toast.LENGTH_SHORT).show()
                renderProfiles()
            }
            if (name != PhormiEnvironmentManager.DEFAULT_ENVIRONMENT && name != PhormiEnvironmentManager.GHOST_ENVIRONMENT) {
                val delete = Button(this).apply {
                    text = "Delete"
                    setOnClickListener {
                        if (PhormiEnvironmentManager.delete(name)) {
                            val prefs = getSharedPreferences("phormi_tabs", MODE_PRIVATE)
                            if (prefs.getString("tab_environment", "") == name) {
                                prefs.edit().putString("tab_environment", PhormiEnvironmentManager.DEFAULT_ENVIRONMENT).apply()
                            }
                            renderProfiles()
                        } else Toast.makeText(this@TabEnvironmentActivity, "Environment is in use or could not be deleted", Toast.LENGTH_SHORT).show()
                    }
                }
                row.addView(delete)
            }
            list.addView(row)
        }
    }

    private fun promptNewEnvironment() {
        val input = EditText(this).apply {
            hint = "Environment name"
            setSingleLine(true)
        }
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
                    getSharedPreferences("phormi_tabs", MODE_PRIVATE).edit().putString("tab_environment", name).apply()
                    renderProfiles()
                } else Toast.makeText(this, "Could not create environment", Toast.LENGTH_SHORT).show()
            }
            .show()
    }
}
