package com.uong.phormi

import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import java.io.File

/**
 * Compatibility render dispatcher for the rebuilt IME.
 *
 * The V2 service keeps its panel builders private. This extension restores the
 * render() call site while adding Phormi-only presentation behavior around the
 * private builders without changing the browser's input architecture.
 */
internal fun PhormiKeyboardServiceV2.render(): View {
    val panel = runCatching {
        val field = PhormiKeyboardServiceV2::class.java.getDeclaredField("panel").apply { isAccessible = true }
        field.get(this)?.toString().orEmpty()
    }.getOrDefault("KEYBOARD")
    val methodName = when {
        panel.endsWith("EMOJI") && panel != "AI_EMOJI" -> "buildEmoji"
        panel == "AI_EMOJI" -> "buildAiEmoji"
        panel == "CLIPBOARD" -> "buildClipboard"
        else -> "buildKeyboard"
    }
    var type: Class<*>? = PhormiKeyboardServiceV2::class.java
    var method: java.lang.reflect.Method? = null
    while (type != null && method == null) {
        method = type.declaredMethods.firstOrNull { it.name == methodName && it.parameterTypes.isEmpty() }
        type = type.superclass
    }
    val builder = method ?: error("Missing Phormi keyboard panel builder: $methodName")
    builder.isAccessible = true
    val built = builder.invoke(this) as View
    val decorated = when {
        panel.endsWith("EMOJI") && panel != "AI_EMOJI" -> decorateEmojiGrid(built)
        panel == "KEYBOARD" -> decorateContextRail(decorateDraftRescue(built))
        else -> built
    }
    applyKeyboardHeight(decorated)
    return decorated
}

/**
 * Phormi Draft Rescue is a keyboard-only recovery action: the user explicitly
 * saves the current text, and Phormi keeps a short-lived local draft per app.
 * The action is unavailable for passwords, URI/email fields, and fields that
 * opt out of personalized learning.
 */
private fun PhormiKeyboardServiceV2.decorateDraftRescue(view: View): View {
    if (view !is LinearLayout) return view
    val info = runCatching {
        PhormiKeyboardServiceV2::class.java.getDeclaredField("editorInfo").apply { isAccessible = true }
            .get(this) as? android.view.inputmethod.EditorInfo
    }.getOrNull()
    if (!PhormiKeyboardDraftRescue.canUse(info)) return view

    val toolbar = view.getChildAt(0) as? android.widget.HorizontalScrollView ?: return view
    val row = toolbar.getChildAt(0) as? LinearLayout ?: return view
    val currentText = PhormiKeyboardDraftRescue.snapshot(currentInputConnection, info)
    val draft = PhormiKeyboardDraftRescue.find(this, info)
    val action = if (currentText.isNotBlank()) "Save" else if (draft != null) "Restore" else "Save"
    val button = Button(this).apply {
        text = "🛟 $action"
        textSize = 12f
        setTextColor(Color.rgb(229, 231, 235))
        isAllCaps = false
        setMinWidth(0)
        setMinHeight(0)
        stateListAnimator = null
        setPadding(dpCompat(7), 0, dpCompat(7), 0)
        background = GradientDrawable().apply {
            setColor(Color.rgb(31, 41, 55))
            cornerRadius = dpCompat(13).toFloat()
        }
        contentDescription = "Phormi Draft Rescue: $action"
        setOnClickListener {
            performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
            val latestInfo = runCatching {
                PhormiKeyboardServiceV2::class.java.getDeclaredField("editorInfo").apply { isAccessible = true }
                    .get(this@decorateDraftRescue) as? android.view.inputmethod.EditorInfo
            }.getOrNull()
            if (latestInfo == null) return@setOnClickListener
            val latestText = PhormiKeyboardDraftRescue.snapshot(currentInputConnection, latestInfo)
            if (latestText.isNotBlank()) {
                val saved = PhormiKeyboardDraftRescue.save(this@decorateDraftRescue, latestInfo, latestText)
                Toast.makeText(this@decorateDraftRescue, if (saved) "Draft rescued for this app" else "Draft could not be saved", Toast.LENGTH_SHORT).show()
            } else {
                val restored = PhormiKeyboardDraftRescue.restore(this@decorateDraftRescue, currentInputConnection, latestInfo)
                Toast.makeText(this@decorateDraftRescue, if (restored) "Draft restored" else "No rescued draft", Toast.LENGTH_SHORT).show()
            }
            setInputView(render())
        }
    }
    row.addView(button, LinearLayout.LayoutParams(dpCompat(88), dpCompat(38)).apply {
        setMargins(dpCompat(2), 0, dpCompat(2), 0)
    })
    return view
}

/**
 * Phormi Context Rail: a compact, local semantic reaction strip that stays in
 * the normal keyboard while the user types. It combines the conversation's
 * detected mood with ordinary keyboard suggestions, rather than hiding the
 * feature behind a separate AI screen.
 */
private fun PhormiKeyboardServiceV2.decorateContextRail(view: View): View {
    val info = runCatching {
        PhormiKeyboardServiceV2::class.java.getDeclaredField("editorInfo").apply { isAccessible = true }
            .get(this) as? android.view.inputmethod.EditorInfo
    }.getOrNull()
    if (view !is LinearLayout || info == null || PhormiKeyboardTextEngine.isPassword(info) ||
        PhormiKeyboardTextEngine.isUriLike(info) || PhormiKeyboardTextEngine.isNoPersonalizedLearning(info)) return view

    val text = PhormiKeyboardTextEngine.contextBeforeCursor(currentInputConnection)
    val moods = PhormiKeyboardAiContext.suggestions(text).take(4)
    if (moods.isEmpty()) return view

    val rail = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        contentDescription = "Phormi contextual reactions"
        setPadding(dpCompat(2), 0, dpCompat(2), 0)
    }
    val label = android.widget.TextView(this).apply {
        setText("React")
        textSize = 11f
        setTextColor(Color.rgb(148, 163, 184))
        gravity = Gravity.CENTER
        contentDescription = "Contextual reactions"
    }
    rail.addView(label, LinearLayout.LayoutParams(dpCompat(46), dpCompat(36)))
    moods.forEach { mood ->
        val button = android.widget.Button(this).apply {
            setText(mood.emoji)
            textSize = 20f
            isAllCaps = false
            setMinWidth(0)
            setMinHeight(0)
            stateListAnimator = null
            contentDescription = "${mood.label} reaction"
            background = GradientDrawable().apply {
                setColor(Color.rgb(31, 41, 55))
                cornerRadius = dpCompat(11).toFloat()
            }
            setOnClickListener {
                performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                PhormiKeyboardServiceV2.commitExternalText(this@decorateContextRail, mood.emoji)
            }
        }
        rail.addView(button, LinearLayout.LayoutParams(0, dpCompat(36), 1f).apply {
            setMargins(dpCompat(2), 0, dpCompat(2), 0)
        })
    }
    val insertAt = if (view.childCount > 1) 1 else view.childCount
    view.addView(rail, insertAt, LinearLayout.LayoutParams(-1, dpCompat(38)))
    return view
}

/** Apply the user's height setting to the actual fixed-height keyboard rows. */
private fun PhormiKeyboardServiceV2.applyKeyboardHeight(view: View) {
    val scale = PhormiKeyboardPreferences.heightScale(this)
    if (scale == 1f) return
    resizeFixedKeyboardRows(view, scale)
}

private fun PhormiKeyboardServiceV2.resizeFixedKeyboardRows(view: View, scale: Float) {
    val params = view.layoutParams
    if (params != null && params.height in dpCompat(44)..dpCompat(56)) {
        params.height = (params.height * scale).toInt().coerceAtLeast(dpCompat(36))
        view.layoutParams = params
    }
    if (view is ViewGroup) {
        for (index in 0 until view.childCount) resizeFixedKeyboardRows(view.getChildAt(index), scale)
    }
}

private fun PhormiKeyboardServiceV2.decorateEmojiGrid(view: View): View {
    val files = runCatching {
        val field = PhormiKeyboardServiceV2::class.java.getDeclaredField("aiFiles").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        (field.get(this) as? List<File>).orEmpty().filter { it.isFile && it.length() > 0 }.take(4)
    }.getOrDefault(emptyList())
    if (files.isEmpty() || view !is LinearLayout) return view

    val scroll = view.childrenSequence().filterIsInstance<ScrollView>().firstOrNull() ?: return view
    val grid = scroll.getChildAt(0) as? LinearLayout ?: return view
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        contentDescription = "Generated emoji suggestions"
    }
    val cell = dpCompat(50)
    files.forEach { file ->
        val frame = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                setColor(Color.rgb(30, 41, 59))
                cornerRadius = dpCompat(12).toFloat()
            }
            contentDescription = "Emoji suggestion"
            isClickable = true
            isFocusable = true
            setOnClickListener {
                val uri = PhormiKeyboardStickerStore.contentUri(this@decorateEmojiGrid, file)
                PhormiKeyboardServiceV2.commitPickedContent(this@decorateEmojiGrid, uri)
            }
        }
        val bitmap = BitmapFactory.decodeFile(file.absolutePath)
        val image = ImageView(this).apply {
            setImageBitmap(bitmap)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dpCompat(4), dpCompat(4), dpCompat(4), dpCompat(4))
            contentDescription = "Emoji suggestion"
        }
        frame.addView(image, FrameLayout.LayoutParams(-1, -1))
        row.addView(frame, LinearLayout.LayoutParams(cell, cell).apply {
            setMargins(dpCompat(2), dpCompat(2), dpCompat(2), dpCompat(2))
        })
    }
    grid.addView(row, 0, LinearLayout.LayoutParams(-1, cell))
    return view
}

private fun PhormiKeyboardServiceV2.dpCompat(value: Int): Int =
    (value * resources.displayMetrics.density).toInt().coerceAtLeast(1)

private fun LinearLayout.childrenSequence(): Sequence<View> =
    (0 until childCount).asSequence().map { getChildAt(it) }
