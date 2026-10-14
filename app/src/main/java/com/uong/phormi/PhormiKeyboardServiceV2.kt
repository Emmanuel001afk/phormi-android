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
import android.view.inputmethod.InputMethodSubtype
import android.widget.PopupWindow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CancellationException
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
        const val EXTRA_EDITOR_TARGET = "keyboard_editor_target"
        private var instance: PhormiKeyboardServiceV2? = null
        internal val activeInstance: PhormiKeyboardServiceV2? get() = instance
        fun commitPickedContent(context: Context, uri: Uri, expectedEditor: String? = null): Boolean {
            if (expectedEditor != null && expectedEditor.isBlank()) return false
            val service = instance ?: return false
            if (service.canCommitExternal() && (expectedEditor == null || service.currentEditorKey() == expectedEditor)) return service.commitContentToEditor(uri)
            service.queueExternal(KEY_PENDING_URI, uri.toString(), expectedEditor ?: service.lastEditorKey)
            return false
        }
        fun startIntegratedVoiceRecognitionFromContext(context: Context, localeTag: String, editorKey: String) {
            instance?.let { it.pendingVoiceLocale = localeTag; it.pendingVoiceEditor = editorKey }
        }
        fun commitExternalText(context: Context, text: String, expectedEditor: String? = null): Boolean {
            if (text.isBlank()) return false
            val service = instance ?: return false
            if (service.canCommitExternal() && (expectedEditor == null || service.currentEditorKey() == expectedEditor)) { service.commitTextToEditor(text); return true }
            service.queueExternal(KEY_PENDING_TEXT, text.take(4000), expectedEditor ?: service.lastEditorKey)
            return false
        }
    }
    enum class Panel { KEYBOARD, EMOJI, CLIPBOARD, AI_EMOJI, TOOLS, MEDIA, SETTINGS, WRITING, VOICE }
    private enum class KeyboardPage { LETTERS, NUMBERS, SYMBOLS }
    private var panel = Panel.KEYBOARD
    private var page = KeyboardPage.LETTERS
    private var shift = false
    private var capsLock = false
    private var autoShift = false
    private var emojiCategory = 0
    private var emojiQuery = ""
    private var emojiSearchActive = false
    private var emojiSearchLabel: TextView? = null
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
    private var keyboardResizeMode = false
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
    internal var activeSubtype: InputMethodSubtype? = null
        private set
    private var editorGeneration = 0L
    private var lastEditorKey = ""
    private var pendingExternalUri: String? = null
    private var pendingExternalText: String? = null
    private var pendingVoiceLocale: String? = null
    private var pendingVoiceEditor: String? = null
    private val letterButtons = mutableListOf<Pair<Button, String>>()
    private var shiftView: Button? = null
    private data class CorrectionUndo(val original: String, val replacement: String, val before: String)
    private var correctionUndo: CorrectionUndo? = null
    private var keepNextSpelling: String? = null
    private var alternativesPopup: PopupWindow? = null
    private val writingScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var writingJob: Job? = null
    private data class WritingCapture(val generation: Long, val editor: String, val connection: InputConnection,
        val source: String, val selected: Boolean, val start: Int, val end: Int)
    private var writingCapture: WritingCapture? = null
    private var writingResult = ""
    private var writingStatus = "Choose an edit. Text is sent only when you tap an action."
    private var writingBusy = false
    private val writingAi by lazy { AiController(applicationContext) }
    private var selectionStart = -1
    private var selectionEnd = -1
    private var voiceStatus = "Ready to listen"
    private var voicePartial = ""
    private var voiceListening = false

    override fun onCreate() {
        super.onCreate(); instance = this
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
            if (isInputViewShown && PhormiKeyboardTextEngine.allowsPersonalizedLearning(editorInfo))
                PhormiKeyboardClipboardStore.capturePrimaryClipboard(this)
        }
        clipboardListener?.let { cm?.addPrimaryClipChangedListener(it) }
    }
    override fun onDestroy() {
        stopVoice(true)
        writingScope.cancel()
        PhormiKeyboardSystemSpellChecker.close()
        alternativesPopup?.dismiss()
        stopRepeat(); aiEmojiRunnable?.let { repeatHandler.removeCallbacks(it) }; aiEmojiRunnable = null
        predictionRefreshRunnable?.let { repeatHandler.removeCallbacks(it) }; predictionRefreshRunnable = null
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboardListener?.let { cm?.removePrimaryClipChangedListener(it) }; clipboardListener = null
        if (instance === this) instance = null; super.onDestroy()
    }
    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        stopVoice(true)
        writingJob?.cancel(); writingJob = null
        editorGeneration++
        emojiSearchActive = false; emojiQuery = ""
        pendingExternalUri = null; pendingExternalText = null
        correctionUndo = null; keepNextSpelling = null
        writingCapture = null; writingResult = ""; writingBusy = false
        selectionStart = attribute?.initialSelStart ?: -1; selectionEnd = attribute?.initialSelEnd ?: -1
        super.onStartInput(attribute, restarting); editorInfo = attribute; page = KeyboardPage.LETTERS; capsLock = false; shift = false
        lastEditorKey = currentEditorKey()
        activeSubtype = (getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)?.currentInputMethodSubtype
        autoShift = PhormiKeyboardPreferences.autoCaps(this) && PhormiKeyboardTextEngine.autoCapitalize(currentInputConnection, attribute); panel = Panel.KEYBOARD
    }
    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        editorInfo = info ?: editorInfo
        panel = Panel.KEYBOARD
        // Keep the IME window transition-free from the moment Android starts
        // showing it. This prevents a stale/miniature copy from appearing
        // during the show/hide handoff.
        runCatching {
            getWindow().window?.setWindowAnimations(0)
            getWindow().window?.decorView?.animate()?.cancel()
        }
        setInputView(render())
        applyKeyboardWindowSize()
        applyPendingInput()
        val voiceLocale = pendingVoiceLocale
        if (voiceLocale != null && pendingVoiceEditor == currentEditorKey()) {
            pendingVoiceLocale = null; pendingVoiceEditor = null
            repeatHandler.postDelayed({ if (isInputViewShown) startIntegratedVoiceRecognition(voiceLocale) }, 180L)
        } else { pendingVoiceLocale = null; pendingVoiceEditor = null }
    }

    override fun onCurrentInputMethodSubtypeChanged(newSubtype: InputMethodSubtype?) {
        super.onCurrentInputMethodSubtypeChanged(newSubtype)
        activeSubtype = newSubtype
        PhormiKeyboardSystemSpellChecker.close()
        lastPredictionWord = ""; lastPredictionPrevious = ""
        if (isInputViewShown) setInputView(render())
    }

    override fun onUpdateSelection(oldSelStart:Int, oldSelEnd:Int, newSelStart:Int, newSelEnd:Int, candidatesStart:Int, candidatesEnd:Int) {
        super.onUpdateSelection(oldSelStart,oldSelEnd,newSelStart,newSelEnd,candidatesStart,candidatesEnd)
        selectionStart = newSelStart; selectionEnd = newSelEnd
        if(panel==Panel.KEYBOARD && page==KeyboardPage.LETTERS){
            schedulePredictionRefresh()
            if(PhormiKeyboardPreferences.autoCaps(this)){
                autoShift=PhormiKeyboardTextEngine.autoCapitalize(currentInputConnection,editorInfo)
                updateLetterCase()
            }
        }
    }

    override fun onFinishInput() {
        stopRepeat(); stopVoice(true); writingJob?.cancel(); editorGeneration++
        editorInfo = null; correctionUndo = null
        writingCapture = null; writingResult = ""
        super.onFinishInput()
    }
    override fun onUnbindInput() {
        stopRepeat(); stopVoice(true); writingJob?.cancel(); editorGeneration++
        editorInfo = null; correctionUndo = null
        super.onUnbindInput()
    }
    override fun onFinishInputView(finishingInput: Boolean) {
        stopRepeat()
        stopVoice(true)
        writingJob?.cancel()
        alternativesPopup?.dismiss()
        predictionRefreshRunnable?.let(repeatHandler::removeCallbacks)
        predictionRefreshRunnable = null
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
        stopVoice(true)
        writingJob?.cancel()
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
        val height = viewportHeight()
        val available = getMaxWidth().coerceAtLeast(1)
        val width = (available * PhormiKeyboardPreferences.widthScale(this)).roundToInt()
            .coerceIn(minOf(dp(220), available), available)

        val floating = PhormiKeyboardPreferences.floating(this)
        val resizeMode = keyboardResizeMode
        val attrs = window.attributes
        // A floating/resizeable keyboard must not participate in the app's IME inset.
        // Otherwise Android moves/resizes the page underneath it, which is unlike a
        // floating Gboard window and makes dragging/resizing feel attached to the page.
        window.setSoftInputMode(
            if (floating || resizeMode) WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            else WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )
        // Disable the IME window transition animation so dismissing it does not briefly
        // flash a miniature/preview copy before the window disappears.
        runCatching {
            window.setWindowAnimations(0)
            window.decorView.animate().cancel()
        }
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
        window.attributes = attrs
        window.setLayout(width, height)
    }

    private fun density(): Float = resources.displayMetrics.density
    private fun dp(value: Int): Int = (value * density()).roundToInt().coerceAtLeast(1)
    private fun baseHeight(): Int = 312
    private fun viewportHeight(): Int {
        val requested = dp(baseHeight()) * PhormiKeyboardPreferences.heightScale(this)
        val maximum = (resources.displayMetrics.heightPixels * 0.68f).roundToInt().coerceAtLeast(dp(160))
        return requested.roundToInt().coerceIn(dp(160), maximum)
    }
    private fun scale(): Float = (viewportHeight().toFloat() / dp(baseHeight())).coerceIn(0.5f, 1.4f)
    private fun scaled(value: Int): Int = dp((value * scale()).roundToInt())
    private fun theme(): Int = PhormiKeyboardPreferences.theme(this)
    private fun themeBackground(): Int = when (theme()) { 1 -> Color.rgb(20,24,29); 2 -> Color.rgb(7,24,42); 3 -> Color.rgb(242,244,247); 4 -> Color.rgb(38,45,58); 5 -> Color.rgb(17,55,42); 6 -> Color.rgb(61,28,48); 7 -> Color.rgb(69,54,38); else -> Color.rgb(13,18,30) }
    private fun themeKey(): Int = when (theme()) { 1 -> Color.rgb(48,53,61); 2 -> Color.rgb(18,52,79); 3 -> Color.WHITE; 4 -> Color.rgb(55,65,81); 5 -> Color.rgb(26,78,58); 6 -> Color.rgb(86,38,64); 7 -> Color.rgb(92,70,48); else -> Color.rgb(39,48,64) }
    private fun themePill(): Int = when (theme()) { 1 -> Color.rgb(37,42,49); 2 -> Color.rgb(15,45,69); 3 -> Color.rgb(224,228,234); 4 -> Color.rgb(47,58,73); 5 -> Color.rgb(20,63,48); 6 -> Color.rgb(70,31,53); 7 -> Color.rgb(78,59,41); else -> Color.rgb(31,41,55) }
    private fun themeText(): Int = if (theme() == 3) Color.rgb(20,27,36) else Color.WHITE
    private fun accent(): Int = PhormiAppearance.accent(this, theme() != 3)
    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.toFloat()
        setStroke(dp(1), androidx.core.graphics.ColorUtils.blendARGB(themeKey(), accent(), if (theme() == 3) 0.22f else 0.28f))
    }
    private fun root(): LinearLayout = LinearLayout(this).apply {
        orientation=LinearLayout.VERTICAL
        setPadding(dp(7),dp(1),dp(7),dp(1))
        setBackgroundColor(themeBackground())
        val height = viewportHeight()
        layoutParams=LinearLayout.LayoutParams(-1,height).apply{gravity=Gravity.CENTER_HORIZONTAL}
        minimumHeight=height
        applyWallpaper(this)
    }
    private fun applyWallpaper(root: View) {
        val base = android.graphics.drawable.ColorDrawable(themeBackground())
        val art = PhormiWallpapers.drawable(PhormiWallpapers.selected(this, true), theme() != 3)?.apply { alpha = 75 }
        root.background = if (art == null) base else android.graphics.drawable.LayerDrawable(arrayOf(base, art))
        val uri = PhormiKeyboardPreferences.wallpaperUri(this) ?: return
        val baseColor = themeBackground()
        PhormiWallpaperLoader.load(root, uri) { view, bitmap ->
            val photo = BitmapDrawable(resources, bitmap).apply { alpha = 75 }
            view.background = android.graphics.drawable.LayerDrawable(arrayOf(android.graphics.drawable.ColorDrawable(baseColor), photo))
        }
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
            PhormiKeyboardClipboardStore.capturePrimaryClipboard(this)
            setInputView(render())
        }
        if (PhormiKeyboardTextEngine.shouldUsePredictions(editorInfo)) {
            add("Write", 60) { openWritingAssistant() }
        }
        if (editorInfo != null && !PhormiKeyboardTextEngine.isPrivateEditor(editorInfo)) {
            add("🎙", 50) { launchVoice() }
        }
        add("▦ Tools", 76) { panel = Panel.TOOLS; setInputView(render()) }
        if (Build.VERSION.SDK_INT >= 28 && shouldOfferSwitchingToNextInputMethod()) {
            add("🌐", 52) { runCatching { switchToNextInputMethod(false) } }
        }
        scroll.addView(row)
        root.addView(scroll, LinearLayout.LayoutParams(-1, scaled(36)))
    }
    private fun predictionStrip(root:LinearLayout){
        val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
        predictionRow=row
        lastPredictionWord = ""; lastPredictionPrevious = ""
        root.addView(row,LinearLayout.LayoutParams(-1,scaled(30)))
        refreshPredictionStrip()
    }
    private fun schedulePredictionRefresh() {
        predictionRefreshRunnable?.let { repeatHandler.removeCallbacks(it) }
        val task = Runnable { refreshPredictionStrip() }
        predictionRefreshRunnable = task
        repeatHandler.postDelayed(task, 70L)
    }

    internal fun onSpellcheckUpdated(key: String) {
        val locale = PhormiKeyboardTextEngine.localeFor(editorInfo)
        if (isInputViewShown && key == PhormiKeyboardSystemSpellChecker.key(PhormiKeyboardTextEngine.currentWord(currentInputConnection), locale)) refreshPredictionStrip(true)
    }

    private fun refreshPredictionStrip(force: Boolean = false) {
        if (emojiSearchActive) { refreshEmojiSearch(); return }
        val row = predictionRow ?: return
        val ic = currentInputConnection
        val word = PhormiKeyboardTextEngine.currentWord(ic)
        val locale = PhormiKeyboardTextEngine.localeFor(editorInfo)
        val previous = PhormiKeyboardTextEngine.previousWord(ic, locale)
        if (!force && word == lastPredictionWord && previous == lastPredictionPrevious && row.childCount > 0) return
        lastPredictionWord = word; lastPredictionPrevious = previous
        row.removeAllViews()
        val allowed = PhormiKeyboardPreferences.suggestions(this) && PhormiKeyboardTextEngine.shouldUsePredictions(editorInfo)
        val suggestions = if (!allowed) emptyList() else if (word.isNotBlank())
            PhormiKeyboardTextEngine.suggestions(this, word, locale)
        else PhormiKeyboardTextEngine.nextWordSuggestions(this, previous, locale)
        suggestions.take(3).forEach { value ->
            val button = pill(value) {
                val connection = currentInputConnection ?: return@pill
                val live = PhormiKeyboardTextEngine.currentWord(connection)
                // A stale suggestion must not replace a different word after cursor movement.
                if (live != word) { refreshPredictionStrip(true); return@pill }
                correctionUndo = null
                connection.beginBatchEdit()
                try {
                    connection.finishComposingText()
                    if (live.isNotBlank()) connection.deleteSurroundingText(live.length, 0)
                    connection.commitText(value + " ", 1)
                } finally { connection.endBatchEdit() }
                if (value.equals(live, true)) PhormiKeyboardTextEngine.rememberLiteralChoice(this, value, editorInfo)
                else {
                    PhormiKeyboardTextEngine.learn(this, value, editorInfo)
                    PhormiKeyboardTextEngine.learnCorrection(this, live, value, editorInfo)
                }
                PhormiKeyboardTextEngine.learnPair(this, previous, value, editorInfo)
                refreshAfterTextKey()
            }
            button.setOnLongClickListener {
                val removed = PhormiKeyboardTextEngine.forgetPersonalizedSuggestion(this, value, previous, locale)
                android.widget.Toast.makeText(this, if (removed) "Removed from your personal dictionary" else "Built-in word; no personal entry to remove", android.widget.Toast.LENGTH_SHORT).show()
                refreshPredictionStrip(true)
                true
            }
            button.contentDescription = if (value == word) "Keep spelling $value" else "Insert suggestion $value"
            row.addView(button, LinearLayout.LayoutParams(0, scaled(34), 1f).apply { setMargins(dp(2), 0, dp(2), 0) })
        }
        row.visibility = if (suggestions.isEmpty()) View.GONE else View.VISIBLE
    }
    private fun buildKeyboard(): View {
        val root = root()
        addPendingInsert(root)
        if (emojiSearchActive) {
            val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            emojiSearchLabel = TextView(this).apply { text = "Search emoji: $emojiQuery"; setTextColor(themeText()); gravity = Gravity.CENTER_VERTICAL; textSize = 14f }
            header.addView(emojiSearchLabel, LinearLayout.LayoutParams(0, scaled(36), 1f))
            header.addView(pill("Results") { emojiSearchActive = false; panel = Panel.EMOJI; setInputView(render()) }, LinearLayout.LayoutParams(dp(74), scaled(36)))
            root.addView(header)
        }
        val type = if (emojiSearchActive) InputType.TYPE_CLASS_TEXT else editorInfo?.inputType ?: InputType.TYPE_CLASS_TEXT
        val clazz = type and InputType.TYPE_MASK_CLASS
        val numeric = clazz in setOf(InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_PHONE, InputType.TYPE_CLASS_DATETIME)
        if (numeric) page = KeyboardPage.NUMBERS
        if (page == KeyboardPage.LETTERS) predictionStrip(root)
        if (!emojiSearchActive) toolbar(root)
        val variation = type and InputType.TYPE_MASK_VARIATION
        when {
            numeric -> buildNumberPage(root)
            page == KeyboardPage.LETTERS -> buildLetterPage(root,
                variation in setOf(InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS), variation == InputType.TYPE_TEXT_VARIATION_URI)
            page == KeyboardPage.NUMBERS -> buildNumberPage(root)
            else -> buildSymbolPage(root)
        }
        return root
    }
    private fun buildLetterPage(root:LinearLayout,email:Boolean,uri:Boolean){
        val body=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        responsiveRows(body, PhormiKeyboardLayouts.rows(PhormiKeyboardTextEngine.localeFor(editorInfo)))
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
    private fun buildNumberPage(root: LinearLayout) {
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val type = editorInfo?.inputType ?: InputType.TYPE_CLASS_TEXT
        val clazz = type and InputType.TYPE_MASK_CLASS
        val decimal = type and InputType.TYPE_NUMBER_FLAG_DECIMAL != 0
        val rows = when (clazz) {
            InputType.TYPE_CLASS_PHONE -> listOf("123", "456", "789", "*0#")
            InputType.TYPE_CLASS_DATETIME -> listOf("123", "456", "789", "0:/")
            InputType.TYPE_CLASS_NUMBER -> listOf("123", "456", "789", if (decimal) "0${java.text.DecimalFormatSymbols.getInstance(PhormiKeyboardTextEngine.localeFor(editorInfo)).decimalSeparator}" else "0")
            else -> listOf("123", "456", "789", "0.,")
        }
        responsiveRows(body, rows)
        if (clazz == InputType.TYPE_CLASS_TEXT) body.addView(keyButton("Symbols") { page = KeyboardPage.SYMBOLS; setInputView(render()) }
            .apply { layoutParams = LinearLayout.LayoutParams(-1, scaled(40)) })
        val scroll = ScrollView(this).apply { isFillViewport = false; addView(body) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        addBottomRow(root, KeyboardPage.NUMBERS)
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
                val base = ch.toString()
                row.addView(keyButton(base){
                    val out = if (shift || capsLock || autoShift) base.uppercase(PhormiKeyboardTextEngine.localeFor(editorInfo)) else base
                    commitTextToEditor(out); if (shift && !capsLock) shift = false; autoShift = false; refreshAfterTextKey()
                }.apply {
                    if (ch.isLetter()) {
                        letterButtons.add(this to base)
                        val options = PhormiKeyboardLayouts.alternatives(base, PhormiKeyboardTextEngine.localeFor(editorInfo))
                        if (options.isNotEmpty()) setOnLongClickListener { showAlternatives(this, options); true }
                    }
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
            shiftView = this
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
            weightSum=0f
            clipChildren=true
            clipToPadding=false
        }
        fun add(label:String,weight:Float=1f,action:()->Unit){
            bottom.addView(keyButton(label,weight,action),LinearLayout.LayoutParams(0,scaled(44),weight).apply{
                setMargins(dp(1),dp(1),dp(1),dp(1))
                width=0
            })
        }
        val numericType = if (emojiSearchActive) InputType.TYPE_CLASS_TEXT else (editorInfo?.inputType ?: 0) and InputType.TYPE_MASK_CLASS
        if (numericType in setOf(InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_PHONE, InputType.TYPE_CLASS_DATETIME)) {
            if (numericType == InputType.TYPE_CLASS_PHONE) add("+", 1f) { commitTextToEditor("+") }
            if (numericType == InputType.TYPE_CLASS_DATETIME || (editorInfo?.inputType ?: 0) and InputType.TYPE_NUMBER_FLAG_SIGNED != 0)
                add("−", 1f) { commitTextToEditor("-") }
            val back = keyButton("⌫") { deleteBackward() }
            installRepeat(back) { deleteBackward() }
            bottom.addView(back, LinearLayout.LayoutParams(0, scaled(44), 1.5f))
            add(actionLabel(), 1.5f) { sendEditorAction() }
            root.addView(bottom, LinearLayout.LayoutParams(-1, scaled(44)))
            return
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
    }
    private fun insertEmoji(emoji: String) {
        currentInputConnection?.commitText(emoji, 1)
        correctionUndo = null
        if (!PhormiKeyboardTextEngine.isPrivateEditor(editorInfo)) PhormiEmojiStore.add(this, emoji)
    }
    private fun buildEmoji(): View {
        val root = root()
        val navigation = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val navScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(navigation) }
        fun nav(label: String, click: () -> Unit) { navigation.addView(pill(label, click), LinearLayout.LayoutParams(dp(62), scaled(38)).apply { setMargins(dp(2), 0, dp(2), 0) }) }
        nav("Recent") { emojiCategory = -1; emojiQuery = ""; setInputView(render()) }
        PhormiKeyboardEmoji.categories.keys.forEachIndexed { index, icon -> nav(icon) { emojiCategory = index; emojiQuery = ""; setInputView(render()) } }
        root.addView(navScroll, LinearLayout.LayoutParams(-1, scaled(38)))
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(pill(if (emojiQuery.isBlank()) "Search emoji" else "Search: ${emojiQuery.take(16)}") {
            emojiSearchActive = true; panel = Panel.KEYBOARD; page = KeyboardPage.LETTERS; setInputView(render())
        }, LinearLayout.LayoutParams(0, scaled(38), 2f))
        actions.addView(pill("Tone ${PhormiEmojiStore.tone(this)}") { PhormiEmojiStore.setTone(this, (PhormiEmojiStore.tone(this) + 1) % 6); setInputView(render()) }, LinearLayout.LayoutParams(0, scaled(38), 1f))
        actions.addView(pill("ABC") { emojiQuery = ""; panel = Panel.KEYBOARD; setInputView(render()) }, LinearLayout.LayoutParams(0, scaled(38), 1f))
        root.addView(actions)
        val items = when {
            emojiQuery.isNotBlank() -> PhormiKeyboardEmoji.search(emojiQuery)
            emojiCategory == -1 -> PhormiEmojiStore.recent(this)
            else -> PhormiKeyboardEmoji.categories.values.elementAtOrNull(emojiCategory).orEmpty()
        }
        if (items.isEmpty()) {
            root.addView(TextView(this).apply { text = if (emojiCategory == -1) "Your recently used emoji will appear here." else "No matching emoji. Try smile, heart, food or cat."; setTextColor(themeText()); gravity = Gravity.CENTER; setPadding(dp(14), dp(14), dp(14), dp(14)) }, LinearLayout.LayoutParams(-1, 0, 1f))
        } else {
            val grid = android.widget.GridView(this).apply {
                numColumns = ((getMaxWidth() * PhormiKeyboardPreferences.widthScale(this@PhormiKeyboardServiceV2)).toInt() / dp(46)).coerceIn(4, 12)
                horizontalSpacing = dp(2); verticalSpacing = dp(3); stretchMode = android.widget.GridView.STRETCH_COLUMN_WIDTH
                adapter = object : android.widget.BaseAdapter() {
                    override fun getCount() = items.size
                    override fun getItem(position: Int) = items[position]
                    override fun getItemId(position: Int) = position.toLong()
                    override fun getView(position: Int, reused: View?, parent: android.view.ViewGroup?): View {
                        val base = items[position]
                        val emoji = PhormiKeyboardEmoji.withTone(base, PhormiEmojiStore.tone(this@PhormiKeyboardServiceV2))
                        val button = reused as? Button ?: keyButton(emoji) {}
                        button.text = emoji; button.textSize = 25f
                        button.layoutParams = android.widget.AbsListView.LayoutParams(-1, scaled(46))
                        button.contentDescription = PhormiKeyboardEmoji.description(emoji)
                        button.setOnClickListener { feedback(button); insertEmoji(emoji) }
                        button.setOnLongClickListener {
                            val variants = PhormiKeyboardEmoji.toneVariants(base)
                            if (variants.size > 1) showAlternatives(button, variants, true)
                            variants.size > 1
                        }
                        return button
                    }
                }
            }
            root.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        addEmojiModeNav(root)
        return root
    }
    private fun refreshEmojiSearch() {
        emojiSearchLabel?.text = "Search emoji: ${emojiQuery.ifBlank { "smile, heart, food…" }}"
        val row = predictionRow ?: return
        row.removeAllViews(); row.visibility = View.VISIBLE
        PhormiKeyboardEmoji.search(emojiQuery).take(5).forEach { emoji ->
            row.addView(pill(emoji) { insertEmoji(PhormiKeyboardEmoji.withTone(emoji, PhormiEmojiStore.tone(this))) }, LinearLayout.LayoutParams(0, scaled(34), 1f))
        }
    }
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
                putExtra(EXTRA_EDITOR_TARGET, currentEditorKey())
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
    private fun buildSettings():View{val root=root();addBackHeader(root,"← Tools"){panel=Panel.TOOLS;setInputView(render())};val scroll=ScrollView(this).apply{overScrollMode=View.OVER_SCROLL_IF_CONTENT_SCROLLS};val list=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(10),dp(6),dp(10),dp(8))};list.addView(TextView(this).apply{text="Keyboard size";textSize=15f;setTextColor(themeText());setPadding(0,dp(4),0,dp(2))});list.addView(TextView(this).apply{text="Use Floating or Resize keyboard in Cabinet. Resize shows a Gboard-style frame with draggable corners and edge handles; the center nub moves a floating keyboard.";textSize=12f;setTextColor(themeText().let{if(theme()==3)Color.DKGRAY else Color.rgb(148,163,184)});setPadding(0,0,0,dp(6))});list.addView(pill("Reset keyboard size"){PhormiKeyboardPreferences.resetSize(this@PhormiKeyboardServiceV2);setInputView(render())},LinearLayout.LayoutParams(-1,scaled(36)).apply{setMargins(0,dp(4),0,dp(8))});fun toggle(title:String,enabled:Boolean,action:()->Unit){list.addView(pill(if(enabled)"✓ $title"else title,action),LinearLayout.LayoutParams(-1,scaled(36)).apply{setMargins(0,dp(3),0,dp(3))})};toggle("Suggestions",PhormiKeyboardPreferences.suggestions(this)){PhormiKeyboardPreferences.set(this@PhormiKeyboardServiceV2,PhormiKeyboardPreferences.KEY_SUGGESTIONS,!PhormiKeyboardPreferences.suggestions(this));setInputView(render())};toggle("Autocorrect",PhormiKeyboardPreferences.autocorrect(this)){PhormiKeyboardPreferences.set(this@PhormiKeyboardServiceV2,PhormiKeyboardPreferences.KEY_AUTOCORRECT,!PhormiKeyboardPreferences.autocorrect(this));setInputView(render())};toggle("Auto-capitalization",PhormiKeyboardPreferences.autoCaps(this)){PhormiKeyboardPreferences.set(this@PhormiKeyboardServiceV2,PhormiKeyboardPreferences.KEY_AUTO_CAPS,!PhormiKeyboardPreferences.autoCaps(this));setInputView(render())};toggle("Haptic feedback",PhormiKeyboardPreferences.haptic(this)){PhormiKeyboardPreferences.set(this@PhormiKeyboardServiceV2,PhormiKeyboardPreferences.KEY_HAPTIC,!PhormiKeyboardPreferences.haptic(this));setInputView(render())};toggle("Key sounds",PhormiKeyboardPreferences.sound(this)){PhormiKeyboardPreferences.set(this@PhormiKeyboardServiceV2,PhormiKeyboardPreferences.KEY_SOUND,!PhormiKeyboardPreferences.sound(this));setInputView(render())};list.addView(pill("Theme: ${themeLabel(theme())}"){
            PhormiKeyboardPreferences.setTheme(this@PhormiKeyboardServiceV2, (theme() + 1) % 8)
            setInputView(render())
        },LinearLayout.LayoutParams(-1,scaled(36)).apply{setMargins(0,dp(3),0,dp(3))})
        list.addView(pill(if(PhormiKeyboardPreferences.wallpaperUri(this).isNullOrBlank())"Add wallpaper"else"Change wallpaper"){
            startActivity(Intent(this@PhormiKeyboardServiceV2, PhormiKeyboardMediaActivity::class.java).apply{addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);putExtra(PhormiKeyboardMediaActivity.EXTRA_MODE,"wallpaper")})
        },LinearLayout.LayoutParams(-1,scaled(36)).apply{setMargins(0,dp(3),0,dp(3))})
        if (!PhormiKeyboardPreferences.wallpaperUri(this).isNullOrBlank()) list.addView(pill("Remove wallpaper"){PhormiKeyboardPreferences.setWallpaperUri(this@PhormiKeyboardServiceV2,null);setInputView(render())},LinearLayout.LayoutParams(-1,scaled(36)).apply{setMargins(0,dp(3),0,dp(3))})
        list.addView(pill(if(PhormiKeyboardPreferences.pollinationsKey(this).isBlank())"Set Pollinations AI key"else"Pollinations AI key ✓"){startActivity(Intent(this,PhormiAiEmojiActivity::class.java).apply{addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);putExtra(PhormiAiEmojiActivity.EXTRA_CONFIG_ONLY,true)})},LinearLayout.LayoutParams(-1,scaled(36)).apply{setMargins(0,dp(3),0,dp(3))});scroll.addView(list);root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f));return root}
    private fun themeLabel(value: Int): String = listOf("Midnight","Graphite","Ocean","Light","Slate","Forest","Berry","Sand").getOrElse(value) { "Midnight" }
    private fun addBackHeader(root:LinearLayout,label:String,action:()->Unit){root.addView(pill(label,action),LinearLayout.LayoutParams(-1,scaled(36)))}
    internal fun render(): View {
        letterButtons.clear(); shiftView = null; predictionRow = null
        val view=when(panel){
            Panel.EMOJI->buildEmoji()
            Panel.CLIPBOARD->buildClipboard()
            Panel.AI_EMOJI->buildAiEmoji()
            Panel.TOOLS->buildTools()
            Panel.MEDIA->buildMedia()
            Panel.SETTINGS->buildSettings()
            Panel.KEYBOARD->buildKeyboard()
            Panel.WRITING->buildWritingAssistant()
            Panel.VOICE->buildVoicePanel()
        }
        updateLetterCase()
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
        if (PhormiKeyboardPreferences.autoCaps(this)) autoShift = PhormiKeyboardTextEngine.autoCapitalize(currentInputConnection, editorInfo)
        updateLetterCase()
        if (panel == Panel.KEYBOARD && page == KeyboardPage.LETTERS) schedulePredictionRefresh()
    }

    private fun updateLetterCase() {
        val upper = shift || capsLock || autoShift
        val locale = PhormiKeyboardTextEngine.localeFor(editorInfo)
        letterButtons.forEach { (button, base) -> button.text = if (upper) base.uppercase(locale) else base }
        shiftView?.apply {
            text = if (capsLock) "⇪" else "⇧"
            background = rounded(if (upper) accent() else themePill(), dp(10))
            setTextColor(if (upper) PhormiAppearance.onAccent(accent()) else themeText())
            contentDescription = if (capsLock) "Caps lock on" else if (upper) "Shift on" else "Shift off"
        }
    }

    private fun showAlternatives(anchor: View, options: List<String>, emoji: Boolean = false) {
        alternativesPopup?.dismiss()
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(5), dp(5), dp(5), dp(5)); background = rounded(themeBackground(), dp(14)) }
        val popup = PopupWindow(row, -2, -2, false).apply { isOutsideTouchable = true; setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)); elevation = dp(8).toFloat() }
        alternativesPopup = popup
        options.forEach { value ->
            row.addView(pill(value) {
                val output = if (shift || capsLock || autoShift) value.uppercase(PhormiKeyboardTextEngine.localeFor(editorInfo)) else value
                if (emoji) insertEmoji(output) else commitTextToEditor(output)
                popup.dismiss(); refreshAfterTextKey()
            }, LinearLayout.LayoutParams(dp(40), dp(48)))
        }
        if (anchor.windowToken != null) popup.showAsDropDown(anchor, 0, -anchor.height - dp(56))
    }
    private fun launchMedia(mode:String){startActivity(Intent(this,PhormiKeyboardMediaActivity::class.java).apply{addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);putExtra(PhormiKeyboardMediaActivity.EXTRA_MODE,mode);putExtra(EXTRA_EDITOR_TARGET,currentEditorKey())})}
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
        val connection = currentInputConnection ?: return
        if (!isInputViewShown || PhormiKeyboardTextEngine.isPrivateEditor(editorInfo)) return
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            PhormiKeyboardVoiceActivity.requestPermissionFromKeyboard(this, localeTag)
            return
        }
        stopVoice(true)
        panel = Panel.VOICE
        voicePartial = ""
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            voiceStatus = "No speech service is installed. Enable a speech-recognition service in Android settings."
            setInputView(render()); return
        }
        val generation = editorGeneration
        val editor = currentEditorKey()
        val recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer = recognizer
        voiceListening = true
        voiceStatus = "Starting microphone…"
        fun valid() = speechRecognizer === recognizer && editorGeneration == generation && currentEditorKey() == editor && isInputViewShown
        fun refresh() { if (valid() && panel == Panel.VOICE) setInputView(render()) }
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: android.os.Bundle?) { if (valid()) { voiceStatus = "Listening — speak now"; refresh() } }
            override fun onBeginningOfSpeech() { if (valid()) { voiceStatus = "Listening…"; refresh() } }
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() { if (valid()) { voiceStatus = "Processing speech…"; refresh() } }
            override fun onPartialResults(partialResults: android.os.Bundle?) {
                if (valid()) {
                    voicePartial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    refresh()
                }
            }
            override fun onEvent(eventType: Int, params: android.os.Bundle?) = Unit
            override fun onError(error: Int) {
                if (!valid()) return
                stopVoice(true)
                voiceStatus = when (error) {
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is required. Tap Try again to grant it."
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER -> "The speech service could not connect. Check your network or install an offline speech pack."
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech was recognized. Try again in a quieter place."
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "The microphone is busy. Close other voice apps and try again."
                    SpeechRecognizer.ERROR_AUDIO -> "The microphone could not be opened. Check Android microphone access."
                    else -> "Voice typing stopped (error $error). Try again."
                }
                if (panel == Panel.VOICE && isInputViewShown) setInputView(render())
            }
            override fun onResults(results: android.os.Bundle?) {
                if (!valid()) return
                val result = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                stopVoice(true)
                if (currentInputConnection === connection && editorGeneration == generation && result.isNotBlank()) {
                    val before = connection.getTextBeforeCursor(1, 0)?.toString().orEmpty()
                    val prefix = if (before.isNotEmpty() && !before.last().isWhitespace() && connection.getSelectedText(0).isNullOrEmpty()) " " else ""
                    connection.commitText(prefix + result, 1)
                    panel = Panel.KEYBOARD
                    setInputView(render())
                } else if (panel == Panel.VOICE) {
                    voiceStatus = "No text was inserted. The editor changed or no speech was recognized."
                    setInputView(render())
                }
            }
        })
        val recognition = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, localeTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        setInputView(render())
        runCatching { recognizer.startListening(recognition) }.onFailure {
            stopVoice(true); voiceStatus = "Voice typing could not start. Check the speech service and microphone permission."
            if (isInputViewShown) setInputView(render())
        }
    }

    private fun stopVoice(discard: Boolean) {
        val recognizer = speechRecognizer
        if (!discard) { runCatching { recognizer?.stopListening() }; return }
        speechRecognizer = null; voiceListening = false
        runCatching { recognizer?.cancel(); recognizer?.destroy() }
    }

    private fun buildVoicePanel(): View {
        val root = root()
        addBackHeader(root, "← Keyboard · cancel voice") { stopVoice(true); panel = Panel.KEYBOARD; setInputView(render()) }
        root.addView(TextView(this).apply { text = voiceStatus; setTextColor(themeText()); textSize = 17f; gravity = Gravity.CENTER; setPadding(dp(14), dp(12), dp(14), dp(8)) }, LinearLayout.LayoutParams(-1, -2))
        val scroll = ScrollView(this)
        scroll.addView(TextView(this).apply { text = voicePartial.ifBlank { "Speech appears here before it is inserted. Recognition is provided by your Android speech service." }; setTextColor(themeText()); textSize = 16f; setPadding(dp(16), dp(10), dp(16), dp(10)) })
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(pill(if (voiceListening) "Finish speaking" else "Try again") {
            if (voiceListening) stopVoice(false) else launchVoice()
        }, LinearLayout.LayoutParams(-1, scaled(44)))
        return root
    }

    private fun openWritingAssistant() {
        val connection = currentInputConnection ?: return
        if (!PhormiKeyboardTextEngine.shouldUsePredictions(editorInfo)) return
        val selected = connection.getSelectedText(0)?.toString().orEmpty()
        if (selected.length > 2000) {
            android.widget.Toast.makeText(this, "Select at most 2,000 characters for a writing edit", android.widget.Toast.LENGTH_LONG).show(); return
        }
        val source = selected.ifBlank { connection.getTextBeforeCursor(2000, 0)?.toString().orEmpty() }
        writingCapture = WritingCapture(editorGeneration, currentEditorKey(), connection, source, selected.isNotEmpty(), selectionStart, selectionEnd)
        writingResult = ""; writingBusy = false
        writingStatus = "${if (selected.isNotEmpty()) "Selected text" else "Text before the cursor"} · ${source.length} characters. Sent only after you choose an edit."
        panel = Panel.WRITING
        setInputView(render())
    }

    private fun buildWritingAssistant(): View {
        val root = root()
        addBackHeader(root, "← Keyboard · Writing assistant") { writingJob?.cancel(); panel = Panel.KEYBOARD; setInputView(render()) }
        val actions = listOf("Fix grammar" to "Fix spelling and grammar without changing the language or meaning.",
            "Rewrite" to "Rewrite clearly and naturally, preserving the meaning.",
            "Shorten" to "Make the text concise, preserving its key points.",
            "Continue" to "Continue with one or two sentences. Return the complete text, including the unchanged original.",
            "Translate" to "Translate into ${PhormiKeyboardTextEngine.localeFor(editorInfo).displayLanguage}.")
        val actionsScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.forEach { (label, instruction) -> row.addView(pill(label) { requestWritingEdit(instruction) }.apply { isEnabled = !writingBusy }, LinearLayout.LayoutParams(dp(110), scaled(40))) }
        actionsScroll.addView(row); root.addView(actionsScroll, LinearLayout.LayoutParams(-1, scaled(42)))
        root.addView(TextView(this).apply { text = writingStatus; setTextColor(themeText()); textSize = 12f; setPadding(dp(10), dp(5), dp(10), dp(5)) }, LinearLayout.LayoutParams(-1, -2))
        val preview = ScrollView(this)
        preview.addView(TextView(this).apply { text = writingResult.ifBlank { writingCapture?.source.orEmpty().ifBlank { "Type or select some text in the current app, then open Write." } }; setTextColor(themeText()); textSize = 15f; setPadding(dp(12), dp(10), dp(12), dp(10)) })
        root.addView(preview, LinearLayout.LayoutParams(-1, 0, 1f))
        if (writingResult.isNotBlank()) {
            val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            controls.addView(pill("Use this edit") { applyWritingResult() }, LinearLayout.LayoutParams(0, scaled(44), 1f))
            controls.addView(pill("Copy") { (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("Phormi writing suggestion", writingResult)) }, LinearLayout.LayoutParams(0, scaled(44), 1f))
            root.addView(controls)
        } else root.addView(pill(if (writingBusy) "Cancel request" else "Configure AI connection") {
            if (writingBusy) { writingJob?.cancel(); writingBusy = false; writingStatus = "Request cancelled."; setInputView(render()) }
            else startActivity(Intent(this, AiActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }, LinearLayout.LayoutParams(-1, scaled(42)))
        return root
    }

    private fun requestWritingEdit(instruction: String) {
        val capture = writingCapture ?: return
        if (capture.source.isBlank() || writingBusy) return
        writingBusy = true; writingResult = ""; writingStatus = "Asking your configured AI…"
        setInputView(render())
        writingJob = writingScope.launch {
            try {
                val result = writingAi.assistWriting(instruction, capture.source)
                if (editorGeneration == capture.generation && panel == Panel.WRITING && writingCapture === capture) {
                    writingResult = result; writingStatus = "Review the suggestion. Nothing changes until you tap Use this edit."
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { if (writingCapture === capture) writingStatus = error.message ?: "AI request failed. Try again." }
            finally {
                writingBusy = false
                if (isInputViewShown && panel == Panel.WRITING && writingCapture === capture) setInputView(render())
            }
        }
    }

    private fun applyWritingResult() {
        val capture = writingCapture ?: return
        val connection = currentInputConnection
        val sameSelection = selectionStart == capture.start && selectionEnd == capture.end
        val unchanged = if (capture.selected) connection?.getSelectedText(0)?.toString() == capture.source
            else connection?.getTextBeforeCursor(capture.source.length, 0)?.toString() == capture.source
        if (writingResult.isBlank() || connection !== capture.connection || currentEditorKey() != capture.editor ||
            editorGeneration != capture.generation || !sameSelection || !unchanged || PhormiKeyboardTextEngine.isPrivateEditor(editorInfo)) {
            writingStatus = "The text field or cursor changed. Reopen Write to capture the current text; nothing was replaced."
            setInputView(render()); return
        }
        connection.beginBatchEdit()
        try {
            connection.finishComposingText()
            if (!capture.selected) connection.deleteSurroundingText(capture.source.length, 0)
            connection.commitText(writingResult, 1)
        } finally { connection.endBatchEdit() }
        writingCapture = null; writingResult = ""; panel = Panel.KEYBOARD
        setInputView(render())
    }

    private fun shareSelectedText(){val text=currentInputConnection?.getSelectedText(0)?.toString().orEmpty();if(text.isBlank()){android.widget.Toast.makeText(this,"Select text first",android.widget.Toast.LENGTH_SHORT).show();return};startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply{type="text/plain";putExtra(Intent.EXTRA_TEXT,text)},"Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}
    internal fun currentEditorKey(): String = editorInfo?.let { "${it.packageName}:${it.fieldId}:${it.inputType}" }.orEmpty()

    private fun canCommitExternal(): Boolean = isInputViewShown && currentInputConnection != null && editorInfo != null && !PhormiKeyboardTextEngine.isPrivateEditor(editorInfo)

    private fun queueExternal(key: String, value: String, editor: String) {
        if (editor.isBlank()) return
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().clear().putString(key, value)
            .putString("pending_editor", editor).putLong("pending_at", System.currentTimeMillis()).apply()
    }

    private fun applyPendingInput() {
        val pending = getSharedPreferences(PREFS, MODE_PRIVATE)
        val recent = System.currentTimeMillis() - pending.getLong("pending_at", 0) in 0..120_000L
        if (!recent) { pending.edit().clear().apply(); return }
        if (pending.getString("pending_editor", null) != currentEditorKey()) return
        if (!PhormiKeyboardTextEngine.isPrivateEditor(editorInfo)) {
            pendingExternalText = pending.getString(KEY_PENDING_TEXT, null)?.takeIf { it.isNotBlank() }
            pendingExternalUri = pending.getString(KEY_PENDING_URI, null)?.takeIf { it.isNotBlank() }
        }
        pending.edit().clear().apply()
        if (pendingExternalText != null || pendingExternalUri != null) setInputView(render())
    }

    private fun addPendingInsert(root: LinearLayout) {
        if (pendingExternalText == null && pendingExternalUri == null) return
        root.addView(pill(if (pendingExternalUri != null) "Insert selected image here" else "Insert returned text here") {
            val uri = pendingExternalUri; val text = pendingExternalText
            pendingExternalUri = null; pendingExternalText = null
            if (!PhormiKeyboardTextEngine.isPrivateEditor(editorInfo)) {
                if (uri != null) commitContentToEditor(Uri.parse(uri)) else if (text != null) commitTextToEditor(text)
            }
            setInputView(render())
        }, LinearLayout.LayoutParams(-1, scaled(38)))
    }

    private fun commitTextToEditor(text: String) {
        if (emojiSearchActive) { emojiQuery = (emojiQuery + text).take(64); refreshEmojiSearch(); return }
        val ic = currentInputConnection ?: return
        correctionUndo = null
        ic.commitText(text, 1)
        if (PhormiKeyboardPreferences.autoCaps(this)) autoShift = PhormiKeyboardTextEngine.autoCapitalize(ic, editorInfo)
    }

    private fun commitSpace() {
        if (emojiSearchActive) { commitTextToEditor(" "); return }
        val ic = currentInputConnection ?: return
        val original = PhormiKeyboardTextEngine.currentWord(ic)
        val locale = PhormiKeyboardTextEngine.localeFor(editorInfo)
        val previous = PhormiKeyboardTextEngine.previousWord(ic, locale)
        val before = ic.getTextBeforeCursor(240, 0)?.toString().orEmpty()
        val keepLiteral = keepNextSpelling == original
        keepNextSpelling = null
        val correction = if (!keepLiteral && PhormiKeyboardPreferences.autocorrect(this) && PhormiKeyboardTextEngine.shouldUsePredictions(editorInfo) && original.isNotBlank())
            PhormiKeyboardTextEngine.correctionFor(this, original, locale) else null
        ic.beginBatchEdit()
        try {
            ic.finishComposingText()
            if (correction != null) {
                ic.deleteSurroundingText(original.length, 0)
                ic.commitText(correction, 1)
            }
            ic.commitText(" ", 1)
        } finally { ic.endBatchEdit() }
        correctionUndo = correction?.let { CorrectionUndo(original, it, before.dropLast(original.length)) }
        if (correction == null && original.isNotBlank()) PhormiKeyboardTextEngine.learn(this, original, editorInfo)
        val completed = correction ?: original
        PhormiKeyboardTextEngine.learnPair(this, previous, completed, editorInfo)
        refreshAfterTextKey()
    }

    private fun deleteBackward() {
        if (emojiSearchActive) { emojiQuery = emojiQuery.dropLast(PhormiTypingRules.deletionLength(emojiQuery)); refreshEmojiSearch(); return }
        val ic = currentInputConnection ?: return
        val selected = ic.getSelectedText(0)?.toString()
        val undo = correctionUndo
        correctionUndo = null
        if (!selected.isNullOrEmpty()) { ic.commitText("", 1); return }
        val before = ic.getTextBeforeCursor(300, 0)?.toString().orEmpty()
        if (undo != null && before.endsWith(undo.before + undo.replacement + " ")) {
            ic.beginBatchEdit()
            try {
                ic.deleteSurroundingText(undo.replacement.length + 1, 0)
                ic.commitText(undo.original, 1)
            } finally { ic.endBatchEdit() }
            PhormiKeyboardTextEngine.rememberLiteralChoice(this, undo.original, editorInfo)
            keepNextSpelling = undo.original
            return
        }
        val length = PhormiTypingRules.deletionLength(before)
        if (length > 0) ic.deleteSurroundingText(length, 0)
    }
    private fun moveCursor(delta:Int){val ic=currentInputConnection?:return;val code=if(delta<0)KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT;ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN,code));ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP,code));refreshAfterTextKey()}
    private fun toggleShift(){if(capsLock){capsLock=false;shift=false;autoShift=false}else if(shift||autoShift){capsLock=true;shift=false;autoShift=false}else shift=true}
    private fun actionLabel(): String {
        if (emojiSearchActive) return "Search"
        val info = editorInfo ?: return "Enter"
        if (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) return "↵"
        return info.actionLabel?.toString()?.takeIf { it.isNotBlank() } ?: when (info.imeOptions and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_GO -> "Go"; EditorInfo.IME_ACTION_SEARCH -> "Search"; EditorInfo.IME_ACTION_SEND -> "Send"
            EditorInfo.IME_ACTION_NEXT -> "Next"; EditorInfo.IME_ACTION_PREVIOUS -> "Previous"; EditorInfo.IME_ACTION_DONE -> "Done"
            else -> "↵"
        }
    }
    private fun sendEditorAction() {
        if (emojiSearchActive) { emojiSearchActive = false; panel = Panel.EMOJI; setInputView(render()); return }
        val ic = currentInputConnection ?: return
        val info = editorInfo
        val action = info?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_NONE
        val allowed = info != null && info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION == 0
        val custom = info?.actionLabel != null && info.actionId != 0
        if (allowed && (custom || action in setOf(EditorInfo.IME_ACTION_GO, EditorInfo.IME_ACTION_SEARCH, EditorInfo.IME_ACTION_SEND,
                EditorInfo.IME_ACTION_NEXT, EditorInfo.IME_ACTION_PREVIOUS, EditorInfo.IME_ACTION_DONE))) {
            if (ic.performEditorAction(if (custom) info!!.actionId else action)) return
        }
        if ((info?.inputType ?: 0) and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0) { ic.commitText("\n", 1); refreshAfterTextKey() }
        else { ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)); ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER)) }
    }
    private fun installRepeat(view:View,action:()->Unit){view.setOnTouchListener{_,event->when(event.actionMasked){MotionEvent.ACTION_DOWN->{action();repeatRunnable=object:Runnable{override fun run(){action();repeatHandler.postDelayed(this,55)}};repeatHandler.postDelayed(repeatRunnable!!,320);true};MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->{stopRepeat();true};else->true}}}
    private fun stopRepeat(){repeatRunnable?.let{repeatHandler.removeCallbacks(it)};repeatRunnable=null}
    private fun commitContentToEditor(uri:Uri):Boolean{if(Build.VERSION.SDK_INT<25)return false;val ic=currentInputConnection?:return false;val info=editorInfo?:return false;val mimeTypes=info.contentMimeTypes?:emptyArray();if(mimeTypes.isEmpty()){android.widget.Toast.makeText(this,"This field does not accept images",android.widget.Toast.LENGTH_SHORT).show();return false};val wanted=contentResolver.getType(uri).orEmpty();val accepted=wanted.isNotBlank()&&mimeTypes.any{it==wanted||(it.endsWith("/*")&&wanted.startsWith(it.removeSuffix("*")))};if(!accepted){android.widget.Toast.makeText(this,"This field does not accept that media type",android.widget.Toast.LENGTH_SHORT).show();return false};return runCatching{val description=android.content.ClipDescription("Phormi media",arrayOf(wanted));val contentInfo=InputContentInfo(uri,description,null);ic.commitContent(contentInfo,InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION,null)}.getOrDefault(false)}
}
