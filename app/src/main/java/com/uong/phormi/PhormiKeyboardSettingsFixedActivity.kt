package com.uong.phormi

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.*

class PhormiKeyboardSettingsFixedActivity : Activity() {
    private fun dp(v:Int)= (v*resources.displayMetrics.density).toInt().coerceAtLeast(1)
    private lateinit var widthLabel:TextView
    private lateinit var heightLabel:TextView
    private fun label(text:String)=TextView(this).apply{this.text=text;textSize=16f;setTextColor(Color.WHITE);setPadding(dp(4),dp(12),dp(4),dp(4))}
    private fun switchRow(text:String,checked:Boolean,on:Boolean.(Boolean)->Unit):Switch=Switch(this).apply{this.text=text;this.isChecked=checked;setTextColor(Color.WHITE);setPadding(dp(4),dp(8),dp(4),dp(8));setOnCheckedChangeListener{_,v->on(checked,v)}}
    override fun onCreate(state:Bundle?){super.onCreate(state);render()}
    private fun render(){
        val scroll=ScrollView(this);val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(18),dp(18),dp(18),dp(24));setBackgroundColor(Color.rgb(13,18,30))};scroll.addView(root)
        root.addView(TextView(this).apply{text="Phormi Keyboard";textSize=25f;setTextColor(Color.WHITE);setTypeface(null,1);setPadding(0,0,0,dp(8))})
        root.addView(TextView(this).apply{text="Keyboard size, appearance, behavior and input features";textSize=13f;setTextColor(Color.LTGRAY);setPadding(0,0,0,dp(14))})
        root.addView(label("Size"))
        widthLabel=label("Width: ${PhormiKeyboardPreferences.widthPercent(this)}%");root.addView(widthLabel)
        root.addView(SeekBar(this).apply{max=30;progress=PhormiKeyboardPreferences.widthPercent(this@PhormiKeyboardSettingsFixedActivity)-70;setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{override fun onProgressChanged(s:SeekBar?,p:Int,f:Boolean){val v=p+70;PhormiKeyboardPreferences.setWidthPercent(this@PhormiKeyboardSettingsFixedActivity,v);widthLabel.text="Width: $v%"};override fun onStartTrackingTouch(s:SeekBar?){ };override fun onStopTrackingTouch(s:SeekBar?){ }})})
        heightLabel=label("Height: ${PhormiKeyboardPreferences.heightPercent(this)}%");root.addView(heightLabel)
        root.addView(SeekBar(this).apply{max=70;progress=PhormiKeyboardPreferences.heightPercent(this@PhormiKeyboardSettingsFixedActivity)-70;setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{override fun onProgressChanged(s:SeekBar?,p:Int,f:Boolean){val v=p+70;PhormiKeyboardPreferences.setHeightPercent(this@PhormiKeyboardSettingsFixedActivity,v);heightLabel.text="Height: $v%"};override fun onStartTrackingTouch(s:SeekBar?){ };override fun onStopTrackingTouch(s:SeekBar?){ }})})
        root.addView(label("Appearance"))
        val theme=Spinner(this).apply{adapter=ArrayAdapter(this@PhormiKeyboardSettingsFixedActivity,android.R.layout.simple_spinner_dropdown_item,arrayOf("Phormi Dark","Slate","Ocean","Light"));setSelection(PhormiKeyboardPreferences.theme(this@PhormiKeyboardSettingsFixedActivity));onItemSelectedListener=object:android.widget.AdapterView.OnItemSelectedListener{override fun onNothingSelected(p:android.widget.AdapterView<*>?){};override fun onItemSelected(p:android.widget.AdapterView<*>?,v:android.view.View?,pos:Int,id:Long){PhormiKeyboardPreferences.setTheme(this@PhormiKeyboardSettingsFixedActivity,pos)}}};root.addView(theme,LinearLayout.LayoutParams(-1,dp(48)))
        root.addView(Button(this).apply{text="Choose keyboard wallpaper";setOnClickListener{startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply{type="image/*";addCategory(Intent.CATEGORY_OPENABLE)},42)}})
        root.addView(label("Behavior"))
        root.addView(switchRow("Suggestions from the first character",PhormiKeyboardPreferences.suggestions(this)){_,v->PhormiKeyboardPreferences.set(this@PhormiKeyboardSettingsFixedActivity,PhormiKeyboardPreferences.KEY_SUGGESTIONS,v)})
        root.addView(switchRow("Autocorrect",PhormiKeyboardPreferences.autocorrect(this)){_,v->PhormiKeyboardPreferences.set(this@PhormiKeyboardSettingsFixedActivity,PhormiKeyboardPreferences.KEY_AUTOCORRECT,v)})
        root.addView(switchRow("Automatic capitalization",PhormiKeyboardPreferences.autoCaps(this)){_,v->PhormiKeyboardPreferences.set(this@PhormiKeyboardSettingsFixedActivity,PhormiKeyboardPreferences.KEY_AUTO_CAPS,v)})
        root.addView(switchRow("Haptic key feedback",PhormiKeyboardPreferences.haptic(this)){_,v->PhormiKeyboardPreferences.set(this@PhormiKeyboardSettingsFixedActivity,PhormiKeyboardPreferences.KEY_HAPTIC,v)})
        root.addView(switchRow("Key sounds",PhormiKeyboardPreferences.sound(this)){_,v->PhormiKeyboardPreferences.set(this@PhormiKeyboardSettingsFixedActivity,PhormiKeyboardPreferences.KEY_SOUND,v)})
        root.addView(switchRow("Automatic contextual AI emoji",PhormiKeyboardPreferences.aiEmoji(this)){_,v->PhormiKeyboardPreferences.set(this@PhormiKeyboardSettingsFixedActivity,PhormiKeyboardPreferences.KEY_AI_EMOJI,v)})
        root.addView(switchRow("Floating keyboard appearance",getSharedPreferences("phormi_keyboard_fixed",MODE_PRIVATE).getBoolean("floating",false)){_,v->getSharedPreferences("phormi_keyboard_fixed",MODE_PRIVATE).edit().putBoolean("floating",v).apply()})
        root.addView(label("Languages"));root.addView(TextView(this).apply{text="Phormi uses the editor locale and its multilingual language data, including Yoruba, Hausa, Igbo and other supported languages. Use the globe key to switch input methods when more than one is installed.";textSize=13f;setTextColor(Color.LTGRAY);setPadding(dp(4),dp(4),dp(4),dp(12))})
        root.addView(Button(this).apply{text="Reset size to neutral";setOnClickListener{PhormiKeyboardPreferences.setWidthPercent(this@PhormiKeyboardSettingsFixedActivity,100);PhormiKeyboardPreferences.setHeightPercent(this@PhormiKeyboardSettingsFixedActivity,100);render()}})
        setContentView(scroll)
    }
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){super.onActivityResult(requestCode,resultCode,data);if(requestCode==42&&resultCode==RESULT_OK){val uri=data?.data;if(uri!=null){runCatching{contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)};PhormiKeyboardPreferences.setWallpaperUri(this,uri.toString());render()}}}
}
