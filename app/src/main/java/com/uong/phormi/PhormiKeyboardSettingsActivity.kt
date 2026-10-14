package com.uong.phormi

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/** Keyboard controls stay touch-sized on phones and share the browser's accent contract. */
class PhormiKeyboardSettingsActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private val wallpaperPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            PhormiKeyboardPreferences.setWallpaperUri(this, uri.toString())
            toast("Keyboard wallpaper saved")
        }
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); render() }
    override fun onResume() { super.onResume(); if (::status.isInitialized) updateStatus() }

    private fun render() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(12, 18, 31)) }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(18), dp(20), dp(24)) }
        content.addView(TextView(this).apply { text = "Phormi Keyboard"; textSize = 25f; setTextColor(Color.WHITE); setPadding(0, 0, 0, dp(10)) })
        status = TextView(this).apply { textSize = 14f; setPadding(0, 0, 0, dp(14)) }; content.addView(status)
        button(content, "Enable Phormi Keyboard") { runCatching { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }.onFailure { toast("Android keyboard settings are unavailable") } }
        button(content, "Choose Phormi Keyboard") { (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showInputMethodPicker() }

        section(content, "Language + typing")
        description(content, "English QWERTY, French AZERTY, German/Czech QWERTZ, Spanish, Turkish, Russian, Ukrainian, Arabic, Greek and Hebrew layouts. Long-press letters for accents, including Yoruba, Igbo and Hausa. Offline starter dictionaries follow your active Android keyboard language; device spell-check dictionaries supplement them where installed.")
        button(content, "Choose active keyboard languages") {
            runCatching { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SUBTYPE_SETTINGS)) }
                .onFailure { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
        }
        option(content, "Suggestions", "The spelling you typed is always offered. Personal words join predictions after three completed uses.", PhormiKeyboardPreferences.suggestions(this)) { PhormiKeyboardPreferences.set(this, PhormiKeyboardPreferences.KEY_SUGGESTIONS, it) }
        option(content, "Conservative autocorrect", "Backspace immediately after a correction to undo it. Reject a spelling three times and Phormi keeps your choice. URLs, email addresses, passwords and PINs are never autocorrected.", PhormiKeyboardPreferences.autocorrect(this)) { PhormiKeyboardPreferences.set(this, PhormiKeyboardPreferences.KEY_AUTOCORRECT, it) }
        option(content, "Automatic capitalization", "Respect the text field's sentence, word and uppercase settings.", PhormiKeyboardPreferences.autoCaps(this)) { PhormiKeyboardPreferences.set(this, PhormiKeyboardPreferences.KEY_AUTO_CAPS, it) }
        option(content, "Private typing", "Disable personal learning, suggestions and AI context while this mode is on. Password/PIN fields are always private.", PhormiKeyboardPreferences.incognito(this)) { PhormiKeyboardPreferences.set(this, PhormiKeyboardPreferences.KEY_INCOGNITO, it) }
        button(content, "Clear personal dictionary") {
            AlertDialog.Builder(this).setTitle("Clear learned words and corrections?")
                .setMessage("Built-in dictionaries remain available. This cannot be undone.")
                .setPositiveButton("Clear") { _, _ -> PhormiKeyboardTextEngine.clearPersonalDictionary(this); toast("Personal dictionary cleared") }
                .setNegativeButton("Keep", null).show()
        }

        section(content, "Appearance")
        option(content, "Match browser theme", "Follow Phormi's System/Light/Dark setting. Browser and keyboard always share the chosen accent.", PhormiKeyboardPreferences.followBrowser(this)) { PhormiKeyboardPreferences.setFollowBrowser(this, it) }
        button(content, "Browser + keyboard accent") { PhormiAppearanceDialogs.accent(this) { toast("Shared accent saved"); render() } }
        val themes = listOf("Midnight", "Graphite", "Ocean", "Light", "Slate", "Forest", "Rose", "Sand")
        themes.withIndex().chunked(2).forEach { pair ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEach { (index, name) ->
                row.addView(Button(this).apply {
                    text = name; isAllCaps = false
                    setOnClickListener { PhormiKeyboardPreferences.setTheme(this@PhormiKeyboardSettingsActivity, index); toast("$name keyboard theme saved"); render() }
                }, LinearLayout.LayoutParams(0, dp(52), 1f).apply { setMargins(dp(2), 0, dp(2), 0) })
            }
            content.addView(row)
        }
        button(content, "Built-in wallpapers / use my photo") {
            PhormiAppearanceDialogs.wallpaper(this, true, { wallpaperPicker.launch(arrayOf("image/*")) }) {
                PhormiKeyboardPreferences.setWallpaperUri(this, null); toast("Offline keyboard wallpaper saved")
            }
        }
        button(content, "Plain keyboard background") { PhormiKeyboardPreferences.setWallpaperUri(this, null); PhormiWallpapers.select(this, "none", true); toast("Keyboard wallpaper removed") }

        section(content, "Size + feedback")
        description(content, "Open Tools on the keyboard to float, move or resize it. Every panel keeps the same viewport so Emoji, Write and Voice do not move the app underneath you.")
        button(content, "Reset keyboard position and size") { PhormiKeyboardPreferences.resetSize(this); toast("Position and size reset") }
        option(content, "Key vibration", "Use Android keyboard haptic feedback.", PhormiKeyboardPreferences.haptic(this)) { PhormiKeyboardPreferences.set(this, PhormiKeyboardPreferences.KEY_HAPTIC, it) }
        option(content, "Key sounds", "Play a click when the device permits it.", PhormiKeyboardPreferences.sound(this)) { PhormiKeyboardPreferences.set(this, PhormiKeyboardPreferences.KEY_SOUND, it) }

        section(content, "Writing + emoji")
        description(content, "Write offers grammar fixes, rewriting, shortening, continuation and translation through the AI connection you enable in Phormi. Review each proposal before using it. Voice uses your installed Android speech service and requests microphone access only when tapped.")
        option(content, "AI emoji tools", "Show explicit image-generation tools. Ordinary typing is never automatically sent to an image service.", PhormiKeyboardPreferences.aiEmoji(this)) { PhormiKeyboardPreferences.set(this, PhormiKeyboardPreferences.KEY_AI_EMOJI, it) }
        button(content, "Configure Phormi AI") { startActivity(Intent(this, AiActivity::class.java)) }
        button(content, "Configure AI emoji image service") { startActivity(Intent(this, PhormiAiEmojiActivity::class.java).putExtra(PhormiAiEmojiActivity.EXTRA_CONFIG_ONLY, true)) }
        val scroll = ScrollView(this).apply { addView(content) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, -1)); setContentView(root)
        PhormiInsets.apply(this, root)
        updateStatus()
    }
    private fun section(root: LinearLayout, value: String) { root.addView(TextView(this).apply { text = value; textSize = 18f; setTextColor(PhormiAppearance.accent(this@PhormiKeyboardSettingsActivity, true)); setPadding(0, dp(22), 0, dp(7)) }) }
    private fun description(root: LinearLayout, value: String) { root.addView(TextView(this).apply { text = value; textSize = 13f; setTextColor(Color.rgb(178, 190, 211)); setPadding(0, 0, 0, dp(8)) }) }
    private fun button(root: LinearLayout, value: String, click: () -> Unit) { root.addView(Button(this).apply { text = value; isAllCaps = false; setOnClickListener { click() } }, LinearLayout.LayoutParams(-1, -2)) }
    private fun option(root: LinearLayout, title: String, summary: String, checked: Boolean, save: (Boolean) -> Unit) {
        root.addView(CheckBox(this).apply { text = title; isChecked = checked; textSize = 16f; setTextColor(Color.WHITE); setOnCheckedChangeListener { _, value -> save(value) } })
        description(root, summary)
    }
    private fun updateStatus() {
        val manager = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        val serviceId = "$packageName/.PhormiKeyboardServiceV2"
        val enabled = manager.enabledInputMethodList.any { it.id == serviceId }
        val selected = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) == serviceId
        status.text = if (selected) "Enabled and selected — ready to type." else if (enabled) "Enabled. Choose Phormi as your active keyboard." else "Enable Phormi in Android settings, then select it."
        status.setTextColor(if (enabled) Color.rgb(125, 224, 174) else Color.rgb(244, 189, 99))
    }
    private fun toast(message: String) = android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
}
