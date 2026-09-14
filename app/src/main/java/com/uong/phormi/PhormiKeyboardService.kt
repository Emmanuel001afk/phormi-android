package com.uong.phormi

import android.Manifest
import android.content.ClipDescription
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.InputType
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.SoundEffectConstants
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputContentInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

class PhormiKeyboardService : InputMethodService() {
    companion object {
        const val ACTION_COMMIT_TEXT = "com.uong.phormi.keyboard.COMMIT_TEXT"
        const val EXTRA_TEXT = "text"
        private var instance: PhormiKeyboardService? = null
        fun commitPickedContent(context: Context, uri: Uri): Boolean = instance?.commitContent(uri) ?: false
    }

    private enum class Panel { KEYBOARD, NUMBERS, SYMBOLS, EMOJI, CLIPBOARD, TOOLS, GIF, STICKER }
    private var shift = false
    private var capsLock = false
    private var panel = Panel.KEYBOARD
    private var emojiCategory = 0
    private var editorInfo: EditorInfo? = null
    private var selectionStart = 0
    private var selectionEnd = 0
    private var speech: SpeechRecognizer? = null
    private var speechListening = false
    private var aiToken = 0
    private var lastAiContext = ""
    private var suggestionRow: LinearLayout? = null
    private var clipListener: android.content.ClipboardManager.OnPrimaryClipChangedListener? = null
    private val aiHandler = Handler(Looper.getMainLooper())

    private val words = setOf(
        "the","and","that","this","there","their","they","them","then","than","you","your","yours","you're",
        "are","was","were","with","what","when","where","why","how","have","has","had","for","from","not",
        "now","can","could","should","would","will","just","very","really","like","love","want","need","know",
        "think","make","going","come","good","great","about","into","out","over","under","after","before",
        "because","please","thanks","hello","hey","yes","yeah","okay","today","tomorrow","later","home","work",
        "friend","family","message","send","open","close","search","download","share","keyboard","browser","emoji",
        "sticker","settings","phone","text","time","day","night","morning","money","school","student","project",
        "happy","sad","angry","amazing","awesome","cool","nice","sorry","welcome","congratulations","maybe","more",
        "much","some","any","all","one","two","three","four","five","first","second","language","learn","learned",
        "system","application","internet","privacy","security","feature","features","everything","understand","correct",
        "correction","suggestion","suggestions","people","person","write","typing","type","typed","automatic"
    )

    override fun onCreate() {
        super.onCreate(); instance = this
        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipListener = android.content.ClipboardManager.OnPrimaryClipChangedListener { PhormiKeyboardClipboardStore.capturePrimaryClipboard(this) }
        cm.addPrimaryClipChangedListener(clipListener)
    }

    override fun onDestroy() {
        aiHandler.removeCallbacksAndMessages(null); stopVoice()
        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipListener?.let(cm::removePrimaryClipChangedListener); clipListener = null; suggestionRow = null
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting); editorInfo = attribute; selectionStart = 0; selectionEnd = 0
        panel = Panel.KEYBOARD; capsLock = false; shift = PhormiKeyboardPreferences.autoCaps(this)
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting); editorInfo = info ?: editorInfo
        PhormiKeyboardClipboardStore.capturePrimaryClipboard(this); panel = Panel.KEYBOARD; setInputView(render())
    }

    override fun onFinishInput() { stopVoice(); editorInfo = null; suggestionRow = null; super.onFinishInput() }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        selectionStart = newSelStart; selectionEnd = newSelEnd; if (panel == Panel.KEYBOARD) refreshSuggestions()
    }

    override fun onEvaluateFullscreenMode() = false
    override fun onCreateInputView(): View = render()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_COMMIT_TEXT) intent.getStringExtra(EXTRA_TEXT)?.let(::commitText)
        return START_NOT_STICKY
    }

    override fun onConfigureWindow(win: Window, isFullscreen: Boolean, isCandidatesOnly: Boolean) {
        super.onConfigureWindow(win, isFullscreen, isCandidatesOnly)
        if (!isFullscreen && !isCandidatesOnly) {
            val screenHeight = resources.displayMetrics.heightPixels
            val desired = ((screenHeight * 0.34f) * (PhormiKeyboardPreferences.height(this) / 100f)).toInt()
                .coerceIn(dp(260), (screenHeight * 0.55f).toInt())
            win.setLayout(WindowManager.LayoutParams.MATCH_PARENT, desired)
        }
    }

    private fun render(): View = when (panel) {
        Panel.KEYBOARD -> keyboard(); Panel.NUMBERS -> numbers(); Panel.SYMBOLS -> symbols(); Panel.EMOJI -> emoji()
        Panel.CLIPBOARD -> clipboard(); Panel.TOOLS -> tools(); Panel.GIF -> media("GIF"); Panel.STICKER -> media("Stickers")
    }

    private fun root() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(3), dp(3), dp(3), dp(3)); setBackgroundColor(themeColor()); applyWallpaper(this)
    }

    private fun themeColor(): Int {
        val selected = PhormiKeyboardPreferences.theme(this)
        val dark = when (selected) {
            "light" -> false; "dark" -> true
            else -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        }
        return when (selected) {
            "purple" -> Color.rgb(42,28,74); "red" -> Color.rgb(58,22,28); "blue" -> Color.rgb(16,38,64)
            "green" -> Color.rgb(20,55,40); "gold" -> Color.rgb(62,50,18)
            else -> if (dark) Color.rgb(20,22,28) else Color.rgb(232,235,241)
        }
    }

    private fun fg(): Int {
        val selected = PhormiKeyboardPreferences.theme(this)
        if (selected in setOf("dark","purple","red","blue","green","gold")) return Color.WHITE
        return if (selected == "light" || (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) != Configuration.UI_MODE_NIGHT_YES) Color.rgb(25,28,34) else Color.WHITE
    }

    private fun applyWallpaper(view: View) {
        val saved = PhormiKeyboardPreferences.wallpaper(this); if (saved.isBlank()) return
        runCatching { contentResolver.openInputStream(Uri.parse(saved))?.use { BitmapFactory.decodeStream(it) }?.let { bitmap ->
            view.background = android.graphics.drawable.BitmapDrawable(resources, bitmap).apply { gravity = Gravity.FILL; alpha = 105 }
        } }
    }

    private fun strip(root: LinearLayout) {
        val horizontal = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        suggestionRow = row; horizontal.addView(row, LinearLayout.LayoutParams(-2,-1)); root.addView(horizontal, LinearLayout.LayoutParams(-1,dp(46))); refreshSuggestions()
    }

    private fun refreshSuggestions() {
        val row = suggestionRow ?: return; row.removeAllViews(); if (password()) return
        val text = currentInputConnection?.getTextBeforeCursor(180,0)?.toString().orEmpty()
        val token = text.takeLastWhile { !it.isWhitespace() }
        if (PhormiKeyboardPreferences.suggestions(this) && token.isNotBlank()) {
            PhormiKeyboardLexicon.suggestions(this, token, 6).forEach { key(row,it) { replaceWord(it) } }
        }
        if (PhormiKeyboardPreferences.aiEmoji(this) && text.trim().length >= 3) {
            PhormiContextEmojiEngine.expressive(text).take(3).forEach { expression -> key(row,expression) { commitText(expression) } }
            scheduleAiGeneration(text)
        }
        key(row,"😀",compact=true) { panel=Panel.EMOJI; refresh() }
        key(row,"📋",compact=true) { panel=Panel.CLIPBOARD; refresh() }
        key(row,"⚙",compact=true) { startActivity(Intent(this,PhormiKeyboardSettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    private fun scheduleAiGeneration(text: String) {
        if (PhormiKeyboardPreferences.pollinationsKey(this).isBlank()) return
        val contextText = text.trim().takeLast(180); if (contextText == lastAiContext) return
        lastAiContext = contextText; val token = ++aiToken; aiHandler.removeCallbacksAndMessages("ai")
        aiHandler.postAtTime({ PhormiContextEmojiEngine.generate(this,contextText) { file ->
            if (token == aiToken && panel == Panel.KEYBOARD) addAiImage(suggestionRow,file)
        } },"ai",System.currentTimeMillis()+650)
    }

    private fun addAiImage(row: LinearLayout?, file: File?) {
        if (row == null || file == null || row !== suggestionRow) return
        val button = ImageButton(this).apply {
            setImageBitmap(BitmapFactory.decodeFile(file.absolutePath)); background = transparentRoundedBackground(); contentDescription = "Expressive AI emoji"
            scaleType = ImageView.ScaleType.CENTER_INSIDE; setPadding(dp(4),dp(4),dp(4),dp(4))
            setOnClickListener { commitContent(PhormiKeyboardStickerStore.contentUri(this@PhormiKeyboardService,file)) }
        }
        row.addView(button,LinearLayout.LayoutParams(dp(52),dp(44)).apply { setMargins(dp(2),dp(1),dp(2),dp(1)) })
    }

    private fun keyboard(): View {
        val r=root(); strip(r); listOf("qwertyuiop","asdfghjkl","zxcvbnm").forEach { r.addView(letterRow(it),LinearLayout.LayoutParams(-1,0,1f)) }
        r.addView(bottom(),LinearLayout.LayoutParams(-1,0,1.05f)); return r
    }

    private fun numbers(): View {
        val r=root(); strip(r); listOf("1234567890","-/:;()$&@\"",".,?!'#+=","%*<>_[]{}~^").forEach { r.addView(chars(it),LinearLayout.LayoutParams(-1,0,1f)) }
        r.addView(bottom(),LinearLayout.LayoutParams(-1,0,1.05f)); return r
    }

    private fun symbols(): View {
        val r=root(); strip(r); listOf("!@#$%^&*()_+","-=/:;\"'\\|~`","<>{}[]©®™§¶•°","¿¡±×÷∞≠≈≤≥µ√∑∏∂∆Ωπ").forEach { r.addView(chars(it),LinearLayout.LayoutParams(-1,0,1f)) }
        r.addView(symbolBottom(),LinearLayout.LayoutParams(-1,0,1.05f)); return r
    }

    private fun letterRow(chars:String):View {
        val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER}
        chars.forEach { c -> val value=if(shift||capsLock)c.uppercaseChar().toString()else c.toString(); key(row,value){ commitText(value); if(shift&&!capsLock){shift=false;refreshKeyboardOnly()} } }
        return row
    }

    private fun chars(chars:String):View {
        val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER}; chars.forEach{c->key(row,c.toString()){commitText(c.toString())}}; return row
    }

    private fun bottom():View {
        val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER}
        val sh=key(row,"⇧"){when{capsLock->{capsLock=false;shift=false};shift->{capsLock=true;shift=false};else->shift=true};refreshKeyboardOnly()}; styleShift(sh)
        when(panel){
            Panel.KEYBOARD -> key(row,"123"){panel=Panel.NUMBERS;refresh()}
            Panel.NUMBERS -> key(row,"🔣"){panel=Panel.SYMBOLS;refresh()}
            else -> key(row,"ABC"){panel=Panel.KEYBOARD;refresh()}
        }
        val space=key(row,"Space",3.4f){commitSpace()}; space.setOnLongClickListener{(getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker();true}
        key(row,"←"){moveCursor(-1)}; key(row,"→"){moveCursor(1)}; key(row,"⌫"){deleteBackward()}; key(row,"↵"){sendEditorAction()}; key(row,if(speechListening)"■"else"🎙",compact=true){toggleVoice()}; return row
    }

    private fun symbolBottom():View {
        val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER}
        key(row,"ABC"){panel=Panel.KEYBOARD;refresh()}; key(row,"123"){panel=Panel.NUMBERS;refresh()}; val space=key(row,"Space",3.4f){commitSpace()}
        space.setOnLongClickListener{(getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker();true}
        key(row,"←"){moveCursor(-1)};key(row,"→"){moveCursor(1)};key(row,"⌫"){deleteBackward()};key(row,"↵"){sendEditorAction()};return row
    }

    private fun emoji():View {
        val r=root(); val nav=HorizontalScrollView(this).apply{isHorizontalScrollBarEnabled=false;overScrollMode=View.OVER_SCROLL_NEVER}; val cats=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
        PhormiKeyboardEmoji.categories.keys.forEachIndexed{i,e->key(cats,e,compact=true){emojiCategory=i;refresh()}}; key(cats,"⌨",compact=true){panel=Panel.KEYBOARD;refresh()}; nav.addView(cats,LinearLayout.LayoutParams(-2,-1)); r.addView(nav,LinearLayout.LayoutParams(-1,dp(44)))
        val scroll=ScrollView(this).apply{isFillViewport=true;overScrollMode=View.OVER_SCROLL_IF_CONTENT_SCROLLS}; val grid=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        PhormiKeyboardEmoji.categories.values.elementAtOrNull(emojiCategory).orEmpty().chunked(8).forEach{group->val rr=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER};group.forEach{e->key(rr,e,compact=true){commitText(e)}};grid.addView(rr,LinearLayout.LayoutParams(-1,dp(48)))}
        scroll.addView(grid,LinearLayout.LayoutParams(-1,-2)); r.addView(scroll,LinearLayout.LayoutParams(-1,0,1f)); return r
    }

    private fun clipboard():View {
        val r=root(); val h=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL};key(h,"← Keyboard"){panel=Panel.KEYBOARD;refresh()};key(h,"Clear"){PhormiKeyboardClipboardStore.clearUnpinned(this);refresh()};r.addView(h,LinearLayout.LayoutParams(-1,dp(44)))
        val scroll=ScrollView(this);val list=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};val items=PhormiKeyboardClipboardStore.list(this)
        if(items.isEmpty())list.addView(TextView(this).apply{text="Copy text normally while Phormi is active and it will appear here.";setTextColor(fg());setPadding(dp(12),dp(20),dp(12),dp(20))})
        items.forEach{item->val rr=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL};val tv=TextView(this).apply{text=item.text;setTextColor(fg());maxLines=3;setPadding(dp(10),dp(8),dp(4),dp(8))};rr.addView(tv,LinearLayout.LayoutParams(0,-2,1f));key(rr,if(item.pinned)"📌"else"○",compact=true){PhormiKeyboardClipboardStore.togglePinned(this@PhormiKeyboardService,item.text);refresh()};rr.setOnClickListener{commitText(item.text)};list.addView(rr)}
        scroll.addView(list);r.addView(scroll,LinearLayout.LayoutParams(-1,0,1f));return r
    }

    private fun tools():View {
        val r=root();key(r,"← Keyboard"){panel=Panel.KEYBOARD;refresh()};val grid=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        listOf("Select all","Copy","Cut","Paste","Clear clipboard").forEach{label->key(grid,label){when(label){"Select all"->selectAll();"Copy"->copy();"Cut"->cut();"Paste"->paste();"Clear clipboard"->PhormiKeyboardClipboardStore.clearUnpinned(this)};refresh()}}
        r.addView(grid,LinearLayout.LayoutParams(-1,0,1f));return r
    }

    private fun media(title:String):View {
        val r=root();val header=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL};key(header,"← Keyboard"){panel=Panel.KEYBOARD;refresh()};key(header,"Import"){startActivity(Intent(this,PhormiKeyboardMediaActivity::class.java).putExtra(PhormiKeyboardMediaActivity.EXTRA_MODE,if(title=="GIF")"gif"else"sticker").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))};r.addView(header,LinearLayout.LayoutParams(-1,dp(44)))
        val scroll=ScrollView(this);val body=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};body.addView(TextView(this).apply{text=if(title=="GIF")"GIFs stay in this keyboard panel. Import a GIF, then use the imported item."else"Stickers stay in this keyboard panel. Import a pack and tap an item to insert it.";setTextColor(fg());setPadding(dp(12),dp(18),dp(12),dp(18))})
        if(title=="Stickers")PhormiKeyboardStickerPackStore.packs(this).forEach{p->body.addView(TextView(this).apply{text="📦 ${p.name} (${p.files.size})";setTextColor(fg());setPadding(dp(12),dp(14),dp(12),dp(14));setOnClickListener{PhormiKeyboardStickerPackStore.files(this@PhormiKeyboardService,p).firstOrNull()?.let{f->commitContent(PhormiKeyboardStickerStore.contentUri(this@PhormiKeyboardService,f))}}})}
        scroll.addView(body);r.addView(scroll,LinearLayout.LayoutParams(-1,0,1f));return r
    }

    private fun refresh(){setInputView(render())}
    private fun refreshKeyboardOnly(){setInputView(render())}

    private fun key(row:LinearLayout,label:String,weight:Float=1f,compact:Boolean=false,action:()->Unit):Button=Button(this).apply{
        text=label;minWidth=0;minHeight=0;isAllCaps=false;textSize=if(compact)20f else 15f;setTextColor(fg());setPadding(dp(1),0,dp(1),0);contentDescription=label;background=roundedKeyBackground();stateListAnimator=null
        setOnClickListener{if(PhormiKeyboardPreferences.haptic(this@PhormiKeyboardService))performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);if(PhormiKeyboardPreferences.sound(this@PhormiKeyboardService))playSoundEffect(SoundEffectConstants.CLICK);action()}
        val gap=((PhormiKeyboardPreferences.keyWidth(this@PhormiKeyboardService)-100)/12).coerceIn(-1,4)+2
        row.addView(this,LinearLayout.LayoutParams(if(compact)dp(48)else 0,-1,if(compact)0f else weight).apply{setMargins(dp(gap),dp(2),dp(gap),dp(2))})
    }

    private fun roundedKeyBackground()=GradientDrawable().apply{val light=PhormiKeyboardPreferences.theme(this@PhormiKeyboardService)=="light"||(PhormiKeyboardPreferences.theme(this@PhormiKeyboardService)=="system"&&(resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)!=Configuration.UI_MODE_NIGHT_YES);setColor(if(light)Color.rgb(250,251,253)else Color.rgb(50,53,60));cornerRadius=dp(8).toFloat();setStroke(dp(1),if(light)Color.rgb(215,218,224)else Color.rgb(75,79,88))}
    private fun transparentRoundedBackground()=GradientDrawable().apply{setColor(Color.TRANSPARENT);cornerRadius=dp(8).toFloat()}
    private fun styleShift(button:Button){val light=PhormiKeyboardPreferences.theme(this)=="light"||(PhormiKeyboardPreferences.theme(this)=="system"&&(resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)!=Configuration.UI_MODE_NIGHT_YES);val color=when{capsLock->Color.rgb(205,48,60);shift->Color.WHITE;light->Color.rgb(225,228,234);else->Color.rgb(50,53,60)};button.background=GradientDrawable().apply{setColor(color);cornerRadius=dp(8).toFloat()};button.setTextColor(if(capsLock||shift)if(capsLock&&!light)Color.WHITE else Color.rgb(25,28,34)else fg())}

    private fun replaceWord(word:String){val ic=currentInputConnection?:return;val before=ic.getTextBeforeCursor(200,0)?.toString().orEmpty();val token=before.takeLastWhile{!it.isWhitespace()};if(token.isNotEmpty())ic.deleteSurroundingText(token.length,0);commitText(word);commitText(" ")}
    private fun commitText(text:String){currentInputConnection?.let{runCatching{it.commitText(text,1)}};refreshSuggestions()}

    private fun commitSpace(){val ic=currentInputConnection?:return;runCatching{
        if(PhormiKeyboardPreferences.autocorrect(this))correctWord(ic)
        val before=ic.getTextBeforeCursor(120,0)?.toString().orEmpty();val token=before.takeLastWhile{!it.isWhitespace()};if(token.isNotBlank()&&!password())PhormiKeyboardLexicon.learn(this,token)
        ic.commitText(" ",1);if(PhormiKeyboardPreferences.autoCaps(this)){val after=ic.getTextBeforeCursor(3,0)?.toString().orEmpty();if(after.endsWith(". ")||after.endsWith("! ")||after.endsWith("? ")||after.endsWith("\n"))shift=true};refreshKeyboardOnly()}}

    private fun correctWord(ic:InputConnection){val before=ic.getTextBeforeCursor(80,0)?.toString().orEmpty();val word=before.takeLastWhile{!it.isWhitespace()};if(word.isBlank())return;val replacement=PhormiKeyboardLexicon.correctWord(this,word)?:return;val formatted=if(word.firstOrNull()?.isUpperCase()==true)replacement.replaceFirstChar{it.uppercase()}else replacement;ic.deleteSurroundingText(word.length,0);ic.commitText(formatted,1)}
    private fun deleteBackward(){val ic=currentInputConnection?:return;runCatching{if(!ic.getSelectedText(0).isNullOrEmpty())ic.commitText("",1)else if(Build.VERSION.SDK_INT>=24)ic.deleteSurroundingTextInCodePoints(1,0)else ic.deleteSurroundingText(1,0)};refreshSuggestions()}
    private fun moveCursor(delta:Int){val ic=currentInputConnection?:return;runCatching{if(selectionStart!=selectionEnd){val target=if(delta<0)min(selectionStart,selectionEnd)else max(selectionStart,selectionEnd);ic.setSelection(target,target);selectionStart=target;selectionEnd=target}else{val target=(selectionStart+delta).coerceAtLeast(0);if(ic.setSelection(target,target)){selectionStart=target;selectionEnd=target}}};refreshSuggestions()}
    private fun sendEditorAction(){val ic=currentInputConnection?:return;val action=(editorInfo?.imeOptions?:0) and EditorInfo.IME_MASK_ACTION;runCatching{if(action!=EditorInfo.IME_ACTION_NONE&&action!=EditorInfo.IME_ACTION_UNSPECIFIED){if(!ic.performEditorAction(action))ic.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN,android.view.KeyEvent.KEYCODE_ENTER))}else{ic.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN,android.view.KeyEvent.KEYCODE_ENTER));ic.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP,android.view.KeyEvent.KEYCODE_ENTER))}}}
    private fun password():Boolean{val variation=(editorInfo?.inputType?:0) and InputType.TYPE_MASK_VARIATION;return variation==InputType.TYPE_TEXT_VARIATION_PASSWORD||variation==InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD||variation==InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD}
    private fun selectAll(){currentInputConnection?.let{runCatching{it.performContextMenuAction(android.R.id.selectAll)}}}
    private fun copy(){currentInputConnection?.let{runCatching{it.performContextMenuAction(android.R.id.copy)};PhormiKeyboardClipboardStore.capturePrimaryClipboard(this)}}
    private fun cut(){currentInputConnection?.let{runCatching{it.performContextMenuAction(android.R.id.cut)};PhormiKeyboardClipboardStore.capturePrimaryClipboard(this)}}
    private fun paste(){currentInputConnection?.let{runCatching{it.performContextMenuAction(android.R.id.paste)}}}

    private fun toggleVoice(){if(speechListening)stopVoice()else startVoice()}
    private fun startVoice(){if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){Toast.makeText(this,"Grant microphone permission in Phormi Keyboard settings first",Toast.LENGTH_SHORT).show();return};if(!SpeechRecognizer.isRecognitionAvailable(this)){Toast.makeText(this,"No speech recognition service is available",Toast.LENGTH_SHORT).show();return};stopVoice();speech=SpeechRecognizer.createSpeechRecognizer(this).apply{setRecognitionListener(object:RecognitionListener{
        override fun onReadyForSpeech(params:Bundle?){speechListening=true;refreshKeyboardOnly()};override fun onBeginningOfSpeech(){};override fun onRmsChanged(rmsdB:Float){};override fun onBufferReceived(buffer:ByteArray?){};override fun onEndOfSpeech(){speechListening=false;refreshKeyboardOnly()};override fun onError(error:Int){speechListening=false;refreshKeyboardOnly();if(error!=SpeechRecognizer.ERROR_NO_MATCH)Toast.makeText(this@PhormiKeyboardService,"Voice input error: $error",Toast.LENGTH_SHORT).show()};override fun onResults(results:Bundle?){results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf{it.isNotBlank()}?.let{commitText(it+" ")};speechListening=false;refreshKeyboardOnly()};override fun onPartialResults(partialResults:Bundle?){};override fun onEvent(eventType:Int,params:Bundle?){}})};val intent=Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply{putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);putExtra(RecognizerIntent.EXTRA_LANGUAGE,Locale.getDefault().toLanguageTag());putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,true)};runCatching{speech?.startListening(intent)}.onFailure{stopVoice()}}
    private fun stopVoice(){speechListening=false;runCatching{speech?.stopListening()};runCatching{speech?.destroy()};speech=null}
    private fun dp(value:Int)= (value*resources.displayMetrics.density).toInt().coerceAtLeast(1)

    private fun commitContent(uri:Uri):Boolean{val ic=currentInputConnection?:return false;if(Build.VERSION.SDK_INT<25)return false;val requested=editorInfo?.contentMimeTypes?.toList().orEmpty();val mime=when{requested.any{it=="image/gif"}->"image/gif";requested.any{it.startsWith("image/")}->"image/png";else->"image/*"};if(requested.isEmpty()||requested.any{ClipDescription.compareMimeTypes(mime,it)}){val info=InputContentInfo(uri,ClipDescription("Phormi media",arrayOf(mime)),null);if(runCatching{ic.commitContent(info,InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION,Bundle())}.getOrDefault(false))return true};Toast.makeText(this,"This text field does not accept image/GIF content",Toast.LENGTH_SHORT).show();return false}
}
