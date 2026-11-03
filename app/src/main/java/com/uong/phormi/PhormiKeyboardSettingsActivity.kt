package com.uong.phormi

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import androidx.activity.result.contract.ActivityResultContracts
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** Full keyboard settings. All size controls are global percentages of the base keyboard viewport. */
class PhormiKeyboardSettingsActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private val wallpaperPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                contentResolver.takePersistableUriPermission(uri, flags)
            }
            PhormiKeyboardPreferences.setWallpaperUri(this, uri.toString())
            ToastCompat.show(this, "Keyboard wallpaper saved")
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt().coerceAtLeast(1)

    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); render() }
    override fun onResume() { super.onResume(); if (::status.isInitialized) updateStatus() }

    private fun render() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(PhormiUiColors.background(this@PhormiKeyboardSettingsActivity)) }
        val scroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = true; setPadding(dp(20), dp(18), dp(20), dp(24)) }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        content.addView(TextView(this).apply { text="Phormi Keyboard"; textSize=24f; setTextColor(PhormiUiColors.text(this@PhormiKeyboardSettingsActivity)); setPadding(0,0,0,dp(10)) })
        status=TextView(this).apply{setTextColor(PhormiUiColors.secondary(this@PhormiKeyboardSettingsActivity));textSize=14f;setPadding(0,0,0,dp(14))};content.addView(status)

        content.addView(Button(this).apply{text="Enable Phormi Keyboard";isAllCaps=false;setOnClickListener{runCatching{startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))}.onFailure{ToastCompat.show(this@PhormiKeyboardSettingsActivity,"Keyboard settings are unavailable")}}})
        content.addView(Button(this).apply{text="Choose Phormi Keyboard";isAllCaps=false;setOnClickListener{(getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showInputMethodPicker()}})

        content.addView(sectionTitle("Languages"))
        content.addView(TextView(this).apply{
            text="Character layouts: " + PhormiKeyboardLayouts.supported.joinToString(", ") { java.util.Locale(it).displayLanguage } + ". Korean supports two-set Hangul composition. Offline glide: English, French, Spanish, Portuguese, German, Italian, Indonesian and Turkish. Chinese, Japanese and Indic composition are not yet available."
            textSize=13f;setTextColor(PhormiUiColors.secondary(this@PhormiKeyboardSettingsActivity));setPadding(0,0,0,dp(8))
        })
        content.addView(Button(this).apply {
            text = "Choose Phormi language"; isAllCaps = false
            setOnClickListener {
                val languages = listOf("") + PhormiKeyboardLayouts.supported.toList()
                val labels = languages.map { if (it.isBlank()) "Follow Android keyboard language" else java.util.Locale(it).getDisplayLanguage(java.util.Locale(it)) }
                androidx.appcompat.app.AlertDialog.Builder(this@PhormiKeyboardSettingsActivity).setTitle("Keyboard language")
                    .setSingleChoiceItems(labels.toTypedArray(), languages.indexOf(PhormiKeyboardPreferences.language(this@PhormiKeyboardSettingsActivity))) { dialog, index ->
                        PhormiKeyboardPreferences.setLanguage(this@PhormiKeyboardSettingsActivity, languages[index])
                        dialog.dismiss()
                    }.show()
            }
        })
        content.addView(TextView(this).apply { text = "Select a language here, or follow Android's active keyboard subtype. Korean uses two-set Hangul: vowels and final consonants combine while typing; Backspace reverses one key. Glide is disabled in passwords, addresses and private fields."; textSize=12f; setTextColor(PhormiUiColors.secondary(this@PhormiKeyboardSettingsActivity)); setPadding(0,dp(5),0,dp(8)) })

        content.addView(sectionTitle("Keyboard size"))
        content.addView(TextView(this).apply{
            text="Use the Floating and Resize controls in Tools on the keyboard itself to position and resize it freely. Changes are saved automatically; there are no fixed percentage presets."
            textSize=13f;setTextColor(PhormiUiColors.secondary(this@PhormiKeyboardSettingsActivity));setPadding(0,0,0,dp(8))
        })
        content.addView(Button(this).apply{
            text="Reset keyboard position and size";isAllCaps=false
            setOnClickListener{
                PhormiKeyboardPreferences.resetSize(this@PhormiKeyboardSettingsActivity)
                ToastCompat.show(this@PhormiKeyboardSettingsActivity,"Keyboard position and size reset")
            }
        })

        content.addView(sectionTitle("Appearance"))
        val themes=arrayOf("Phormi","Graphite","Ocean","Light","Slate","Forest","Berry","Sand")
        themes.toList().chunked(2).forEachIndexed { rowIndex, names ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            names.forEachIndexed { column, name ->
                val index = rowIndex * 2 + column
                row.addView(Button(this).apply {
                    text = (if (PhormiKeyboardPreferences.theme(this@PhormiKeyboardSettingsActivity) == index) "✓ " else "") + name
                    isAllCaps = false
                    minimumHeight = dp(52)
                    setOnClickListener { PhormiKeyboardPreferences.setTheme(this@PhormiKeyboardSettingsActivity, index); render() }
                }, LinearLayout.LayoutParams(0, -2, 1f))
            }
            content.addView(row)
        }
        content.addView(TextView(this).apply{text="Appearance presets change the keyboard surface and key treatment without changing the keyboard's feature set.";setTextColor(PhormiUiColors.secondary(this@PhormiKeyboardSettingsActivity));textSize=12f;setPadding(0,dp(6),0,dp(8))})
        content.addView(Button(this).apply{text="Choose keyboard wallpaper";isAllCaps=false;setOnClickListener{wallpaperPicker.launch(arrayOf("image/*"))}})
        content.addView(Button(this).apply{text="Remove keyboard wallpaper";isAllCaps=false;setOnClickListener{PhormiKeyboardPreferences.setWallpaperUri(this@PhormiKeyboardSettingsActivity,null);ToastCompat.show(this@PhormiKeyboardSettingsActivity,"Keyboard wallpaper removed")}})
        content.addView(TextView(this).apply{text="Wallpaper is selected from your device and kept as a persistent document URI so the keyboard can reuse it after restart.";setTextColor(PhormiUiColors.secondary(this@PhormiKeyboardSettingsActivity));textSize=12f;setPadding(0,dp(6),0,dp(8))})

        content.addView(sectionTitle("Keyboard behavior"))
        option(content,"Suggestions","Show word suggestions when the editor does not provide its own completions.",PhormiKeyboardPreferences.suggestions(this),PhormiKeyboardPreferences.KEY_SUGGESTIONS)
        option(content,"Online English suggestions (optional)","Off by default. When enabled, ordinary English word prefixes are sent to Datamuse. Password and private fields are excluded. Voice typing uses your device’s speech provider separately.",PhormiKeyboardPreferences.onlineSuggestions(this),PhormiKeyboardPreferences.KEY_ONLINE_SUGGESTIONS)
        option(content,"Autocorrect","Apply conservative local corrections for common typing mistakes.",PhormiKeyboardPreferences.autocorrect(this),PhormiKeyboardPreferences.KEY_AUTOCORRECT)
        option(content,"Auto-capitalization","Capitalize sentence starts and the first word in a text field.",PhormiKeyboardPreferences.autoCaps(this),PhormiKeyboardPreferences.KEY_AUTO_CAPS)
        option(content,"Contextual emoji","Suggest Unicode emoji from typed context, on-device (not image stickers).",PhormiKeyboardPreferences.aiEmoji(this),PhormiKeyboardPreferences.KEY_AI_EMOJI)
        option(content,"Key vibration","Use device haptic feedback for key presses.",PhormiKeyboardPreferences.haptic(this),PhormiKeyboardPreferences.KEY_HAPTIC)
        option(content,"Key sounds","Play a short key sound where the device allows it.",PhormiKeyboardPreferences.sound(this),PhormiKeyboardPreferences.KEY_SOUND)

        scroll.addView(content);root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f));setContentView(root);updateStatus()
    }

    private fun sectionTitle(value:String)=TextView(this).apply{text=value;textSize=18f;setTextColor(PhormiUiColors.accent(this@PhormiKeyboardSettingsActivity));setPadding(0,dp(20),0,dp(7))}
    private fun updateStatus(){val imm=getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager;val serviceId="$packageName/.PhormiKeyboardServiceV2";val enabled=imm.enabledInputMethodList.any{it.id==serviceId};val selected=Settings.Secure.getString(contentResolver,Settings.Secure.DEFAULT_INPUT_METHOD)==serviceId;status.text=when{selected->"Status: enabled and currently selected as the active keyboard.";enabled->"Status: enabled, but another keyboard is currently selected.";else->"Status: not enabled yet. Enable it in Android settings, then choose Phormi as the active keyboard."};status.setTextColor(if (enabled) PhormiUiColors.success(this) else PhormiUiColors.warning(this))}
    private fun option(root:LinearLayout,title:String,summary:String,checked:Boolean,key:String){val row=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(0,dp(7),0,dp(7))};row.addView(CheckBox(this).apply{text=title;isChecked=checked;setTextColor(PhormiUiColors.text(this@PhormiKeyboardSettingsActivity));textSize=16f;setOnCheckedChangeListener{_,value->PhormiKeyboardPreferences.set(this@PhormiKeyboardSettingsActivity,key,value)}});row.addView(TextView(this).apply{text=summary;setTextColor(PhormiUiColors.secondary(this@PhormiKeyboardSettingsActivity));textSize=12f;setPadding(dp(48),0,dp(48),dp(6))});root.addView(row)}
}
private object ToastCompat{fun show(context:android.content.Context,message:String)=android.widget.Toast.makeText(context,message,android.widget.Toast.LENGTH_SHORT).show()}
