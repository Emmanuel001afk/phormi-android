package com.uong.phormi

import android.app.Activity
import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.*

/** Pollinations-powered emoji studio. Automatic contextual generation is also handled by the IME. */
class PhormiAiEmojiActivity : Activity() {
    private lateinit var prompt:EditText
    private lateinit var status:TextView
    override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);render()}
    private fun render(){val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(20,20,20,20)};root.addView(TextView(this).apply{text="Phormi AI Emoji";textSize=22f});root.addView(TextView(this).apply{text="Describe the emotion. Phormi creates a unique expressive emoji image with Pollinations AI.";setPadding(0,8,0,12)});prompt=EditText(this).apply{hint="e.g. extremely happy and celebrating";minLines=2};root.addView(prompt);root.addView(Button(this).apply{text="Generate emoji";setOnClickListener{generate()}});status=TextView(this).apply{setPadding(0,12,0,12)};root.addView(status);setContentView(root)}
    private fun generate(){val text=prompt.text.toString().trim();if(text.isBlank()){status.text="Describe the emoji first.";return};status.text="Generating with Pollinations AI…";PhormiContextEmojiEngine.generate(this,text){file->if(file==null){status.text=if(PhormiKeyboardPreferences.pollinationsKey(this).isBlank())"Add a Pollinations user-authorized key in Keyboard Settings first."else"Generation failed. Check the key/network and try again.";return@generate};status.text="Generated — tap the image to insert it.";val image=ImageButton(this).apply{setImageBitmap(BitmapFactory.decodeFile(file.absolutePath));adjustViewBounds=true;contentDescription="Generated AI emoji";setOnClickListener{PhormiKeyboardService.commitPickedContent(this@PhormiAiEmojiActivity,PhormiKeyboardStickerStore.contentUri(this@PhormiAiEmojiActivity,file))}};findViewById<LinearLayout>(android.R.id.content).getChildAt(0)?.let{(it as LinearLayout).addView(image,LinearLayout.LayoutParams(-1,280))}}}
}
