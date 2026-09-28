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
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
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
import java.util.Locale
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
        fun startIntegratedVoiceRecognitionFromContext(context: Context, localeTag: String) { instance?.startIntegratedVoiceRecognition(localeTag) }
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
    private var speechRecognizer: SpeechRecognizer? = null
    private var predictionRefreshRunnable: Runnable? = null
    private var lastPredictionWord = ""
    private var lastPredictionPrevious = ""

    override fun onCreate() {
        super.onCreate(); instance = this
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboardListener = ClipboardManager.OnPrimaryClipChangedListener { PhormiKeyboardClipboardStore.capturePrimaryClipboard(this) }
        clipboardListener?.let { cm?.addPrimaryClipChangedListener(it) }
    }
    override fun onDestroy() {
        speechRecognizer?.cancel(); speechRecognizer?.destroy(); speechRecognizer = null
        stopRepeat(); aiEmojiRunnable?.let { repeatHandler.removeCallbacks(it) }; aiEmojiRunnable = null
        predictionRefreshRunnable?.let { repeatHandler.removeCallbacks(it) }; predictionRefreshRunnable = null
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboardListener?.let { cm?.removePrimaryClipChangedListener(it) }; clipboardListener = null
        if (instance === this) instance = null; super.onDestroy()
    }
    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting); editorInfo = attribute; page = KeyboardPage.LETTERS; capsLock = false; shift = false
        autoShift = PhormiKeyboardPreferences.autoCaps(this) && PhormiKeyboardTextEngine.autoCapitalize(currentInputConnection, attribute); panel = Panel.KEYBOARD
    }
    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) { super.onStartInputView(info, restarting); editorInfo = info ?: editorInfo; panel = Panel.KEYBOARD; setInputView(render()); applyKeyboardWindowSize(); applyPendingInput() }
    override fun onUpdateSelection(oldSelStart:Int, oldSelEnd:Int, newSelStart:Int, newSelEnd:Int, candidatesStart:Int, candidatesEnd:Int) {
        super.onUpdateSelection(oldSelStart,oldSelEnd,newSelStart,newSelEnd,candidatesStart,candidatesEnd)
        if(panel==Panel.KEYBOARD && page==KeyboardPage.LETTERS){
            schedulePredictionRefresh()
            if(PhormiKeyboardPreferences.autoCaps(this)){
                autoShift=PhormiKeyboardTextEngine.autoCapitalize(currentInputConnection,editorInfo)
            }
        }
    }

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
        val screenW = resources.displayMetrics.widthPixels.coerceAtLeast(dp(1))
        val screenH = resources.displayMetrics.heightPixels.coerceAtLeast(dp(1))
        val baseWindowHeight = (baseHeight() * density() * PhormiKeyboardPreferences.heightScale(this)).roundToInt()
        val height = (baseWindowHeight + if (PhormiKeyboardPreferences.floating(this)) scaled(30) else 0)
            .coerceIn(dp(210), (screenH * 0.82f).roundToInt().coerceAtLeast(dp(240)))
        val available = getMaxWidth().coerceAtLeast(dp(240))
        val width = (available * PhormiKeyboardPreferences.widthScale(this)).roundToInt()
            .coerceIn(dp(220), available)

        val floating = PhormiKeyboardPreferences.floating(this)
        val attrs = window.attributes
        if (floating) {
            // Floating mode is a real independent window: its top-left position is
            // controlled by the drag offsets and is not re-anchored to the IME bottom.
            attrs.gravity = Gravity.TOP or Gravity.START
            attrs.x = ((PhormiKeyboardPreferences.offsetX(this) + 0.9f) / 1.8f * (screenW - width))
                .roundToInt().coerceIn(0, (screenW - width).coerceAtLeast(0))
            attrs.y = ((PhormiKeyboardPreferences.offsetY(this) + 0.9f) / 1.8f * (screenH - height))
                .roundToInt().coerceIn(0, (screenH - height).coerceAtLeast(0))
        } else {
            attrs.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            attrs.x = 0
            attrs.y = 0
        }
        window.attributes = attrs
        window.setLayout(width, height)
    }

    private fun density(): Float = resources.displayMetrics.density
    private fun dp(value: Int): Int = (value * density()).roundToInt().coerceAtLeast(1)
    private fun baseHeight(): Int = 312
    private fun scale(): Float = PhormiKeyboardPreferences.heightScale(this)
    private fun scaled(value: Int): Int = dp((value * scale()).roundToInt())
    private fun theme(): Int = PhormiKeyboardPreferences.theme(this)
    private fun themeBackground(): Int = when (theme()) { 1 -> Color.rgb(20,24,29); 2 -> Color.rgb(7,24,42); 3 -> Color.rgb(242,244,247); 4 -> Color.rgb(38,45,58); 5 -> Color.rgb(17,55,42); 6 -> Color.rgb(61,28,48); 7 -> Color.rgb(69,54,38); else -> Color.rgb(13,18,30) }
    private fun themeKey(): Int = when (theme()) { 1 -> Color.rgb(48,53,61); 2 -> Color.rgb(18,52,79); 3 -> Color.WHITE; 4 -> Color.rgb(55,65,81); 5 -> Color.rgb(26,78,58); 6 -> Color.rgb(86,38,64); 7 -> Color.rgb(92,70,48); else -> Color.rgb(39,48,64) }
    private fun themePill(): Int = when (theme()) { 1 -> Color.rgb(37,42,49); 2 -> Color.rgb(15,45,69); 3 -> Color.rgb(224,228,234); 4 -> Color.rgb(47,58,73); 5 -> Color.rgb(20,63,48); 6 -> Color.rgb(70,31,53); 7 -> Color.rgb(78,59,41); else -> Color.rgb(31,41,55) }
    private fun themeText(): Int = if (theme() == 3) Color.rgb(20,27,36) else Color.WHITE
    private fun accent(): Int = when (theme()) { 2 -> Color.rgb(65,135,220); 3 -> Color.rgb(98,72,220); 5 -> Color.rgb(52,211,153); 6 -> Color.rgb(244,114,182); 7 -> Color.rgb(245,158,11); else -> Color.rgb(112,75,255) }
    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.toFloat()
        setStroke(dp(1), if (theme() == 3) Color.rgb(205,210,218) else Color.rgb(56,68,88))
    }
    private fun root(): LinearLayout = LinearLayout(this).apply {
        orientation=LinearLayout.VERTICAL
        setPadding(dp(7),dp(1),dp(7),dp(1))
        setBackgroundColor(themeBackground())
        val width=(getMaxWidth().coerceAtLeast(dp(240))*PhormiKeyboardPreferences.widthScale(this@PhormiKeyboardServiceV2)).roundToInt().coerceIn(dp(240),getMaxWidth().coerceAtLeast(dp(240)))
        val height=scaled(baseHeight()) + if (PhormiKeyboardPreferences.floating(this@PhormiKeyboardServiceV2)) scaled(30) else 0
        layoutParams=LinearLayout.LayoutParams(-1,height).apply{gravity=Gravity.CENTER_HORIZONTAL}
        minimumHeight=height
        applyWallpaper(this)
    }
    private fun applyWallpaper(root: View) {
        val value = PhormiKeyboardPreferences.wallpaperUri(this) ?: return
        Thread {
            val bitmap = runCatching { contentResolver.openInputStream(Uri.parse(value))?.use { BitmapFactory.decodeStream(it) } }.getOrNull() ?: return@Thread
            Handler(Looper.getMainLooper()).post {
                if (!isDestroyed) root.background = BitmapDrawable(resources, bitmap).apply { alpha = 72 }
            }
        }.start()
    }
    private fun feedback(view: View) {
        if (PhormiKeyboardPreferences.haptic(this)) {
            view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP, android.view.HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING)
        }
        if (PhormiKeyboardPreferences.sound(this)) {
            (getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager)?.playSoundEffect(android.media.AudioManager.FX_KEY_CLICK, 1.0f)
        }
    }
    private fun pill(label:String,action:()->Unit):Button=Button(this).apply{text=label;textSize=13f;setTextColor(themeText());typeface=Typeface.DEFAULT_BOLD;minWidth=0;minHeight=0;isAllCaps=false;stateListAnimator=null;setPadding(dp(7),0,dp(7),0);background=rounded(themePill(),dp(13));contentDescription=label;setOnClickListener{feedback(this);action()}}
    private fun keyButton(label:String,weight:Float=1f,action:()->Unit):Button=Button(this).apply{
        val special=label=="Space"||label=="⌫"||label in setOf("?123","ABC","Symbols","Enter","Go","Search","Send","Next","Done")
        text=label
        textSize=when{
            label.codePointCount(0,label.length)==1->20f
            label=="Space"->13f
            label.length>5->10f
            label.length>3->11f
            else->12f
        }
        setTextColor(themeText())
        typeface=if(special)Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        gravity=Gravity.CENTER
        includeFontPadding=false
        minWidth=0
        minHeight=0
        isAllCaps=false
        stateListAnimator=null
        elevation=dp(if(special)1 else 2).toFloat()
        setPadding(dp(1),0,dp(1),0)
        background=rounded(if(special)themePill() else themeKey(),dp(if(special)10 else 9))
        contentDescription=label
        setOnClickListener{feedback(this);action()}
        layoutParams=LinearLayout.LayoutParams(0,scaled(42),weight).apply{setMargins(dp(2),dp(1),dp(2),dp(1))}
    }
    private fun shiftButton(action:()->Unit):Button{val state=when{capsLock->2;shift||autoShift->1;else->0};return keyButton(if(state==0)"⇧"else"⇧A",action=action).apply{background=rounded(when(state){2->Color.rgb(220,38,38);1->accent();else->themeKey()},dp(9));setTextColor(Color.WHITE);contentDescription=when(state){2->"Caps lock";1->"One-letter capitalization";else->"Shift"}}}
    private fun addResizeControls(parent: LinearLayout) {
        fun control(label: String, description: String, action: (MotionEvent, TextView) -> Boolean): TextView =
            TextView(this).apply {
                text = label
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(if (theme() == 3) Color.DKGRAY else Color.rgb(203, 213, 225))
                background = rounded(themePill(), dp(10))
                contentDescription = description
                isClickable = true
                isFocusable = true
                setOnTouchListener { view, event -> action(event, view as TextView) }
            }

        val move = control(
            if (PhormiKeyboardPreferences.floating(this)) "⠿ Move" else "⠿ Float",
            if (PhormiKeyboardPreferences.floating(this)) "Move floating Phormi Keyboard" else "Make Phormi Keyboard floating and move it"
        ) { event, _ ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    resizeStartX = event.rawX
                    resizeStartY = event.rawY
                    resizePreviewHeight = PhormiKeyboardPreferences.offsetX(this)
                    resizePreviewWidth = PhormiKeyboardPreferences.offsetY(this)
                    if (!PhormiKeyboardPreferences.floating(this)) {
                        PhormiKeyboardPreferences.setFloating(this, true)
                        // Floating mode has an independent width; entering it should not
                        // mutate the saved width unless it was still the docked full width.
                        if (PhormiKeyboardPreferences.widthScale(this) >= 0.99f) {
                            PhormiKeyboardPreferences.setWidthScale(this, 0.78f)
                        }
                    }
                    applyKeyboardWindowSize()
                    true
                }
                MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val screenW = resources.displayMetrics.widthPixels.coerceAtLeast(1)
                    val screenH = resources.displayMetrics.heightPixels.coerceAtLeast(1)
                    if (PhormiKeyboardPreferences.floating(this) && event.actionMasked == MotionEvent.ACTION_MOVE) {
                        PhormiKeyboardPreferences.setOffsetX(
                            this,
                            resizePreviewHeight + (event.rawX - resizeStartX) / screenW.toFloat() * 1.8f
                        )
                        PhormiKeyboardPreferences.setOffsetY(
                            this,
                            resizePreviewWidth + (event.rawY - resizeStartY) / screenH.toFloat() * 1.8f
                        )
                    }
                    applyKeyboardWindowSize()
                    true
                }
                else -> true
            }
        }

        val size = control("↘ Size", "Resize Phormi Keyboard") { event, _ ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    resizeStartX = event.rawX
                    resizeStartY = event.rawY
                    resizeStartHeight = PhormiKeyboardPreferences.heightScale(this)
                    resizeStartWidth = PhormiKeyboardPreferences.widthScale(this)
                    true
                }
                MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (event.actionMasked == MotionEvent.ACTION_MOVE) {
                        val dx = (event.rawX - resizeStartX) /
                            resources.displayMetrics.widthPixels.coerceAtLeast(1).toFloat() * 1.8f
                        val dy = (resizeStartY - event.rawY) /
                            resources.displayMetrics.heightPixels.coerceAtLeast(1).toFloat() * 1.8f
                        val delta = maxOf(dx, dy).coerceIn(-0.45f, 0.45f)
                        PhormiKeyboardPreferences.setWidthScale(this, (resizeStartWidth + delta).coerceIn(0.55f, 1.0f))
                        PhormiKeyboardPreferences.setHeightScale(this, (resizeStartHeight + delta).coerceIn(0.60f, 1.40f))
                    }
                    applyKeyboardWindowSize()
                    true
                }
                else -> true
            }
        }

        parent.addView(move, LinearLayout.LayoutParams(dp(70), scaled(34)).apply {
            setMargins(dp(2), 0, dp(2), 0)
        })
        parent.addView(size, LinearLayout.LayoutParams(dp(66), scaled(34)).apply {
            setMargins(dp(2), 0, dp(2), 0)
        })
    }
    private fun toolbar(root: LinearLayout) {
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fun add(label: String, width: Int = 58, action: () -> Unit) {
            row.addView(
                pill(label, action),
                LinearLayout.LayoutParams(dp(width), scaled(34)).apply {
                    setMargins(dp(2), 0, dp(2), 0)
                }
            )
        }
        add("😀") { panel = Panel.EMOJI; setInputView(render()) }
        add("📋") {
            panel = Panel.CLIPBOARD
            PhormiKeyboardClipboardStore.capturePrimaryClipboard(this)
            setInputView(render())
        }
        add("▦ Cabinet", 76) { panel = Panel.TOOLS; setInputView(render()) }
        if (Build.VERSION.SDK_INT >= 28 && shouldOfferSwitchingToNextInputMethod()) {
            add("🌐", 52) { runCatching { switchToNextInputMethod(false) } }
        }
        scroll.addView(row)
        root.addView(scroll, LinearLayout.LayoutParams(-1, scaled(36)))
    }
    private fun predictionStrip(root:LinearLayout){
        val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
        predictionRow=row
        root.addView(row,LinearLayout.LayoutParams(-1,scaled(30)))
        refreshPredictionStrip()
    }
    private fun schedulePredictionRefresh() {
        predictionRefreshRunnable?.let { repeatHandler.removeCallbacks(it) }
        val task = Runnable { refreshPredictionStrip() }
        predictionRefreshRunnable = task
        repeatHandler.postDelayed(task, 70L)
    }

    private fun refreshPredictionStrip(){
        val row=predictionRow ?: return
        val ic=currentInputConnection
        val word=PhormiKeyboardTextEngine.currentWord(ic)
        val previous=PhormiKeyboardTextEngine.previousWord(ic,PhormiKeyboardTextEngine.localeFor(editorInfo))
        if (word == lastPredictionWord && previous == lastPredictionPrevious && row.childCount > 0) return
        lastPredictionWord = word
        lastPredictionPrevious = previous
        row.removeAllViews()
        if(!PhormiKeyboardPreferences.suggestions(this) || !PhormiKeyboardTextEngine.shouldUsePredictions(editorInfo)) return
        val suggestions=if(word.isNotBlank()) PhormiKeyboardTextEngine.suggestions(this,word,PhormiKeyboardTextEngine.localeFor(editorInfo)) else PhormiKeyboardTextEngine.nextWordSuggestions(this,previous,PhormiKeyboardTextEngine.localeFor(editorInfo))
        if(PhormiKeyboardTextEngine.allowsAiEmoji(editorInfo)&&PhormiKeyboardPreferences.aiEmoji(this)){row.addView(pill("✨ AI expression"){insertAiEmojiExpression()},LinearLayout.LayoutParams(dp(94),scaled(32)).apply{setMargins(dp(2),0,dp(2),0)})};suggestions.take(4).forEach{value->
            val button=pill(value,{})
            button.setOnClickListener {
                feedback(button)
                val ic = currentInputConnection
                if (ic != null) {
                    val liveWord = PhormiKeyboardTextEngine.currentWord(ic)
                    if (liveWord.isNotBlank()) {
                        ic.beginBatchEdit()
                        try {
                            ic.finishComposingText()
                            ic.deleteSurroundingText(liveWord.length, 0)
                            ic.commitText(value, 1)
                            commitSpace()
                        } finally {
                            ic.endBatchEdit()
                        }
                    } else {
                        val before = ic.getTextBeforeCursor(1, 0)?.toString().orEmpty()
                        if (before.isNotEmpty() && !before.last().isWhitespace()) ic.commitText(" ", 1)
                        ic.commitText(value, 1)
                        commitSpace()
                    }
                }
                refreshPredictionStrip()
            }
            button.setOnLongClickListener{
                val removed=PhormiKeyboardTextEngine.forgetPersonalizedSuggestion(this,value,previous,PhormiKeyboardTextEngine.localeFor(editorInfo))
                if(removed) {
                    android.widget.Toast.makeText(this,"Removed \"$value\" from learned suggestions",android.widget.Toast.LENGTH_SHORT).show()
                    schedulePredictionRefresh()
                } else {
                    android.widget.Toast.makeText(this,"Built-in suggestion — nothing learned to remove",android.widget.Toast.LENGTH_SHORT).show()
                }
                true
            }
            button.contentDescription="$value. Tap to insert. Long press to remove it from learned suggestions."
            row.addView(button,LinearLayout.LayoutParams(0,scaled(32),1f).apply{setMargins(dp(2),0,dp(2),0)})
        }
        row.visibility=if(row.childCount>0) View.VISIBLE else View.GONE
    }
    private fun buildKeyboard():View{val root=root();if(page==KeyboardPage.LETTERS)predictionStrip(root);toolbar(root);val type=editorInfo?.inputType?:InputType.TYPE_CLASS_TEXT;val clazz=type and InputType.TYPE_MASK_CLASS;val variation=type and InputType.TYPE_MASK_VARIATION;val number=clazz==InputType.TYPE_CLASS_NUMBER;val phone=clazz==InputType.TYPE_CLASS_PHONE;val dateTime=clazz==InputType.TYPE_CLASS_DATETIME;val email=variation==InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS||variation==InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS;val uri=variation==InputType.TYPE_TEXT_VARIATION_URI;if((number||phone||dateTime)&&page==KeyboardPage.LETTERS)page=KeyboardPage.NUMBERS;when{phone->responsiveRows(root,listOf("1234567890","*#+","()-"));dateTime->responsiveRows(root,listOf("1234567890","4567890",":/-"));number&&page==KeyboardPage.SYMBOLS->buildSymbolPage(root);number->buildNumberPage(root);page==KeyboardPage.LETTERS->buildLetterPage(root,email,uri);page==KeyboardPage.NUMBERS->buildNumberPage(root);else->buildSymbolPage(root)};return root}
    private fun buildLetterPage(root:LinearLayout,email:Boolean,uri:Boolean){
        val body=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        responsiveRows(body,listOf("qwertyuiop","asdfghjkl","zxcvbnm"))
        if(email||uri){
            val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER}
            val tokens=if(email)listOf("@",".com",".net",".org")else listOf("/",".com","https://",".org")
            tokens.forEach{token->row.addView(keyButton(token){commitTextToEditor(token);refreshAfterTextKey()},LinearLayout.LayoutParams(0,scaled(38),1f).apply{setMargins(dp(1),dp(1),dp(1),dp(1))})}
            body.addView(row,LinearLayout.LayoutParams(-1,scaled(40)))
        }
        val scroll=ScrollView(this).apply{
            isFillViewport=false
            clipToPadding=false
            overScrollMode=View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(body)
        }
        // Keep the action row outside the scroll area. On short phone IME windows the
        // old layout let the third-party/URI row push Space, Backspace and Enter below
        // the visible viewport.
        root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        addBottomRow(root,KeyboardPage.LETTERS)
    }
    private fun buildNumberPage(root:LinearLayout){
        val body=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        responsiveRows(body,listOf("123","456","789","0.,"))
        body.addView(keyButton("Symbols",action={page=KeyboardPage.SYMBOLS;setInputView(render())}).apply{layoutParams=LinearLayout.LayoutParams(-1,scaled(44)).apply{setMargins(dp(2),dp(2),dp(2),dp(2))}})
        val scroll=ScrollView(this).apply{isFillViewport=false;clipToPadding=false;addView(body)}
        root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
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
        root.addView(categoryBar,LinearLayout.LayoutParams(-1,scaled(36)))
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
            line.forEach{ch->row.addView(keyButton(ch.toString()){commitTextToEditor(ch.toString());refreshAfterTextKey()},LinearLayout.LayoutParams(0,scaled(42),1f).apply{setMargins(dp(2),dp(2),dp(2),dp(2))})}
            repeat(10-line.length){row.addView(View(this),LinearLayout.LayoutParams(0,scaled(42),1f).apply{setMargins(dp(2),dp(2),dp(2),dp(2))})}
            grid.addView(row,LinearLayout.LayoutParams(-1,scaled(44)))
        }
    }
    private fun responsiveRows(root:LinearLayout,rows:List<String>){
        rows.forEach{chars->
            val row=LinearLayout(this).apply{
                orientation=LinearLayout.HORIZONTAL
                gravity=Gravity.CENTER
                weightSum=chars.length.toFloat()
                clipChildren=true
                clipToPadding=true
                minimumWidth=0
            }
            chars.forEach{ch->
                row.addView(keyButton(ch.toString()){
                    val out=if(shift||capsLock||autoShift)ch.uppercaseChar().toString()else ch.toString()
                    commitTextToEditor(out);if(shift&&!capsLock)shift=false;autoShift=false;refreshAfterTextKey()
                }.apply{
                    // Gboard-style compact letter sizing keeps every key inside the safe
                    // IME viewport even on narrow screens and reduced-width settings.
                    textSize=18f
                    minimumWidth=0
                },LinearLayout.LayoutParams(0,scaled(44),1f).apply{setMargins(dp(1),dp(2),dp(1),dp(2));width=0})
            }
            root.addView(row,LinearLayout.LayoutParams(-1,scaled(44)).apply{width=-1})
        }
    }
    private fun shiftKey(weight: Float): Button {
        val state = when {
            capsLock -> 2
            shift || autoShift -> 1
            else -> 0
        }
        return keyButton("⇧", weight) {
            toggleShift()
            setInputView(render())
        }.apply {
            // Keep one universal shift glyph; state is communicated by color.
            // Purple = one-letter capitalization, red = caps lock.
            background = rounded(
                when (state) {
                    2 -> Color.rgb(220, 38, 38)
                    1 -> accent()
                    else -> themeKey()
                },
                dp(9)
            )
            setTextColor(Color.WHITE)
            textSize = 21f
            contentDescription = when (state) {
                2 -> "Caps lock — all letters uppercase"
                1 -> "Shift — next letter uppercase"
                else -> "Shift — next letter uppercase"
            }
        }
    }

    private fun addBottomRow(root:LinearLayout,currentPage:KeyboardPage){
        val bottom=LinearLayout(this).apply{
            orientation=LinearLayout.HORIZONTAL
            gravity=Gravity.CENTER
            weightSum=8f
            clipChildren=true
            clipToPadding=false
        }
        fun add(label:String,weight:Float=1f,action:()->Unit){
            bottom.addView(keyButton(label,weight,action),LinearLayout.LayoutParams(0,scaled(44),weight).apply{
                setMargins(dp(1),dp(1),dp(1),dp(1))
                width=0
            })
        }
        when(currentPage){
            KeyboardPage.LETTERS->{
                add("?123",0.9f){page=KeyboardPage.NUMBERS;setInputView(render())}
                bottom.addView(shiftKey(0.9f),LinearLayout.LayoutParams(0,scaled(44),0.9f).apply{
                    setMargins(dp(1),dp(1),dp(1),dp(1))
                    width=0
                })
            }
            KeyboardPage.NUMBERS->{add("ABC",0.9f){page=KeyboardPage.LETTERS;setInputView(render())};add("Symbols",0.9f){page=KeyboardPage.SYMBOLS;setInputView(render())}}
            KeyboardPage.SYMBOLS->{
                add("ABC",0.9f){page=KeyboardPage.LETTERS;setInputView(render())}
                add("123",0.9f){page=KeyboardPage.NUMBERS;setInputView(render())}
            }
        }
        add(",",0.65f){commitTextToEditor(",");refreshAfterTextKey()}
        val space=keyButton("Space",3.1f){commitSpace()}
        space.setOnTouchListener{_,event->when(event.actionMasked){
            MotionEvent.ACTION_DOWN->{spaceDownX=event.x;spaceMoved=false;true}
            MotionEvent.ACTION_MOVE->{if(!spaceMoved&&abs(event.x-spaceDownX)>dp(28)){spaceMoved=true;moveCursor(if(event.x>spaceDownX)1 else -1)};true}
            MotionEvent.ACTION_UP->{if(!spaceMoved)commitSpace();true}
            MotionEvent.ACTION_CANCEL->{spaceMoved=false;true}
            else->true
        }}
        bottom.addView(space,LinearLayout.LayoutParams(0,scaled(44),3.1f).apply{setMargins(dp(1),dp(1),dp(1),dp(1));width=0})
        add(".",0.65f){commitTextToEditor(".");refreshAfterTextKey()}
        val back=keyButton("⌫",0.9f){deleteBackward();refreshAfterTextKey()}
        installRepeat(back){deleteBackward();refreshAfterTextKey()}
        bottom.addView(back,LinearLayout.LayoutParams(0,scaled(44),0.9f).apply{setMargins(dp(1),dp(1),dp(1),dp(1));width=0})
        add(actionLabel(),0.9f){sendEditorAction()}
        root.addView(bottom,LinearLayout.LayoutParams(-1,scaled(44)))
        addFloatingControls(root)
    }
    private fun addFloatingControls(root: LinearLayout) {
        if (!PhormiKeyboardPreferences.floating(this)) return
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(dp(10), 0, dp(10), 0) }
        fun resizeHandle(label: String, description: String, direction: Float): TextView = TextView(this).apply {
            text = label; textSize = 16f; gravity = Gravity.CENTER; setTextColor(themeText()); background = rounded(themePill(), dp(10)); contentDescription = description
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { resizeStartX = event.rawX; resizeStartY = event.rawY; resizeStartWidth = PhormiKeyboardPreferences.widthScale(this@PhormiKeyboardServiceV2); resizeStartHeight = PhormiKeyboardPreferences.heightScale(this@PhormiKeyboardServiceV2); true }
                    MotionEvent.ACTION_MOVE -> {
                        val screenW = resources.displayMetrics.widthPixels.coerceAtLeast(1); val screenH = resources.displayMetrics.heightPixels.coerceAtLeast(1)
                        val dx = (event.rawX - resizeStartX) * direction; val dy = (resizeStartY - event.rawY) * direction
                        val delta = maxOf(dx / (screenW * 0.55f), dy / (screenH * 0.40f))
                        val next = (resizeStartWidth + delta).coerceIn(0.55f, 1.0f)
                        PhormiKeyboardPreferences.setWidthScale(this@PhormiKeyboardServiceV2, next)
                        PhormiKeyboardPreferences.setHeightScale(this@PhormiKeyboardServiceV2, next.coerceIn(0.60f, 1.40f))
                        applyKeyboardWindowSize(); true
                    }
                    else -> true
                }
            }
        }
        row.addView(resizeHandle("◀", "Resize floating keyboard", -1f), LinearLayout.LayoutParams(dp(38), scaled(28)).apply { setMargins(dp(3), 0, dp(3), 0) })
        val move = TextView(this).apply {
            text = "⠿"; textSize = 18f; gravity = Gravity.CENTER; setTextColor(themeText()); background = rounded(themePill(), dp(10)); contentDescription = "Move floating keyboard; drag to the bottom to dock"
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { resizeStartX = event.rawX; resizeStartY = event.rawY; resizePreviewHeight = PhormiKeyboardPreferences.offsetX(this@PhormiKeyboardServiceV2); resizePreviewWidth = PhormiKeyboardPreferences.offsetY(this@PhormiKeyboardServiceV2); true }
                    MotionEvent.ACTION_MOVE -> {
                        val screenW = resources.displayMetrics.widthPixels.coerceAtLeast(1); val screenH = resources.displayMetrics.heightPixels.coerceAtLeast(1)
                        PhormiKeyboardPreferences.setOffsetX(this@PhormiKeyboardServiceV2, resizePreviewHeight + (event.rawX - resizeStartX) / (screenW * 0.55f))
                        PhormiKeyboardPreferences.setOffsetY(this@PhormiKeyboardServiceV2, resizePreviewWidth + (event.rawY - resizeStartY) / (screenH * 0.55f))
                        applyKeyboardWindowSize(); true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (event.rawY > resources.displayMetrics.heightPixels - dp(90)) {
                            PhormiKeyboardPreferences.setFloating(this@PhormiKeyboardServiceV2, false)
                            PhormiKeyboardPreferences.setWidthScale(this@PhormiKeyboardServiceV2, 1f)
                            PhormiKeyboardPreferences.setOffsetX(this@PhormiKeyboardServiceV2, 0f)
                            PhormiKeyboardPreferences.setOffsetY(this@PhormiKeyboardServiceV2, 0f)
                            setInputView(render())
                        }
                        true
                    }
                    else -> true
                }
            }
        }
        row.addView(move, LinearLayout.LayoutParams(dp(46), scaled(28)).apply { setMargins(dp(5), 0, dp(5), 0) })
        row.addView(resizeHandle("▶", "Resize floating keyboard", 1f), LinearLayout.LayoutParams(dp(38), scaled(28)).apply { setMargins(dp(3), 0, dp(3), 0) })
        root.addView(row, LinearLayout.LayoutParams(-1, scaled(30)))
    }
    private fun buildEmoji():View{val root=root();val nav=HorizontalScrollView(this).apply{isHorizontalScrollBarEnabled=false;overScrollMode=View.OVER_SCROLL_NEVER};val categories=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL};PhormiKeyboardEmoji.categories.keys.forEachIndexed{index,icon->categories.addView(pill(icon){emojiCategory=index;setInputView(render())},LinearLayout.LayoutParams(dp(48),scaled(36)).apply{setMargins(dp(2),0,dp(2),0)})};categories.addView(pill("ABC"){panel=Panel.KEYBOARD;page=KeyboardPage.LETTERS;setInputView(render())},LinearLayout.LayoutParams(dp(58),scaled(36)));nav.addView(categories);root.addView(nav,LinearLayout.LayoutParams(-1,scaled(36)));if(PhormiKeyboardTextEngine.allowsAiEmoji(editorInfo)&&PhormiKeyboardPreferences.aiEmoji(this)){root.addView(pill("✨ Create unique emoji"){panel=Panel.AI_EMOJI;setInputView(render())},LinearLayout.LayoutParams(-1,scaled(38)).apply{setMargins(dp(2),dp(3),dp(2),dp(3))})};val scroll=ScrollView(this).apply{isFillViewport=true;clipToPadding=true;overScrollMode=View.OVER_SCROLL_IF_CONTENT_SCROLLS};val grid=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};PhormiKeyboardEmoji.categories.values.elementAtOrNull(emojiCategory).orEmpty().chunked(8).forEach{group->val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER};group.forEach{emoji->row.addView(keyButton(emoji){commitTextToEditor(emoji)},LinearLayout.LayoutParams(0,scaled(42),1f))};repeat(8-group.size){row.addView(View(this),LinearLayout.LayoutParams(0,scaled(42),1f))};grid.addView(row,LinearLayout.LayoutParams(-1,scaled(44)))};scroll.addView(grid);root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f));addEmojiModeNav(root);return root}
    private fun addEmojiModeNav(root:LinearLayout){val nav=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER;weightSum=3f};fun addMode(label:String,target:KeyboardPage){nav.addView(pill(label){panel=Panel.KEYBOARD;page=target;setInputView(render())},LinearLayout.LayoutParams(0,scaled(40),1f).apply{setMargins(dp(2),dp(2),dp(2),dp(2))})};addMode("ABC",KeyboardPage.LETTERS);addMode("123",KeyboardPage.NUMBERS);addMode("Symbols",KeyboardPage.SYMBOLS);root.addView(nav,LinearLayout.LayoutParams(-1,scaled(44)))}
    private fun buildClipboard():View{
        val root=root()
        addBackHeader(root,"← Keyboard"){panel=Panel.KEYBOARD;setInputView(render())}
        val list=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(8),dp(4),dp(8),dp(4))}
        list.addView(pill("Clear all clipboard history"){PhormiKeyboardClipboardStore.clear(this);setInputView(render())},LinearLayout.LayoutParams(-1,scaled(38)).apply{setMargins(0,0,0,dp(5))})
        val scroll=ScrollView(this)
        val items=PhormiKeyboardClipboardStore.list(this)
        if(items.isEmpty()) list.addView(TextView(this).apply{text="Your copied text, screenshots and images will appear here.";setTextColor(themeText());textSize=14f;setPadding(dp(12),dp(18),dp(12),dp(18))})
        items.forEach{item->
            val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
            val label=item.text.take(60).ifBlank{"Copied item"}
            val clip=pill(label){if(item.text.isNotBlank())commitTextToEditor(item.text)else item.uri?.let{commitContentToEditor(Uri.parse(it))}}
            val delete=pill("Delete"){PhormiKeyboardClipboardStore.remove(this,item);setInputView(render())}
            delete.visibility=View.GONE
            clip.setOnLongClickListener{delete.visibility=View.VISIBLE;true}
            row.addView(clip,LinearLayout.LayoutParams(0,scaled(44),1f).apply{setMargins(0,dp(2),dp(3),dp(2))})
            row.addView(delete,LinearLayout.LayoutParams(dp(72),scaled(44)).apply{setMargins(dp(3),dp(2),0,dp(2))})
            list.addView(row)
        }
        scroll.addView(list)
        root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        return root
    }
    private fun insertAiEmojiExpression(){
        val text=PhormiKeyboardTextEngine.contextBeforeCursor(currentInputConnection).trim()
        if(text.isBlank()){
            openAiEmojiFromContext()
            return
        }
        val value=text.lowercase(Locale.getDefault())
        val expression=when{
            value.matches(Regex(".*\\b(love|loving|romantic|kiss|heart|crush|adore|sweet)\\b.*"))->"❤️🥰"
            value.matches(Regex(".*\\b(happy|joy|glad|great|good|wonderful|fun|excited|celebrate|celebration)\\b.*"))->"😊🔥"
            value.matches(Regex(".*\\b(laugh|laughing|funny|lol|lmao|hilarious)\\b.*"))->"😂🤣"
            value.matches(Regex(".*\\b(sad|sadness|cry|crying|hurt|lonely|miss|missing|sorry)\\b.*"))->"😔💙"
            value.matches(Regex(".*\\b(angry|anger|mad|furious|annoyed|hate|frustrated)\\b.*"))->"😤🔥"
            value.matches(Regex(".*\\b(surprised|surprise|shock|shocked|wow|omg)\\b.*"))->"😮✨"
            value.matches(Regex(".*\\b(scared|afraid|fear|worried|worry|danger)\\b.*"))->"😨⚠️"
            value.matches(Regex(".*\\b(proud|win|won|success|successful|achievement)\\b.*"))->"😎🏆"
            value.matches(Regex(".*\\b(thanks|thankful|grateful|bless)\\b.*"))->"🙏❤️"
            value.matches(Regex(".*\\b(tired|sleepy|sleep|exhausted)\\b.*"))->"😴💤"
            else->"🙂✨"
        }
        commitTextToEditor(expression)
        refreshPredictionStrip()
    }

    private fun openAiEmojiFromContext(){
        aiEmojiContext=PhormiKeyboardTextEngine.contextBeforeCursor(currentInputConnection).trim()
        panel=Panel.AI_EMOJI
        setInputView(render())
    }

    private fun buildAiEmoji():View{
        val root=root(); addBackHeader(root,"← Emoji"){aiEmojiContext="";panel=Panel.EMOJI;setInputView(render())}
        val scroll=ScrollView(this).apply{isFillViewport=true;overScrollMode=View.OVER_SCROLL_IF_CONTENT_SCROLLS}
        val list=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER_HORIZONTAL;setPadding(dp(8),dp(8),dp(8),dp(8))}
        list.addView(TextView(this).apply{text="Create a unique emoji";textSize=18f;typeface=Typeface.DEFAULT_BOLD;setTextColor(themeText());gravity=Gravity.CENTER})
        list.addView(TextView(this).apply{text="Generate one fused emoji-style reaction from your text. The result appears here so it can be inserted directly.";textSize=13f;setTextColor(themeText());gravity=Gravity.CENTER;setPadding(dp(8),dp(6),dp(8),dp(10))})
        val contextPreview=TextView(this).apply{
            text=if(aiEmojiContext.isBlank())"No text captured yet. Type in the current app first, then return here." else "Current text: $aiEmojiContext"
            textSize=13f
            setTextColor(themeText())
            setPadding(dp(10),dp(10),dp(10),dp(10))
            background=rounded(themePill(),dp(10))
        }
        list.addView(contextPreview,LinearLayout.LayoutParams(-1,dp(72)))
        list.addView(pill("Type a description in AI Emoji Studio"){
            startActivity(Intent(this@PhormiKeyboardServiceV2, PhormiAiEmojiActivity::class.java).apply{
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(PhormiAiEmojiActivity.EXTRA_CONTEXT, aiEmojiContext)
            })
        },LinearLayout.LayoutParams(-1,scaled(44)))
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
        list.addView(pill(if(aiEmojiContext.isBlank())"Use what I'm typing"else"Create from current text"){generate(if(aiEmojiContext.isBlank())PhormiKeyboardTextEngine.contextBeforeCursor(currentInputConnection)else aiEmojiContext)},LinearLayout.LayoutParams(-1,scaled(46)))
        list.addView(pill("Generate from description"){
            val current=aiEmojiContext.ifBlank { PhormiKeyboardTextEngine.contextBeforeCursor(currentInputConnection) }
            generate(current)
        },LinearLayout.LayoutParams(-1,scaled(46)))
        scroll.addView(list);root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f));return root
    }
    private fun buildMedia():View{val root=root();addBackHeader(root,"← Keyboard"){panel=Panel.KEYBOARD;setInputView(render())};val scroll=ScrollView(this);val list=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(8),dp(8),dp(8),dp(8))};list.addView(pill("Import GIF / Image"){launchMedia("gif")},LinearLayout.LayoutParams(-1,scaled(46)));list.addView(pill("Import Sticker"){launchMedia("sticker")},LinearLayout.LayoutParams(-1,scaled(46)));list.addView(pill("Create AI Emoji"){panel=Panel.AI_EMOJI;setInputView(render())},LinearLayout.LayoutParams(-1,scaled(46)));list.addView(TextView(this).apply{text="Images/GIFs are inserted only when the focused field accepts the requested MIME type.";setTextColor(themeText());textSize=13f;setPadding(dp(8),dp(12),dp(8),dp(12))});scroll.addView(list);root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f));return root}
    private fun buildTools():View{val root=root();addBackHeader(root,"← Keyboard"){panel=Panel.KEYBOARD;setInputView(render())};val scroll=ScrollView(this);val list=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(8),dp(8),dp(8),dp(8))};list.addView(pill("Settings"){panel=Panel.SETTINGS;setInputView(render())},LinearLayout.LayoutParams(-1,scaled(46)));addResizeControls(list);list.addView(pill("Voice typing"){launchVoice()},LinearLayout.LayoutParams(-1,scaled(46)));list.addView(pill("Select all"){currentInputConnection?.performContextMenuAction(android.R.id.selectAll)},LinearLayout.LayoutParams(-1,scaled(46)));list.addView(pill("Copy"){currentInputConnection?.performContextMenuAction(android.R.id.copy)},LinearLayout.LayoutParams(-1,scaled(46)));list.addView(pill("Paste"){currentInputConnection?.performContextMenuAction(android.R.id.paste)},LinearLayout.LayoutParams(-1,scaled(46)));list.addView(pill("Share text"){shareSelectedText()},LinearLayout.LayoutParams(-1,scaled(46)));scroll.addView(list);root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f));return root}
    private fun buildSettings():View{val root=root();addBackHeader(root,"← Tools"){panel=Panel.TOOLS;setInputView(render())};val scroll=ScrollView(this).apply{overScrollMode=View.OVER_SCROLL_IF_CONTENT_SCROLLS};val list=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(10),dp(6),dp(10),dp(8))};list.addView(TextView(this).apply{text="Keyboard size";textSize=15f;setTextColor(themeText());setPadding(0,dp(4),0,dp(2))});list.addView(TextView(this).apply{text="Use Move and Size inside Cabinet to reposition or resize the keyboard. Changes are saved automatically.";textSize=12f;setTextColor(themeText().let{if(theme()==3)Color.DKGRAY else Color.rgb(148,163,184)});setPadding(0,0,0,dp(6))});list.addView(pill("Reset keyboard size"){PhormiKeyboardPreferences.resetSize(this@PhormiKeyboardServiceV2);setInputView(render())},LinearLayout.LayoutParams(-1,scaled(36)).apply{setMargins(0,dp(4),0,dp(8))});fun toggle(title:String,enabled:Boolean,action:()->Unit){list.addView(pill(if(enabled)"✓ $title"else title,action),LinearLayout.LayoutParams(-1,scaled(36)).apply{setMargins(0,dp(3),0,dp(3))})};toggle("Suggestions",PhormiKeyboardPreferences.suggestions(this)){PhormiKeyboardPreferences.set(this@PhormiKeyboardServiceV2,PhormiKeyboardPreferences.KEY_SUGGESTIONS,!PhormiKeyboardPreferences.suggestions(this));setInputView(render())};toggle("Autocorrect",PhormiKeyboardPreferences.autocorrect(this)){PhormiKeyboardPreferences.set(this@PhormiKeyboardServiceV2,PhormiKeyboardPreferences.KEY_AUTOCORRECT,!PhormiKeyboardPreferences.autocorrect(this));setInputView(render())};toggle("Auto-capitalization",PhormiKeyboardPreferences.autoCaps(this)){PhormiKeyboardPreferences.set(this@PhormiKeyboardServiceV2,PhormiKeyboardPreferences.KEY_AUTO_CAPS,!PhormiKeyboardPreferences.autoCaps(this));setInputView(render())};toggle("Haptic feedback",PhormiKeyboardPreferences.haptic(this)){PhormiKeyboardPreferences.set(this@PhormiKeyboardServiceV2,PhormiKeyboardPreferences.KEY_HAPTIC,!PhormiKeyboardPreferences.haptic(this));setInputView(render())};toggle("Key sounds",PhormiKeyboardPreferences.sound(this)){PhormiKeyboardPreferences.set(this@PhormiKeyboardServiceV2,PhormiKeyboardPreferences.KEY_SOUND,!PhormiKeyboardPreferences.sound(this));setInputView(render())};list.addView(pill("Theme: ${themeLabel(theme())}"){\n            PhormiKeyboardPreferences.setTheme(this@PhormiKeyboardServiceV2, (theme() + 1) % 8)\n            setInputView(render())\n        },LinearLayout.LayoutParams(-1,scaled(36)).apply{setMargins(0,dp(3),0,dp(3))})\n        list.addView(pill(if(PhormiKeyboardPreferences.wallpaperUri(this).isNullOrBlank())"Add wallpaper"else"Change wallpaper"){\n            startActivity(Intent(this@PhormiKeyboardServiceV2, PhormiKeyboardMediaActivity::class.java).apply{addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);putExtra(PhormiKeyboardMediaActivity.EXTRA_MODE,"wallpaper")})\n        },LinearLayout.LayoutParams(-1,scaled(36)).apply{setMargins(0,dp(3),0,dp(3))})\n        if (!PhormiKeyboardPreferences.wallpaperUri(this).isNullOrBlank()) list.addView(pill("Remove wallpaper"){PhormiKeyboardPreferences.setWallpaperUri(this@PhormiKeyboardServiceV2,null);setInputView(render())},LinearLayout.LayoutParams(-1,scaled(36)).apply{setMargins(0,dp(3),0,dp(3))})\n        list.addView(pill(if(PhormiKeyboardPreferences.pollinationsKey(this).isBlank())"Set Pollinations AI key"else"Pollinations AI key ✓"){startActivity(Intent(this,PhormiAiEmojiActivity::class.java).apply{addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);putExtra(PhormiAiEmojiActivity.EXTRA_CONFIG_ONLY,true)})},LinearLayout.LayoutParams(-1,scaled(36)).apply{setMargins(0,dp(3),0,dp(3))});scroll.addView(list);root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f));return root}
    private fun themeLabel(value: Int): String = listOf("Midnight","Graphite","Ocean","Light","Slate","Forest","Berry","Sand").getOrElse(value) { "Midnight" }\n    private fun addBackHeader(root:LinearLayout,label:String,action:()->Unit){root.addView(pill(label,action),LinearLayout.LayoutParams(-1,scaled(36)))}
    private fun render():View{
        val view=when(panel){
            Panel.EMOJI->buildEmoji()
            Panel.CLIPBOARD->buildClipboard()
            Panel.AI_EMOJI->buildAiEmoji()
            Panel.TOOLS->buildTools()
            Panel.MEDIA->buildMedia()
            Panel.SETTINGS->buildSettings()
            Panel.KEYBOARD->buildKeyboard()
        }
        view.post { applyKeyboardWindowSize() }
        return view
    }
    private fun refreshAfterTextKey(){if(panel==Panel.KEYBOARD && page==KeyboardPage.LETTERS) schedulePredictionRefresh()}
    private fun launchMedia(mode:String){startActivity(Intent(this,PhormiKeyboardMediaActivity::class.java).apply{addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);putExtra(PhormiKeyboardMediaActivity.EXTRA_MODE,mode)})}
    private fun launchVoice() {
        if (currentInputConnection == null) {
            android.widget.Toast.makeText(this, "No text field is active", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val locale = PhormiKeyboardTextEngine.localeFor(editorInfo).toLanguageTag()
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            PhormiKeyboardVoiceActivity.requestPermissionFromKeyboard(this, locale)
            return
        }
        startIntegratedVoiceRecognition(locale)
    }

    fun startIntegratedVoiceRecognition(localeTag: String) {
        if (currentInputConnection == null) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            android.widget.Toast.makeText(this, "Speech recognition is not available on this device", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        speechRecognizer?.cancel()
        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).also { sr ->
            sr.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: android.os.Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: android.os.Bundle?) {}
                override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
                override fun onError(error: Int) {
                    speechRecognizer?.cancel()
                }
                override fun onResults(results: android.os.Bundle?) {
                    results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { commitTextToEditor(it) }
                    speechRecognizer?.cancel()
                }
            })
        }
        val recognition = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, localeTag)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, localeTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        runCatching { speechRecognizer?.startListening(recognition) }
            .onFailure {
                speechRecognizer?.cancel()
                android.widget.Toast.makeText(this, "Unable to start voice typing", android.widget.Toast.LENGTH_SHORT).show()
            }
    }
    private fun shareSelectedText(){val text=currentInputConnection?.getSelectedText(0)?.toString().orEmpty();if(text.isBlank()){android.widget.Toast.makeText(this,"Select text first",android.widget.Toast.LENGTH_SHORT).show();return};startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply{type="text/plain";putExtra(Intent.EXTRA_TEXT,text)},"Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}
    private fun applyPendingInput(){val prefs=getSharedPreferences(PREFS,MODE_PRIVATE);prefs.getString(KEY_PENDING_TEXT,null)?.takeIf{it.isNotBlank()}?.let{commitTextToEditor(it)};prefs.edit().remove(KEY_PENDING_TEXT).apply();prefs.getString(KEY_PENDING_URI,null)?.let{runCatching{commitContentToEditor(Uri.parse(it))}};prefs.edit().remove(KEY_PENDING_URI).apply()}
    private fun commitTextToEditor(text:String){
        val ic=currentInputConnection ?: return
        ic.commitText(text,1)
        if(PhormiKeyboardPreferences.autoCaps(this) && text.any{it in charArrayOf('.','!','?',':',';','\n')}){
            autoShift=PhormiKeyboardTextEngine.autoCapitalize(ic,editorInfo)
        }
    }
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
