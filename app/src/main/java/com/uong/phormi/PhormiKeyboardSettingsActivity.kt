package com.uong.phormi

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** Full keyboard settings. All size controls are global percentages of the base keyboard viewport. */
class PhormiKeyboardSettingsActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var heightValue: TextView

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt().coerceAtLeast(1)

    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); render() }
    override fun onResume() { super.onResume(); if (::status.isInitialized) updateStatus() }

    private fun render() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(11,18,32)) }
        val scroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = true; setPadding(dp(20), dp(18), dp(20), dp(24)) }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        content.addView(TextView(this).apply { text="Phormi Keyboard"; textSize=24f; setTextColor(Color.WHITE); setPadding(0,0,0,dp(10)) })
        status=TextView(this).apply{setTextColor(Color.rgb(203,213,225));textSize=14f;setPadding(0,0,0,dp(14))};content.addView(status)

        content.addView(Button(this).apply{text="Enable Phormi Keyboard";isAllCaps=false;setOnClickListener{runCatching{startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))}.onFailure{ToastCompat.show(this@PhormiKeyboardSettingsActivity,"Keyboard settings are unavailable")}}})
        content.addView(Button(this).apply{text="Choose Phormi Keyboard";isAllCaps=false;setOnClickListener{(getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showInputMethodPicker()}})

        content.addView(sectionTitle("Languages"))
        content.addView(TextView(this).apply{
            text="Built-in language layers: English, French, Spanish, Portuguese, German, Italian, Indonesian, Turkish, Yoruba, Igbo, Hausa, Swahili, Arabic, Hindi, Bengali, Urdu, Punjabi, Gujarati, Tamil, Telugu, Malayalam, Thai, Vietnamese, Chinese, Japanese, Korean, Russian, Ukrainian, Polish, Dutch, Swedish, Norwegian, Danish, Finnish, Czech, Romanian, Hungarian, Greek and Hebrew."
            textSize=13f;setTextColor(Color.rgb(203,213,225));setPadding(0,0,0,dp(8))
        })
        content.addView(Button(this).apply{text="Choose Phormi language";isAllCaps=false;setOnClickListener{runCatching{startActivity(Intent(Settings.ACTION_INPUT_METHOD_SUBTYPE_SETTINGS))}.onFailure{startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))}}})
        content.addView(TextView(this).apply{text="Android controls active IME subtypes. Prediction, correction and character long-presses follow the selected locale; installed system spell-check dictionaries are also used when available.";textSize=12f;setTextColor(Color.rgb(148,163,184));setPadding(0,dp(5),0,dp(8))})

        content.addView(sectionTitle("Keyboard size"))
        heightValue=TextView(this).apply{setTextColor(Color.rgb(203,213,225));textSize=13f;setPadding(0,0,0,dp(4))};content.addView(heightValue)
        val heightSeek=SeekBar(this).apply{
            max=6;progress=PhormiKeyboardPreferences.height(this@PhormiKeyboardSettingsActivity);contentDescription="Global keyboard height percentage"
            setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
                override fun onProgressChanged(seekBar:SeekBar?,progress:Int,fromUser:Boolean){updateHeightLabel(progress)}
                override fun onStartTrackingTouch(seekBar:SeekBar?)=Unit
                override fun onStopTrackingTouch(seekBar:SeekBar?){PhormiKeyboardPreferences.setHeight(this@PhormiKeyboardSettingsActivity,seekBar?.progress?:3)}
            })
        }
        content.addView(heightSeek,LinearLayout.LayoutParams(-1,dp(52)))
        content.addView(TextView(this).apply{text="85%  •  92%  •  97%  •  100%  •  108%  •  117%  •  127%";setTextColor(Color.rgb(148,163,184));textSize=11f;setPadding(0,0,0,dp(8))})
        updateHeightLabel(heightSeek.progress)
        content.addView(TextView(this).apply{text="The same global percentage applies to the keyboard viewport and its key geometry. Emoji/media panels remain inside that same viewport and scroll internally.";textSize=12f;setTextColor(Color.rgb(148,163,184));setPadding(0,0,0,dp(8))})

        content.addView(sectionTitle("Appearance"))
        val themes=arrayOf("Midnight","Graphite","Ocean","Light")
        val themeRow=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER}
        themes.forEachIndexed{index,name->themeRow.addView(Button(this).apply{text=name;isAllCaps=false;setOnClickListener{PhormiKeyboardPreferences.setTheme(this@PhormiKeyboardSettingsActivity,index);ToastCompat.show(this@PhormiKeyboardSettingsActivity,"$name appearance saved")}},LinearLayout.LayoutParams(0,dp(52),1f).apply{setMargins(dp(2),0,dp(2),0)})}
        content.addView(themeRow)
        content.addView(TextView(this).apply{text="Appearance presets change the keyboard surface and key treatment without changing the keyboard's feature set.";setTextColor(Color.rgb(148,163,184));textSize=12f;setPadding(0,dp(6),0,dp(8))})

        content.addView(sectionTitle("Keyboard behavior"))
        option(content,"Suggestions","Show word suggestions when the editor does not provide its own completions.",PhormiKeyboardPreferences.suggestions(this),PhormiKeyboardPreferences.KEY_SUGGESTIONS)
        option(content,"Autocorrect","Apply conservative local corrections for common typing mistakes.",PhormiKeyboardPreferences.autocorrect(this),PhormiKeyboardPreferences.KEY_AUTOCORRECT)
        option(content,"Auto-capitalization","Capitalize sentence starts and the first word in a text field.",PhormiKeyboardPreferences.autoCaps(this),PhormiKeyboardPreferences.KEY_AUTO_CAPS)
        option(content,"AI Emoji","Allow context-aware reactions from typed text.",PhormiKeyboardPreferences.aiEmoji(this),PhormiKeyboardPreferences.KEY_AI_EMOJI)
        option(content,"Key vibration","Use device haptic feedback for key presses.",PhormiKeyboardPreferences.haptic(this),PhormiKeyboardPreferences.KEY_HAPTIC)
        option(content,"Key sounds","Play a short key sound where the device allows it.",PhormiKeyboardPreferences.sound(this),PhormiKeyboardPreferences.KEY_SOUND)

        scroll.addView(content);root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f));setContentView(root);updateStatus()
    }

    private fun sectionTitle(value:String)=TextView(this).apply{text=value;textSize=18f;setTextColor(Color.rgb(56,189,248));setPadding(0,dp(20),0,dp(7))}
    private fun updateHeightLabel(progress:Int){if(::heightValue.isInitialized){val scales=arrayOf("85%","92%","97%","100%","108%","117%","127%");heightValue.text="${scales[progress.coerceIn(0,6)]} global keyboard height"}}
    private fun updateStatus(){val imm=getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager;val serviceId="$packageName/.PhormiKeyboardServiceV2";val enabled=imm.enabledInputMethodList.any{it.id==serviceId};val selected=Settings.Secure.getString(contentResolver,Settings.Secure.DEFAULT_INPUT_METHOD)==serviceId;status.text=when{selected->"Status: enabled and currently selected as the active keyboard.";enabled->"Status: enabled, but another keyboard is currently selected.";else->"Status: not enabled yet. Enable it in Android settings, then choose Phormi as the active keyboard."};status.setTextColor(if(enabled)Color.rgb(134,239,172)else Color.rgb(248,196,113))}
    private fun option(root:LinearLayout,title:String,summary:String,checked:Boolean,key:String){val row=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(0,dp(7),0,dp(7))};row.addView(CheckBox(this).apply{text=title;isChecked=checked;setTextColor(Color.WHITE);textSize=16f;setOnCheckedChangeListener{_,value->PhormiKeyboardPreferences.set(this@PhormiKeyboardSettingsActivity,key,value)}});row.addView(TextView(this).apply{text=summary;setTextColor(Color.rgb(148,163,184));textSize=12f;setPadding(dp(48),0,dp(48),dp(6))});root.addView(row)}
}
private object ToastCompat{fun show(context:android.content.Context,message:String)=android.widget.Toast.makeText(context,message,android.widget.Toast.LENGTH_SHORT).show()}
