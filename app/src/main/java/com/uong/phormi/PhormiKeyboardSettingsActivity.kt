package com.uong.phormi

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class PhormiKeyboardSettingsActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private val PICK_WALLPAPER = 9201
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); render() }
    override fun onResume() { super.onResume(); if (::status.isInitialized) updateStatus() }

    private fun render() {
        val root = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(22,18,22,28); setBackgroundColor(Color.rgb(11,18,32)) }
        val scroll=ScrollView(this); val content=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        title(content,"Phormi Keyboard")
        status=TextView(this).apply{setTextColor(Color.LTGRAY);textSize=14f;setPadding(0,0,0,14)};content.addView(status)
        button(content,"Enable Phormi Keyboard"){startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))}
        button(content,"Choose Phormi Keyboard"){(getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showInputMethodPicker()}
        button(content,"Language & subtype settings"){runCatching{startActivity(Intent(Settings.ACTION_INPUT_METHOD_SUBTYPE_SETTINGS))}.onFailure{startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))}}
        section(content,"Typing")
        check(content,"Suggestions","Continuously show word predictions.",PhormiKeyboardPreferences.suggestions(this),PhormiKeyboardPreferences.KEY_SUGGESTIONS)
        check(content,"Autocorrect","Correct common mistakes when a word is completed.",PhormiKeyboardPreferences.autocorrect(this),PhormiKeyboardPreferences.KEY_AUTOCORRECT)
        check(content,"Auto-capitalization","Automatically capitalize sentence starts.",PhormiKeyboardPreferences.autoCaps(this),PhormiKeyboardPreferences.KEY_AUTO_CAPS)
        check(content,"AI contextual emoji","Read what you type and offer expressive emoji in the same suggestion strip.",PhormiKeyboardPreferences.aiEmoji(this),PhormiKeyboardPreferences.KEY_AI_EMOJI)
        check(content,"Key vibration","Use keyboard haptic feedback.",PhormiKeyboardPreferences.haptic(this),PhormiKeyboardPreferences.KEY_HAPTIC)
        check(content,"Key sounds","Play the device keyboard click sound.",PhormiKeyboardPreferences.sound(this),PhormiKeyboardPreferences.KEY_SOUND)
        section(content,"Size")
        slider(content,"Keyboard height",PhormiKeyboardPreferences.height(this)){PhormiKeyboardPreferences.setInt(this,PhormiKeyboardPreferences.KEY_HEIGHT,it)}
        slider(content,"Key width / spacing",PhormiKeyboardPreferences.keyWidth(this)){PhormiKeyboardPreferences.setInt(this,PhormiKeyboardPreferences.KEY_KEY_WIDTH,it)}
        note(content,"Default is 100%. Content panels scroll instead of making the keyboard taller than the available IME area.")
        section(content,"Appearance")
        spinner(content,"Theme",arrayOf("system","dark","light","purple","red","blue","green","gold"),PhormiKeyboardPreferences.theme(this)){PhormiKeyboardPreferences.setString(this,PhormiKeyboardPreferences.KEY_THEME,it)}
        button(content,"Choose keyboard wallpaper"){startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply{addCategory(Intent.CATEGORY_OPENABLE);type="image/*"},PICK_WALLPAPER)}
        button(content,"Remove keyboard wallpaper"){PhormiKeyboardPreferences.setString(this,PhormiKeyboardPreferences.KEY_WALLPAPER,"");Toast.makeText(this,"Wallpaper removed",Toast.LENGTH_SHORT).show()}
        section(content,"Languages")
        spinner(content,"Active language profile",arrayOf("English (US) + Yoruba","English + Yoruba","English + French","English + Spanish","Multilingual / auto-detect"),PhormiKeyboardPreferences.language(this)){PhormiKeyboardPreferences.setString(this,PhormiKeyboardPreferences.KEY_LANGUAGE,it)}
        note(content,"Language configuration remains independent from the Phormi browser.")
        section(content,"Voice typing")
        button(content,if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED)"Microphone permission: granted" else "Grant microphone permission"){if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO),9301)}
        note(content,"Voice typing runs from inside the keyboard. The keyboard stays visible and recognized text is committed to the active field.")
        section(content,"Pollinations AI")
        val key=EditText(this).apply{hint="Pollinations publishable API key (pk_...)";setSingleLine(true);setText(PhormiKeyboardPreferences.pollinationsKey(this@PhormiKeyboardSettingsActivity));setTextColor(Color.WHITE);setHintTextColor(Color.GRAY)};content.addView(key)
        button(content,"Save Pollinations key"){PhormiKeyboardPreferences.setString(this,PhormiKeyboardPreferences.KEY_AI_KEY,key.text.toString().trim());Toast.makeText(this,"Pollinations key saved",Toast.LENGTH_SHORT).show()}
        note(content,"The current Pollinations generation API requires a user-authorized key. Phormi will use it for AI emoji artwork.")
        section(content,"Clipboard")
        note(content,"The keyboard captures normal system clipboard text while Phormi is active. Clipboard history supports copy, paste, pin and clear from inside the keyboard.")
        scroll.addView(content);root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f));setContentView(root);updateStatus()
    }
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){super.onActivityResult(requestCode,resultCode,data);if(requestCode==PICK_WALLPAPER&&resultCode==Activity.RESULT_OK)data?.data?.let{runCatching{contentResolver.takePersistableUriPermission(it,Intent.FLAG_GRANT_READ_URI_PERMISSION)};PhormiKeyboardPreferences.setString(this,PhormiKeyboardPreferences.KEY_WALLPAPER,it.toString());Toast.makeText(this,"Wallpaper saved",Toast.LENGTH_SHORT).show()}}
    private fun updateStatus(){val imm=getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager;val id="$packageName/.PhormiKeyboardService";val enabled=imm.enabledInputMethodList.any{it.id==id};val selected=Settings.Secure.getString(contentResolver,Settings.Secure.DEFAULT_INPUT_METHOD)==id;status.text=when{selected->"Status: enabled and selected.";enabled->"Status: enabled; another keyboard is selected.";else->"Status: not enabled. Enable it below, then select it."};status.setTextColor(if(enabled)Color.rgb(134,239,172)else Color.rgb(248,196,113))}
    private fun title(r:LinearLayout,t:String){r.addView(TextView(this).apply{text=t;textSize=24f;setTextColor(Color.WHITE);setPadding(0,0,0,12)})}
    private fun section(r:LinearLayout,t:String){r.addView(TextView(this).apply{text=t;textSize=17f;setTextColor(Color.rgb(56,189,248));setPadding(0,20,0,8)})}
    private fun button(r:LinearLayout,t:String,a:()->Unit){r.addView(Button(this).apply{text=t;setOnClickListener{a()}})}
    private fun note(r:LinearLayout,t:String){r.addView(TextView(this).apply{text=t;setTextColor(Color.GRAY);textSize=12f;setPadding(0,4,0,12)})}
    private fun check(r:LinearLayout,t:String,s:String,v:Boolean,k:String){val row=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(0,5,0,5)};row.addView(CheckBox(this).apply{text=t;isChecked=v;setTextColor(Color.WHITE);textSize=16f;setOnCheckedChangeListener{_,x->PhormiKeyboardPreferences.set(this@PhormiKeyboardSettingsActivity,k,x)}});row.addView(TextView(this).apply{text=s;setTextColor(Color.GRAY);textSize=12f;setPadding(48,0,0,5)});r.addView(row)}
    private fun slider(r:LinearLayout,label:String,value:Int,onChange:(Int)->Unit){val tv=TextView(this).apply{text="$label: $value%";setTextColor(Color.WHITE);textSize=14f};val s=SeekBar(this).apply{max=60;progress=(value-75).coerceIn(0,60);setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{override fun onProgressChanged(b:SeekBar?,p:Int,f:Boolean){val x=p+75;tv.text="$label: $x%";if(f)onChange(x)};override fun onStartTrackingTouch(b:SeekBar?){ };override fun onStopTrackingTouch(b:SeekBar?){ }})};r.addView(tv);r.addView(s)}
    private fun spinner(r:LinearLayout,label:String,values:Array<String>,selected:String,onPick:(String)->Unit){r.addView(TextView(this).apply{text=label;setTextColor(Color.LTGRAY);setPadding(0,5,0,3)});r.addView(Spinner(this).apply{adapter=ArrayAdapter(this@PhormiKeyboardSettingsActivity,android.R.layout.simple_spinner_dropdown_item,values);setSelection(values.indexOf(selected).coerceAtLeast(0));onItemSelectedListener=object:AdapterView.OnItemSelectedListener{override fun onItemSelected(p:AdapterView<*>?,v:android.view.View?,pos:Int,id:Long){onPick(values[pos])};override fun onNothingSelected(p:AdapterView<*>?){}}})}
}
