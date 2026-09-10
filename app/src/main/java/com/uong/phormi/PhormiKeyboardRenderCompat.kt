package com.uong.phormi

import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import java.io.File

/**
 * Compatibility render dispatcher for the rebuilt IME.
 *
 * The V2 service keeps its panel builders private. This extension restores the
 * render() call site used by the service while keeping those builders private.
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
    val method = generateSequence(PhormiKeyboardServiceV2::class.java) { it.superclass }
        .flatMap { it.declaredMethods.asSequence() }
        .firstOrNull { it.name == methodName && it.parameterTypes.isEmpty() }
        ?: error("Missing Phormi keyboard panel builder: $methodName")
    method.isAccessible = true
    val view = method.invoke(this) as View
    return if (panel.endsWith("EMOJI") && panel != "AI_EMOJI") decorateEmojiGrid(view) else view
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
