package com.uong.phormi

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.inputmethodservice.InputMethodService
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.CompletionInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputContentInfo
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.graphics.drawable.GradientDrawable
import java.io.File
import java.util.Locale

/** Phormi's system-wide Android IME. */
class PhormiKeyboardServiceV2 : InputMethodService() {
    companion object {
        private const val PREFS = "phormi_keyboard_pending"
        private const val KEY_PENDING_URI = "pending_uri"
        private const val KEY_PENDING_TEXT = "pending_text"
        private var instance: PhormiKeyboardServiceV2? = null

        fun commitPickedContent(context: Context, uri: Uri): Boolean {
            val service = instance
            if (service?.currentInputConnection != null) return service.commitContent(uri)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_PENDING_URI, uri.toString()).apply()
            return false
        }

        fun commitExternalText(context: Context, text: String): Boolean {
            if (text.isBlank()) return false
            val service = instance
            if (service?.currentInputConnection != null) return service.commitText(text)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_PENDING_TEXT, text).apply()
            return false
        }
    }

    private enum class Panel { KEYBOARD, EMOJI, CLIPBOARD, AI_EMOJI }
    private var panel = Panel.KEYBOARD
    private var symbols = false
    private var shift = false
    private var capsLock = false
    private var emojiCategory = 0
    private var editorInfo: EditorInfo? = null
    private var selectionStart = 0
    private var selectionEnd = 0
    private var completions: List<CompletionInfo> = emptyList()
    private var lastSuggestions = emptyList<String>()
    private var repeatRunnable: Runnable? = null
    private val repeatHandler = Handler(Looper.getMainLooper())
    private var spaceDownX = 0f
    private var spaceMoved = false
    private var aiPrompt = ""
    private var aiFiles: List<File> = emptyList()
    private var aiGenerating = false
    private var clipboardListener: ClipboardManager.OnPrimaryClipChangedListener? = null
    private val aiController by lazy { PhormiKeyboardAiEmojiController(this) { files, loading -> aiFiles = files; aiGenerating = loading; if (panel == Panel.EMOJI || panel == Panel.AI_EMOJI) setInputView(render()) } }

    override fun onCreate() {
        super.onCreate()
        instance = this
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboardListener = ClipboardManager.OnPrimaryClipChangedListener { PhormiKeyboardClipboardStore.capturePrimaryClipboard(this) }
        clipboardListener?.let { cm?.addPrimaryClipChangedListener(it) }
    }

    override fun onDestroy() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboardListener?.let { cm?.removePrimaryClipChangedListener(it) }
        clipboardListener = null
        aiController.cancel(); stopRepeat()
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onInitializeInterface() { super.onInitializeInterface(); stopRepeat() }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        editorInfo = attribute
        selectionStart = 0; selectionEnd = 0
        symbols = false; shift = false; capsLock = false; panel = Panel.KEYBOARD
        completions = emptyList(); lastSuggestions = emptyList(); aiFiles = emptyList(); aiGenerating = false; aiPrompt = ""
        aiController.cancel()
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        editorInfo = info ?: editorInfo
        panel = Panel.KEYBOARD
        setInputView(render())
        applyPendingInput()
        refreshPredictions()
    }

    override fun onFinishInput() { stopRepeat(); editorInfo = null; completions = emptyList(); lastSuggestions = emptyList(); aiController.cancel(); super.onFinishInput() }
    override fun onUnbindInput() { stopRepeat(); editorInfo = null; aiController.cancel(); super.onUnbindInput() }
    override fun onFinishInputView(finishingInput: Boolean) { stopRepeat(); super.onFinishInputView(finishingInput) }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        selectionStart = newSelStart; selectionEnd = newSelEnd
        if (panel == Panel.KEYBOARD) refreshPredictions(false)
    }

    override fun onDisplayCompletions(values: Array<out CompletionInfo>?) {
        super.onDisplayCompletions(values)
        completions = values?.filter { !it.text.isNullOrBlank() }?.take(5).orEmpty()
        refreshPredictions(false)
    }

    override fun onEvaluateFullscreenMode(): Boolean = false
    override fun onCreateInputView(): View = render()

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && panel != Panel.KEYBOARD) { panel = Panel.KEYBOARD; setInputView(render()); return true }
        return super.onKeyDown(keyCode, event)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt().coerceAtLeast(1)
    private fun root(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(6),dp(5),dp(6),dp(5)); setBackgroundColor(Color.rgb(13,18,30)); isFocusable = true }
    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = radius.toFloat() }

    private fun pill(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label; textSize = 13f; setTextColor(Color.rgb(229,231,235)); typeface = Typeface.DEFAULT_BOLD
        minWidth = 0; minHeight = 0; isAllCaps = false; stateListAnimator = null; setPadding(dp(7),0,dp(7),0)
        background = rounded(Color.rgb(31,41,55),dp(13)); contentDescription = label; setOnClickListener { action() }
    }

    private fun keyButton(label: String, weight: Float = 1f, action: () -> Unit): Button = Button(this).apply {
        text = label; textSize = if (label.length == 1) 20f else 13f; setTextColor(Color.WHITE); minWidth = 0; minHeight = 0
        isAllCaps = false; typeface = Typeface.DEFAULT; stateListAnimator = null; setPadding(dp(2),0,dp(2),0)
        background = rounded(Color.rgb(39,48,64),dp(9)); contentDescription = label; setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(0,dp(46),weight).apply { setMargins(dp(2),dp(2),dp(2),dp(2)) }
    }

    private fun toolbar(root: LinearLayout) {
        val scroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        fun add(label:String,width:Int=58,action:()->Unit){row.addView(pill(label,action),LinearLayout.LayoutParams(dp(width),dp(38)).apply{setMargins(dp(2),0,dp(2),0)})}
        add("😀") { panel=Panel.EMOJI; prepareAiContext(); setInputView(render()) }
        if (!isPrivateEditor()) add("✨",52) { prepareAiContext(); panel=Panel.AI_EMOJI; setInputView(render()) }
        add("📋") { PhormiKeyboardClipboardStore.capturePrimaryClipboard(this); panel=Panel.CLIPBOARD; setInputView(render()) }
        add("GIF",52){openMedia("gif")}; add("Sticker",68){openMedia("sticker")}
        if (shouldOfferSwitchingToNextInputMethod()) add("🌐",52){runCatching{switchToNextInputMethod(false)}.onFailure{showToast("No alternate keyboard available")}}
        scroll.addView(row); root.addView(scroll,LinearLayout.LayoutParams(-1,dp(40)))
        if (PhormiKeyboardTextEngine.shouldUsePredictions(editorInfo)) {
            val values=(if(completions.isNotEmpty()) completions.mapNotNull{it.text?.toString()} else lastSuggestions).distinct().take(5)
            if(values.isNotEmpty()){val s=HorizontalScrollView(this).apply{isHorizontalScrollBarEnabled=false};val r=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL};values.forEach{v->r.addView(pill(v){acceptSuggestion(v)},LinearLayout.LayoutParams(-2,dp(36)).apply{setMargins(dp(2),dp(1),dp(2),dp(1))})};s.addView(r);root.addView(s,LinearLayout.LayoutParams(-1,dp(38)))}
        }
    }

    private fun buildKeyboard(): View {
        val root=root(); toolbar(root)
        val type=editorInfo?.inputType?:InputType.TYPE_CLASS_TEXT; val clazz=type and InputType.TYPE_MASK_CLASS; val variation=type and InputType.TYPE_MASK_VARIATION
        val number=clazz==InputType.TYPE_CLASS_NUMBER; val phone=clazz==InputType.TYPE_CLASS_PHONE; val dateTime=clazz==InputType.TYPE_CLASS_DATETIME
        val email=variation==InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS||variation==InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS; val uri=variation==InputType.TYPE_TEXT_VARIATION_URI
        when { phone->addRows(root,listOf("1234567890","*#+-","().")); number->addRows(root,listOf("1234567890","789456123","0.,+-")); dateTime->addRows(root,listOf("1234567890","4567891230",":/-")); symbols->addRows(root,listOf("1234567890","-=[]\\;',./","!@#\$%^&*()","_+{}|:\"<>?")); else->addRows(root,listOf("qwertyuiop","asdfghjkl","zxcvbnm")) }
        if(email||uri){val r=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER};(if(email)listOf("@",".com",".net",".org")else listOf("/",".com","https://",".org")).forEach{token->r.addView(keyButton(token){commitText(token);refreshPredictions()})};root.addView(r,LinearLayout.LayoutParams(-1,dp(50)))}
        val bottom=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER}
        if(!number&&!phone&&!dateTime){bottom.addView(keyButton(if(symbols)"ABC" else "123"){symbols=!symbols;setInputView(render())});bottom.addView(keyButton(if(capsLock)"⇧·" else "⇧"){toggleShift();setInputView(render())})}
        bottom.addView(keyButton("Sel"){selectAll()});bottom.addView(keyButton("Copy"){copySelection()});bottom.addView(keyButton("Paste"){pasteClipboard()})
        val space=keyButton("Space",if(number||phone)2f else 2.7f){commitSpace()}
        space.setOnTouchListener{_,e->when(e.actionMasked){MotionEvent.ACTION_DOWN->{spaceDownX=e.x;spaceMoved=false};MotionEvent.ACTION_MOVE->{if(!spaceMoved&&kotlin.math.abs(e.x-spaceDownX)>dp(30)){spaceMoved=true;moveCursor(if(e.x>spaceDownX)1 else -1)}};MotionEvent.ACTION_UP->{if(!spaceMoved)commitSpace()};MotionEvent.ACTION_CANCEL->{spaceMoved=false}};true}
        bottom.addView(space);bottom.addView(keyButton("←"){moveCursor(-1)});bottom.addView(keyButton("→"){moveCursor(1)});val back=keyButton("⌫"){deleteBackward()};installRepeat(back){deleteBackward()};bottom.addView(back);bottom.addView(keyButton(actionLabel()){sendEditorAction()})
        if(!number&&!phone&&!dateTime) bottom.addView(keyButton("🎙"){startActivity(Intent(this,PhormiKeyboardVoiceActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))})
        root.addView(bottom,LinearLayout.LayoutParams(-1,dp(54))); return root
    }

    private fun addRows(root:LinearLayout,rows:List<String>){rows.forEach{chars->val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER};chars.forEach{c->val label=if(c.isLetter()&&(shift||capsLock))c.uppercaseChar().toString()else c.toString();row.addView(keyButton(label){commitText(label);if(shift&&!capsLock)shift=false;refreshPredictions();setInputView(render())})};root.addView(row,LinearLayout.LayoutParams(-1,dp(50)))}}

    private fun buildEmoji(): View {
        val root=root(); val nav=HorizontalScrollView(this).apply{isHorizontalScrollBarEnabled=false}; val categories=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        PhormiKeyboardEmoji.categories.keys.forEachIndexed{index,icon->categories.addView(pill(icon){emojiCategory=index;setInputView(render())},LinearLayout.LayoutParams(dp(48),dp(38)).apply{setMargins(dp(2),0,dp(2),0)})}
        if(!isPrivateEditor()) categories.addView(pill("✨"){prepareAiContext();panel=Panel.AI_EMOJI;setInputView(render())},LinearLayout.LayoutParams(dp(48),dp(38)))
        categories.addView(pill("ABC"){panel=Panel.KEYBOARD;setInputView(render())},LinearLayout.LayoutParams(dp(58),dp(38)));nav.addView(categories);root.addView(nav,LinearLayout.LayoutParams(-1,dp(42)))

        val scroll=ScrollView(this); val grid=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        val contextText=PhormiKeyboardTextEngine.contextBeforeCursor(currentInputConnection)
        if(!isPrivateEditor()){
            val moods=PhormiKeyboardAiContext.suggestions(contextText); if(moods.isNotEmpty()){
                val moodRow=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER};moods.forEach{m->moodRow.addView(keyButton(m.emoji){commitText(m.emoji)},LinearLayout.LayoutParams(0,dp(50),1f))};grid.addView(moodRow,LinearLayout.LayoutParams(-1,dp(54)))
            }
            if(aiFiles.isNotEmpty()){
                val aiRow=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER}
                aiFiles.take(4).forEachIndexed{index,file->aiRow.addView(emojiImageButton(file,"Suggested emoji ${index+1}"){if(commitContent(PhormiKeyboardStickerStore.contentUri(this@PhormiKeyboardServiceV2,file))){aiFiles=aiFiles.filterNot{it==file};setInputView(render())}},LinearLayout.LayoutParams(0,dp(50),1f))}
                grid.addView(aiRow,LinearLayout.LayoutParams(-1,dp(54)))
            }
        }
        PhormiKeyboardEmoji.categories.values.elementAtOrNull(emojiCategory).orEmpty().chunked(8).forEach{group->val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER};group.forEach{emoji->row.addView(keyButton(emoji){commitText(emoji)})};grid.addView(row,LinearLayout.LayoutParams(-1,dp(50)))}
        scroll.addView(grid);root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f));return root
    }

    private fun emojiImageButton(file:File,description:String,action:()->Unit):View=ImageView(this).apply{setImageBitmap(android.graphics.BitmapFactory.decodeFile(file.absolutePath));contentDescription=description;scaleType=ImageView.ScaleType.CENTER_INSIDE;setPadding(dp(8),dp(6),dp(8),dp(6));background=rounded(Color.rgb(39,48,64),dp(9));setOnClickListener{action()}}

    private fun buildClipboard():View{val root=root();val header=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL};header.addView(pill("← Keyboard"){panel=Panel.KEYBOARD;setInputView(render())});header.addView(pill("Clear"){PhormiKeyboardClipboardStore.clearUnpinned(this);setInputView(render())});root.addView(header,LinearLayout.LayoutParams(-1,dp(42)));val scroll=ScrollView(this);val list=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};val items=PhormiKeyboardClipboardStore.list(this);if(items.isEmpty())list.addView(TextView(this).apply{text="Copy something to build your Phormi clipboard history.";setTextColor(Color.LTGRAY);setPadding(dp(12),dp(20),dp(12),dp(20))});items.forEach{item->val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;background=rounded(Color.rgb(27,35,49),dp(10));setPadding(dp(8),dp(3),dp(8),dp(3))};row.addView(TextView(this).apply{text=item.text;setTextColor(Color.WHITE);textSize=13f;maxLines=3},LinearLayout.LayoutParams(0,dp(56),1f));row.addView(pill(if(item.pinned)"📌" else "○"){PhormiKeyboardClipboardStore.togglePinned(this@PhormiKeyboardServiceV2,item.text);setInputView(render())},LinearLayout.LayoutParams(dp(50),dp(42)));row.setOnClickListener{commitText(item.text)};row.setOnLongClickListener{PhormiKeyboardClipboardStore.remove(this,item.text);setInputView(render());true};list.addView(row,LinearLayout.LayoutParams(-1,dp(64)).apply{setMargins(0,dp(3),0,dp(3))})};scroll.addView(list);root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f));return root}

    private fun buildAiEmoji():View{if(isPrivateEditor()){panel=Panel.KEYBOARD;return buildKeyboard()};val root=root();val header=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL};header.addView(pill("← Emoji"){panel=Panel.EMOJI;setInputView(render())});header.addView(TextView(this).apply{text="Emoji";setTextColor(Color.WHITE);textSize=17f;typeface=Typeface.DEFAULT_BOLD;setPadding(dp(8),0,0,0)});root.addView(header,LinearLayout.LayoutParams(-1,dp(44)));val context=PhormiKeyboardTextEngine.contextBeforeCursor(currentInputConnection);val moods=PhormiKeyboardAiContext.suggestions(context);val moodRow=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER};moods.forEach{m->moodRow.addView(keyButton(m.emoji){commitText(m.emoji)},LinearLayout.LayoutParams(0,dp(50),1f))};root.addView(moodRow,LinearLayout.LayoutParams(-1,dp(54)));val actions=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER};actions.addView(pill(if(aiGenerating)"Generating…" else "Generate"){if(!aiGenerating)generateAiEmoji()},LinearLayout.LayoutParams(0,dp(42),1f));actions.addView(pill("Use typed context"){aiPrompt=PhormiKeyboardTextEngine.contextBeforeCursor(currentInputConnection).takeLast(140);generateAiEmoji()},LinearLayout.LayoutParams(0,dp(42),1f));root.addView(actions,LinearLayout.LayoutParams(-1,dp(46)));val scroll=ScrollView(this);val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER};aiFiles.forEach{file->row.addView(emojiImageButton(file,"Suggested emoji"){commitContent(PhormiKeyboardStickerStore.contentUri(this@PhormiKeyboardServiceV2,file))},LinearLayout.LayoutParams(dp(50),dp(50)))};scroll.addView(row);root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f));return root}

    private fun prepareAiContext(){aiPrompt=PhormiKeyboardTextEngine.contextBeforeCursor(currentInputConnection).takeLast(140)}
    private fun generateAiEmoji(){if(isPrivateEditor())return;val prompt=aiPrompt.ifBlank{PhormiKeyboardTextEngine.contextBeforeCursor(currentInputConnection).takeLast(140)}.ifBlank{"happy expressive emoji"};aiPrompt=prompt;aiController.generate(prompt)}

    private fun acceptSuggestion(value:String){val ic=currentInputConnection?:return;val word=PhormiKeyboardTextEngine.currentWord(ic);if(word.isBlank()){commitText(value);return};runCatching{ic.beginBatchEdit();ic.deleteSurroundingText(word.length,0);ic.commitText(matchCase(value,word)+" ",1)}.also{runCatching{ic.endBatchEdit()}};PhormiKeyboardTextEngine.learn(this,value,editorInfo);refreshPredictions()}
    private fun matchCase(value:String,source:String)=when{source.all{!it.isLetter()||it.isUpperCase()}->value.uppercase(Locale.US);source.firstOrNull()?.isUpperCase()==true->value.replaceFirstChar{it.titlecase(Locale.US)};else->value}
    private fun toggleShift(){if(capsLock){capsLock=false;shift=false}else if(shift){capsLock=true;shift=false}else shift=true}
    private fun refreshPredictions(redraw:Boolean=true){if(!PhormiKeyboardTextEngine.shouldUsePredictions(editorInfo)){lastSuggestions=emptyList();if(redraw&&panel==Panel.KEYBOARD)setInputView(render());return};lastSuggestions=if(PhormiKeyboardPreferences.suggestions(this))PhormiKeyboardTextEngine.suggestions(this,PhormiKeyboardTextEngine.currentWord(currentInputConnection))else emptyList();if(redraw&&panel==Panel.KEYBOARD)setInputView(render())}

    fun commitText(text:String):Boolean{if(text.isEmpty())return false;val ic=currentInputConnection?:return false;return runCatching{if(editorInfo?.inputType==InputType.TYPE_NULL){text.forEach{c->val code=keyCodeFor(c);if(code!=KeyEvent.KEYCODE_UNKNOWN){ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN,code));ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP,code))}else ic.commitText(c.toString(),1)}}else ic.commitText(text,1);true}.getOrDefault(false)}
    private fun keyCodeFor(c:Char):Int=when(c){' '->KeyEvent.KEYCODE_SPACE;'\n'->KeyEvent.KEYCODE_ENTER;'.'->KeyEvent.KEYCODE_PERIOD;','->KeyEvent.KEYCODE_COMMA;'-'->KeyEvent.KEYCODE_MINUS else->KeyEvent.keyCodeFromString("KEYCODE_${c.uppercaseChar()}")}

    private fun commitSpace(){val ic=currentInputConnection?:return;if(!PhormiKeyboardPreferences.autocorrect(this)){ic.commitText(" ",1);refreshPredictions();return};val word=PhormiKeyboardTextEngine.currentWord(ic);runCatching{ic.beginBatchEdit();if(PhormiKeyboardTextEngine.shouldUsePredictions(editorInfo)&&word.isNotBlank()){PhormiKeyboardTextEngine.correctionFor(word)?.let{c->ic.deleteSurroundingText(word.length,0);ic.commitText(matchCase(c,word),1)};PhormiKeyboardTextEngine.learn(this,word,editorInfo)};val before=ic.getTextBeforeCursor(2,0)?.toString().orEmpty();if(before.endsWith("  ")){ic.deleteSurroundingText(2,0);ic.commitText(". ",1)}else ic.commitText(" ",1)}.also{runCatching{ic.endBatchEdit()}};refreshPredictions()}

    private fun deleteBackward(){val ic=currentInputConnection?:return;runCatching{ic.beginBatchEdit();if(!ic.getSelectedText(0).isNullOrEmpty()){ic.commitText("",1);return@runCatching};val before=ic.getTextBeforeCursor(128,0)?.toString().orEmpty();if(before.isEmpty())return@runCatching;val iterator=android.icu.text.BreakIterator.getCharacterInstance().apply{setText(before)};val boundary=iterator.preceding(before.length);val count=if(boundary>=0)before.length-boundary else 1;if(!ic.deleteSurroundingText(count,0))ic.deleteSurroundingTextInCodePoints(1,0)}.also{runCatching{ic.endBatchEdit()}};refreshPredictions()}
    private fun installRepeat(view:View,action:()->Unit){view.setOnLongClickListener{stopRepeat();val r=object:Runnable{override fun run(){if(currentInputConnection==null){stopRepeat();return};action();repeatHandler.postDelayed(this,55)}};repeatRunnable=r;repeatHandler.postDelayed(r,280);true};view.setOnTouchListener{_,e->if(e.actionMasked==MotionEvent.ACTION_UP||e.actionMasked==MotionEvent.ACTION_CANCEL)stopRepeat();false}}
    private fun stopRepeat(){repeatRunnable?.let{repeatHandler.removeCallbacks(it)};repeatRunnable=null}

    private fun moveCursor(delta:Int){val ic=currentInputConnection?:return;runCatching{if(selectionStart!=selectionEnd){val c=if(delta<0)minOf(selectionStart,selectionEnd)else maxOf(selectionStart,selectionEnd);ic.setSelection(c,c);selectionStart=c;selectionEnd=c;return@runCatching};val before=ic.getTextBeforeCursor(256,0)?.toString().orEmpty();val after=ic.getTextAfterCursor(256,0)?.toString().orEmpty();val target=if(delta<0){val it=android.icu.text.BreakIterator.getCharacterInstance().apply{setText(before)};val b=it.preceding(before.length);(selectionStart-(before.length-b)).coerceAtLeast(0)}else{val it=android.icu.text.BreakIterator.getCharacterInstance().apply{setText(after)};val n=it.following(0).let{if(it<0)after.length else it};(selectionStart+n).coerceAtMost(selectionStart+after.length)};if(!ic.setSelection(target,target))ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN,if(delta<0)KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT));selectionStart=target;selectionEnd=target}}
    private fun selectAll(){currentInputConnection?.performContextMenuAction(android.R.id.selectAll)};private fun copySelection(){currentInputConnection?.performContextMenuAction(android.R.id.copy)};private fun pasteClipboard(){currentInputConnection?.performContextMenuAction(android.R.id.paste)}
    private fun actionLabel()=when((editorInfo?.imeOptions?:0)and EditorInfo.IME_MASK_ACTION){EditorInfo.IME_ACTION_GO->"Go";EditorInfo.IME_ACTION_SEARCH->"Search";EditorInfo.IME_ACTION_NEXT->"Next";EditorInfo.IME_ACTION_DONE->"Done";EditorInfo.IME_ACTION_SEND->"Send";EditorInfo.IME_ACTION_PREVIOUS->"Prev";else->"↵"}
    private fun sendEditorAction(){val ic=currentInputConnection?:return;val action=(editorInfo?.imeOptions?:0)and EditorInfo.IME_MASK_ACTION;runCatching{if(action!=EditorInfo.IME_ACTION_NONE&&action!=EditorInfo.IME_ACTION_UNSPECIFIED){if(!ic.performEditorAction(action))sendEnter(ic)}else sendEnter(ic)}}
    private fun sendEnter(ic:InputConnection){ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_ENTER));ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_ENTER))}
    private fun openMedia(mode:String){startActivity(Intent(this,PhormiKeyboardMediaActivity::class.java).putExtra(PhormiKeyboardMediaActivity.EXTRA_MODE,mode).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}

    private fun commitContent(uri:Uri):Boolean{val ic=currentInputConnection?:return false;if(android.os.Build.VERSION.SDK_INT<25)return false;val requested=editorInfo?.contentMimeTypes?.toList().orEmpty();val mime=when{requested.any{it=="image/gif"}->"image/gif";requested.any{it=="image/jpeg"}->"image/jpeg";requested.any{it=="image/png"}->"image/png";requested.any{it.startsWith("image/")}->requested.first{it.startsWith("image/")};requested.any{it.startsWith("video/")}->requested.first{it.startsWith("video/")};else->"image/png"};if(requested.isNotEmpty()&&!requested.any{ClipDescription.compareMimeTypes(mime,it)})return false;val content=InputContentInfo(uri,ClipDescription("Phormi media",arrayOf(mime)),null);if(runCatching{ic.commitContent(content,InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION,Bundle())}.getOrDefault(false))return true;runCatching{(getSystemService(Context.CLIPBOARD_SERVICE)as ClipboardManager).setPrimaryClip(android.content.ClipData.newRawUri("Phormi media",uri));showToast("Media is ready to paste.")};return false}
    private fun applyPendingInput(){val p=getSharedPreferences(PREFS,MODE_PRIVATE);p.getString(KEY_PENDING_TEXT,null)?.let{p.edit().remove(KEY_PENDING_TEXT).apply();commitText(it)};p.getString(KEY_PENDING_URI,null)?.let{p.edit().remove(KEY_PENDING_URI).apply();commitContent(Uri.parse(it))}}
    private fun isPrivateEditor():Boolean{val info=editorInfo?:return true;return PhormiKeyboardTextEngine.isPrivateEditor(info)||(info.inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)!=0}
    private fun showToast(text:String){Toast.makeText(this,text,Toast.LENGTH_SHORT).show()}
}