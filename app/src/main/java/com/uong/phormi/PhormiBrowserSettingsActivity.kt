package com.uong.phormi

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/** Browsing preferences are separate from page actions and optional features. */
class PhormiBrowserSettingsActivity : AppCompatActivity() {
    private val prefs by lazy { getSharedPreferences(PhormiBrowserPreferences.STORE, MODE_PRIVATE) }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(PhormiUiColors.surface(this@PhormiBrowserSettingsActivity))
        }
        val header = TextView(this).apply {
            text = "‹    Browser settings"
            textSize = 21f
            setTextColor(PhormiUiColors.text(this@PhormiBrowserSettingsActivity))
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), 0, dp(20), 0)
            setOnClickListener { finish() }
            contentDescription = "Back to browser menu"
        }
        root.addView(header, LinearLayout.LayoutParams(-1, dp(54)))
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(24))
        }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        section(content, "Reading")
        val zoomLabel = description(content, "Website text size · ${PhormiBrowserPreferences.textZoom(this)}%")
        val preview = TextView(this).apply {
            text = "A more comfortable way to read the web."
            setTextColor(PhormiUiColors.text(this@PhormiBrowserSettingsActivity))
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = GradientDrawable().apply {
                setColor(PhormiUiColors.surface(this@PhormiBrowserSettingsActivity)); cornerRadius = dp(16).toFloat()
            }
        }
        val slider = SeekBar(this).apply {
            max = 24
            progress = ((PhormiBrowserPreferences.textZoom(this@PhormiBrowserSettingsActivity) - 80) / 5)
            contentDescription = "Website text size from 80 to 200 percent"
        }
        fun updateZoom(progress: Int) {
            val zoom = 80 + progress * 5
            prefs.edit().putInt(PhormiBrowserPreferences.TEXT_ZOOM, zoom).apply()
            zoomLabel.text = "Website text size · $zoom%"
            preview.textSize = 16f * zoom / 100f
        }
        updateZoom(slider.progress)
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) updateZoom(progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        content.addView(slider)
        content.addView(preview, LinearLayout.LayoutParams(-1, -2))
        description(content, "Applies to normal browser tabs when you return. Some websites set fixed-size text that cannot be enlarged by WebView.")
        button(content, "Reset text size") { slider.progress = 4; updateZoom(4) }

        section(content, "Controls")
        option(content, "Show bottom navigation bar", "When enabled, the bottom bar owns Back and Forward; the address, refresh, tabs and menu stay in the top row. When hidden, Back and Forward sit at the start of the top row, Chrome-style.",
            PhormiBrowserPreferences.BOTTOM_TOOLBAR, false)
        option(content, "Pull to refresh", "Off by default so an ordinary up/down scroll never reloads a page. The top-bar ↻ button is the normal way to refresh; when this is on, use a long deliberate pull at the very top of a page. Holding two fingers still on a page always refreshes, with or without this setting.",
            PhormiBrowserPreferences.PULL_TO_REFRESH, false)
        description(content, "Top bar: ${PhormiBrowserChrome.topSummary()}. Refresh is always visible: it shows × while a page loads (tap to stop) and ↻ when the page is idle. On a blank tab it is empty, exactly like Chrome. In the menu: ${PhormiBrowserChrome.menuHomeSummary()}.")

        section(content, "Networks and data")
        option(content, "Flag local-network HTTP pages", "LAN addresses such as a transfer server, router or NAS at 192.168.1.20:8080 plus .local names always open. This controls whether they are labelled 'local network' in site information. Public sites always keep the 'continue over HTTP' warning.",
            PhormiNetworkPolicy.KEY_ALLOW_LAN_HTTP, true)
        button(content, "Data saver · ${PhormiDataSaver.label(dataSaverMode())}") { showDataSaverChooser() }
        description(content, "Cache-first loading, no media autoplay, and an optional image block. WebView has no compression proxy, so this saves data honestly rather than promising a percentage.")
        button(content, "Allow again: HTTP sites I blocked") {
            prefs.edit().remove(PhormiNetworkPolicy.KEY_CLEARTEXT_HOSTS).apply()
            AlertDialog.Builder(this)
                .setMessage("Cleared. A public HTTP address will ask for permission again before opening.")
                .setPositiveButton("OK", null)
                .show()
        }

        section(content, "Website layout")
        content.addView(CheckBox(this).apply {
            text = "Use desktop sites by default"
            textSize = 16f
            minHeight = dp(48)
            setTextColor(PhormiUiColors.text(this@PhormiBrowserSettingsActivity))
            isChecked = prefs.getBoolean(PhormiDesktopMode.KEY_GLOBAL, false)
            setOnCheckedChangeListener { _, value ->
                prefs.edit()
                    .putBoolean(PhormiDesktopMode.KEY_GLOBAL, value)
                    .putStringSet(PhormiDesktopMode.KEY_DESKTOP_HOSTS, emptySet())
                    .putStringSet(PhormiDesktopMode.KEY_MOBILE_HOSTS, emptySet())
                    .putBoolean(PhormiDesktopMode.KEY_POLICY_DIRTY, true)
                    .apply()
            }
        })
        description(content, "The Desktop site menu item switches the current website between desktop and mobile. Changing this default clears per-site choices and reloads open websites when you return to the browser.")

        section(content, "Tabs and sessions")
        button(content, "Live page budget · ${PhormiBrowserPreferences.liveTabBudget(this)}") {
            val choices = intArrayOf(2, 3, 4, 6, 8)
            AlertDialog.Builder(this).setTitle("Live page budget")
                .setItems(choices.map { "$it live pages" }.toTypedArray()) { _, index ->
                    prefs.edit().putInt("live_tab_budget", choices[index]).apply()
                    recreate()
                }.show()
        }
        description(content, "Inactive pages are saved and their WebViews destroyed to release memory. Switching back recreates the page and its navigation history. Active/split tabs, AI tasks, device access, playing media and unsaved edits are protected; these may temporarily exceed the budget. Everything stays on this device.")
        button(content, "Tab retention · ${retentionLabel()}") { showRetentionChooser() }
        description(content, "Normal tabs have independent pages and navigation. Sign-ins are shared across tabs in the same browsing environment, as in other browsers. Use a separate environment for another account.")
        button(content, "Separate account environments") {
            startActivity(android.content.Intent(this, TabEnvironmentActivity::class.java))
        }
        button(content, "Accounts and sign-in") {
            startActivity(android.content.Intent(this, AccountsActivity::class.java))
        }
    }

    private fun dataSaverMode(): String =
        prefs.getString(PhormiDataSaver.KEY_MODE, PhormiDataSaver.OFF) ?: PhormiDataSaver.OFF

    private fun showDataSaverChooser() {
        val labels = PhormiDataSaver.modes.map { PhormiDataSaver.label(it) }.toTypedArray()
        val checked = PhormiDataSaver.modes.indexOf(dataSaverMode()).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Data saver")
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                prefs.edit().putString(PhormiDataSaver.KEY_MODE, PhormiDataSaver.modes[which]).apply()
                dialog.dismiss()
                recreate()
            }
            .show()
    }

    private fun section(root: LinearLayout, title: String) {
        root.addView(TextView(this).apply {
            text = title
            setTextColor(PhormiUiColors.accent(this@PhormiBrowserSettingsActivity))
            textSize = 17f
            setPadding(0, dp(18), 0, dp(8))
        })
    }

    private fun description(root: LinearLayout, value: String): TextView = TextView(this).apply {
        text = value
        textSize = 13f
        setTextColor(PhormiUiColors.secondary(this@PhormiBrowserSettingsActivity))
        setPadding(dp(4), dp(6), dp(4), dp(8))
        root.addView(this)
    }

    private fun button(root: LinearLayout, label: String, action: () -> Unit) {
        root.addView(Button(this).apply {
            text = label; isAllCaps = false; setOnClickListener { action() }
        }, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(5) })
    }

    private fun option(root: LinearLayout, title: String, summary: String, key: String, default: Boolean) {
        root.addView(CheckBox(this).apply {
            text = title
            textSize = 16f
            minHeight = dp(48)
            setTextColor(PhormiUiColors.text(this@PhormiBrowserSettingsActivity))
            isChecked = prefs.getBoolean(key, default)
            setOnCheckedChangeListener { _, value -> prefs.edit().putBoolean(key, value).apply() }
        })
        description(root, summary)
    }

    private fun retentionLabel(): String = when (prefs.getString(PhormiBrowserPreferences.TAB_RETENTION, "never")) {
        "1_month" -> "1 month"; "3_months" -> "3 months"; "1_year" -> "1 year"; else -> "never"
    }

    private fun showRetentionChooser() {
        val labels = arrayOf("Never", "1 month", "3 months", "1 year")
        val values = arrayOf("never", "1_month", "3_months", "1_year")
        AlertDialog.Builder(this).setTitle("Automatically close inactive tabs")
            .setSingleChoiceItems(labels, values.indexOf(prefs.getString(PhormiBrowserPreferences.TAB_RETENTION, "never")).coerceAtLeast(0)) { dialog, index ->
                prefs.edit().putString(PhormiBrowserPreferences.TAB_RETENTION, values[index]).apply()
                dialog.dismiss()
                recreate()
            }.setNegativeButton("Cancel", null).show()
    }
}
