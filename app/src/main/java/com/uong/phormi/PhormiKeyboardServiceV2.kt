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
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputContentInfo
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.FrameLayout
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
        fun activeInstance(): PhormiKeyboardServiceV2? = instance
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
    private var shiftKeyView: Button? = null
    private var renderedShiftState = -1
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
    private var confirmClearClipboard = false
    private var keyboardResizeMode = false
    private data class AutoCorrection(val original: String, val corrected: String, val time: Long)
    private var lastAutoCorrection: AutoCorrection? = null
    private val repeatHandler = Handler(Looper.getMainLooper())
    private var repeatRunnable: Runnable? = null
    private var predictionRow: LinearLayout? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var predictionRefreshRunnable: Runnable? = null
    private var lastPredictionWord = ""
    private var lastPredictionPrevious = ""
    private var reuseViewOnRestart = false

    override fun onCreate() {
        super.onCreate(); instance = this
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboardListener = ClipboardManager.OnPrimaryClipChangedListener { PhormiKeyboardClipboardStore.capturePrimaryClipboard(this) }
        clipboardListener?.let { cm?.addPrimaryClipChangedListener(it) }
    }
    override fun onDestroy() {
        speechRecognizer?.cancel(); speechRecognizer?.destroy(); speechRecognizer = null
        stopRepeat()
        predictionRefreshRunnable?.let { repeatHandler.removeCallbacks(it) }; predictionRefreshRunnable = null
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboardListener?.let { cm?.removePrimaryClipChangedListener(it) }; clipboardListener = null
        if (instance === this) instance = null; super.onDestroy()
    }
    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        PhormiKeyboardOnlineDictionary.cancelPending()
        val previous = editorInfo
        reuseViewOnRestart = restarting && previous != null && attribute != null &&
            previous.packageName == attribute.packageName && previous.fieldId == attribute.fieldId &&
            previous.fieldName == attribute.fieldName && previous.inputType == attribute.inputType &&
            previous.hintText?.toString() == attribute.hintText?.toString()
        super.onStartInput(attribute, restarting)
        editorInfo = attribute
        if (!reuseViewOnRestart) {
            page = KeyboardPage.LETTERS
            capsLock = false; shift = false
            lastAutoCorrection = null; lastPredictionWord = ""; lastPredictionPrevious = ""
            confirmClearClipboard = false
            autoShift = PhormiKeyboardPreferences.autoCaps(this) &&
                PhormiKeyboardTextEngine.autoCapitalize(currentInputConnection, attribute)
            panel = Panel.KEYBOARD
        }
        if (attribute != null && PhormiKeyboardTextEngine.shouldUsePredictions(attribute)) {
            PhormiKeyboardDictionary.warmUp(applicationContext, PhormiKeyboardTextEngine.localeFor(attribute))
        }
    }
    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        editorInfo = info ?: editorInfo
        if (reuseViewOnRestart && restarting && currentInputView != null && panel == Panel.KEYBOARD) {
            // WebView can restart its input connection as a site updates its form.
            // Replacing the entire IME view on each restart makes it look as if the
            // keyboard closes after every character.
            schedulePredictionRefresh()
            return
        }
        panel = Panel.KEYBOARD
        runCatching { getWindow().window?.setWindowAnimations(0) }
        setInputView(render())
        applyKeyboardWindowSize()
        applyPendingInput()
    }
    override fun onUpdateSelection(oldSelStart:Int, oldSelEnd:Int, newSelStart:Int, newSelEnd:Int, candidatesStart:Int, candidatesEnd:Int) {
        super.onUpdateSelection(oldSelStart,oldSelEnd,newSelStart,newSelEnd,candidatesStart,candidatesEnd)
        if(panel==Panel.KEYBOARD && page==KeyboardPage.LETTERS){
            schedulePredictionRefresh()
            if(PhormiKeyboardPreferences.autoCaps(this)){
                autoShift=PhormiKeyboardTextEngine.autoCapitalize(currentInputConnection,editorInfo)
            }
            updateShiftKeyAppearance()
        }
    }

    override fun onFinishInput() { stopRepeat(); shiftKeyView = null; editorInfo = null; super.onFinishInput() }
    override fun onUnbindInput() { stopRepeat(); shiftKeyView = null; editorInfo = null; super.onUnbindInput() }
    override fun onFinishInputView(finishingInput: Boolean) {
        PhormiKeyboardOnlineDictionary.cancelPending()
        stopRepeat()
        // Do not rebuild/reposition the input view during dismissal. Rebuilding
        // here can produce a brief duplicate/miniature keyboard flash.
        runCatching { getWindow().window?.setWindowAnimations(0) }
        super.onFinishInputView(finishingInput)
    }

    override fun onWindowShown() {
        runCatching {
            getWindow().window?.setWindowAnimations(0)
            getWindow().window?.decorView?.animate()?.cancel()
        }
        super.onWindowShown()
    }

    override fun onWindowHidden() {
        stopRepeat()
        runCatching {
            getWindow().window?.setWindowAnimations(0)
            getWindow().window?.decorView?.animate()?.cancel()
        }
        super.onWindowHidden()
    }
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
        val resizeMode = keyboardResizeMode
        val attrs = window.attributes
        val oldGravity = attrs.gravity
        val oldX = attrs.x
        val oldY = attrs.y
        val oldWidth = attrs.width
        val oldHeight = attrs.height
        // A floating/resizeable keyboard must not participate in the app's IME inset.
        // Otherwise Android moves/resizes the page underneath it, which is unlike a
        // floating Gboard window and makes dragging/resizing feel attached to the page.
        val adjust = if (floating || resizeMode) WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            else WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        if (attrs.softInputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST != adjust) {
            window.setSoftInputMode(adjust)
        }
        if (attrs.windowAnimations != 0) window.setWindowAnimations(0)
        if (floating) {
            // Floating IME: keep the initial keyboard near the bottom while
            // preserving independent X/Y movement after the user drags it.
            attrs.gravity = Gravity.TOP or Gravity.START
            val usableW = (screenW - width).coerceAtLeast(0)
            val usableH = (screenH - height).coerceAtLeast(0)
            val baseY = (usableH - dp(48)).coerceAtLeast(0)
            attrs.x = ((PhormiKeyboardPreferences.offsetX(this) + 0.9f) / 1.8f * usableW)
                .roundToInt().coerceIn(0, usableW)
            attrs.y = (baseY + PhormiKeyboardPreferences.offsetY(this) / 0.9f * (usableH * 0.42f))
                .roundToInt().coerceIn(0, usableH)
        } else if (resizeMode) {
            // During resize, use a top-left positioned window while retaining the
            // centered starting position (offsetX=0). This lets an edge stay anchored
            // while only the dragged edge changes size.
            attrs.gravity = Gravity.BOTTOM or Gravity.START
            attrs.x = ((PhormiKeyboardPreferences.offsetX(this) + 0.9f) / 1.8f * (screenW - width))
                .roundToInt().coerceIn(0, (screenW - width).coerceAtLeast(0))
            attrs.y = 0
        } else {
            attrs.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            attrs.x = 0
            attrs.y = 0
        }
        // Avoid re-laying out the whole browser/IME on every input-connection restart.
        if (oldGravity != attrs.gravity || oldX != attrs.x || oldY != attrs.y) window.attributes = attrs
        if (oldWidth != width || oldHeight != height) window.setLayout(width, height)
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
                if (instance === this) root.background = BitmapDrawable(resources, bitmap).apply { alpha = 72 }
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
    private fun shiftButton(action:()->Unit):Button{val state=when{capsLock->2;shift||autoShift->1;else->0};return keyButton(if(state==0)"⇧"else"⇧A",action=action).apply{background=rounded(when(state){2->Color.rgb(220,38,38);1->Color.rgb(124, 58, 237);else->themeKey()},dp(9));setTextColor(Color.WHITE);contentDescription=when(state){2->"Caps lock";1->"One-letter capitalization";else->"Shift"}}}
    private fun addResizeControls(parent: LinearLayout) {
        parent.addView(
            pill(if (PhormiKeyboardPreferences.floating(this)) "Dock keyboard" else "Floating keyboard") {
                if (PhormiKeyboardPreferences.floating(this)) {
                    PhormiKeyboardPreferences.setFloating(this@PhormiKeyboardServiceV2, false)
                    PhormiKeyboardPreferences.setOffsetX(this@PhormiKeyboardServiceV2, 0f)
                    PhormiKeyboardPreferences.setOffsetY(this@PhormiKeyboardServiceV2, 0f)
                    PhormiKeyboardPreferences.setWidthScale(this@PhormiKeyboardServiceV2, 1f)
                    keyboardResizeMode = false
                } else {
                    PhormiKeyboardPreferences.setFloating(this@PhormiKeyboardServiceV2, true)
                    if (PhormiKeyboardPreferences.widthScale(this) >= 0.99f) {
                        PhormiKeyboardPreferences.setWidthScale(this@PhormiKeyboardServiceV2, 0.78f)
                    }
                }
                setInputView(render())
            },
            LinearLayout.LayoutParams(-1, scaled(42)).apply { setMargins(0, dp(2), 0, dp(2)) }
        )
        parent.addView(
            pill(if (keyboardResizeMode) "Done resizing" else "Resize keyboard") {
                keyboardResizeMode = !keyboardResizeMode
                panel = Panel.KEYBOARD
                setInputView(render())
            },
            LinearLayout.LayoutParams(-1, scaled(42)).apply { setMargins(0, dp(2), 0, dp(2)) }
        )
        parent.addView(
            pill("Reset position and size") {
                PhormiKeyboardPreferences.resetSize(this@PhormiKeyboardServiceV2)
                keyboardResizeMode = false
                panel = Panel.KEYBOARD
                setInputView(render())
            },
            LinearLayout.LayoutParams(-1, scaled(42)).apply { setMargins(0, dp(2), 0, dp(2)) }
        )
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
            confirmClearClipboard = false
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

    fun onSpellCheckerSuggestions(word: String) {
        if (panel == Panel.KEYBOARD && page == KeyboardPage.LETTERS &&
            PhormiKeyboardTextEngine.currentWord(currentInputConnection).equals(word, true)) {
            lastPredictionWord = "" // cached dictionary results arrived; re-render in the same reserved strip
            refreshPredictionStrip()
        }
    }

    fun onDictionaryReady(language: String) {
        if (panel == Panel.KEYBOARD && page == KeyboardPage.LETTERS &&
            PhormiKeyboardTextEngine.localeFor(editorInfo).language == language) {
            lastPredictionWord = ""
            schedulePredictionRefresh()
        }
    }

    fun onOnlineDictionarySuggestions(word: String) = onSpellCheckerSuggestions(word)

    private fun refreshPredictionStrip() {
        val row = predictionRow ?: return
        val ic = currentInputConnection
        val locale = PhormiKeyboardTextEngine.localeFor(editorInfo)
        val word = PhormiKeyboardTextEngine.currentWord(ic)
        val previous = PhormiKeyboardTextEngine.previousWord(ic, locale)
        if (word == lastPredictionWord && previous == lastPredictionPrevious && row.childCount > 0) return
        lastPredictionWord = word
        lastPredictionPrevious = previous
        row.removeAllViews()
        val enabled = PhormiKeyboardPreferences.suggestions(this) &&
            PhormiKeyboardTextEngine.shouldUsePredictions(editorInfo)
        val options = LinkedHashSet<String>()
        var correction: String? = null
        if (enabled && word.isNotBlank()) {
            correction = PhormiKeyboardTextEngine.correctionFor(this, word, locale)
            correction?.let(options::add)
            if (word.length >= 2) options.add(word) // The user's spelling is always an option.
            PhormiKeyboardTextEngine.suggestions(this, word, locale, editorInfo).forEach(options::add)
        } else if (enabled) {
            options.addAll(PhormiKeyboardTextEngine.nextWordSuggestions(this, previous, locale))
        }
        if (PhormiKeyboardTextEngine.allowsAiEmoji(editorInfo) &&
            PhormiKeyboardPreferences.aiEmoji(this)) {
            row.addView(pill("✨ Emoji") { insertAiEmojiExpression() },
                LinearLayout.LayoutParams(dp(72), scaled(32)).apply { setMargins(dp(2), 0, dp(2), 0) })
        }
        options.take(3).forEach { value ->
            val button = pill(value, {})
            if (value == correction) button.setTextColor(accent())
            else if (word.isNotBlank() && value.equals(word, true)) button.setTextColor(themeText())
            button.setOnClickListener {
                feedback(button)
                val connection = currentInputConnection ?: return@setOnClickListener
                val liveWord = PhormiKeyboardTextEngine.currentWord(connection)
                val liveLocale = PhormiKeyboardTextEngine.localeFor(editorInfo)
                if (liveWord.isNotBlank() && value.equals(liveWord, true) &&
                    PhormiKeyboardTextEngine.correctionFor(this, liveWord, liveLocale) != null) {
                    PhormiKeyboardTextEngine.ignoreCorrection(this, liveWord, liveLocale)
                }
                PhormiKeyboardTextEngine.acceptWord(this, value, editorInfo)
                connection.beginBatchEdit()
                try {
                    if (liveWord.isNotBlank()) {
                        connection.finishComposingText()
                        connection.deleteSurroundingText(liveWord.length, 0)
                    } else if (connection.getTextBeforeCursor(1, 0)?.lastOrNull()?.isWhitespace() == false) {
                        connection.commitText(" ", 1)
                    }
                    connection.commitText(value, 1)
                } finally { connection.endBatchEdit() }
                commitSpace(applyCorrection = false)
                schedulePredictionRefresh()
            }
            button.setOnLongClickListener {
                val removed = PhormiKeyboardTextEngine.forgetPersonalizedSuggestion(this, value, previous, locale)
                android.widget.Toast.makeText(this,
                    if (removed) "Removed \"$value\" from learned words" else "Built-in word — nothing to remove",
                    android.widget.Toast.LENGTH_SHORT).show()
                if (removed) { lastPredictionWord = ""; schedulePredictionRefresh() }
                true
            }
            button.contentDescription = when {
                value == correction -> "Correct to $value"
                word.isNotBlank() && value.equals(word, true) -> "Keep $value"
                else -> "Insert $value"
            }
            row.addView(button, LinearLayout.LayoutParams(0, scaled(32), 1f).apply {
                setMargins(dp(2), 0, dp(2), 0)
            })
        }
        if (options.isEmpty()) {
            row.addView(TextView(this).apply {
                text = if (enabled) "Type to see suggestions" else "Suggestions off for this field"
                textSize = 12f
                setTextColor(if (theme() == 3) Color.DKGRAY else Color.rgb(148, 163, 184))
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(0, scaled(30), 1f))
        }
        // Never collapse the strip: the letter keys stay at the same vertical position.
        row.visibility = View.VISIBLE
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
            "Legal & marks" to "§¶†‡°′″№‰©®™℠℗℮",
            "Greek" to "ΑΒΓΔΕΘΛΞΠΣΦΨΩαβγδεθλξπσφψω",
            "Fractions" to "½⅓⅔¼¾⅛⅜⅝⅞⅑⅕⅖⅗⅘‰‱",
            "Shapes" to "■□▣▢▲△▼▽◆◇○●◎◉★☆♠♣♥♦",
            "Music" to "♪♫♬♩♭♮♯𝄞",
            "Tech" to "⌘⌥⌫⌦⎋⏎⏏⌂⌚⌛⚙⚡☰☷",
            "Weather" to "☀☁☂☃☄☔☕☼❄⛅⛈☾☽"
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
        val glyphs = buildList {
            var index = 0
            while (index < symbols.length) {
                val cp = symbols.codePointAt(index)
                add(String(Character.toChars(cp)))
                index += Character.charCount(cp)
            }
        }
        glyphs.chunked(10).forEach{line->
            val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER}
            line.forEach{ch->row.addView(keyButton(ch){commitTextToEditor(ch);refreshAfterTextKey()},LinearLayout.LayoutParams(0,scaled(42),1f).apply{setMargins(dp(2),dp(2),dp(2),dp(2))})}
            repeat(10-line.size){row.addView(View(this),LinearLayout.LayoutParams(0,scaled(42),1f).apply{setMargins(dp(2),dp(2),dp(2),dp(2))})}
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
    private fun shiftKey(weight: Float): Button = keyButton("⇧", weight) {
        toggleShift()
        // Update just this key. Rebuilding the IME for a shift tap can flicker and
        // briefly displace the suggestion strip and letter keys.
        updateShiftKeyAppearance()
    }.apply {
        shiftKeyView = this
        textSize = 21f
        updateShiftKeyAppearance()
    }

    private fun updateShiftKeyAppearance() {
        val key = shiftKeyView ?: return
        val state = when {
            capsLock -> 2
            shift || autoShift -> 1
            else -> 0
        }
        if (renderedShiftState == state) return
        renderedShiftState = state
        // Purple = one-letter capitalization; red = caps lock.
        key.background = rounded(when (state) {
            2 -> Color.rgb(220, 38, 38)
            1 -> Color.rgb(124, 58, 237)
            else -> themeKey()
        }, dp(9))
        key.setTextColor(Color.WHITE)
        key.contentDescription = when (state) {
            2 -> "Caps lock — all letters uppercase"
            1 -> "Shift — next letter uppercase"
            else -> "Shift — off"
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
                add("Symbols",0.9f){page=KeyboardPage.SYMBOLS;setInputView(render())}
                bottom.addView(shiftKey(0.9f),LinearLayout.LayoutParams(0,scaled(44),0.9f).apply{
                    setMargins(dp(1),dp(1),dp(1),dp(1))
                    width=0
                })
            }
            KeyboardPage.NUMBERS->{add("ABC",0.9f){page=KeyboardPage.LETTERS;setInputView(render())}}
            KeyboardPage.SYMBOLS->{
                add("ABC",0.9f){page=KeyboardPage.LETTERS;setInputView(render())}
                add("123",0.9f){page=KeyboardPage.NUMBERS;setInputView(render())}
            }
        }
        add(",",0.65f){commitTextToEditor(",");refreshAfterTextKey()}
        val spaceWeight = when (currentPage) {
            KeyboardPage.LETTERS -> 2.2f // Symbols sits directly on the ABC row.
            KeyboardPage.NUMBERS -> 4.0f // No duplicate Symbols key on the number row.
            KeyboardPage.SYMBOLS -> 3.1f
        }
        val space=keyButton("Space",spaceWeight){commitSpace()}
        space.setOnTouchListener{_,event->when(event.actionMasked){
            MotionEvent.ACTION_DOWN->{spaceDownX=event.x;spaceMoved=false;true}
            MotionEvent.ACTION_MOVE->{if(!spaceMoved&&abs(event.x-spaceDownX)>dp(28)){spaceMoved=true;moveCursor(if(event.x>spaceDownX)1 else -1)};true}
            MotionEvent.ACTION_UP->{if(!spaceMoved)commitSpace();true}
            MotionEvent.ACTION_CANCEL->{spaceMoved=false;true}
            else->true
        }}
        bottom.addView(space,LinearLayout.LayoutParams(0,scaled(44),spaceWeight).apply{setMargins(dp(1),dp(1),dp(1),dp(1));width=0})
        add(".",0.65f){commitTextToEditor(".");refreshAfterTextKey()}
        val back=keyButton("⌫",0.9f){deleteBackward();refreshAfterTextKey()}
        installRepeat(back){deleteBackward();refreshAfterTextKey()}
        bottom.addView(back,LinearLayout.LayoutParams(0,scaled(44),0.9f).apply{setMargins(dp(1),dp(1),dp(1),dp(1));width=0})
        add(actionLabel(),0.9f){sendEditorAction()}
        root.addView(bottom,LinearLayout.LayoutParams(-1,scaled(44)))
    }
    private fun buildEmoji(): View {
        val root = root()
        val nav = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val categories = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        PhormiKeyboardEmoji.categories.keys.forEachIndexed { index, icon ->
            val name = PhormiKeyboardEmoji.categoryNames.getOrElse(index) { "Emoji" }
            categories.addView(pill(icon) { emojiCategory = index; setInputView(render()) }.apply {
                contentDescription = "$name emoji"
                if (emojiCategory == index) background = rounded(Color.rgb(80, 63, 152), dp(13))
            }, LinearLayout.LayoutParams(dp(46), scaled(36)).apply { setMargins(dp(2), 0, dp(2), 0) })
        }
        nav.addView(categories)
        root.addView(nav, LinearLayout.LayoutParams(-1, scaled(36)))
        root.addView(TextView(this).apply {
            text = PhormiKeyboardEmoji.categoryNames.getOrElse(emojiCategory) { "Emoji" }
            textSize = 12f; setTextColor(themeText()); setPadding(dp(10), 0, 0, 0)
            gravity = Gravity.CENTER_VERTICAL
        }, LinearLayout.LayoutParams(-1, scaled(24)))
        if (PhormiKeyboardTextEngine.allowsAiEmoji(editorInfo) && PhormiKeyboardPreferences.aiEmoji(this)) {
            root.addView(pill("✨ Contextual AI emoji · text only") { openAiEmojiFromContext() },
                LinearLayout.LayoutParams(-1, scaled(36)).apply { setMargins(dp(2), 0, dp(2), dp(2)) })
        }
        val scroll = ScrollView(this).apply { overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS }
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        PhormiKeyboardEmoji.forCategory(this, emojiCategory).chunked(8).forEach { group ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
            group.forEach { emoji ->
                row.addView(keyButton(emoji) {
                    commitTextToEditor(emoji)
                    if (!PhormiKeyboardTextEngine.isPrivateEditor(editorInfo)) PhormiKeyboardEmoji.recordRecent(this, emoji)
                }.apply { textSize = 22f; contentDescription = "Insert $emoji" },
                    LinearLayout.LayoutParams(0, scaled(44), 1f).apply { setMargins(dp(1), dp(1), dp(1), dp(1)) })
            }
            repeat(8 - group.size) { row.addView(View(this), LinearLayout.LayoutParams(0, scaled(44), 1f)) }
            grid.addView(row, LinearLayout.LayoutParams(-1, scaled(46)))
        }
        scroll.addView(grid)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        addEmojiModeNav(root)
        return root
    }
    private fun addEmojiModeNav(root:LinearLayout){val nav=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER;weightSum=3f};fun addMode(label:String,target:KeyboardPage){nav.addView(pill(label){panel=Panel.KEYBOARD;page=target;setInputView(render())},LinearLayout.LayoutParams(0,scaled(40),1f).apply{setMargins(dp(2),dp(2),dp(2),dp(2))})};addMode("ABC",KeyboardPage.LETTERS);addMode("123",KeyboardPage.NUMBERS);addMode("Symbols",KeyboardPage.SYMBOLS);root.addView(nav,LinearLayout.LayoutParams(-1,scaled(44)))}
    private fun buildClipboard():View{
        val root=root()
        addBackHeader(root,"← Keyboard"){panel=Panel.KEYBOARD;setInputView(render())}
        val list=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(8),dp(4),dp(8),dp(4))}
        list.addView(pill(if (confirmClearClipboard) "Cancel clearing history" else "Clear all clipboard history") {
            confirmClearClipboard = !confirmClearClipboard
            setInputView(render())
        }, LinearLayout.LayoutParams(-1, scaled(38)).apply { bottomMargin = dp(5) })
        if (confirmClearClipboard) {
            list.addView(TextView(this).apply {
                text = "Delete saved clipboard history? This cannot be undone. The system clipboard stays unchanged."
                textSize = 12f; setTextColor(themeText()); setPadding(dp(6), dp(4), dp(6), dp(4))
            })
            list.addView(pill("Delete all saved items") {
                PhormiKeyboardClipboardStore.clear(this)
                confirmClearClipboard = false
                setInputView(render())
            }, LinearLayout.LayoutParams(-1, scaled(38)))
        }
        val scroll=ScrollView(this)
        val items=PhormiKeyboardClipboardStore.list(this)
        if(items.isEmpty()) list.addView(TextView(this).apply{text="Your copied text, screenshots and images will appear here.";setTextColor(themeText());textSize=14f;setPadding(dp(12),dp(18),dp(12),dp(18))})
        items.forEach{item->
            val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
            val label=item.text.take(60).ifBlank{"Copied item"}
            val clip=pill(label){if(item.text.isNotBlank())commitTextToEditor(item.text)else item.uri?.let{commitContentToEditor(Uri.parse(it))}}
            val delete=pill("Delete") { PhormiKeyboardClipboardStore.remove(this,item);setInputView(render()) }
            delete.contentDescription = "Delete clipboard item: $label"
            row.addView(clip,LinearLayout.LayoutParams(0,scaled(44),1f).apply{setMargins(0,dp(2),dp(3),dp(2))})
            row.addView(delete,LinearLayout.LayoutParams(dp(72),scaled(44)).apply{setMargins(dp(3),dp(2),0,dp(2))})
            list.addView(row)
        }
        scroll.addView(list)
        root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        return root
    }
    private fun insertAiEmojiExpression() {
        if (!PhormiKeyboardTextEngine.allowsAiEmoji(editorInfo)) return
        val text = PhormiKeyboardTextEngine.contextBeforeCursor(currentInputConnection)
        if (text.isBlank()) { openAiEmojiFromContext(); return }
        val emoji = PhormiKeyboardAiContext.suggestions(text).first().emoji
        commitTextToEditor(emoji)
        PhormiKeyboardEmoji.recordRecent(this, emoji)
        refreshPredictionStrip()
    }

    private fun openAiEmojiFromContext(){
        aiEmojiContext=PhormiKeyboardTextEngine.contextBeforeCursor(currentInputConnection).trim()
        panel=Panel.AI_EMOJI
        setInputView(render())
    }

    private fun buildAiEmoji(): View {
        if (!PhormiKeyboardTextEngine.allowsAiEmoji(editorInfo)) {
            panel = Panel.EMOJI
            return buildEmoji()
        }
        val root = root()
        addBackHeader(root, "← Emoji") { panel = Panel.EMOJI; setInputView(render()) }
        val scroll = ScrollView(this).apply { overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS }
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(12))
        }
        list.addView(TextView(this).apply {
            text = "Emoji for what you're saying"
            textSize = 17f; typeface = Typeface.DEFAULT_BOLD; setTextColor(themeText())
        })
        list.addView(TextView(this).apply {
            text = "On-device context suggestions. Tap to insert real Unicode emoji characters, not an image."
            textSize = 12f
            setTextColor(if (theme() == 3) Color.DKGRAY else Color.rgb(148, 163, 184))
            setPadding(0, dp(4), 0, dp(8))
        })
        val context = aiEmojiContext.ifBlank { PhormiKeyboardTextEngine.contextBeforeCursor(currentInputConnection) }
        list.addView(TextView(this).apply {
            text = if (context.isBlank()) "Start typing for tailored emoji, or pick one below."
                else "Context: ${context.takeLast(120)}"
            textSize = 12f; setTextColor(themeText()); setPadding(dp(8), dp(8), dp(8), dp(8))
            background = rounded(themePill(), dp(10))
        }, LinearLayout.LayoutParams(-1, -2))
        list.addView(pill("↻  Update from current text") {
            aiEmojiContext = PhormiKeyboardTextEngine.contextBeforeCursor(currentInputConnection)
            setInputView(render())
        }, LinearLayout.LayoutParams(-1, scaled(38)).apply { topMargin = dp(6) })
        PhormiKeyboardAiContext.suggestions(context).forEach { mood ->
            list.addView(pill("${mood.emoji}   ${mood.label}") {
                commitTextToEditor(mood.emoji)
                PhormiKeyboardEmoji.recordRecent(this, mood.emoji)
                panel = Panel.KEYBOARD
                setInputView(render())
            }, LinearLayout.LayoutParams(-1, scaled(44)).apply { topMargin = dp(3) })
        }
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        return root
    }
    private fun buildMedia():View{val root=root();addBackHeader(root,"← Keyboard"){panel=Panel.KEYBOARD;setInputView(render())};val scroll=ScrollView(this);val list=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(8),dp(8),dp(8),dp(8))};list.addView(pill("Import GIF / Image"){launchMedia("gif")},LinearLayout.LayoutParams(-1,scaled(46)));list.addView(pill("Import Sticker"){launchMedia("sticker")},LinearLayout.LayoutParams(-1,scaled(46)));list.addView(pill("Create AI Sticker (image)"){
            startActivity(Intent(this@PhormiKeyboardServiceV2, PhormiAiEmojiActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        },LinearLayout.LayoutParams(-1,scaled(46)));list.addView(TextView(this).apply{text="Images/GIFs are inserted only when the focused field accepts the requested MIME type.";setTextColor(themeText());textSize=13f;setPadding(dp(8),dp(12),dp(8),dp(12))});scroll.addView(list);root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f));return root}
    private fun buildTools():View{val root=root();addBackHeader(root,"← Keyboard"){panel=Panel.KEYBOARD;setInputView(render())};val scroll=ScrollView(this);val list=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(8),dp(8),dp(8),dp(8))};list.addView(pill("Settings"){panel=Panel.SETTINGS;setInputView(render())},LinearLayout.LayoutParams(-1,scaled(46)));addResizeControls(list);list.addView(pill("GIFs & stickers"){panel=Panel.MEDIA;setInputView(render())},LinearLayout.LayoutParams(-1,scaled(46)));list.addView(pill("Voice typing"){launchVoice()},LinearLayout.LayoutParams(-1,scaled(46)));list.addView(pill("Select all"){currentInputConnection?.performContextMenuAction(android.R.id.selectAll)},LinearLayout.LayoutParams(-1,scaled(46)));list.addView(pill("Copy"){currentInputConnection?.performContextMenuAction(android.R.id.copy)},LinearLayout.LayoutParams(-1,scaled(46)));list.addView(pill("Paste"){currentInputConnection?.performContextMenuAction(android.R.id.paste)},LinearLayout.LayoutParams(-1,scaled(46)));list.addView(pill("Share text"){shareSelectedText()},LinearLayout.LayoutParams(-1,scaled(46)));scroll.addView(list);root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f));return root}
    private fun buildSettings():View{val root=root();addBackHeader(root,"← Tools"){panel=Panel.TOOLS;setInputView(render())};val scroll=ScrollView(this).apply{overScrollMode=View.OVER_SCROLL_IF_CONTENT_SCROLLS};val list=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(10),dp(6),dp(10),dp(8))};list.addView(TextView(this).apply{text="Keyboard size";textSize=15f;setTextColor(themeText());setPadding(0,dp(4),0,dp(2))});list.addView(TextView(this).apply{text="Use Floating or Resize keyboard in Cabinet. Resize shows a Gboard-style frame with draggable corners and edge handles; the center nub moves a floating keyboard.";textSize=12f;setTextColor(themeText().let{if(theme()==3)Color.DKGRAY else Color.rgb(148,163,184)});setPadding(0,0,0,dp(6))});list.addView(pill("Reset keyboard size"){PhormiKeyboardPreferences.resetSize(this@PhormiKeyboardServiceV2);setInputView(render())},LinearLayout.LayoutParams(-1,scaled(36)).apply{setMargins(0,dp(4),0,dp(8))});fun toggle(title:String,enabled:Boolean,action:()->Unit){list.addView(pill(if(enabled)"✓ $title"else title,action),LinearLayout.LayoutParams(-1,scaled(36)).apply{setMargins(0,dp(3),0,dp(3))})};toggle("Suggestions",PhormiKeyboardPreferences.suggestions(this)){PhormiKeyboardPreferences.set(this@PhormiKeyboardServiceV2,PhormiKeyboardPreferences.KEY_SUGGESTIONS,!PhormiKeyboardPreferences.suggestions(this));setInputView(render())};toggle("Autocorrect",PhormiKeyboardPreferences.autocorrect(this)){PhormiKeyboardPreferences.set(this@PhormiKeyboardServiceV2,PhormiKeyboardPreferences.KEY_AUTOCORRECT,!PhormiKeyboardPreferences.autocorrect(this));setInputView(render())};list.addView(pill("Dictionary: offline + optional online") {
            startActivity(Intent(this, PhormiKeyboardSettingsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }, LinearLayout.LayoutParams(-1,scaled(36)).apply { setMargins(0,dp(3),0,dp(3)) });toggle("Auto-capitalization",PhormiKeyboardPreferences.autoCaps(this)){PhormiKeyboardPreferences.set(this@PhormiKeyboardServiceV2,PhormiKeyboardPreferences.KEY_AUTO_CAPS,!PhormiKeyboardPreferences.autoCaps(this));setInputView(render())};toggle("Haptic feedback",PhormiKeyboardPreferences.haptic(this)){PhormiKeyboardPreferences.set(this@PhormiKeyboardServiceV2,PhormiKeyboardPreferences.KEY_HAPTIC,!PhormiKeyboardPreferences.haptic(this));setInputView(render())};toggle("Key sounds",PhormiKeyboardPreferences.sound(this)){PhormiKeyboardPreferences.set(this@PhormiKeyboardServiceV2,PhormiKeyboardPreferences.KEY_SOUND,!PhormiKeyboardPreferences.sound(this));setInputView(render())};list.addView(pill("Theme: ${themeLabel(theme())}"){
            PhormiKeyboardPreferences.setTheme(this@PhormiKeyboardServiceV2, (theme() + 1) % 8)
            setInputView(render())
        },LinearLayout.LayoutParams(-1,scaled(36)).apply{setMargins(0,dp(3),0,dp(3))})
        list.addView(pill(if(PhormiKeyboardPreferences.wallpaperUri(this).isNullOrBlank())"Add wallpaper"else"Change wallpaper"){
            startActivity(Intent(this@PhormiKeyboardServiceV2, PhormiKeyboardMediaActivity::class.java).apply{addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);putExtra(PhormiKeyboardMediaActivity.EXTRA_MODE,"wallpaper")})
        },LinearLayout.LayoutParams(-1,scaled(36)).apply{setMargins(0,dp(3),0,dp(3))})
        if (!PhormiKeyboardPreferences.wallpaperUri(this).isNullOrBlank()) list.addView(pill("Remove wallpaper"){PhormiKeyboardPreferences.setWallpaperUri(this@PhormiKeyboardServiceV2,null);setInputView(render())},LinearLayout.LayoutParams(-1,scaled(36)).apply{setMargins(0,dp(3),0,dp(3))})
        list.addView(pill(if(PhormiKeyboardPreferences.pollinationsKey(this).isBlank())"Set sticker generation key"else"Sticker generation key ✓"){startActivity(Intent(this,PhormiAiEmojiActivity::class.java).apply{addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);putExtra(PhormiAiEmojiActivity.EXTRA_CONFIG_ONLY,true)})},LinearLayout.LayoutParams(-1,scaled(36)).apply{setMargins(0,dp(3),0,dp(3))});scroll.addView(list);root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f));return root}
    private fun themeLabel(value: Int): String = listOf("Midnight","Graphite","Ocean","Light","Slate","Forest","Berry","Sand").getOrElse(value) { "Midnight" }
    private fun addBackHeader(root:LinearLayout,label:String,action:()->Unit){root.addView(pill(label,action),LinearLayout.LayoutParams(-1,scaled(36)))}
    private fun render():View{
        shiftKeyView = null
        renderedShiftState = -1
        val view=when(panel){
            Panel.EMOJI->buildEmoji()
            Panel.CLIPBOARD->buildClipboard()
            Panel.AI_EMOJI->buildAiEmoji()
            Panel.TOOLS->buildTools()
            Panel.MEDIA->buildMedia()
            Panel.SETTINGS->buildSettings()
            Panel.KEYBOARD->buildKeyboard()
        }
        val finalView = if (panel == Panel.KEYBOARD && (keyboardResizeMode || PhormiKeyboardPreferences.floating(this))) {
            FrameLayout(this).apply {
                setBackgroundColor(Color.TRANSPARENT)
                addView(view, FrameLayout.LayoutParams(-1, -1))
                addView(
                    PhormiKeyboardResizeOverlay(
                        this@PhormiKeyboardServiceV2,
                        allowMove = PhormiKeyboardPreferences.floating(this@PhormiKeyboardServiceV2),
                        allowResize = keyboardResizeMode,
                        onChange = { width, height, x, y ->
                            PhormiKeyboardPreferences.setWidthScale(this@PhormiKeyboardServiceV2, width)
                            PhormiKeyboardPreferences.setHeightScale(this@PhormiKeyboardServiceV2, height)
                            if (PhormiKeyboardPreferences.floating(this@PhormiKeyboardServiceV2)) {
                                PhormiKeyboardPreferences.setOffsetX(this@PhormiKeyboardServiceV2, x)
                                PhormiKeyboardPreferences.setOffsetY(this@PhormiKeyboardServiceV2, y)
                            }
                            applyKeyboardWindowSize()
                        },
                        onDone = {
                            keyboardResizeMode = false
                            setInputView(render())
                        }
                    ),
                    FrameLayout.LayoutParams(-1, -1)
                )
            }
        } else view
        finalView.post { applyKeyboardWindowSize() }
        return finalView
    }
    private fun refreshAfterTextKey() {
        if (panel == Panel.KEYBOARD && page == KeyboardPage.LETTERS) {
            updateShiftKeyAppearance()
            schedulePredictionRefresh()
        }
    }
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
    private fun commitTextToEditor(text: String) {
        val ic = currentInputConnection ?: return
        if (text in setOf(".", ",", "!", "?", ";", ":")) autoCorrectCurrentWord(ic)
        ic.commitText(text, 1)
        if (PhormiKeyboardPreferences.autoCaps(this) && text.any { it in charArrayOf('.', '!', '?', ':', ';', '\n') }) {
            autoShift = PhormiKeyboardTextEngine.autoCapitalize(ic, editorInfo)
        }
    }

    /** Apply corrections on separators without a prompt; never in protected/address fields. */
    private fun autoCorrectCurrentWord(ic: InputConnection) {
        if (!PhormiKeyboardPreferences.autocorrect(this) ||
            !PhormiKeyboardTextEngine.shouldUsePredictions(editorInfo)) return
        val current = PhormiKeyboardTextEngine.currentWord(ic)
        if (current.isBlank()) return
        val locale = PhormiKeyboardTextEngine.localeFor(editorInfo)
        if (lastAutoCorrection?.original?.equals(current, true) == true) {
            PhormiKeyboardTextEngine.ignoreCorrection(this, current, locale)
            lastAutoCorrection = null
            return
        }
        val correction = PhormiKeyboardTextEngine.correctionFor(this, current, locale) ?: return
        ic.beginBatchEdit()
        try {
            ic.finishComposingText()
            ic.deleteSurroundingText(current.length, 0)
            ic.commitText(correction, 1)
        } finally {
            ic.endBatchEdit()
        }
        lastAutoCorrection = AutoCorrection(current, correction, System.currentTimeMillis())
    }

    private fun commitSpace(applyCorrection: Boolean = true) {
        val ic = currentInputConnection ?: return
        if (applyCorrection) autoCorrectCurrentWord(ic)
        val locale = PhormiKeyboardTextEngine.localeFor(editorInfo)
        val previous = PhormiKeyboardTextEngine.previousWord(ic, locale)
        val word = PhormiKeyboardTextEngine.currentWord(ic)
        if (word.isNotBlank()) PhormiKeyboardTextEngine.learn(this, word, editorInfo)
        ic.commitText(" ", 1)
        if (previous.isNotBlank() && word.isNotBlank())
            PhormiKeyboardTextEngine.learnPair(this, previous, word, editorInfo)
        if (PhormiKeyboardPreferences.autoCaps(this))
            autoShift = PhormiKeyboardTextEngine.autoCapitalize(ic, editorInfo)
        refreshAfterTextKey()
    }

    private fun deleteBackward() {
        val ic = currentInputConnection ?: return
        val selected = ic.getSelectedText(0)?.toString()
        if (!selected.isNullOrEmpty()) { ic.commitText("", 1); return }
        val correction = lastAutoCorrection
        if (correction != null && System.currentTimeMillis() - correction.time < 15_000L &&
            ic.getTextBeforeCursor(correction.corrected.length + 1, 0)?.toString()
                ?.endsWith(correction.corrected + " ") == true) {
            ic.beginBatchEdit()
            try {
                ic.deleteSurroundingText(correction.corrected.length + 1, 0)
                ic.commitText(correction.original, 1)
            } finally { ic.endBatchEdit() }
            PhormiKeyboardTextEngine.ignoreCorrection(this, correction.original,
                PhormiKeyboardTextEngine.localeFor(editorInfo))
            lastAutoCorrection = null
        } else ic.deleteSurroundingText(1, 0)
    }
    private fun moveCursor(delta:Int){val ic=currentInputConnection?:return;val code=if(delta<0)KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT;ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN,code));ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP,code));refreshAfterTextKey()}
    private fun toggleShift() {
        when {
            capsLock -> { capsLock = false; shift = false; autoShift = false }
            shift -> { capsLock = true; shift = false; autoShift = false }
            // Automatic sentence capitalization is a suggested one-letter shift, not
            // a deliberate first tap. Tapping it turns capitalization off, not lock on.
            autoShift -> autoShift = false
            else -> shift = true
        }
    }
    private fun actionLabel():String=if(editorInfo?.let{it.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0}==true)"↵" else when(editorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION)){EditorInfo.IME_ACTION_GO->"Go";EditorInfo.IME_ACTION_SEARCH->"Search";EditorInfo.IME_ACTION_SEND->"Send";EditorInfo.IME_ACTION_NEXT->"Next";EditorInfo.IME_ACTION_DONE->"Done";else->"Enter"}
    private fun sendEditorAction() {
        val ic = currentInputConnection ?: return
        autoCorrectCurrentWord(ic)
        if (editorInfo?.let { it.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0 } == true) {
            ic.commitText("\n", 1); refreshAfterTextKey(); return
        }
        val action = editorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_NONE
        if (action != EditorInfo.IME_ACTION_NONE) ic.performEditorAction(action)
        else {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        }
    }
    private fun installRepeat(view:View,action:()->Unit){view.setOnTouchListener{_,event->when(event.actionMasked){MotionEvent.ACTION_DOWN->{action();repeatRunnable=object:Runnable{override fun run(){action();repeatHandler.postDelayed(this,55)}};repeatHandler.postDelayed(repeatRunnable!!,320);true};MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->{stopRepeat();true};else->true}}}
    private fun stopRepeat(){repeatRunnable?.let{repeatHandler.removeCallbacks(it)};repeatRunnable=null}
    private fun commitContentToEditor(uri:Uri):Boolean{if(Build.VERSION.SDK_INT<25)return false;val ic=currentInputConnection?:return false;val info=editorInfo?:return false;val mimeTypes=info.contentMimeTypes?:emptyArray();if(mimeTypes.isEmpty()){android.widget.Toast.makeText(this,"This field does not accept images",android.widget.Toast.LENGTH_SHORT).show();return false};val wanted=contentResolver.getType(uri).orEmpty();val accepted=wanted.isNotBlank()&&mimeTypes.any{it==wanted||(it.endsWith("/*")&&wanted.startsWith(it.removeSuffix("*")))};if(!accepted){android.widget.Toast.makeText(this,"This field does not accept that media type",android.widget.Toast.LENGTH_SHORT).show();return false};return runCatching{val description=android.content.ClipDescription("Phormi media",arrayOf(wanted));val contentInfo=InputContentInfo(uri,description,null);ic.commitContent(contentInfo,InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION,null)}.getOrDefault(false)}
}
