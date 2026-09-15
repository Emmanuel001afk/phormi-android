package com.uong.phormi

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.*
import java.util.Locale
import kotlin.math.roundToInt

class PhormiKeyboardServiceFixed : InputMethodService(), RecognitionListener {
    private enum class Page { LETTERS, NUMBERS, SYMBOLS }
    private enum class Panel { KEYBOARD, EMOJI, TOOLS }
    private var page=Page.LETTERS; private var panel=Panel.KEYBOARD; private var shift=false; private var caps=false
    private var editorInfo:EditorInfo?=null; private var emojiCategory=0; private var speech:SpeechRecognizer?=null
    private var surface:LinearLayout?=null; private var resizeDownX=0f; private var resizeDownY=0f; private var resizeStartW=100; private var resizeStartH=100; private var recognizing=false
    private val categories=listOf("🕘" to "Recent","😀" to "Smileys & Emotion","🧑" to "People & Body","🐶" to "Animals & Nature","🍔" to "Food & Drink","✈️" to "Travel & Places","⚽" to "Activities","💡" to "Objects","🔣" to "Symbols","🇳🇬" to "Flags")
    private val emojiSets=listOf(
        listOf("😀","😃","😄","😁","😆","😅","😂","🤣","😊","😇","🙂","🙃","😉","😌","😍","🥰","😘","😗","😙","😚","😋","😛","😝","😜","🤪","🤨","🧐","🤓","😎","🤩","🥳","😏","😒","😞","😔","😟","😕","🙁","☹️","😣","😖","😫","😩","🥺","😢","😭","😤","😠","😡","🤬","🤯","😳","🥵","🥶","😱","😨","😰","😥","😓","🤗","🤔","🫣","🤭","🤫","🤥","😶","😐","😑","😬","🙄","😯","😦","😧","😮","😲","🥱","😴","🤤","😪","😵","🤐","🤢","🤮","🤧","😷","🤒","🤕"),
        listOf("👋","🤚","🖐️","✋","🖖","👌","🤌","🤏","✌️","🤞","🫰","🤟","🤘","🤙","👈","👉","👆","👇","☝️","👍","👎","✊","👊","🤛","🤜","👏","🙌","🫶","🙏","✍️","💅","🤳","💪","🦾","🦿","👀","👁️","🧠","👄","🫦","👶","🧒","👦","👧","🧑","👨","👩","🧓","👴","👵","❤️","🧡","💛","💚","💙","💜","🖤","🤍","🤎","💔","❣️","💕","💞","💓","💗","💖","💘","💝","💟"),
        listOf("🐶","🐱","🐭","🐹","🐰","🦊","🐻","🐼","🐨","🐯","🦁","🐮","🐷","🐸","🐵","🙈","🙉","🙊","🐔","🐧","🐦","🐤","🦆","🦅","🦉","🦇","🐺","🐗","🐴","🦄","🐝","🪲","🐛","🦋","🐌","🐞","🐜","🕷️","🦂","🐢","🐍","🦎","🦖","🦕","🐙","🦑","🦀","🐠","🐟","🐡","🦈","🐬","🐳","🐋","🐊","🐘","🦏","🦛","🐪","🐫","🦒","🦘","🐃","🐂","🐄","🐎","🐖","🐏","🐑","🦙","🐐","🦌","🐕","🐈","🐓","🦜","🦢","🦩","🦚","🌸","🌹","🌺","🌻","🌼","🌷","🌱","🌲","🌳","🌴","🌵","🍀","🍁","🍂","🍃"),
        listOf("🍏","🍎","🍐","🍊","🍋","🍌","🍉","🍇","🍓","🫐","🍈","🍒","🍑","🥭","🍍","🥥","🥝","🍅","🍆","🥑","🥦","🥬","🥒","🌶️","🌽","🥕","🧄","🧅","🥔","🍞","🥐","🥖","🥨","🧀","🥚","🍳","🧈","🥞","🧇","🥓","🥩","🍗","🍔","🍟","🍕","🌭","🥪","🌮","🌯","🥗","🍝","🍜","🍲","🍛","🍣","🍱","🥟","🍤","🍚","🍙","🍘","🍧","🍨","🍦","🥧","🧁","🍰","🎂","🍪","🍩","🍫","🍿","☕","🫖","🥤","🧃","🧋","🍺","🍻","🍷","🥂","🍸"),
        listOf("🚗","🚕","🚙","🚌","🚎","🏎️","🚓","🚑","🚒","🚐","🛻","🚚","🚛","🚜","🛵","🏍️","🚲","🛴","🚨","🚆","🚇","🚊","✈️","🛫","🛬","🛩️","🚁","🚀","🛸","⛵","🚤","🛥️","🗽","🗼","🏰","🏯","🏝️","🏖️","🏜️","🏕️","⛰️","🌋","🏠","🏡","🏢","🏥","🏦","🏫","⛪","🕌","🛕","🕍","🌅","🌄","🌇","🌆","🌃","🌉","🌌","🌍","🌎","🌏","🌙","⭐","🌟","✨","☀️","🌤️","🌧️","⛈️","❄️","☃️"),
        listOf("⚽","🏀","🏈","⚾","🥎","🎾","🏐","🏉","🥏","🎱","🪀","🏓","🏸","🏒","🏑","🥍","🏏","⛳","🏹","🎣","🤿","🥊","🥋","🎽","🛹","🛼","🎿","⛷️","🏂","🏋️","🤼","🤸","⛹️","🤺","🏆","🥇","🥈","🥉","🎯","🎮","🕹️","🎰","🎲","🧩","🎨","🎭","🎼","🎵","🎶","🎤","🎧","🎷","🎸","🎹","🥁","🎻","🎬","🎟️","🎪"),
        listOf("💡","🔦","🏮","🪔","📱","💻","⌨️","🖥️","🖨️","🖱️","💾","💿","📷","📸","📺","📻","☎️","📞","🔋","🔌","💰","💳","💎","🔑","🔒","🔓","🧰","🔧","🔨","⚙️","🧲","🧪","💊","📚","📖","✏️","📝","📌","📍","📎","✂️","🗑️","🔔","🎁","🎈","🎉","🧸","🪄","🛒","🧳"),
        listOf("!","?","‼️","⁉️","#","*","+","−","×","÷","=","≠","≈","<",">","≤","≥","%","‰","@","&","§","¶","©","®","™","°","′","″","€","£","₦","¥","₹","₽","₩","₺","₴","₱","₿","∞","√","∑","∫","∆","π","µ","Ω","✓","✔️","✕","✖️","⚠️","❌","⭕","❗","❓","🔴","🟠","🟡","🟢","🔵","🟣","⚫","⚪","⬆️","⬇️","⬅️","➡️","↩️","↪️","🔄","🔒","🔓"),
        listOf("🇳🇬","🇺🇸","🇬🇧","🇨🇦","🇦🇺","🇿🇦","🇬🇭","🇰🇪","🇺🇬","🇹🇿","🇷🇼","🇪🇹","🇪🇬","🇲🇦","🇩🇿","🇸🇳","🇨🇮","🇨🇲","🇧🇯","🇹🇬","🇸🇱","🇱🇷","🇬🇳","🇫🇷","🇩🇪","🇪🇸","🇮🇹","🇵🇹","🇳🇱","🇧🇪","🇨🇭","🇸🇪","🇳🇴","🇩🇰","🇫🇮","🇮🇪","🇧🇷","🇲🇽","🇦🇷","🇯🇵","🇰🇷","🇨🇳","🇮🇳","🇦🇪","🇸🇦","🇮🇱","🇹🇷","🇷🇺","🇺🇦","🇹🇭","🇸🇬","🇳🇿")
    )
    override fun onCreate(){super.onCreate()}
    override fun onStartInput(info:EditorInfo?,restarting:Boolean){super.onStartInput(info,restarting);editorInfo=info;page=Page.LETTERS;panel=Panel.KEYBOARD;shift=false;caps=false}
    override fun onStartInputView(info:EditorInfo?,restarting:Boolean){super.onStartInputView(info,restarting);editorInfo=info?:editorInfo;render()}
    override fun onDestroy(){speech?.destroy();speech=null;super.onDestroy()}
    override fun onEvaluateFullscreenMode()=false
    override fun onCreateInputView():View=buildRoot()
    private fun dp(v:Int)=(v*resources.displayMetrics.density).roundToInt().coerceAtLeast(1)
    private fun prefs()=getSharedPreferences("phormi_keyboard_fixed",MODE_PRIVATE)
    private fun floating()=prefs().getBoolean("floating",false)
    private fun widthPercent()=PhormiKeyboardPreferences.widthPercent(this).coerceIn(70,100)
    private fun heightPercent()=PhormiKeyboardPreferences.heightPercent(this).coerceIn(70,140)
    private fun bg():Int=when(PhormiKeyboardPreferences.theme(this)){1->Color.rgb(25,28,34);2->Color.rgb(8,28,48);3->Color.rgb(244,246,249);else->Color.rgb(13,18,30)}
    private fun key():Int=when(PhormiKeyboardPreferences.theme(this)){1->Color.rgb(50,55,64);2->Color.rgb(19,55,82);3->Color.WHITE;else->Color.rgb(39,48,64)}
    private fun text():Int=if(PhormiKeyboardPreferences.theme(this)==3)Color.rgb(20,27,36)else Color.WHITE
    private fun pillBg():Int=if(PhormiKeyboardPreferences.theme(this)==3)Color.rgb(225,229,235)else Color.rgb(31,41,55)
    private fun drawable(color:Int,r:Int=10)=GradientDrawable().apply{setColor(color);cornerRadius=dp(r).toFloat()}
    private fun button(label:String,action:()->Unit)=Button(this).apply{text=label;textSize=if(label.length<=2)20f else 12f;isAllCaps=false;minWidth=0;minHeight=0;stateListAnimator=null;setTextColor(text());background=drawable(key());setPadding(dp(2),0,dp(2),0);setOnClickListener{if(PhormiKeyboardPreferences.haptic(this@PhormiKeyboardServiceFixed))performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);action()}}
    private fun pill(label:String,action:()->Unit)=button(label,action).apply{background=drawable(pillBg(),14);textSize=12f}
    private fun render(){setInputView(buildRoot())}
    private fun buildRoot():View{
        val outer=FrameLayout(this).apply{setBackgroundColor(Color.TRANSPARENT);clipChildren=true};val w=widthPercent()/100f;val h=heightPercent()/100f
        val card=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(6),dp(3),dp(6),dp(5));background=drawable(bg(),12);clipChildren=true}
        val params=FrameLayout.LayoutParams((resources.displayMetrics.widthPixels*w).roundToInt().coerceAtLeast(dp(280)),dp((360*h).roundToInt()));params.gravity=if(floating())Gravity.CENTER else Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM;card.layoutParams=params;surface=card
        addResizeGrip(card);addToolbar(card);when(panel){Panel.KEYBOARD->buildKeyboard(card);Panel.EMOJI->buildEmoji(card);Panel.TOOLS->buildTools(card)};outer.addView(card);return outer
    }
    private fun addResizeGrip(card:LinearLayout){val grip=TextView(this).apply{text="↘";gravity=Gravity.CENTER;textSize=17f;setTextColor(Color.LTGRAY);background=drawable(pillBg(),9);contentDescription="Resize keyboard width and height"};grip.setOnTouchListener{_,e->when(e.actionMasked){MotionEvent.ACTION_DOWN->{resizeDownX=e.rawX;resizeDownY=e.rawY;resizeStartW=widthPercent();resizeStartH=heightPercent();true};MotionEvent.ACTION_MOVE->{val dx=((e.rawX-resizeDownX)/resources.displayMetrics.widthPixels*100).roundToInt();val dy=((resizeDownY-e.rawY)/dp(360).toFloat()*100).roundToInt();val nw=(resizeStartW+dx).coerceIn(70,100);val nh=(resizeStartH+dy).coerceIn(70,140);PhormiKeyboardPreferences.setWidthPercent(this,nw);PhormiKeyboardPreferences.setHeightPercent(this,nh);val lp=card.layoutParams as FrameLayout.LayoutParams;lp.width=(resources.displayMetrics.widthPixels*nw/100f).roundToInt();lp.height=dp((360*nh/100f).roundToInt());card.layoutParams=lp;card.requestLayout();true};else->true}};card.addView(grip,LinearLayout.LayoutParams(dp(44),dp(24)).apply{gravity=Gravity.CENTER_HORIZONTAL;bottomMargin=dp(2)})}
    private fun addToolbar(root:LinearLayout){val scroll=HorizontalScrollView(this).apply{isHorizontalScrollBarEnabled=false;overScrollMode=View.OVER_SCROLL_NEVER};val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL};fun add(s:String,a:()->Unit){row.addView(pill(s,a),LinearLayout.LayoutParams(dp(58),dp(36)).apply{setMargins(dp(2),0,dp(2),0)})};add("😀"){panel=Panel.EMOJI;render()};add("🎤"){startVoice()};add("📋"){commitClipboard()};add("GIF"){openMedia(false)};add("Sticker"){openMedia(true)};add("⚙"){startActivity(Intent(this@PhormiKeyboardServiceFixed,PhormiKeyboardSettingsFixedActivity::class.java))};add("▦"){panel=Panel.TOOLS;render()};scroll.addView(row);root.addView(scroll,LinearLayout.LayoutParams(-1,dp(39)))}
    private fun suggestions(root:LinearLayout){if(!PhormiKeyboardPreferences.suggestions(this))return;val word=runCatching{PhormiKeyboardTextEngine.currentWord(currentInputConnection)}.getOrDefault("");val locale=runCatching{PhormiKeyboardTextEngine.localeFor(editorInfo)}.getOrDefault(Locale.getDefault());val vals=if(word.isNotBlank())runCatching{PhormiKeyboardTextEngine.suggestions(this,word,locale)}.getOrDefault(emptyList())else emptyList();val context=runCatching{PhormiKeyboardTextEngine.contextBeforeCursor(currentInputConnection)}.getOrDefault("").lowercase(Locale.getDefault());val ai=when{context.contains(Regex("\\b(angry|mad|furious|annoyed|hate)\\b"))->"😡🔥";context.contains(Regex("\\b(sad|cry|crying|sorry|hurt)\\b"))->"😢💔";context.contains(Regex("\\b(love|lovely|romantic)\\b"))->"🥰❤️";context.contains(Regex("\\b(happy|great|good|joy|excited)\\b"))->"🥳✨";context.contains(Regex("\\b(lol|laugh|funny|😂)\\b"))->"😂🤣";else->null};if(vals.isEmpty()&&ai==null)return;val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL};vals.take(3).forEach{v->row.addView(pill(v){if(word.isNotBlank())currentInputConnection?.deleteSurroundingText(word.length,0);commitText(v+" ");render()},LinearLayout.LayoutParams(0,dp(32),1f).apply{setMargins(dp(2),0,dp(2),0)})};ai?.let{emoji->row.addView(pill(emoji){commitText(emoji+" ")},LinearLayout.LayoutParams(dp(58),dp(32)).apply{setMargins(dp(2),0,dp(2),0)})};root.addView(row,LinearLayout.LayoutParams(-1,dp(34)))}
    private fun buildKeyboard(root:LinearLayout){suggestions(root);val clazz=(editorInfo?.inputType?:InputType.TYPE_CLASS_TEXT)and InputType.TYPE_MASK_CLASS;if(clazz==InputType.TYPE_CLASS_NUMBER){buildNumbers(root);return};when(page){Page.LETTERS->buildLetters(root);Page.NUMBERS->buildNumbers(root);Page.SYMBOLS->buildSymbols(root)}}
    private fun row(root:LinearLayout,chars:String){val r=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER};chars.forEach{c->r.addView(button(if(shift||caps)c.uppercase()else c.toString()){commitText(if(shift||caps)c.uppercase()else c.toString());if(shift&&!caps)shift=false;render()},LinearLayout.LayoutParams(0,dp(47),1f).apply{setMargins(dp(2),dp(2),dp(2),dp(2))})};root.addView(r,LinearLayout.LayoutParams(-1,dp(51)))}
    private fun buildLetters(root:LinearLayout){row(root,"qwertyuiop");row(root,"asdfghjkl");val r=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER};r.addView(button(if(caps)"⇧A"else"⇧"){caps=!caps;shift=caps;render()},LinearLayout.LayoutParams(0,dp(47),1.2f));"zxcvbnm".forEach{c->r.addView(button(if(shift||caps)c.uppercase()else c.toString()){commitText(if(shift||caps)c.uppercase()else c.toString());if(shift&&!caps)shift=false;render()},LinearLayout.LayoutParams(0,dp(47),1f).apply{setMargins(dp(2),dp(2),dp(2),dp(2))})};r.addView(button("⌫"){deleteBackspace()},LinearLayout.LayoutParams(0,dp(47),1.2f));root.addView(r,LinearLayout.LayoutParams(-1,dp(51)));bottomRow(root)}
    private fun buildNumbers(root:LinearLayout){row(root,"1234567890");row(root,"7894561230");row(root,".,-+*/=()\$#");bottomRow(root)}
    private fun buildSymbols(root:LinearLayout){val scroll=ScrollView(this).apply{isFillViewport=true};val grid=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};listOf("!@#$%^&*()","-+=_[]{}\\|;:'\",.<>/?","€£₦¥₹₽₩₺₴₱₿©®™°§¶","~`^…·•‰∞√∑πµΩ∆≠≈≤≥","✓✔✕✖❌⭕❗❓⚠♥♡★☆→←↑↓↔↩↪").forEach{row(grid,it)};scroll.addView(grid);root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f));root.addView(button("ABC  •  123"){page=Page.LETTERS;render()},LinearLayout.LayoutParams(-1,dp(44)))}
    private fun bottomRow(root:LinearLayout){val r=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER};r.addView(button("123"){page=if(page==Page.NUMBERS)Page.SYMBOLS else Page.NUMBERS;render()},LinearLayout.LayoutParams(0,dp(47),1.2f));r.addView(button("Space"){currentInputConnection?.commitText(" ",1)},LinearLayout.LayoutParams(0,dp(47),4f));r.addView(button("↵"){currentInputConnection?.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN,android.view.KeyEvent.KEYCODE_ENTER));currentInputConnection?.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP,android.view.KeyEvent.KEYCODE_ENTER))},LinearLayout.LayoutParams(0,dp(47),1.2f));root.addView(r,LinearLayout.LayoutParams(-1,dp(51)))}
    private fun buildEmoji(root:LinearLayout){val top=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL};categories.forEachIndexed{index,(icon,_)->top.addView(pill(icon){emojiCategory=index;render()},LinearLayout.LayoutParams(0,dp(38),1f).apply{setMargins(1,0,1,0)})};root.addView(top,LinearLayout.LayoutParams(-1,dp(40)));val scroll=ScrollView(this).apply{isFillViewport=false;overScrollMode=View.OVER_SCROLL_IF_CONTENT_SCROLLS};val grid=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};val items=emojiSets.getOrElse(emojiCategory){emojiSets[0]};items.chunked(8).forEach{line->val r=LinearLayout(this@PhormiKeyboardServiceFixed).apply{orientation=LinearLayout.HORIZONTAL};line.forEach{e->r.addView(button(e){commitText(e);getSharedPreferences("phormi_keyboard_recent",MODE_PRIVATE).edit().putString("last",e).apply()},LinearLayout.LayoutParams(0,dp(46),1f).apply{setMargins(1,1,1,1)})};grid.addView(r)};scroll.addView(grid);root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f));root.addView(button("⌨ ABC"){panel=Panel.KEYBOARD;page=Page.LETTERS;render()},LinearLayout.LayoutParams(-1,dp(44)))}
    private fun buildTools(root:LinearLayout){val scroll=ScrollView(this);val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};listOf("Clipboard" to {commitClipboard()},"Voice typing" to {startVoice()},"GIFs" to {openMedia(false)},"Stickers" to {openMedia(true)},"Keyboard settings" to {startActivity(Intent(this@PhormiKeyboardServiceFixed,PhormiKeyboardSettingsFixedActivity::class.java))},"ABC keyboard" to {panel=Panel.KEYBOARD;render()}).forEach{(label,a)->box.addView(pill(label,a),LinearLayout.LayoutParams(-1,dp(44)).apply{setMargins(0,dp(3),0,dp(3))})};scroll.addView(box);root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))}
    private fun commitText(s:String){currentInputConnection?.commitText(s,1)}
    private fun deleteBackspace(){currentInputConnection?.deleteSurroundingText(1,0)}
    private fun commitClipboard(){val cm=getSystemService(Context.CLIPBOARD_SERVICE)as?android.content.ClipboardManager;val text=cm?.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString();if(!text.isNullOrBlank())commitText(text)}
    private fun openMedia(sticker:Boolean){runCatching{startActivity(Intent(this,PhormiKeyboardMediaActivity::class.java).putExtra("mode",if(sticker)"sticker"else"gif"))}}
    private fun startVoice(){if(recognizing)return;if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){runCatching{startActivity(Intent(this,PhormiKeyboardVoiceActivity::class.java))};return};if(!SpeechRecognizer.isRecognitionAvailable(this)){runCatching{startActivity(Intent(this,PhormiKeyboardVoiceActivity::class.java))};return};speech?.destroy();speech=SpeechRecognizer.createSpeechRecognizer(this).also{it.setRecognitionListener(this)};val intent=Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply{putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);putExtra(RecognizerIntent.EXTRA_LANGUAGE,Locale.getDefault().toLanguageTag());putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,true);putExtra(RecognizerIntent.EXTRA_MAX_RESULTS,3)};recognizing=true;runCatching{speech?.startListening(intent)}.onFailure{recognizing=false}}
    override fun onResults(results:Bundle?){recognizing=false;val spoken=results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull();if(!spoken.isNullOrBlank())commitText(spoken+" ");render()}
    override fun onPartialResults(results:Bundle?){ }
    override fun onError(error:Int){recognizing=false;render()}
    override fun onReadyForSpeech(params:Bundle?){ }
    override fun onBeginningOfSpeech(){ }
    override fun onRmsChanged(rmsdB:Float){ }
    override fun onBufferReceived(buffer:ByteArray?){ }
    override fun onEndOfSpeech(){ }
    override fun onEvent(eventType:Int,params:Bundle?){ }
}
