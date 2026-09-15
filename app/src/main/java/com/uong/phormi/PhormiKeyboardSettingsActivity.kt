package com.uong.phormi

import android.content.Intent
import android.graphics.Color
import android.net.Uri
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

/** Full keyboard settings. Size controls are global percentages of the base keyboard viewport. */
class PhormiKeyboardSettingsActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var heightValue: TextView
    private lateinit var widthValue: TextView
    private val wallpaperRequestCode = 4107

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt().coerceAtLeast(1)

    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); render() }
    override fun onResume() { super.onResume(); if (::status.isInitialized) updateStatus() }
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){super.onActivityResult(requestCode,resultCode,data);if(requestCode==wallpaperRequestCode&&resultCode==RESULT_OK){data?.data?.let{uri->runCatching{contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)};PhormiKeyboardPreferences.setWallpaperUri(this,uri.toString());ToastCompat.show(this,"Keyboard wallpaper saved");render()}}}

    private fun render() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(11,18,32)) }
        val scroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = true; setPadding(dp(20), dp(18), dp(20), dp(24)) }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        content.addView(TextView(this).apply { text="Phormi Keyboard"; textSize=24f; setTextColor(Color.WHITE); setPadding(0,0,0,dp(10)) })
        status=TextView(this).apply{setTextColor(Color.rgb(203,213,225));textSize=14f;setPadding(0,0,0,dp(14))};content.addView(status)
        content.addView(Button(this).apply{text="Enable Phormi Keyboard";isAllCaps=false;setOnClickListener{runCatching{startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))}.onFailure{ToastCompat.show(this@PhormiKeyboardSettingsActivity,"Keyboard settings are unavailable")}}})
        content.addView(Button(this).apply{text="Choose Phormi Keyboard";isAllCaps=false;setOnClickListener{(getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showInputMethodPicker()}})

        content.addView(sectionTitle("Languages"))
        content.addView(TextView(this).apply{text="Built-in language layers: English, French, Spanish, Portuguese, German, Italian, Indonesian, Turkish, Yoruba, Igbo, Hausa, Swahili, Arabic, Hindi, Bengali, Urdu, Punjabi, Gujarati, Tamil, Telugu, Malayalam, Thai, Vietnamese, Chinese, Japanese, Korean, Russian, Ukrainian, Polish, Dutch, Swedish, Norwegian, Danish, Finnish, Czech, Romanian, Hungarian, Greek and Hebrew.";textSize=13f;setTextColor(Color.rgb(203,213,225));setPadding(0,0,0,dp(8))})
        content.addView(Button(this).apply{text="Choose Phormi language";isAllCaps=false;setOnClickListener{runCatching{startActivity(Intent(Settings.ACTION_INPUT_METHOD_SUBTYPE_SETTINGS))}.onFailure{startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))}}})
        content.addView(TextView(this).apply{text="Prediction, correction and character input follow the selected locale. Yoruba, Igbo and Hausa are included in the local language data.";textSize=12f;setTextColor(Color.rgb(148,163,184));setPadding(0,dp(5),0,dp(8))})

        content.addView(sectionTitle("Keyboard size"))
        widthValue=TextView(this).apply{setTextColor(Color.rgb(203,213,225));textSize=13f;setPadding(0,0,0,dp(4))};content.addView(widthValue)
        val widthSeek=SeekBar(this).apply{max=30;progress=PhormiKeyboardPreferences.widthPercent(this@PhormiKeyboardSettingsActivity)-70;contentDescription="Global keyboard width percentage";setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{override fun onProgressChanged(s:SeekBar?,p:Int,fromUser:Boolean){updateWidthLabel(p+70)};override fun onStartTrackingTouch(s:SeekBar?)=Unit;override fun onStopTrackingTouch(s:SeekBar?){PhormiKeyboardPreferences.setWidthPercent(this@PhormiKeyboardSettingsActivity,(s?.progress?:30)+70)}})}
        content.addView(widthSeek,LinearLayout.LayoutParams(-1,dp(52)));updateWidthLabel(PhormiKeyboardPreferences.widthPercent(this))
        content.addView(TextView(this).apply{text="70% = compact width • 100% = full available width. The keyboard remains centered and inside the IME boundary.";setTextColor(Color.rgb(148,163,184));textSize=11f;setPadding(0,0,0,dp(8))})

        heightValue=TextView(this).apply{setTextColor(Color.rgb(203,213,225));textSize=13f;setPadding(0,0,0,dp(4))};content.addView(heightValue)
        val heightSeek=SeekBar(this).apply{max=70;progress=PhormiKeyboardPreferences.heightPercent(this@PhormiKeyboardSettingsActivity)-70;contentDescription="Global keyboard height percentage";setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{override fun onProgressChanged(s:SeekBar?,p:Int,fromUser:Boolean){updateHeightLabel(p+70)};override fun onStartTrackingTouch(s:SeekBar?)=Unit;override fun onStopTrackingTouch(s:SeekBar?){PhormiKeyboardPreferences.setHeightPercent(this@PhormiKeyboardSettingsActivity,(s?.progress?:30)+70)}})}
        content.addView(heightSeek,LinearLayout.LayoutParams(-1,dp(52)));updateHeightLabel(PhormiKeyboardPreferences.heightPercent(this))
        content.addView(TextView(this).apply{text="70%–140% continuous height. The top resize grip lets you change width and height directly, like a normal resizable keyboard.";setTextColor(Color.rgb(148,163,184));textSize=11f;setPadding(0,0,0,dp(8))})

        content.addView(sectionTitle("Appearance"))
        val themes=arrayOf("Midnight","Graphite","Ocean","Light")
        val themeRow=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER}
        themes.forEachIndexed{index,name->themeRow.addView(Button(this).apply{text=name;isAllCaps=false;setOnClickListener{PhormiKeyboardPreferences.setTheme(this@PhormiKeyboardSettingsActivity,index);ToastCompat.show(this@PhormiKeyboardSettingsActivity,"$name appearance saved")}},LinearLayout.LayoutParams(0,dp(52),1f).apply{setMargins(dp(2),0,dp(2),0)})}
        content.addView(themeRow)
        content.addView(Button(this).apply{text="Choose keyboard wallpaper";isAllCaps=false;setOnClickListener{startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply{type="image/*";addCategory(Intent.CATEGORY_OPENABLE);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)},wallpaperRequestCode)}})
        content.addView(Button(this).apply{text="Remove keyboard wallpaper";isAllCaps=false;setOnClickListener{PhormiKeyboardPreferences.setWallpaperUri(this@PhormiKeyboardSettingsActivity,null);ToastCompat.show(this@PhormiKeyboardSettingsActivity,"Keyboard wallpaper removed");render()}})
        content.addView(TextView(this).apply{text="The wallpaper is drawn on the same keyboard surface, including symbols and emoji panels.";setTextColor(Color.rgb(148,163,184));textSize=12f;setPadding(0,dp(6),0,dp(8))})

        content.addView(sectionTitle("Keyboard behavior"))
        option(content,"Suggestions","Show continuous word suggestions from the first typed character.",PhormiKeyboardPreferences.suggestions(this),PhormiKeyboardPreferences.KEY_SUGGESTIONS)
        option(content,"Autocorrect","Apply conservative local corrections and learn accepted vocabulary.",PhormiKeyboardPreferences.autocorrect(this),PhormiKeyboardPreferences.KEY_AUTOCORRECT)
        option(content,"Auto-capitalization","Capitalize sentence starts and the first word in a text field.",PhormiKeyboardPreferences.autoCaps(this),PhormiKeyboardPreferences.KEY_AUTO_CAPS)
        option(content,"AI Emoji","Show automatic contextual expressive emoji suggestions while typing.",PhormiKeyboardPreferences.aiEmoji(this),PhormiKeyboardPreferences.KEY_AI_EMOJI)
        option(content,"Key vibration","Use device haptic feedback for key presses.",PhormiKeyboardPreferences.haptic(this),PhormiKeyboardPreferences.KEY_HAPTIC)
        option(content,"Key sounds","Play a short key sound where the device allows it.",PhormiKeyboardPreferences.sound(this),PhormiKeyboardPreferences.KEY_SOUND)

        content.addView(sectionTitle("AI Emoji provider"))
        content.addView(Button(this).apply{text=if(PhormiKeyboardPreferences.pollinationsKey(this).isBlank())"Configure Pollinations API key"else"Pollinations API key configured ✓";isAllCaps=false;setOnClickListener{startActivity(Intent(this@PhormiKeyboardSettingsActivity,PhormiAiEmojiActivity::class.java).apply{addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);putExtra(PhormiAiEmojiActivity.EXTRA_CONFIG_ONLY,true)})}})
        content.addView(TextView(this).apply{text="Contextual local emoji works without an API. Pollinations is optional for richer generated reactions.";setTextColor(Color.rgb(148,163,184));textSize=12f;setPadding(0,dp(6),0,dp(8))})

        scroll.addView(content);root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f));setContentView(root);updateStatus()
    }

    private fun sectionTitle(value:String)=TextView(this).apply{text=value;textSize=18f;setTextColor(Color.rgb(56,189,248));setPadding(0,dp(20),0,dp(7))}
    private fun updateWidthLabel(value:Int){if(::widthValue.isInitialized)widthValue.text="$value% global keyboard width"}
    private fun updateHeightLabel(value:Int){if(::heightValue.isInitialized)heightValue.text="$value% global keyboard height"}
    private fun updateStatus(){val imm=getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager;val serviceId="$packageName/.PhormiKeyboardServiceV2";val enabled=imm.enabledInputMethodList.any{it.id==serviceId};val selected=Settings.Secure.getString(contentResolver,Settings.Secure.DEFAULT_INPUT_METHOD)==serviceId;status.text=when{selected->"Status: enabled and currently selected as the active keyboard.";enabled->"Status: enabled, but another keyboard is currently selected.";else->"Status: not enabled yet. Enable it in Android settings, then choose Phormi as the active keyboard."};status.setTextColor(if(enabled)Color.rgb(134,239,172)else Color.rgb(248,196,113))}
    private fun option(root:LinearLayout,title:String,summary:String,checked:Boolean,key:String){val row=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(0,dp(7),0,dp(7))};row.addView(CheckBox(this).apply{text=title;isChecked=checked;setTextColor(Color.WHITE);textSize=16f;setOnCheckedChangeListener{_,value->PhormiKeyboardPreferences.set(this@PhormiKeyboardSettingsActivity,key,value)}});row.addView(TextView(this).apply{text=summary;setTextColor(Color.rgb(148,163,184));textSize=12f;setPadding(dp(48),0,dp(48),dp(6))});root.addView(row)}
}
private object ToastCompat{fun show(context:android.content.Context,message:String)=android.widget.Toast.makeText(context,message,android.widget.Toast.LENGTH_SHORT).show()}
