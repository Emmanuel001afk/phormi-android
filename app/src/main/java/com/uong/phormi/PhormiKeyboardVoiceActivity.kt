package com.uong.phormi

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import java.util.Locale

class PhormiKeyboardVoiceActivity:Activity(){
 override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState); val i=Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply{putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);putExtra(RecognizerIntent.EXTRA_LANGUAGE,Locale.getDefault());putExtra(RecognizerIntent.EXTRA_PROMPT,"Speak to Phormi Keyboard")};runCatching{startActivityForResult(i,REQUEST)}.onFailure{finish()}}
 override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){super.onActivityResult(requestCode,resultCode,data);if(requestCode==REQUEST&&resultCode==RESULT_OK){data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.takeIf{it.isNotBlank()}?.let{text->startService(Intent(this,PhormiKeyboardService::class.java).apply{action=PhormiKeyboardService.ACTION_COMMIT_TEXT;putExtra(PhormiKeyboardService.EXTRA_TEXT,text)})}};finish()}
 companion object{private const val REQUEST=7101}
}
