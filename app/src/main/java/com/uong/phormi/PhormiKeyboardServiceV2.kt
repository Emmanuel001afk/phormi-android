package com.uong.phormi

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputContentInfo
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.roundToInt

/** Phormi IME. Every panel uses one fixed viewport; long content scrolls inside it. */
class PhormiKeyboardServiceV2 : InputMethodService() {
    companion object {
        private const val PREFS = "phormi_keyboard_pending"
        private const val KEY_PENDING_URI = "pending_uri"
        private const val KEY_PENDING_TEXT = "pending_text"
        private var instance: PhormiKeyboardServiceV2? = null
        fun commitPickedContent(context: Context, uri: Uri): Boolean {
            val service = instance
            if (service?.currentInputConnection != null) return service.commitContentToEditor(uri)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_PENDING_URI, uri.toString()).apply()
            return false
        }
        fun commitExternalText(context: Context, text: String): Boolean {
            if (text.isBlank()) return false
            val service = instance
            if (service?.currentInputConnection != null) { service.commitTextToEditor(text); return true }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_PENDING_TEXT, text.take(4000)).apply()
            return false
        }
    }
    enum class Panel { KEYBOARD, EMOJI, CLIPBOARD, AI_EMOJI, TOOLS, MEDIA, SETTINGS }
    private enum class KeyboardPage { LETTERS, NUMBERS, SYMBOLS }
    private var panel = Panel.KEYBOARD
    private var page = KeyboardPage.LETTERS
    private var shift = false
    private var capsLock = false
    private var autoShift = false
    private var emojiCategory = 0
    private var editorInfo: EditorInfo? = null
    private var clipboardListener: ClipboardManager.OnPrimaryClipChangedListener? = null
    private var resizeStartY = 0f
    private var resizeStartX = 0f
    private var resizeStartHeight = 1f
    private var resizeStartWidth = 1f
    private var resizePreviewHeight = 1f
    private var resizePreviewWidth = 1f
    private var spaceDownX = 0f
    private var spaceMoved = false
    private var aiEmojiContext = ""
    private var aiEmojiFile: java.io.File? = null
    private var aiEmojiLoading = false
    private var aiEmojiRunnable: Runnable? = null
    private val repeatHandler = Handler(Looper.getMainLooper())
    private var repeatRunnable: Runnable? = null
    private var predictionRow: LinearLayout? = null

    override fun onCreate() {
        super.onCreate(); instance = this
        PhormiKeyboardAiBridge.start(this)
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboardListener = ClipboardManager.OnPrimaryClipChangedListener { PhormiKeyboardClipboardStore.capturePrimaryClipboard(this) }
        clipboardListener?.let { cm?.addPrimaryClipChangedListener(it) }
    }
    override fun onDestroy() {
        stopRepeat(); aiEmojiRunnable?.let { repeatHandler.removeCallbacks(it) }; aiEmojiRunnable = null
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboardListener?.let { cm?.removePrimaryClipChangedListener(it) }; clipboardListener = null
        PhormiKeyboardAiBridge.stop()
        if (instance === this) instance = null; super.onDestroy()
    }
    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting); editorInfo = attribute; page = KeyboardPage.LETTERS; capsLock = false; shift = false
        autoShift = PhormiKeyboardPreferences.autoCaps(this) && PhormiKeyboardTextEngine.autoCapitalize(currentInputConnection, attribute); panel = Panel.KEYBOARD
    }
    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) { super.onStartInputView(info, restarting); editorInfo = info ?: editorInfo; panel = Panel.KEYBOARD; setInputView(render()); applyKeyboardWindowSize(); applyPendingInput() }
    override fun onFinishInput() { stopRepeat(); editorInfo = null; super.onFinishInput() }
    override fun onUnbindInput() { stopRepeat(); editorInfo = null; super.onUnbindInput() }
    override fun onFinishInputView(finishingInput: Boolean) { stopRepeat(); super.onFinishInputView(finishingInput) }
    override fun onEvaluateFullscreenMode(): Boolean = false
    override fun onCreateInputView(): View = render().also { applyKeyboardWindowSize() }

    override fun onConfigureWindow(win: Window, isFullscreen: Boolean, isCandidatesOnly: Boolean) {
        super.onConfigureWindow(win, isFullscreen, isCandidatesOnly)
        if (!isFullscreen && !isCandidatesOnly) applyKeyboardWindowSize(win)
    }

    private fun applyKeyboardWindowSize(win: Window? = getWindow().window) {
        val window = win ?: return
        val height = scaled(baseHeight())
        val width = (resources.displayMetrics.widthPixels * PhormiKeyboardPreferences.widthScale(this)).roundToInt()
            .coerceIn(dp(240), getMaxWidth().coerceAtLeast(dp(240)))
        window.setLayout(width, height)
    }

    private fun density(): Float = resources.displayMetrics.density
    private fun dp(value: Int): Int = (value * density()).roundToInt().coerceAtLeast(1)
    private fun baseHeight(): Int = 300
    private fun scale(level: Int = PhormiKeyboardPreferences.height(this)): Float = PhormiKeyboardPreferences.heightScaleFor(level)
    private fun scaled(value: Int, level: Int = PhormiKeyboardPreferences.height(this)): Int = dp((value * scale(level)).roundToInt())
    private fun theme(): Int = PhormiKeyboardPreferences.theme(this)
    private fun themeBackground(): Int = when (theme()) { 1 -> Color.rgb(20,24,29); 2 -> Color.rgb(7,24,42); 3 -> Color.rgb(242,244,247); else -> Color.rgb(13,18,30) }
    private fun themeKey(): Int = when (theme()) { 1 -> Color.rgb(48,53,61); 2 -> Color.rgb(18,52,79); 3 -> Color.WHITE; else -> Color.rgb(39,48,64) }
    private fun themePill(): Int = when (theme()) { 1 -> Color.rgb(37,42,49); 2 -> Color.rgb(15,45,69); 3 -> Color.rgb(224,228,234); else -> Color.rgb(31,41,55) }
    private fun themeText(): Int = if (theme() == 3) Color.rgb(20,27,36) else Color.WHITE
    private fun accent(): Int = when (theme()) { 2 -> Color.rgb(65,135,220); 3 -> Color.rgb(98,72,220); else -> Color.rgb(112,75,255) }
    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = radius.toFloat() }
    private fun root(): LinearLayout = LinearLayout(this).apply {
        orientation=LinearLayout.VERTICAL
        setPadding(dp(6),dp(2),dp(6),dp(2))
        setBackgroundColor(themeBackground())
        val width=(resources.displayMetrics.widthPixels*PhormiKeyboardPreferences.widthScale(this@PhormiKeyboardServiceV2)).roundToInt().coerceAtLeast(dp(240))
        val height=scaled(baseHeight())
        layoutParams=LinearLayout.LayoutParams(width,height).apply{gravity=Gravity.CENTER_HORIZONTAL}
        minimumHeight=height
        applyWallpaper(this)
        addResizeGrip(this)
    }
    private fun applyWallpaper(root: View) { val value=PhormiKeyboardPreferences.wallpaperUri(this)?:return;runCatching{contentResolver.openInputStream(Uri.parse(value))?.use{BitmapFactory.decodeStream(it)}?.let{root.background=BitmapDrawable(resources,it).apply{alpha=72}}} }
    private fun feedback(view: View) { if(PhormiKeyboardPreferences.haptic(this))view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);if(PhormiKeyboardPreferences.sound(this))view.playSoundEffect(android.view.SoundEffectConstants.CLICK) }
    private fun pill(label:String,action:()->Unit):Button=Button(this).apply{text=label;textSize=13f;setTextColor(themeText());typeface=Typeface.DEFAULT_BOLD;minWidth=0;minHeight=0;isAllCaps=false;stateListAnimator=null;setPadding(dp(7),0,dp(7),0);background=rounded(themePill(),dp(13));contentDescription=label;setOnClickListener{feedback(this);action()}}
    private fun keyButton(label:String,weight:Float=1f,action:()->Unit):Button=Button(this).apply{text=label;textSize=when{label.codePointCount(0,label.length)==1->20f;label=="Space"->13f;else->12f};setTextColor(themeText());minWidth=0;minHeight=0;isAllCaps=false;stateListAnimator=null;setPadding(dp(2),0,dp(2),0);background=rounded(themeKey(),dp(9));contentDescription=label;setOnClickListener{feedback(this);action()};layoutParams=LinearLayout.LayoutParams(0,scaled(46),weight).apply{setMargins(dp(2),dp(2),dp(2),dp(2))}}
    private fun shiftButton(action:()->Unit):Button{val state=when{capsLock->2;shift||autoShift->1;else->0};return keyButton(if(state==0)"⇧"else"⇧A",action=action).apply{background=rounded(when(state){2->Color.rgb(220,38,38);1->accent();else->themeKey()},dp(9));setTextColor(Color.WHITE);contentDescription=when(state){2->"Caps lock";1->"One-letter capitalization";else->"Shift"}}}
    private fun addResizeGrip(root:LinearLayout){val grip=TextView(this).apply{text="↕↔";textSize=16f;gravity=Gravity.CENTER;setTextColor(if(theme()==3)Color.DKGRAY else Color.rgb(148,163,184));background=rounded(themePill(),dp(10));contentDescription="Resize Phormi Keyboard height and width";setOnTouchListener{_,event->when(event.actionMasked){MotionEvent.ACTION_DOWN->{resizeStartY=event.rawY;resizeStartX=event.rawX;resizeStartHeight=PhormiKeyboardPreferences.heightScale(this@PhormiKeyboardServiceV2);resizeStartWidth=PhormiKeyboardPreferences.widthScale(this@PhormiKeyboardServiceV2);resizePreviewHeight=resizeStartHeight;resizePreviewWidth=resizeStartWidth;true};MotionEvent.ACTION_MOVE->{resizePreviewHeight=(resizeStartHeight+(resizeStartY-event.rawY)/(520f*density())).coerceIn(0.70f,1.35f);resizePreviewWidth=(resizeStartWidth+(event.rawX-resizeStartX)/resources.displayMetrics.widthPixels.toFloat().coerceAtLeast(1f)).coerceIn(0.70f,1.00f);val lp=(root.layoutParams as? LinearLayout.LayoutParams?:LinearLayout.LayoutParams(-1,scaled(baseHeight())));lp.width=(resources.displayMetrics.widthPixels*resizePreviewWidth).roundToInt().coerceAtLeast(dp(240));lp.height=(baseHeight()*density()*resizePreviewHeight).roundToInt().coerceAtLeast(dp(240));lp.gravity=Gravity.CENTER_HORIZONTAL;root.layoutParams=lp;root.minimumHeight=lp.height;root.requestLayout();true};MotionEvent.ACTION_UP->{PhormiKeyboardPreferences.setHeightScale(this@PhormiKeyboardServiceV2,resizePreviewHeight);PhormiKeyboardPreferences.setWidthScale(this@PhormiKeyboardServiceV2,resizePreviewWidth);setInputView(render());applyKeyboardWindowSize();true};MotionEvent.ACTION_CANCEL->{setInputView(render());true};else->true}}};root.addView(grip,0,LinearLayout.LayoutParams(dp(64),dp(26)).apply{gravity=Gravity.CENTER_HORIZONTAL;bottomMargin=dp(2)})}
    private fun toolbar(root:LinearLayout){val scroll=HorizontalScrollView(this).apply{isHorizontalScrollBarEnabled=false;overScrollMode=View.OVER_SCROLL_NEVER};val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL};fun add(label:String,width:Int=58,action:()->Unit){row.addView(pill(label,action),LinearLayout.LayoutParams(dp(width),scaled(36)).apply{setMargins(dp(2),0,dp(2),0)})};add("😀"){panel=Panel.EMOJI;setInputView(render())};add("📋"){panel=Panel.CLIPBOARD;PhormiKeyboardClipboardStore.capturePrimaryClipboard(this);setInputView(render())};add("▦ Cabinet",76){panel=Panel.TOOLS;setInputView(render())};if(Build.VERSION.SDK_INT>=28&&shouldOfferSwitchingToNextInputMethod())add("🌐",52){runCatching{switchToNextInputMethod(false)}};scroll.addView(row);root.addView(scroll,LinearLayout.LayoutParams(-1,scaled(40)))}
    private fun predictionStrip(root:LinearLayout){
        val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
        predictionRow=row
        root.addView(row,LinearLayout.LayoutParams(-1,scaled(34)))
        refreshPredictionStrip()
    }
    private fun refreshPredictionStrip(){
        val row=predictionRow ?: return
        row.removeAllViews()
        if(!PhormiKeyboardPreferences.suggestions(this) || !PhormiKeyboardTextEngine.shouldUsePredictions(editorInfo)) return
        val ic=currentInputConnection
        val word=PhormiKeyboardTextEngine.currentWord(ic)
        val previous=PhormiKeyboardTextEngine.previousWord(ic,PhormiKeyboardTextEngine.localeFor(editorInfo))
        val suggestions=if(word.isNotBlank()) PhormiKeyboardTextEngine.suggestions(this,word,PhormiKeyboardTextEngine.localeFor(editorInfo)) else PhormiKeyboardTextEngine.nextWordSuggestions(this,previous,PhormiKeyboardTextEngine.localeFor(editorInfo))
        if(PhormiKeyboardTextEngine.allowsAiEmoji(editorInfo)&&PhormiKeyboardPreferences.aiEmoji(this)){row.addView(pill("✨"){panel=Panel.AI_EMOJI;setInputView(render())},LinearLayout.LayoutParams(dp(48),scaled(32)).apply{setMargins(dp(2),0,dp(2),0)})};suggestions.take(4).forEach{value->
            val button=pill(value,{})
            button.setOnClickListener{feedback(button);if(word.isNotBlank()){currentInputConnection?.deleteSurroundingText(word.length,0);commitTextToEditor(value)}else commitTextToEditor("$value ");refreshPredictionStrip()}
            row.addView(button,LinearLayout.LayoutParams(0,scaled(32),1f).apply{setMargins(dp(2),0,dp(2),0)})
        }
        row.visibility=if(row.childCount>0) View.VISIBLE else View.GONE
    }
    private fun buildKeyboard():View{val root=root();if(page==KeyboardPage.LETTERS)predictionStrip(root);toolbar(root);val type=editorInfo?.inputType?:InputType.TYPE_CLASS_TEXT;val clazz=type and InputType.TYPE_MASK_CLASS;val variation=type and InputType.TYPE_MASK_VARIATION;val number=clazz==InputType.TYPE_CLASS_NUMBER;val phone=clazz==InputType.TYPE_CLASS_PHONE;val dateTime=clazz==InputType.TYPE_CLASS_DATETIME;val email=variation==InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS||variation==InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS;val uri=variation==InputType.TYPE_TEXT_VARIATION_URI;if((number||phone||dateTime)&&page==KeyboardPage.LETTERS)page=KeyboardPage.NUMBERS;when{phone->responsiveRows(root,listOf("1234567890","*#+","()-"));dateTime->responsiveRows(root,listOf("1234567890","4567890",":/-"));number&&page==KeyboardPage.SYMBOLS->buildSymbolPage(root);number->buildNumberPage(root);page==KeyboardPage.LETTERS->buildLetterPage(root,email,uri);page==KeyboardPage.NUMBERS->buildNumberPage(root);else->buildSymbolPage(root)};return root}
    private fun buildLetterPage(root:LinearLayout,email:Boolean,uri:Boolean){responsiveRows(root,listOf("qwertyuiop","asdfghjkl","zxcvbnm"));if(email||uri){val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER};val tokens=if(email)listOf("@",".com",".net",".org")else listOf("/",".com","https://",".org");tokens.forEach{token->row.addView(keyButton(token){commitTextToEditor(token);refreshAfterTextKey()})};root.addView(row,LinearLayout.LayoutParams(-1,scaled(50)))};addBottomRow(root,KeyboardPage.LETTERS)}
    private fun buildNumberPage(root:LinearLayout){
        responsiveRows(root,listOf("123","456","789","0.,"))
        root.addView(keyButton("Symbols",action={page=KeyboardPage.SYMBOLS;setInputView(render())}).apply{layoutParams=LinearLayout.LayoutParams(-1,scaled(44)).apply{setMargins(dp(2),dp(2),dp(2),dp(2))}})
        addBottomRow(root,KeyboardPage.NUMBERS)
    }
    private fun buildSymbolPage(root:LinearLayout){
        val categories=linkedMapOf(
            "Basic" to "!@#$%^&*()_-+=~",
            "Brackets" to "()[]{}\\\\|",
            "Punctuation" to ".,;:'\"!?¿¡…•·",
            "Currency" to "€£₦¥₹₽₩₺₴₫₱₪₲₵₡₭₮₸₾₼៛฿₨₳₥₰₢₣₤₧₯₠₻₿",
            "Math" to "±×÷≤≥≠≈√∑∏∫∆πµΩαβγλθ∞∂",
            "Arrows & controls" to "←→↑↓↔↕↩↪↵⤴⤵↻↺⇐⇒⇧⇩",
            "Legal & marks" to "§¶†‡°′″№‰©®™℠℗℮"
        )
        val categoryBar=HorizontalScrollView(this).apply{isHorizontalScrollBarEnabled=false;overScrollMode=View.OVER_SCROLL_NEVER}
        val cats=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        categories.keys.forEach{name->cats.addView(pill(name){showSymbolCategory(root,categories,name)},LinearLayout.LayoutParams(dp(92),scaled(36)).apply{setMargins(dp(2),0,dp(2),0)})}
        categoryBar.addView(cats)
        root.addView(categoryBar,LinearLayout.LayoutParams(-1,scaled(40)))
        val scroll=ScrollView(this).apply{isFillViewport=false;clipToPadding=true;overScrollMode=View.OVER_SCROLL_IF_CONTENT_SCROLLS}
        val grid=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        scroll.addView(grid)
        root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        renderSymbolGrid(grid,categories["Basic"].orEmpty())
        addBottomRow(root,KeyboardPage.SYMBOLS)
    }
    private fun showSymbolCategory(root:LinearLayout,categories:Map<String,String>,name:String){
        val scroll=root.getChildAt(root.childCount-2) as? ScrollView ?: return
        val grid=scroll.getChildAt(0) as? LinearLayout ?: return
        renderSymbolGrid(grid,categories[name].orEmpty())
    }
    private fun renderSymbolGrid(grid:LinearLayout,symbols:String){
        grid.removeAllViews()
        symbols.chunked(10).forEach{line->
            val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER}
            line.forEach{ch->row.addView(keyButton(ch.toString()){commitTextToEditor(ch.toString());refreshAfterTextKey()},LinearLayout.LayoutParams(0,scaled(46),1f).apply{setMargins(dp(2),dp(2),dp(2),dp(2))})}
            repeat(10-line.length){row.addView(View(this),LinearLayout.LayoutParams(0,scaled(46),1f).apply{setMargins(dp(2),dp(2),dp(2),dp(2))})}
            grid.addView(row,LinearLayout.LayoutParams(-1,scaled(50)))
        }
    }
    private fun addBottomRow(root:LinearLayout,currentPage:KeyboardPage){val bottom=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER};when(currentPage){KeyboardPage.LETTERS->{bottom.addView(keyButton("?123"){page=KeyboardPage.NUMBERS;setInputView(render())});bottom.addView(shiftButton{toggleShift();setInputView(render())})};KeyboardPage.NUMBERS->{bottom.addView(keyButton("ABC"){page=KeyboardPage.LETTERS;setInputView(render())})};KeyboardPage.SYMBOLS->{bottom.addView(keyButton("ABC"){page=KeyboardPage.LETTERS;setInputView(render())});bottom.addView(keyButton("123"){page=KeyboardPage.NUMBERS;setInputView(render())})}};bottom.addView(keyButton(","){commitTextToEditor(",");refreshAfterTextKey()});val space=keyButton("Space",3.6f){commitSpace()};space.setOnTouchListener{_,event->when(event.actionMasked){MotionEvent.ACTION_DOWN->{spaceDownX=event.x;spaceMoved=false;true};MotionEvent.ACTION_MOVE->{if(!spaceMoved&&abs(event.x-spaceDownX)>dp(28)){spaceMoved=true;moveCursor(if(event.x>spaceDownX)1 else -1)};true};MotionEvent.ACTION_UP->{if(!spaceMoved)commitSpace();true};MotionEvent.ACTION_CANCEL->{spaceMoved=false;true};else->true}};bottom.addView(space);bottom.addView(keyButton("."){commitTextToEditor(".");refreshAfterTextKey()});val back=keyButton("⌫"){deleteBackward();refreshAfterTextKey()};installRepeat(back){deleteBackward();refreshAfterTextKey()};bottom.addView(back);bottom.addView(keyButton(actionLabel()){sendEditorAction()});root.addView(bottom,LinearLayout.LayoutParams(-1,scaled(52)))}
    private fun buildEmoji():View{val root=root();val nav=HorizontalScrollView(this).apply{isHorizontalScrollBarEnabled=false;overScrollMode=View.OVER_SCROLL_NEVER};val categories=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL};PhormiKeyboardEmoji.categories.keys.forEachIndexed{index,icon->categories.addView(pill(icon){emojiCategory=index;setInputView(render())},LinearLayout.LayoutParams(dp(48),scaled(36)).apply{setMargins(dp(2),0,dp(2),0)})};categories.addView(pill("ABC"){panel=Panel.KEYBOARD;page=KeyboardPage.LETTERS;setInputView(render())},LinearLayout.LayoutParams(dp(58),scaled(36)));nav.addView(categories);root.addView(nav,LinearLayout.LayoutParams(-1,scaled(40)));if(PhormiKeyboardTextEngine.allowsAiEmoji(editorInfo)&&PhormiKeyboardPreferences.aiEmoji(this)){root.addView(pill("✨ Create unique emoji"){panel=Panel.AI_EMOJI;setInputView(render())},LinearLayout.LayoutParams(-1,scaled(38)).apply{setMargins(dp(2),dp(3),dp(2),dp(3))})};val scroll=ScrollView(this).apply{isFillViewport=true;clipToPadding=true;overScrollMode=View.OVER_SCROLL_IF_CONTENT_SCROLLS};val grid=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};PhormiKeyboardEmoji.categories.values.elementAtOrNull(emojiCategory).orEmpty().chunked(8).forEach{group->val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER};group.forEach{emoji->row.addView(keyButton(emoji){commitTextToEditor(emoji)},LinearLayout.LayoutParams(0,scaled(46),1f))};repeat(8-group.size){row.addView(View(this),LinearLayout.LayoutParams(0,scaled(46),1f))};grid.addView(row,LinearLayout.LayoutParams(-1,scaled(48)))};scroll.addView(grid);root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f));return root}
    private fun buildClipboard():View{val root=root();addBackHeader(root,"← Keyboard"){panel=Panel.KEYBOARD;setInputView(render())};val scroll=ScrollView(this);val list=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};val items=PhormiKeyboardClipboardStore.list(this);if(items.isEmpty())list.addView(TextView(this).apply{text="Your copied text, screenshots and images will appear here.";setTextColor(themeText());textSize=14f;setPadding(dp(12),dp(18),dp(12),dp(18))});items.forEach{item->val label=item.text.take(60).ifBlank{"Copied item"};list.addView(pill(label){if(item.text.isNotBlank())commitTextToEditor(item.text)else item.uri?.let{commitContentToEditor(Uri.parse(it))}},LinearLayout.LayoutParams(-1,scaled(44)))};scroll.addView(list);root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f));return root}
    private fun buildAiEmoji():View{
        val root=root(); addBackHeader(root,"← Emoji"){panel=Panel.EMOJI;setInputView(render())}
        val scroll=ScrollView(this).apply{isFillViewport=true;overScrollMode=View.OVER_SCROLL_IF_CONTENT_SCROLLS}
        val list=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER_HORIZONTAL;setPadding(dp(8),dp(8),dp(8),dp(8))}
        list.addView(TextView(this).apply{text="Create a unique emoji";textSize=18f;typeface=Typeface.DEFAULT_BOLD;setTextColor(themeText());gravity=Gravity.CENTER})
        list.addView(TextView(this).apply{text="Generate one fused emoji-style reaction from your text. The result appears here so it can be inserted directly.";textSize=13f;setTextColor(themeText());gravity=Gravity.CENTER;setPadding(dp(8),dp(6),dp(8),dp(10))})
        val input=android.widget.EditText(this).apply{hint="Describe the feeling or idea";setTextColor(themeText());setHintTextColor(Color.GRAY);minLines=2;setSingleLine(false)}
        list.addView(input,LinearLayout.LayoutParams(-1,dp(72)))
        val status=TextView(this).apply{text="Ready";textSize=12f;setTextColor(themeText());gravity=Gravity.CENTER}
        list.addView(status,LinearLayout.LayoutParams(-1,dp(28)))
        val image=android.widget.ImageView(this).apply{scaleType=android.widget.ImageView.ScaleType.FIT_CENTER;contentDescription="Generated AI emoji";visibility=View.GONE}
        list.addView(image,LinearLayout.LayoutParams(-1,dp(150)))
        val controller=PhormiKeyboardAiEmojiController(this){files,loading->
            repeatHandler.post{
                status.text=if(loading)"Creating emoji…"else if(files.isEmpty())"Generation failed — try again or add the Pollinations key in Tools."else"Emoji ready — tap it to insert"
                val file=files.firstOrNull()
                if(file!=null){
                    image.setImageBitmap(BitmapFactory.decodeFile(file.absolutePath))
                    image.visibility=View.VISIBLE
                    image.setOnClickListener{PhormiKeyboardServiceV2.commitPickedContent(this@PhormiKeyboardServiceV2, PhormiKeyboardStickerStore.contentUri(this@PhormiKeyboardServiceV2,file))}
                }
            }
        }
        fun generate(text:String){
            val clean=text.trim()
            if(clean.isBlank()){android.widget.Toast.makeText(this@PhormiKeyboardServiceV2,"Describe the emoji first",android.widget.Toast.LENGTH_SHORT).show();return}
            controller.generate(clean)
        }
        list.addView(pill("Use what I'm typing"){generate(PhormiKeyboardTextEngine.contextBeforeCursor(currentInputConnection))},LinearLayout.LayoutParams(-1,scaled(46)))
        list.addView(pill("Generate from description"){generate(input.text.toString())},LinearLayout.LayoutParams(-1,scaled(46)))
        scroll.addView(list);root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f));return root
    }
    private fun buildMedia():View{val root=root();addBackHeader(root,"← Keyboard"){panel=Panel.KEYBOARD;setInputView(render())};val scroll=ScrollView(this);val list=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(8),dp(8),dp(8),dp(8))};list.addView(pill("Import GIF / Image"){launchMedia("gif")},LinearLayout.LayoutParams(-1,scaled(46)));list.addView(pill("Import Sticker"){launchMedia("sticker")},LinearLayout.LayoutParams(-1,scaled(46)));list.addView(pill("Create AI Emoji"){panel=Panel.AI_EMOJI;setInputView(render())},LinearLayout.LayoutParams(-1,scaled(46)));list.addView(TextView(this).apply{text="Images/GIFs are inserted only when the focused field accepts the requested MIME type.";setTextColor(themeText());textSize=13f;setPadding(dp(8),dp(12),dp(8),dp(12))});scroll.addView(list);root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f));return root}
    private fun buildTools():View{val root=root();addBackHeader(root,"← Keyboard"){panel=Panel.KEYBOARD;setInputView(render())};val scroll=ScrollView(this);val list=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(8),dp(8),dp(8),dp(8))};list.addView(pill("Settings"){panel=Panel.SETTINGS;setInputView(render())},LinearLayout.LayoutParams(-1,scaled(46)));list.addView(pill("Voice typing"){launchVoice()},LinearLayout.LayoutParams(-1,scaled(46)));list.addView(pill("Select all"){currentInputConnection?.performContextMenuAction(android.R.id.selectAll)},LinearLayout.LayoutParams(-1,scaled(46)));list.addView(pill("Copy"){currentInputConnection?.performContextMenuAction(android.R.id.copy)},LinearLayout.LayoutParams(-1,scaled(46)));list.addView(pill("Paste"){currentInputConnection?.performContextMenuAction(android.R.id.paste)},LinearLayout.LayoutParams(-1,scaled(46)));list.addView(pill("Share text"){shareSelectedText()},LinearLayout.LayoutParams(-1,scaled(46)));scroll.addView(list);root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f));return root}
    private fun buildSettings():View{val root=root();addBackHeader(root,"← Tools"){panel=Panel.TOOLS;setInputView(render())};val scroll=ScrollView(this).apply{overScrollMode=View.OVER_SCROLL_IF_CONTENT_SCROLLS};val list=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(10),dp(6),dp(10),dp(8))};list.addView(TextView(this).apply{text="Keyboard size";textSize=15f;setTextColor(themeText());setPadding(0,dp(4),0,dp(2))});list.addView(TextView(this).apply{text="Drag ↕↔ at the top edge of the keyboard to resize height and width freely. Presets below control height.";textSize=12f;setTextColor(themeText().let{if(theme()==3)Color.DKGRAY else Color.rgb(148,163,184)});setPadding(0,0,0,dp(6))});val labels=listOf("85%","92%","97%","100%","108%","117%","127%");val current=PhormiKeyboardPreferences.height(this);labels.forEachIndexed{index,sizeLabel->list.addView(pill(if(index==current)"✓ $sizeLabel"else sizeLabel){PhormiKeyboardPreferences.setHeight(this@PhormiKeyboardServiceV2,index);setInputView(render())},LinearLayout.LayoutParams(-1,scaled(38)).apply{setMargins(0,dp(2),0,dp(2))})};list.addView(pill("Reset keyboard size"){PhormiKeyboardPreferences.resetSize(this@PhormiKeyboardServiceV2);setInputView(render())},LinearLayout.LayoutParams(-1,scaled(40)).apply{setMargins(0,dp(4),0,dp(6))});fun toggle(title:String,enabled:Boolean,action:()->Unit){list.addView(pill(if(enabled)"✓ $title"else title,action),LinearLayout.LayoutParams(-1,scaled(40)).apply{setMargins(0,dp(3),0,dp(3))})};toggle("Suggestions",PhormiKeyboardPreferences.suggestions(this)){PhormiKeyboardPreferences.set(this@PhormiKeyboardServiceV2,PhormiKeyboardPreferences.KEY_SUGGESTIONS,!PhormiKeyboardPreferences.suggestions(this));setInputView(render())};toggle("Autocorrect",PhormiKeyboardPreferences.autocorrect(this)){PhormiKeyboardPreferences.set(this@PhormiKeyboardServiceV2,PhormiKeyboardPreferences.KEY_AUTOCORRECT,!PhormiKeyboardPreferences.autocorrect(this));setInputView(render())};toggle("Auto-capitalization",PhormiKeyboardPreferences.autoCaps(this)){PhormiKeyboardPreferences.set(this@PhormiKeyboardServiceV2,PhormiKeyboardPreferences.KEY_AUTO_CAPS,!PhormiKeyboardPreferences.autoCaps(this));setInputView(render())};toggle("Haptic feedback",PhormiKeyboardPreferences.haptic(this)){PhormiKeyboardPreferences.set(this@PhormiKeyboardServiceV2,PhormiKeyboardPreferences.KEY_HAPTIC,!PhormiKeyboardPreferences.haptic(this));setInputView(render())};toggle("Key sounds",PhormiKeyboardPreferences.sound(this)){PhormiKeyboardPreferences.set(this@PhormiKeyboardServiceV2,PhormiKeyboardPreferences.KEY_SOUND,!PhormiKeyboardPreferences.sound(this));setInputView(render())};list.addView(pill(if(PhormiKeyboardPreferences.pollinationsKey(this).isBlank())"Set Pollinations AI key"else"Pollinations AI key ✓"){startActivity(Intent(this,PhormiAiEmojiActivity::class.java).apply{addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);putExtra(PhormiAiEmojiActivity.EXTRA_CONFIG_ONLY,true)})},LinearLayout.LayoutParams(-1,scaled(40)).apply{setMargins(0,dp(3),0,dp(3))});scroll.addView(list);root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f));return root}
    private fun addBackHeader(root:LinearLayout,label:String,action:()->Unit){root.addView(pill(label,action),LinearLayout.LayoutParams(-1,scaled(40)))}
    private fun render():View=when(panel){Panel.EMOJI->buildEmoji();Panel.CLIPBOARD->buildClipboard();Panel.AI_EMOJI->buildAiEmoji();Panel.TOOLS->buildTools();Panel.MEDIA->buildMedia();Panel.SETTINGS->buildSettings();Panel.KEYBOARD->buildKeyboard()}
    private fun refreshAfterTextKey(){if(panel==Panel.KEYBOARD && page==KeyboardPage.LETTERS) refreshPredictionStrip()}
    private fun launchMedia(mode:String){startActivity(Intent(this,PhormiKeyboardMediaActivity::class.java).apply{addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);putExtra(PhormiKeyboardMediaActivity.EXTRA_MODE,mode)})}
    private fun launchVoice(){startActivity(Intent(this,PhormiKeyboardVoiceActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}
    private fun shareSelectedText(){val text=currentInputConnection?.getSelectedText(0)?.toString().orEmpty();if(text.isBlank()){android.widget.Toast.makeText(this,"Select text first",android.widget.Toast.LENGTH_SHORT).show();return};startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply{type="text/plain";putExtra(Intent.EXTRA_TEXT,text)},"Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}
    private fun applyPendingInput(){val prefs=getSharedPreferences(PREFS,MODE_PRIVATE);prefs.getString(KEY_PENDING_TEXT,null)?.takeIf{it.isNotBlank()}?.let{commitTextToEditor(it)};prefs.edit().remove(KEY_PENDING_TEXT).apply();prefs.getString(KEY_PENDING_URI,null)?.let{runCatching{commitContentToEditor(Uri.parse(it))}};prefs.edit().remove(KEY_PENDING_URI).apply()}
    private fun commitTextToEditor(text:String){currentInputConnection?.commitText(text,1)}
    private fun commitSpace(){val ic=currentInputConnection?:return;val current=PhormiKeyboardTextEngine.currentWord(ic);if(PhormiKeyboardPreferences.autocorrect(this)&&current.isNotBlank()){PhormiKeyboardTextEngine.correctionFor(this,current)?.let{correction->ic.deleteSurroundingText(current.length,0);ic.commitText(correction,1);PhormiKeyboardTextEngine.learnCorrection(this,current,correction,editorInfo)}};val previous=PhormiKeyboardTextEngine.previousWord(ic,PhormiKeyboardTextEngine.localeFor(editorInfo));val corrected=PhormiKeyboardTextEngine.currentWord(ic);if(corrected.isNotBlank())PhormiKeyboardTextEngine.learn(this,corrected,editorInfo);ic.commitText(" ",1);if(previous.isNotBlank()&&corrected.isNotBlank())PhormiKeyboardTextEngine.learnPair(this,previous,corrected,editorInfo);if(PhormiKeyboardPreferences.autoCaps(this))autoShift=PhormiKeyboardTextEngine.autoCapitalize(ic,editorInfo);refreshAfterTextKey()}
    private fun deleteBackward(){val ic=currentInputConnection?:return;val selected=ic.getSelectedText(0)?.toString();if(!selected.isNullOrEmpty())ic.commitText("",1)else ic.deleteSurroundingText(1,0)}
    private fun moveCursor(delta:Int){val ic=currentInputConnection?:return;val code=if(delta<0)KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT;ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN,code));ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP,code));refreshAfterTextKey()}
    private fun toggleShift(){if(capsLock){capsLock=false;shift=false;autoShift=false}else if(shift||autoShift){capsLock=true;shift=false;autoShift=false}else shift=true}
    private fun actionLabel():String=if(editorInfo?.let{it.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0}==true)"↵" else when(editorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION)){EditorInfo.IME_ACTION_GO->"Go";EditorInfo.IME_ACTION_SEARCH->"Search";EditorInfo.IME_ACTION_SEND->"Send";EditorInfo.IME_ACTION_NEXT->"Next";EditorInfo.IME_ACTION_DONE->"Done";else->"Enter"}
    private fun sendEditorAction(){val ic=currentInputConnection?:return;if(editorInfo?.let{it.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0}==true){ic.commitText("\n",1);refreshAfterTextKey();return};val action=editorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION)?:EditorInfo.IME_ACTION_NONE;if(action!=EditorInfo.IME_ACTION_NONE)ic.performEditorAction(action)else{ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_ENTER));ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_ENTER))}}
    private fun installRepeat(view:View,action:()->Unit){view.setOnTouchListener{_,event->when(event.actionMasked){MotionEvent.ACTION_DOWN->{action();repeatRunnable=object:Runnable{override fun run(){action();repeatHandler.postDelayed(this,55)}};repeatHandler.postDelayed(repeatRunnable!!,320);true};MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->{stopRepeat();true};else->true}}}
    private fun stopRepeat(){repeatRunnable?.let{repeatHandler.removeCallbacks(it)};repeatRunnable=null}
    private fun commitContentToEditor(uri:Uri):Boolean{if(Build.VERSION.SDK_INT<25)return false;val ic=currentInputConnection?:return false;val info=editorInfo?:return false;val mimeTypes=info.contentMimeTypes?:emptyArray();if(mimeTypes.isEmpty()){android.widget.Toast.makeText(this,"This field does not accept images",android.widget.Toast.LENGTH_SHORT).show();return false};val wanted=contentResolver.getType(uri).orEmpty();val accepted=wanted.isNotBlank()&&mimeTypes.any{it==wanted||(it.endsWith("/*")&&wanted.startsWith(it.removeSuffix("*")))};if(!accepted){android.widget.Toast.makeText(this,"This field does not accept that media type",android.widget.Toast.LENGTH_SHORT).show();return false};return runCatching{val description=android.content.ClipDescription("Phormi media",arrayOf(wanted));val contentInfo=InputContentInfo(uri,description,null);ic.commitContent(contentInfo,InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION,null)}.getOrDefault(false)}
}
