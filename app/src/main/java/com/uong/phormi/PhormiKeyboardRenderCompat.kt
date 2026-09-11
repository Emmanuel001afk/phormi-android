package com.uong.phormi

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.roundToInt

/** Renderer bridge for V2. Every panel resolves to a real in-IME surface. */
internal fun PhormiKeyboardServiceV2.render(): View {
    val panelName = runCatching { javaClass.walkHierarchyFields("panel")?.apply { isAccessible = true }?.get(this)?.toString() }.getOrDefault("KEYBOARD")
    val view = when (panelName) {
        "EMOJI" -> invokePrivateBuilder("buildEmoji")
        "CLIPBOARD" -> invokePrivateBuilder("buildClipboard")
        "AI_EMOJI" -> invokePrivateBuilder("buildAiEmoji")
        "TOOLS" -> buildToolsSurface()
        "MEDIA" -> buildMediaSurface()
        "SETTINGS" -> invokePrivateBuilder("buildSettings")
        else -> invokePrivateBuilder("buildKeyboard")
    }
    installResizeHandle(view)
    if (panelName == "KEYBOARD") {
        installGlideCompat(view)
        installDeleteCompat(view)
        decorateShiftState(view)
    }
    return view
}

private fun PhormiKeyboardServiceV2.invokePrivateBuilder(name: String): View = runCatching {
    javaClass.walkHierarchyMethods(name)?.apply { isAccessible = true }?.invoke(this) as View
}.getOrElse { throw IllegalStateException("Unable to render Phormi keyboard panel: $name", it) }

/** A stable, visible drag handle; the keyboard's saved height remains shared by every panel. */
private fun PhormiKeyboardServiceV2.installResizeHandle(root: View) {
    val container = root as? ViewGroup ?: return
    if (container.childCount == 0) return
    val old = container.getChildAt(0)
    if (old.contentDescription?.toString() == "Resize Phormi Keyboard") container.removeViewAt(0)

    val handle = object : View(this) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            paint.color = if (PhormiKeyboardPreferences.theme(this@installResizeHandle) == 3) Color.DKGRAY else Color.rgb(148, 163, 184)
            paint.strokeWidth = 3f * resources.displayMetrics.density
            paint.strokeCap = Paint.Cap.ROUND
            val cx = width / 2f
            val y = height / 2f
            canvas.drawLine(cx - 11f * resources.displayMetrics.density, y - 4f * resources.displayMetrics.density, cx + 11f * resources.displayMetrics.density, y - 4f * resources.displayMetrics.density, paint)
            canvas.drawLine(cx - 11f * resources.displayMetrics.density, y + 4f * resources.displayMetrics.density, cx + 11f * resources.displayMetrics.density, y + 4f * resources.displayMetrics.density, paint)
        }
    }.apply {
        background = GradientDrawable().apply {
            setColor(if (PhormiKeyboardPreferences.theme(this@installResizeHandle) == 3) Color.rgb(224, 228, 234) else Color.rgb(31, 41, 55))
            cornerRadius = 10f * resources.displayMetrics.density
        }
        contentDescription = "Resize Phormi Keyboard"
        isClickable = true
    }
    var startY = 0f
    var startLevel = PhormiKeyboardPreferences.height(this)
    var preview = startLevel
    handle.setOnTouchListener { _, event ->
        val density = resources.displayMetrics.density
        fun heightPx(level: Int) = (360f * density * PhormiKeyboardPreferences.heightScaleFor(level)).roundToInt()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startY = event.rawY; startLevel = PhormiKeyboardPreferences.height(this); preview = startLevel; true
            }
            MotionEvent.ACTION_MOVE -> {
                val delta = ((startY - event.rawY) / (26f * density)).roundToInt()
                preview = (startLevel + delta).coerceIn(0, 6)
                container.layoutParams = (container.layoutParams ?: ViewGroup.LayoutParams(-1, heightPx(preview))).apply { height = heightPx(preview) }
                container.minimumHeight = heightPx(preview)
                container.requestLayout()
                true
            }
            MotionEvent.ACTION_UP -> {
                PhormiKeyboardPreferences.setHeight(this, preview)
                setInputView(render())
                true
            }
            MotionEvent.ACTION_CANCEL -> { setInputView(render()); true }
            else -> true
        }
    }
    container.addView(handle, 0, ViewGroup.LayoutParams((56f * resources.displayMetrics.density).roundToInt(), (28f * resources.displayMetrics.density).roundToInt()))
}

/** Keep normal, one-shot and locked Shift visually distinct. */
private fun PhormiKeyboardServiceV2.decorateShiftState(root: View) {
    val shift = javaClass.walkHierarchyFields("shift")?.apply { isAccessible = true }?.get(this) as? Boolean ?: false
    val caps = javaClass.walkHierarchyFields("capsLock")?.apply { isAccessible = true }?.get(this) as? Boolean ?: false
    val auto = javaClass.walkHierarchyFields("autoShift")?.apply { isAccessible = true }?.get(this) as? Boolean ?: false
    walkViews(root) { view ->
        if (view is Button) {
            val text = (view as TextView).text?.toString().orEmpty()
            if (text == "⇧" || text == "⇧·" || text == "⇧A" || text == "⇧🔒" || text == "⇧ LOCK") {
                view.text = when {
                    caps -> "⇧ LOCK"
                    shift -> "⇧·"
                    auto -> "⇧"
                    else -> "⇧"
                }
                view.contentDescription = when {
                    caps -> "Caps Lock on"
                    shift -> "Shift for next letter"
                    auto -> "Automatic capitalization"
                    else -> "Shift"
                }
            }
        }
    }
}

/** Prevent the delete/re-render cycle from leaving some editors unable to accept the next key. */
private fun PhormiKeyboardServiceV2.installDeleteCompat(root: View) {
    walkViews(root) { view ->
        if (view !is Button) return@walkViews
        if ((view as TextView).text?.toString() != "⌫") return@walkViews
        var repeat: Runnable? = null
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        fun deleteOnce() {
            val ic = currentInputConnection ?: return
            runCatching {
                val selected = ic.getSelectedText(0)
                if (!selected.isNullOrEmpty()) ic.commitText("", 1)
                else if (!ic.deleteSurroundingTextInCodePoints(1, 0)) ic.deleteSurroundingText(1, 0)
            }
        }
        view.setOnClickListener { deleteOnce() }
        view.setOnLongClickListener {
            repeat?.let(handler::removeCallbacks)
            val r = object : Runnable {
                override fun run() {
                    if (currentInputConnection == null) return
                    deleteOnce(); handler.postDelayed(this, 55L)
                }
            }
            repeat = r; handler.postDelayed(r, 280L); true
        }
        view.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                repeat?.let(handler::removeCallbacks); repeat = null
            }
            false
        }
    }
}

/** Adds swipe typing without changing ordinary tap behavior. */
private fun PhormiKeyboardServiceV2.installGlideCompat(root: View) {
    fun isLetterButton(v: View): Boolean {
        if (v !is Button) return false
        val label = (v as TextView).text?.toString().orEmpty()
        return label.length == 1 && label[0].isLetter() && v.height >= (resources.displayMetrics.density * 40f)
    }
    fun findLetter(parent: ViewGroup, rawX: Float, rawY: Float): Char? {
        val rect = Rect()
        for (i in parent.childCount - 1 downTo 0) {
            val child = parent.getChildAt(i)
            if (!child.isShown) continue
            child.getGlobalVisibleRect(rect)
            if (!rect.contains(rawX.toInt(), rawY.toInt())) continue
            if (isLetterButton(child)) return (child as TextView).text.toString()[0].lowercaseChar()
            if (child is ViewGroup) findLetter(child, rawX, rawY)?.let { return it }
        }
        return null
    }
    fun commitWord(word: String) {
        runCatching {
            javaClass.walkHierarchyMethods("commitTextToEditor")?.apply { isAccessible = true }?.invoke(this, word)
            javaClass.walkHierarchyMethods("updateAutoCaps")?.apply { isAccessible = true }?.invoke(this)
            javaClass.walkHierarchyMethods("refreshPredictions")?.let { method ->
                method.isAccessible = true
                if (method.parameterTypes.size == 1) method.invoke(this, true) else method.invoke(this)
            }
        }
        setInputView(render())
    }
    fun wire(view: View) {
        if (view is Button && isLetterButton(view)) {
            val base = (view as TextView).text.toString()[0].lowercaseChar()
            var downX = 0f; var downY = 0f; var gliding = false; val word = StringBuilder(); var last: Char? = null
            view.setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { downX = event.rawX; downY = event.rawY; gliding = false; word.setLength(0); word.append(base); last = base; false }
                    MotionEvent.ACTION_MOVE -> {
                        if (abs(event.rawX - downX) > 18f * resources.displayMetrics.density || abs(event.rawY - downY) > 18f * resources.displayMetrics.density) {
                            gliding = true
                            findLetter(root as ViewGroup, event.rawX, event.rawY)?.let { if (it != last) { word.append(it); last = it } }
                            true
                        } else false
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!gliding) { view.performClick(); true } else {
                            findLetter(root as ViewGroup, event.rawX, event.rawY)?.let { if (it != last) word.append(it) }
                            if (word.isNotEmpty()) commitWord(word.toString()) else true
                            true
                        }
                    }
                    MotionEvent.ACTION_CANCEL -> { word.setLength(0); gliding = false; true }
                    else -> false
                }
            }
        }
        if (view is ViewGroup) for (i in 0 until view.childCount) wire(view.getChildAt(i))
    }
    wire(root)
}

private fun PhormiKeyboardServiceV2.buildToolsSurface(): View {
    val height = dpCompat((360f * PhormiKeyboardPreferences.heightScale(this)).roundToInt())
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dpCompat(8), dpCompat(5), dpCompat(8), dpCompat(5))
        setBackgroundColor(if (PhormiKeyboardPreferences.theme(this@buildToolsSurface) == 3) Color.rgb(242,244,247) else Color.rgb(13,18,30))
        layoutParams = LinearLayout.LayoutParams(-1, height)
        minimumHeight = height
    }
    root.addView(TextView(this).apply {
        text = "Phormi Keyboard Tools"
        textSize = 18f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        setTextColor(if (PhormiKeyboardPreferences.theme(this@buildToolsSurface) == 3) Color.rgb(20,27,36) else Color.WHITE)
        gravity = Gravity.CENTER_VERTICAL
    }, LinearLayout.LayoutParams(-1, dpCompat(38)))

    val scroll = ScrollView(this).apply { isFillViewport = true; isVerticalScrollBarEnabled = false }
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    fun tool(label: String, action: () -> Unit) {
        list.addView(Button(this).apply {
            text = label; isAllCaps = false; minWidth = 0; minHeight = 0; stateListAnimator = null
            textSize = 14f; setTextColor(if (PhormiKeyboardPreferences.theme(this@buildToolsSurface) == 3) Color.rgb(20,27,36) else Color.WHITE)
            background = GradientDrawable().apply { setColor(if (PhormiKeyboardPreferences.theme(this@buildToolsSurface) == 3) Color.WHITE else Color.rgb(39,48,64)); cornerRadius = dpCompat(10).toFloat() }
            contentDescription = label; setOnClickListener { action() }
        }, LinearLayout.LayoutParams(-1, dpCompat(46)).apply { setMargins(0, dpCompat(3), 0, dpCompat(3)) })
    }
    tool("⚙  Keyboard Settings") { setPanelCompat("SETTINGS"); setInputView(render()) }

    val editBox = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dpCompat(6), dpCompat(5), dpCompat(6), dpCompat(5))
        background = GradientDrawable().apply { setColor(if (PhormiKeyboardPreferences.theme(this@buildToolsSurface) == 3) Color.rgb(224,228,234) else Color.rgb(31,41,55)); cornerRadius = dpCompat(12).toFloat() }
    }
    editBox.addView(TextView(this).apply { text = "Text editing"; textSize = 13f; typeface = android.graphics.Typeface.DEFAULT_BOLD; setTextColor(if (PhormiKeyboardPreferences.theme(this@buildToolsSurface) == 3) Color.rgb(20,27,36) else Color.WHITE) }, LinearLayout.LayoutParams(-1, dpCompat(28)))
    val editRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
    fun edit(label: String, action: () -> Unit) { editRow.addView(Button(this).apply { text = label; isAllCaps = false; minWidth = 0; minHeight = 0; stateListAnimator = null; textSize = 12f; setTextColor(if (PhormiKeyboardPreferences.theme(this@buildToolsSurface) == 3) Color.rgb(20,27,36) else Color.WHITE); setOnClickListener { action() } }, LinearLayout.LayoutParams(0, dpCompat(42), 1f).apply { setMargins(dpCompat(2), 0, dpCompat(2), 0) }) }
    edit("Select all") { selectAll() }; edit("Copy") { copySelection() }; edit("Paste") { pasteClipboard() }
    editBox.addView(editRow); list.addView(editBox, LinearLayout.LayoutParams(-1, dpCompat(80)).apply { setMargins(0, dpCompat(3), 0, dpCompat(3)) })

    tool(if (PhormiKeyboardPreferences.aiEmoji(this)) "✨  AI Emoji: ON" else "✨  AI Emoji: OFF") { PhormiKeyboardPreferences.set(this, PhormiKeyboardPreferences.KEY_AI_EMOJI, !PhormiKeyboardPreferences.aiEmoji(this)); setInputView(render()) }
    tool("↔  Cursor movement") { showToast("Use ← / → or slide across Space") }
    tool("⌨  URL / email shortcuts") { showToast("Shown only in matching URL and email fields") }
    tool("GIF & Stickers") { setPanelCompat("MEDIA"); setInputView(render()) }
    tool("😀  Emoji") { setPanelCompat("EMOJI"); prepareAiContextCompat(); setInputView(render()) }
    tool("🎙  Voice typing") { startVoiceCompat() }
    tool("🌐  Switch keyboard") { runCatching { switchToNextInputMethod(false) } }
    tool("ABC Keyboard") { setPanelCompat("KEYBOARD"); setInputView(render()) }

    scroll.addView(list); root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
    root.addView(Button(this).apply { text = "← Back to keyboard"; isAllCaps = false; minHeight = 0; setTextColor(if (PhormiKeyboardPreferences.theme(this@buildToolsSurface) == 3) Color.rgb(20,27,36) else Color.WHITE); background = GradientDrawable().apply { setColor(if (PhormiKeyboardPreferences.theme(this@buildToolsSurface) == 3) Color.WHITE else Color.rgb(31,41,55)); cornerRadius = dpCompat(12).toFloat() }; setOnClickListener { setPanelCompat("KEYBOARD"); setInputView(render()) } }, LinearLayout.LayoutParams(-1, dpCompat(42)))
    return root
}

private fun PhormiKeyboardServiceV2.buildMediaSurface(): View {
    val height = dpCompat((360f * PhormiKeyboardPreferences.heightScale(this)).roundToInt())
    val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dpCompat(8), dpCompat(5), dpCompat(8), dpCompat(5)); setBackgroundColor(if (PhormiKeyboardPreferences.theme(this@buildMediaSurface) == 3) Color.rgb(242,244,247) else Color.rgb(13,18,30)); layoutParams = LinearLayout.LayoutParams(-1, height); minimumHeight = height }
    root.addView(TextView(this).apply { text = "GIF & Stickers"; textSize = 18f; typeface = android.graphics.Typeface.DEFAULT_BOLD; setTextColor(if (PhormiKeyboardPreferences.theme(this@buildMediaSurface) == 3) Color.rgb(20,27,36) else Color.WHITE); gravity = Gravity.CENTER_VERTICAL }, LinearLayout.LayoutParams(-1, dpCompat(38)))
    val scroll = ScrollView(this).apply { isFillViewport = true; isVerticalScrollBarEnabled = false }
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    fun action(label: String, mode: String) { list.addView(Button(this).apply { text = label; isAllCaps = false; minHeight = 0; textSize = 14f; setTextColor(if (PhormiKeyboardPreferences.theme(this@buildMediaSurface) == 3) Color.rgb(20,27,36) else Color.WHITE); background = GradientDrawable().apply { setColor(if (PhormiKeyboardPreferences.theme(this@buildMediaSurface) == 3) Color.WHITE else Color.rgb(39,48,64)); cornerRadius = dpCompat(10).toFloat() }; setOnClickListener { startActivity(android.content.Intent(this@buildMediaSurface, PhormiKeyboardMediaActivity::class.java).putExtra(PhormiKeyboardMediaActivity.EXTRA_MODE, mode).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } }, LinearLayout.LayoutParams(-1, dpCompat(48)).apply { setMargins(0, dpCompat(3), 0, dpCompat(3)) }) }
    action("GIF — import from device", "gif")
    action("Sticker — import from device", "sticker")
    list.addView(Button(this).apply { text = "✨ Create AI Emoji"; isAllCaps = false; minHeight = 0; textSize = 14f; setTextColor(if (PhormiKeyboardPreferences.theme(this@buildMediaSurface) == 3) Color.rgb(20,27,36) else Color.WHITE); background = GradientDrawable().apply { setColor(if (PhormiKeyboardPreferences.theme(this@buildMediaSurface) == 3) Color.WHITE else Color.rgb(39,48,64)); cornerRadius = dpCompat(10).toFloat() }; setOnClickListener { setPanelCompat("AI_EMOJI"); prepareAiContextCompat(); setInputView(render()) } }, LinearLayout.LayoutParams(-1, dpCompat(48)).apply { setMargins(0, dpCompat(3), 0, dpCompat(3)) })
    scroll.addView(list); root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
    root.addView(Button(this).apply { text = "← Back to keyboard"; isAllCaps = false; minHeight = 0; setTextColor(if (PhormiKeyboardPreferences.theme(this@buildMediaSurface) == 3) Color.rgb(20,27,36) else Color.WHITE); background = GradientDrawable().apply { setColor(if (PhormiKeyboardPreferences.theme(this@buildMediaSurface) == 3) Color.WHITE else Color.rgb(31,41,55)); cornerRadius = dpCompat(12).toFloat() }; setOnClickListener { setPanelCompat("KEYBOARD"); setInputView(render()) } }, LinearLayout.LayoutParams(-1, dpCompat(42)))
    return root
}

private fun PhormiKeyboardServiceV2.startVoiceCompat() {
    runCatching { javaClass.walkHierarchyMethods("startVoice")?.apply { isAccessible = true }?.invoke(this) }.onFailure { showToastCompat("Voice input is unavailable") }
}

private fun PhormiKeyboardServiceV2.showToastCompat(text: String) = android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_SHORT).show()
private fun PhormiKeyboardServiceV2.dpCompat(value: Int): Int = (value * resources.displayMetrics.density).roundToInt().coerceAtLeast(1)
private fun PhormiKeyboardServiceV2.setPanelCompat(name: String) { runCatching { val field = javaClass.walkHierarchyFields("panel")?.apply { isAccessible = true } ?: return; val value = field.type.enumConstants?.firstOrNull { it.toString() == name } ?: return; field.set(this, value) } }
private fun PhormiKeyboardServiceV2.prepareAiContextCompat() { runCatching { javaClass.walkHierarchyMethods("prepareAiContext")?.apply { isAccessible = true }?.invoke(this) } }
private fun walkViews(root: View, action: (View) -> Unit) { action(root); if (root is ViewGroup) for (i in 0 until root.childCount) walkViews(root.getChildAt(i), action) }
private fun Class<*>.walkHierarchyFields(name: String): java.lang.reflect.Field? { var type: Class<*>? = this; while (type != null) { type.declaredFields.firstOrNull { it.name == name }?.let { return it }; type = type.superclass }; return null }
private fun Class<*>.walkHierarchyMethods(name: String): java.lang.reflect.Method? { var type: Class<*>? = this; while (type != null) { type.declaredMethods.firstOrNull { it.name == name }?.let { return it }; type = type.superclass }; return null }