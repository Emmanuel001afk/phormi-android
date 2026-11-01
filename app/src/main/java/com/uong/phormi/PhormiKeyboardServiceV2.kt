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
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_PENDING_URI, uri.toString()).remove(KEY_PENDING_TEXT)
                .putLong("picked_at", System.currentTimeMillis()).apply()
            instance?.showPendingInput()
            return false // Explicit insertion is required after returning to an editor.
        }
        fun startIntegratedVoiceRecognitionFromContext(context: Context, localeTag: String) { instance?.startIntegratedVoiceRecognition(localeTag) }
        fun commitExternalText(context: Context, text: String): Boolean {
            if (text.isBlank()) return false
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_PENDING_TEXT, text.take(4000)).remove(KEY_PENDING_URI)
                .putLong("picked_at", System.currentTimeMillis()).apply()
            instance?.showPendingInput()
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
    private var spaceSteps = 0
    private val letterButtons = mutableListOf<Pair<Button, String>>()
    private var hangulKeys = ""
    private fun finishHangul() {
        if (hangulKeys.isNotEmpty()) currentInputConnection?.finishComposingText()
        hangulKeys = ""
    }
    private fun insertHangul(base: String): Boolean {
        if (PhormiKeyboardTextEngine.localeFor(editorInfo).language != "ko" || !PhormiHangulComposer.accepts(base)) return false
        val connection = currentInputConnection ?: return true
        if (hangulKeys.length >= 64) finishHangul()
        hangulKeys += if (shift || capsLock) PhormiHangulComposer.shifted(base) else base
        if (!connection.setComposingText(PhormiHangulComposer.compose(hangulKeys), 1)) hangulKeys = ""
        if (!capsLock) shift = false
        autoShift = false
        refreshAfterTextKey()
        return true
    }
    override fun onCurrentInputMethodSubtypeChanged(newSubtype: android.view.inputmethod.InputMethodSubtype?) {
        finishGlide(); finishHangul()
        super.onCurrentInputMethodSubtypeChanged(newSubtype)
        page = KeyboardPage.LETTERS
        if (isInputViewShown) setInputView(render())
    }
    private var inputSession = 0L
    private val glideWorker = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var glideFuture: java.util.concurrent.Future<*>? = null
    private var glideGeneration = 0L
    private var glidePending = false
    private var glideCandidates = emptyList<String>()
    private var glideComposing = false
    private fun finishGlide(space: Boolean = false) {
        glideGeneration++; glideFuture?.cancel(true); glidePending = false
        if (glideComposing) {
            currentInputConnection?.finishComposingText()
            if (space) currentInputConnection?.commitText(" ", 1)
        }
        glideCandidates = emptyList(); glideComposing = false
        lastPredictionWord = ""; lastPredictionPrevious = ""
    }
    private fun canGlide(): Boolean = PhormiKeyboardPreferences.glide(this) &&
        PhormiKeyboardTextEngine.shouldUsePredictions(editorInfo) &&
        PhormiKeyboardTextEngine.localeFor(editorInfo).language in PhormiGlideDecoder.languages

    private fun decodeGlide(trace: List<PhormiGlideDecoder.Point>, keys: Map<Char, PhormiGlideDecoder.Point>) {
        if (!canGlide()) return
        finishGlide()
        val connection = currentInputConnection ?: return
        val locale = PhormiKeyboardTextEngine.localeFor(editorInfo)
        val capital = shift || capsLock || autoShift
        val allCaps = capsLock
        val generation = ++glideGeneration
        val session = inputSession
        glidePending = true; refreshPredictionStrip()
        glideFuture = glideWorker.submit {
            val result = runCatching { PhormiGlideDecoder.decode(trace, keys,
                PhormiKeyboardDictionary.glideWords(applicationContext, locale), locale) }.getOrDefault(emptyList())
            repeatHandler.post {
                if (generation != glideGeneration || session != inputSession || !isInputViewShown || !canGlide() ||
                    locale != PhormiKeyboardTextEngine.localeFor(editorInfo)) return@post
                glidePending = false
                glideCandidates = result.map { candidate ->
                    if (allCaps) candidate.word.uppercase(locale)
                    else if (capital) candidate.word.replaceFirstChar { it.titlecase(locale) } else candidate.word
                }
                if (glideCandidates.isNotEmpty()) {
                    connection.beginBatchEdit()
                    try {
                        val previous = connection.getTextBeforeCursor(1, 0)?.lastOrNull()
                        if (previous?.isLetterOrDigit() == true) connection.commitText(" ", 1)
                        glideComposing = true
                        if (!connection.setComposingText(glideCandidates.first(), 1)) {
                            glideComposing = false; glideCandidates = emptyList()
                        }
                    } finally { connection.endBatchEdit() }
                    if (!capsLock) shift = false
                    autoShift = false; updateLetterLabels()
                } else android.widget.Toast.makeText(this, "Gesture not recognized — try again or tap the word", android.widget.Toast.LENGTH_SHORT).show()
                refreshPredictionStrip()
            }
        }
    }
    private fun showGlideCandidates(row: LinearLayout): Boolean {
        if (!glidePending && glideCandidates.isEmpty()) return false
        row.removeAllViews()
        if (glidePending) row.addView(TextView(this).apply { text = "Recognizing gesture…"; setTextColor(themeText()); gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-1, -1))
        else glideCandidates.forEach { word ->
            row.addView(pill(word) {
                if (glideComposing) currentInputConnection?.setComposingText(word, 1)
                finishGlide(); refreshPredictionStrip()
            }.apply { contentDescription = "Glide candidate $word" }, LinearLayout.LayoutParams(0, -1, 1f))
        }
        return true
    }
    private var voiceStatus = ""
    private var voiceActive = false
    private var accentPopup: android.widget.PopupWindow? = null
    private val wallpaperWorker = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var wallpaperUriLoaded: String? = null
    private var wallpaperUriLoading: String? = null
    private var wallpaperBitmap: android.graphics.Bitmap? = null
    private val wallpaperTargets = mutableMapOf<String, MutableList<java.lang.ref.WeakReference<View>>>()
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

    override fun onCreate() {
        super.onCreate(); instance = this
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboardListener = ClipboardManager.OnPrimaryClipChangedListener { PhormiKeyboardClipboardStore.capturePrimaryClipboard(this) }
        clipboardListener?.let { cm?.addPrimaryClipChangedListener(it) }
    }
    override fun onDestroy() {
        finishGlide(); finishHangul()
        glideWorker.shutdownNow()
        wallpaperWorker.shutdownNow()
        wallpaperBitmap = null
        stopVoice(false)
        stopRepeat()
        predictionRefreshRunnable?.let { repeatHandler.removeCallbacks(it) }; predictionRefreshRunnable = null
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboardListener?.let { cm?.removePrimaryClipChangedListener(it) }; clipboardListener = null
        if (instance === this) instance = null; super.onDestroy()
    }
    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        finishGlide(); finishHangul()
        stopVoice(false)
        inputSession++
        super.onStartInput(attribute, restarting); editorInfo = attribute
        if (!restarting) { page = KeyboardPage.LETTERS; capsLock = false; shift = false; panel = Panel.KEYBOARD }
        lastAutoCorrection = null; lastPredictionWord = ""; lastPredictionPrevious = ""
        confirmClearClipboard = false
        autoShift = PhormiKeyboardPreferences.autoCaps(this) && PhormiKeyboardTextEngine.autoCapitalize(currentInputConnection, attribute)
        if (!restarting) panel = Panel.KEYBOARD
    }
    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        editorInfo = info ?: editorInfo
        if (!restarting) panel = Panel.KEYBOARD
        // Keep the IME window transition-free from the moment Android starts
        // showing it. This prevents a stale/miniature copy from appearing
        // during the show/hide handoff.
        runCatching {
            getWindow().window?.setWindowAnimations(0)
            getWindow().window?.decorView?.animate()?.cancel()
        }
        applyPendingInput()
        setInputView(render())
        applyKeyboardWindowSize()
    }
    override fun onUpdateSelection(oldSelStart:Int, oldSelEnd:Int, newSelStart:Int, newSelEnd:Int, candidatesStart:Int, candidatesEnd:Int) {
        super.onUpdateSelection(oldSelStart,oldSelEnd,newSelStart,newSelEnd,candidatesStart,candidatesEnd)
        if (hangulKeys.isNotEmpty() && (candidatesStart < 0 || newSelStart != newSelEnd || newSelEnd != candidatesEnd)) finishHangul()
        if ((glidePending && (oldSelStart != newSelStart || oldSelEnd != newSelEnd)) ||
            (glideComposing && (candidatesStart < 0 || newSelStart != newSelEnd || newSelEnd != candidatesEnd))) finishGlide()
        if(panel==Panel.KEYBOARD && page==KeyboardPage.LETTERS){
            schedulePredictionRefresh()
            if(PhormiKeyboardPreferences.autoCaps(this)){
                autoShift=PhormiKeyboardTextEngine.autoCapitalize(currentInputConnection,editorInfo)
            }
            updateShiftKeyAppearance()
        }
    }

    override fun onFinishInput() { finishGlide(); finishHangul(); inputSession++; stopVoice(false); accentPopup?.dismiss(); stopRepeat(); shiftKeyView = null; editorInfo = null; super.onFinishInput() }
    override fun onUnbindInput() { finishGlide(); finishHangul(); inputSession++; stopVoice(false); accentPopup?.dismiss(); stopRepeat(); shiftKeyView = null; editorInfo = null; super.onUnbindInput() }
    override fun onFinishInputView(finishingInput: Boolean) {
        finishGlide(); finishHangul()
        stopVoice(false)
        accentPopup?.dismiss()
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
        stopVoice(false)
        accentPopup?.dismiss()
        stopRepeat()
        runCatching {
            getWindow().window?.setWindowAnimations(0)
            getWindow().window?.decorView?.animate()?.cancel()
        }
        super.onWindowHidden()
    }
    override fun onCurrentInputMethodSubtypeChanged(newSubtype: android.view.inputmethod.InputMethodSubtype?) {
        super.onCurrentInputMethodSubtypeChanged(newSubtype)
        lastPredictionWord = ""; lastPredictionPrevious = ""
        if (isInputViewShown) setInputView(render())
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
        layoutParams=LinearLayout.LayoutParams(-1,-1).apply{gravity=Gravity.CENTER_HORIZONTAL}
        minimumHeight=0
        applyWallpaper(this)
    }
    private fun applyWallpaper(root: View) {
        val value = PhormiKeyboardPreferences.wallpaperUri(this) ?: return
        fun paint(target: View, bitmap: android.graphics.Bitmap) {
            target.background = android.graphics.drawable.LayerDrawable(arrayOf(
                android.graphics.drawable.ColorDrawable(themeBackground()),
                BitmapDrawable(resources, bitmap).apply { alpha = 72 }
            ))
        }
        if (wallpaperUriLoaded == value) { wallpaperBitmap?.let { paint(root, it) }; return }
        wallpaperTargets.getOrPut(value) { mutableListOf() }.add(java.lang.ref.WeakReference(root))
        if (wallpaperUriLoading == value) return
        wallpaperUriLoading = value
        wallpaperWorker.execute {
            val bitmap = runCatching {
                val uri = Uri.parse(value)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                val options = BitmapFactory.Options()
                while (bounds.outWidth / options.inSampleSize > 1600 || bounds.outHeight / options.inSampleSize > 1200) options.inSampleSize *= 2
                contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            }.getOrNull()
            repeatHandler.post {
                wallpaperUriLoading = null
                if (instance === this && bitmap != null && PhormiKeyboardPreferences.wallpaperUri(this) == value) {
                    wallpaperUriLoaded = value; wallpaperBitmap = bitmap
                    wallpaperTargets.remove(value)?.forEach { ref -> ref.get()?.let { paint(it, bitmap) } }
                } else wallpaperTargets.remove(value)
            }
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
        minimumWidth=0
        minimumHeight=0
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
        val picked = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (picked.contains(KEY_PENDING_URI) || picked.contains(KEY_PENDING_TEXT)) {
            add("Insert picked", 110) { insertPendingInput() }
        }
        add("😀") { panel = Panel.EMOJI; setInputView(render()) }
        add("📋") {
            panel = Panel.CLIPBOARD
            confirmClearClipboard = false
            PhormiKeyboardClipboardStore.capturePrimaryClipboard(this)
            setInputView(render())
        }
        add("Tools", 68) { panel = Panel.TOOLS; setInputView(render()) }
        if (Build.VERSION.SDK_INT >= 28 && shouldOfferSwitchingToNextInputMethod()) {
            add("🌐", 52) { runCatching { switchToNextInputMethod(false) } }
        }
        add(if (voiceActive) "Stop mic" else "Mic", 72) { if (voiceActive) stopVoice(true) else launchVoice() }
        scroll.addView(row)
        root.addView(scroll, LinearLayout.LayoutParams(-1, scaled(40)))
        if (voiceStatus.isNotBlank()) root.addView(TextView(this).apply {
            text = voiceStatus; textSize = 12f; setTextColor(themeText()); gravity = Gravity.CENTER
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }, LinearLayout.LayoutParams(-1, scaled(28)))
    }
    private fun predictionStrip(root:LinearLayout){
        val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
        predictionRow=row
        root.addView(row,LinearLayout.LayoutParams(-1,scaled(36)))
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

    private fun refreshPredictionStrip(){
        val row=predictionRow ?: return
        if (showGlideCandidates(row)) return
        if (hangulKeys.isNotEmpty()) {
            row.removeAllViews()
            row.addView(pill(PhormiHangulComposer.compose(hangulKeys) + "  ✓") { finishHangul(); refreshPredictionStrip() }, LinearLayout.LayoutParams(-1, -1))
            return
        }
        val ic=currentInputConnection
        val eligible = PhormiKeyboardTextEngine.shouldUsePredictions(editorInfo)
        val word=if (eligible) PhormiKeyboardTextEngine.currentWord(ic) else ""
        val previous=if (eligible) PhormiKeyboardTextEngine.previousWord(ic,PhormiKeyboardTextEngine.localeFor(editorInfo)) else ""
        if (word == lastPredictionWord && previous == lastPredictionPrevious && row.childCount > 0) return
        lastPredictionWord = word
        lastPredictionPrevious = previous
        row.removeAllViews()
        val enabled = PhormiKeyboardPreferences.suggestions(this) && PhormiKeyboardTextEngine.shouldUsePredictions(editorInfo)
        val suggestions = if (!enabled) emptyList() else if (word.isNotBlank())
            PhormiKeyboardTextEngine.suggestions(this, word, PhormiKeyboardTextEngine.localeFor(editorInfo))
        else PhormiKeyboardTextEngine.nextWordSuggestions(this, previous, PhormiKeyboardTextEngine.localeFor(editorInfo))
        suggestions.take(3).forEach { value ->
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
                schedulePredictionRefresh()
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
        if (row.childCount == 0) row.addView(TextView(this).apply {
            text = if (enabled) "Type to see suggestions" else "Suggestions are off for this field"
            textSize = 12f
            setTextColor(if (theme() == 3) Color.DKGRAY else Color.rgb(148, 163, 184))
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-1, scaled(30)))
        // Never collapse the strip: the letter keys must not move while typing.
        row.visibility = View.VISIBLE
    }
    private fun buildKeyboard(): View {
        val root = root()
        val clazz = (editorInfo?.inputType ?: InputType.TYPE_CLASS_TEXT) and InputType.TYPE_MASK_CLASS
        val specialized = clazz in setOf(InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_PHONE, InputType.TYPE_CLASS_DATETIME)
        if (!specialized && page == KeyboardPage.LETTERS) predictionStrip(root)
        toolbar(root)
        when {
            specialized -> buildFieldKeypad(root, clazz)
            page == KeyboardPage.LETTERS -> buildLetterPage(root)
            page == KeyboardPage.NUMBERS -> buildNumberPage(root)
            else -> buildSymbolPage(root)
        }
        return root
    }

    private fun buildLetterPage(root: LinearLayout) {
        val locale = PhormiKeyboardTextEngine.localeFor(editorInfo)
        val rows = PhormiKeyboardLayouts.rows(locale.language)
        val body = PhormiGlideLayout(this).apply {
            enabledForEditor = { canGlide() && accentPopup?.isShowing != true }
            keyGeometry = {
                val origin = IntArray(2); getLocationOnScreen(origin)
                letterButtons.mapNotNull { (button, label) ->
                    val letter = label.singleOrNull() ?: return@mapNotNull null
                    val location = IntArray(2); button.getLocationOnScreen(location)
                    PhormiGlideLayout.Key(letter, location[0] - origin[0] + button.width / 2f,
                        location[1] - origin[1] + button.height / 2f, button.width.toFloat(), button.height.toFloat())
                }
            }
            onGlide = { trace, keys -> decodeGlide(trace, keys) }
        }
        rows.forEachIndexed { index, chars ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
                layoutDirection = View.LAYOUT_DIRECTION_LTR
                if (index == 1) setPadding(dp(12), 0, dp(12), 0)
            }
            if (index == 2) row.addView(shiftKey(1.3f), LinearLayout.LayoutParams(0, -1, 1.3f).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) })
            chars.forEach { ch ->
                val base = ch.toString()
                val button = keyButton(base) { insertLetter(base) }
                letterButtons.add(button to base)
                val alternatives = PhormiKeyboardLayouts.alternatives(base, locale.language)
                if (alternatives.isNotEmpty()) button.setOnLongClickListener { showAccents(button, alternatives); true }
                row.addView(button, LinearLayout.LayoutParams(0, -1, 1f).apply { setMargins(dp(1), dp(2), dp(1), dp(2)) })
            }
            if (index == 2) row.addView(keyButton("⌫") { deleteBackward(); refreshAfterTextKey() }.also {
                it.contentDescription = "Backspace"
                installRepeat(it) { deleteBackward(); refreshAfterTextKey() }
            }, LinearLayout.LayoutParams(0, -1, 1.3f).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) })
            body.addView(row, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        addBottomRow(root, KeyboardPage.LETTERS)
        updateLetterLabels()
    }

    private fun insertLetter(base: String) {
        finishGlide(space = true)
        if (insertHangul(base)) return
        finishHangul()
        val locale = PhormiKeyboardTextEngine.localeFor(editorInfo)
        commitTextToEditor(if (shift || capsLock || autoShift) base.uppercase(locale) else base)
        if (!capsLock) shift = false
        autoShift = PhormiKeyboardPreferences.autoCaps(this) && PhormiKeyboardTextEngine.autoCapitalize(currentInputConnection, editorInfo)
        refreshAfterTextKey()
    }
    private fun updateLetterLabels() {
        val locale = PhormiKeyboardTextEngine.localeFor(editorInfo)
        letterButtons.forEach { (button, base) ->
            button.text = if (locale.language == "ko") {
                if (shift || capsLock) PhormiHangulComposer.shifted(base) else base
            } else if (shift || capsLock || autoShift) base.uppercase(locale) else base
            button.contentDescription = button.text
        }
    }
    private fun showAccents(anchor: View, alternatives: List<String>) {
        accentPopup?.dismiss()
        val grid = android.widget.GridLayout(this).apply { columnCount = 6; setPadding(dp(4), dp(4), dp(4), dp(4)); setBackgroundColor(themeBackground()) }
        alternatives.forEach { value -> grid.addView(pill(if (value.firstOrNull()?.let { Character.getType(it) == Character.NON_SPACING_MARK.toInt() } == true) "◌$value" else value) {
            insertLetter(value); accentPopup?.dismiss()
        }, android.widget.GridLayout.LayoutParams().apply { width = dp(44); height = dp(48) }) }
        accentPopup = android.widget.PopupWindow(grid, minOf(getMaxWidth(), dp(280)), -2, true).apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(themeBackground())); isOutsideTouchable = true
            showAtLocation(anchor, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0, scaled(54))
        }
    }

    private fun buildNumberPage(root: LinearLayout) {
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        responsiveRows(body, listOf("1234567890", "@#£_&-+()/", "*\"':;!?"))
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        addBottomRow(root, KeyboardPage.NUMBERS)
    }

    private fun buildFieldKeypad(root: LinearLayout, clazz: Int) {
        val type = editorInfo?.inputType ?: 0
        val phone = clazz == InputType.TYPE_CLASS_PHONE
        val date = clazz == InputType.TYPE_CLASS_DATETIME
        val signed = type and InputType.TYPE_NUMBER_FLAG_SIGNED != 0
        val decimal = type and InputType.TYPE_NUMBER_FLAG_DECIMAL != 0
        val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"),
            listOf(if (phone) "*" else if (signed || date) "-" else "", "0", if (phone) "#" else if (date) "/" else if (decimal) java.text.DecimalFormatSymbols.getInstance().decimalSeparator.toString() else ""))
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        rows.forEach { tokens ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            tokens.forEach { token -> row.addView(keyButton(token) { commitTextToEditor(token) }.apply { isEnabled = token.isNotEmpty() },
                LinearLayout.LayoutParams(0, -1, 1f).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) }) }
            body.addView(row, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        if (phone || date) actions.addView(keyButton(if (phone) "+" else ":") { commitTextToEditor(if (phone) "+" else ":") })
        actions.addView(keyButton("⌫") { deleteBackward() }.also { it.contentDescription = "Backspace"; installRepeat(it) { deleteBackward() } })
        actions.addView(keyButton(actionLabel()) { sendEditorAction() })
        root.addView(actions, LinearLayout.LayoutParams(-1, scaled(48)))
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
            root.addView(row,LinearLayout.LayoutParams(-1,scaled(48)).apply{width=-1})
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
        updateLetterLabels()
        if (renderedShiftState == state) return
        renderedShiftState = state
        // Purple = one-letter capitalization; red = caps lock.
        key.background = rounded(when (state) {
            2 -> Color.rgb(220, 38, 38)
            1 -> Color.rgb(124, 58, 237)
            else -> themeKey()
        }, dp(9))
        key.setTextColor(if (state == 0) themeText() else Color.WHITE)
        key.contentDescription = when (state) {
            2 -> "Caps lock — all letters uppercase"
            1 -> "Shift — next letter uppercase"
            else -> "Shift — off"
        }
    }

    private fun addBottomRow(root: LinearLayout, currentPage: KeyboardPage) {
        val bottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        fun add(label: String, weight: Float = 1f, action: () -> Unit): Button {
            val button = keyButton(label, weight, action)
            bottom.addView(button, LinearLayout.LayoutParams(0, -1, weight).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) })
            return button
        }
        if (currentPage == KeyboardPage.LETTERS) add("?123", 1.3f) { page = KeyboardPage.NUMBERS; setInputView(render()) }
        else {
            add("ABC", 1.3f) { page = KeyboardPage.LETTERS; setInputView(render()) }
            add(if (currentPage == KeyboardPage.NUMBERS) "=\\<" else "123", 1.2f) {
                page = if (currentPage == KeyboardPage.NUMBERS) KeyboardPage.SYMBOLS else KeyboardPage.NUMBERS; setInputView(render())
            }
        }
        val variation = (editorInfo?.inputType ?: 0) and InputType.TYPE_MASK_VARIATION
        val punctuation = when (variation) {
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS -> "@"
            InputType.TYPE_TEXT_VARIATION_URI -> "/"
            else -> ","
        }
        add(punctuation) { commitTextToEditor(punctuation); refreshAfterTextKey() }
        val space = add("Space", 4f) { commitSpace() }
        space.contentDescription = "Space. Slide left or right to move the cursor."
        space.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { spaceDownX = event.x; spaceMoved = false; spaceSteps = 0; true }
                MotionEvent.ACTION_MOVE -> {
                    val steps = ((event.x - spaceDownX) / dp(12)).toInt()
                    if (steps != spaceSteps) {
                        spaceMoved = true
                        repeat(abs(steps - spaceSteps).coerceAtMost(30)) { moveCursor(if (steps > spaceSteps) 1 else -1) }
                        spaceSteps = steps
                    }
                    true
                }
                MotionEvent.ACTION_UP -> { if (!spaceMoved) view.performClick(); true }
                MotionEvent.ACTION_CANCEL -> { spaceMoved = false; true }
                else -> true
            }
        }
        add(".") { commitTextToEditor("."); refreshAfterTextKey() }
        if (currentPage != KeyboardPage.LETTERS) add("⌫") { deleteBackward(); refreshAfterTextKey() }.also { installRepeat(it) { deleteBackward(); refreshAfterTextKey() } }
        add(actionLabel(), 1.4f) { sendEditorAction() }
        root.addView(bottom, LinearLayout.LayoutParams(-1, scaled(48)))
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
            val label=(if (item.pinned) "📌 " else "") + item.text.take(60).ifBlank{"Copied item"}
            val clip=pill(label){
                if (!item.uri.isNullOrBlank()) {
                    if (!commitContentToEditor(Uri.parse(item.uri))) android.widget.Toast.makeText(this,"This field could not receive the image",android.widget.Toast.LENGTH_SHORT).show()
                } else commitTextToEditor(item.text)
            }
            val delete=pill("Delete") { PhormiKeyboardClipboardStore.remove(this,item);setInputView(render()) }
            delete.contentDescription = "Delete clipboard item: $label"
            row.addView(clip,LinearLayout.LayoutParams(0,scaled(44),1f).apply{setMargins(0,dp(2),dp(3),dp(2))})
            row.addView(pill(if (item.pinned) "Unpin" else "Pin") { PhormiKeyboardClipboardStore.togglePinned(this,item);setInputView(render()) },LinearLayout.LayoutParams(dp(60),scaled(44)))
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
    private fun buildTools():View{val root=root();addBackHeader(root,"← Keyboard"){panel=Panel.KEYBOARD;setInputView(render())};val scroll=ScrollView(this);val list=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(8),dp(8),dp(8),dp(8))};list.addView(pill("Contextual emoji"){openAiEmojiFromContext()},LinearLayout.LayoutParams(-1,scaled(46)));list.addView(pill("Settings"){panel=Panel.SETTINGS;setInputView(render())},LinearLayout.LayoutParams(-1,scaled(46)));addResizeControls(list);list.addView(pill("GIFs & stickers"){panel=Panel.MEDIA;setInputView(render())},LinearLayout.LayoutParams(-1,scaled(46)));list.addView(pill("Voice typing"){launchVoice()},LinearLayout.LayoutParams(-1,scaled(46)));list.addView(pill("Select all"){currentInputConnection?.performContextMenuAction(android.R.id.selectAll)},LinearLayout.LayoutParams(-1,scaled(46)));list.addView(pill("Copy"){currentInputConnection?.performContextMenuAction(android.R.id.copy)},LinearLayout.LayoutParams(-1,scaled(46)));list.addView(pill("Paste"){currentInputConnection?.performContextMenuAction(android.R.id.paste)},LinearLayout.LayoutParams(-1,scaled(46)));list.addView(pill("Share text"){shareSelectedText()},LinearLayout.LayoutParams(-1,scaled(46)));scroll.addView(list);root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f));return root}
    private fun buildSettings():View{val root=root();addBackHeader(root,"← Tools"){panel=Panel.TOOLS;setInputView(render())};val scroll=ScrollView(this).apply{overScrollMode=View.OVER_SCROLL_IF_CONTENT_SCROLLS};val list=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(10),dp(6),dp(10),dp(8))};list.addView(TextView(this).apply{text="Keyboard size";textSize=15f;setTextColor(themeText());setPadding(0,dp(4),0,dp(2))});list.addView(TextView(this).apply{text="Use Floating or Resize keyboard in Tools. Resize shows a Gboard-style frame with draggable corners and edge handles; the center nub moves a floating keyboard.";textSize=12f;setTextColor(themeText().let{if(theme()==3)Color.DKGRAY else Color.rgb(148,163,184)});setPadding(0,0,0,dp(6))});list.addView(pill("Reset keyboard size"){PhormiKeyboardPreferences.resetSize(this@PhormiKeyboardServiceV2);setInputView(render())},LinearLayout.LayoutParams(-1,scaled(36)).apply{setMargins(0,dp(4),0,dp(8))});fun toggle(title:String,enabled:Boolean,action:()->Unit){list.addView(pill(if(enabled)"✓ $title"else title,action),LinearLayout.LayoutParams(-1,scaled(36)).apply{setMargins(0,dp(3),0,dp(3))})};toggle("Offline glide (8 dictionary languages)",PhormiKeyboardPreferences.glide(this)){PhormiKeyboardPreferences.set(this,PhormiKeyboardPreferences.KEY_GLIDE,!PhormiKeyboardPreferences.glide(this));finishGlide();setInputView(render())};toggle("Suggestions",PhormiKeyboardPreferences.suggestions(this)){PhormiKeyboardPreferences.set(this@PhormiKeyboardServiceV2,PhormiKeyboardPreferences.KEY_SUGGESTIONS,!PhormiKeyboardPreferences.suggestions(this));setInputView(render())};toggle("Autocorrect",PhormiKeyboardPreferences.autocorrect(this)){PhormiKeyboardPreferences.set(this@PhormiKeyboardServiceV2,PhormiKeyboardPreferences.KEY_AUTOCORRECT,!PhormiKeyboardPreferences.autocorrect(this));setInputView(render())};toggle("Auto-capitalization",PhormiKeyboardPreferences.autoCaps(this)){PhormiKeyboardPreferences.set(this@PhormiKeyboardServiceV2,PhormiKeyboardPreferences.KEY_AUTO_CAPS,!PhormiKeyboardPreferences.autoCaps(this));setInputView(render())};toggle("Haptic feedback",PhormiKeyboardPreferences.haptic(this)){PhormiKeyboardPreferences.set(this@PhormiKeyboardServiceV2,PhormiKeyboardPreferences.KEY_HAPTIC,!PhormiKeyboardPreferences.haptic(this));setInputView(render())};toggle("Key sounds",PhormiKeyboardPreferences.sound(this)){PhormiKeyboardPreferences.set(this@PhormiKeyboardServiceV2,PhormiKeyboardPreferences.KEY_SOUND,!PhormiKeyboardPreferences.sound(this));setInputView(render())};list.addView(pill("Theme: ${themeLabel(theme())}"){
            PhormiKeyboardPreferences.setTheme(this@PhormiKeyboardServiceV2, (theme() + 1) % 8)
            setInputView(render())
        },LinearLayout.LayoutParams(-1,scaled(36)).apply{setMargins(0,dp(3),0,dp(3))})
        list.addView(pill(if(PhormiKeyboardPreferences.wallpaperUri(this).isNullOrBlank())"Add wallpaper"else"Change wallpaper"){
            startActivity(Intent(this@PhormiKeyboardServiceV2, PhormiKeyboardMediaActivity::class.java).apply{addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);putExtra(PhormiKeyboardMediaActivity.EXTRA_MODE,"wallpaper")})
        },LinearLayout.LayoutParams(-1,scaled(36)).apply{setMargins(0,dp(3),0,dp(3))})
        if (!PhormiKeyboardPreferences.wallpaperUri(this).isNullOrBlank()) list.addView(pill("Remove wallpaper"){PhormiKeyboardPreferences.setWallpaperUri(this@PhormiKeyboardServiceV2,null);setInputView(render())},LinearLayout.LayoutParams(-1,scaled(36)).apply{setMargins(0,dp(3),0,dp(3))})
        list.addView(pill(if(PhormiKeyboardPreferences.pollinationsKey(this).isBlank())"Set sticker generation key"else"Sticker generation key ✓"){startActivity(Intent(this,PhormiAiEmojiActivity::class.java).apply{addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);putExtra(PhormiAiEmojiActivity.EXTRA_CONFIG_ONLY,true)})},LinearLayout.LayoutParams(-1,scaled(36)).apply{setMargins(0,dp(3),0,dp(3))});scroll.addView(list);root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f));return root}
    private fun themeLabel(value: Int): String = listOf("Midnight","Graphite","Ocean","Light","Slate","Forest","Rose","Sand").getOrElse(value) { "Midnight" }
    private fun addBackHeader(root:LinearLayout,label:String,action:()->Unit){root.addView(pill(label,action),LinearLayout.LayoutParams(-1,scaled(36)))}
    private fun render():View{
        if (panel != Panel.KEYBOARD || page != KeyboardPage.LETTERS) { finishGlide(); finishHangul() }
        shiftKeyView = null
        letterButtons.clear()
        predictionRow = null
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
                            // Keep the overlay (and its active touch gesture), update only the key geometry.
                            val oldKeys = getChildAt(0)
                            removeView(oldKeys)
                            letterButtons.clear(); shiftKeyView = null; renderedShiftState = -1
                            addView(buildKeyboard(), 0, FrameLayout.LayoutParams(-1, -1))
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
        finishGlide(); finishHangul()
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

    private fun stopVoice(refresh: Boolean, message: String = "") {
        voiceActive = false
        val recognizer = speechRecognizer
        speechRecognizer = null
        recognizer?.cancel(); recognizer?.destroy()
        voiceStatus = message
        if (refresh && isInputViewShown) setInputView(render())
    }

    fun startIntegratedVoiceRecognition(localeTag: String) {
        val connection = currentInputConnection ?: return
        if (!isInputViewShown || PhormiKeyboardTextEngine.isPrivateEditor(editorInfo)) {
            android.widget.Toast.makeText(this, "Voice typing is unavailable in a private or password field", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { stopVoice(true, "Speech recognition is unavailable on this device"); return }
        stopVoice(false)
        val session = inputSession
        val recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer = recognizer
        voiceActive = true
        voiceStatus = "Starting microphone…"
        fun current() = voiceActive && speechRecognizer === recognizer && session == inputSession && isInputViewShown
        fun status(text: String) { if (current()) { voiceStatus = text; setInputView(render()) } }
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: android.os.Bundle?) { status("Listening — tap Stop mic to cancel") }
            override fun onBeginningOfSpeech() { status("Listening…") }
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() { status("Processing speech…") }
            override fun onPartialResults(partialResults: android.os.Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { status(it.take(100)) }
            }
            override fun onEvent(eventType: Int, params: android.os.Bundle?) = Unit
            override fun onError(error: Int) {
                if (!current()) return
                val message = when(error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected. Tap Mic to try again."
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Voice connection failed. Try again when online."
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is required."
                    else -> "Voice typing stopped. Tap Mic to retry."
                }
                stopVoice(true, message)
            }
            override fun onResults(results: android.os.Bundle?) {
                if (!current()) return
                results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { connection.commitText(it, 1) }
                stopVoice(true)
            }
        })
        setInputView(render())
        runCatching { recognizer.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, localeTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }) }.onFailure { stopVoice(true, "Unable to start voice typing") }
    }
    private fun shareSelectedText(){val text=currentInputConnection?.getSelectedText(0)?.toString().orEmpty();if(text.isBlank()){android.widget.Toast.makeText(this,"Select text first",android.widget.Toast.LENGTH_SHORT).show();return};startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply{type="text/plain";putExtra(Intent.EXTRA_TEXT,text)},"Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}
    private fun showPendingInput() {
        if (isInputViewShown) setInputView(render())
    }
    private fun applyPendingInput() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (System.currentTimeMillis() - prefs.getLong("picked_at", 0L) > 10 * 60_000L) prefs.edit().clear().apply()
    }
    private fun insertPendingInput() {
        applyPendingInput()
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val uri = prefs.getString(KEY_PENDING_URI, null)
        val text = prefs.getString(KEY_PENDING_TEXT, null)
        val success = if (uri != null) commitContentToEditor(Uri.parse(uri))
            else if (text != null) currentInputConnection?.commitText(text, 1) == true else false
        if (success) prefs.edit().clear().apply()
        else android.widget.Toast.makeText(this, "This field could not receive the picked item", android.widget.Toast.LENGTH_SHORT).show()
        setInputView(render())
    }
    private fun commitTextToEditor(text: String) {
        finishGlide(); finishHangul()
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

    private fun commitSpace() {
        finishGlide(); finishHangul()
        val ic = currentInputConnection ?: return
        autoCorrectCurrentWord(ic)
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
        if (hangulKeys.isNotEmpty()) {
            hangulKeys = hangulKeys.dropLast(1)
            if (hangulKeys.isEmpty()) { ic.commitText("", 1); ic.finishComposingText() }
            else ic.setComposingText(PhormiHangulComposer.compose(hangulKeys), 1)
            return
        }
        val deletingGlide = glideComposing
        if (deletingGlide) ic.commitText("", 1)
        finishGlide()
        if (deletingGlide) return
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
        } else {
            val before = ic.getTextBeforeCursor(256, 0)?.toString().orEmpty()
            val platformLength = if (before.isEmpty()) 0 else android.icu.text.BreakIterator.getCharacterInstance().let {
                it.setText(before)
                before.length - it.preceding(before.length).coerceAtLeast(0)
            }
            val length = maxOf(platformLength, PhormiKeyboardInputRules.deletionLength(before))
            if (length > 0) ic.deleteSurroundingText(length, 0)
            else { ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL)); ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL)) }
        }
    }
    private fun moveCursor(delta:Int){finishGlide();finishHangul();val ic=currentInputConnection?:return;val code=if(delta<0)KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT;ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN,code));ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP,code));refreshAfterTextKey()}
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
    private fun editorAction(): Int {
        val info = editorInfo ?: return EditorInfo.IME_ACTION_NONE
        if (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) return EditorInfo.IME_ACTION_NONE
        return info.imeOptions and EditorInfo.IME_MASK_ACTION
    }
    private fun actionLabel(): String = editorInfo?.actionLabel?.toString() ?: when(editorAction()) {
        EditorInfo.IME_ACTION_GO -> "Go"; EditorInfo.IME_ACTION_SEARCH -> "Search"
        EditorInfo.IME_ACTION_SEND -> "Send"; EditorInfo.IME_ACTION_NEXT -> "Next"
        EditorInfo.IME_ACTION_DONE -> "Done"; EditorInfo.IME_ACTION_PREVIOUS -> "Previous"; else -> "↵"
    }
    private fun sendEditorAction() {
        finishGlide(); finishHangul()
        val ic = currentInputConnection ?: return
        autoCorrectCurrentWord(ic)
        val info = editorInfo
        if (info?.actionLabel != null && info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION == 0) {
            ic.performEditorAction(info.actionId); return
        }
        val action = editorAction()
        if (action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            ic.performEditorAction(action)
        } else if (info != null && info.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0) {
            ic.commitText("\n", 1); refreshAfterTextKey()
        } else {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        }
    }
    private fun installRepeat(view:View,action:()->Unit){view.setOnTouchListener{_,event->when(event.actionMasked){MotionEvent.ACTION_DOWN->{stopRepeat();feedback(view);action();repeatRunnable=object:Runnable{override fun run(){action();repeatHandler.postDelayed(this,55)}};repeatHandler.postDelayed(repeatRunnable!!,320);true};MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->{stopRepeat();true};else->true}}}
    private fun stopRepeat(){repeatRunnable?.let{repeatHandler.removeCallbacks(it)};repeatRunnable=null}
    private fun commitContentToEditor(uri:Uri):Boolean{finishGlide();finishHangul();if(Build.VERSION.SDK_INT<25)return false;val ic=currentInputConnection?:return false;val info=editorInfo?:return false;val mimeTypes=info.contentMimeTypes?:emptyArray();if(mimeTypes.isEmpty()){android.widget.Toast.makeText(this,"This field does not accept images",android.widget.Toast.LENGTH_SHORT).show();return false};val wanted=contentResolver.getType(uri).orEmpty();val accepted=wanted.isNotBlank()&&mimeTypes.any{it==wanted||(it.endsWith("/*")&&wanted.startsWith(it.removeSuffix("*")))};if(!accepted){android.widget.Toast.makeText(this,"This field does not accept that media type",android.widget.Toast.LENGTH_SHORT).show();return false};return runCatching{val description=android.content.ClipDescription("Phormi media",arrayOf(wanted));val contentInfo=InputContentInfo(uri,description,null);ic.commitContent(contentInfo,InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION,null)}.getOrDefault(false)}
}
