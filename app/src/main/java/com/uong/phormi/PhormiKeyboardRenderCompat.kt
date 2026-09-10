package com.uong.phormi

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Renderer bridge for V2. Every panel must resolve to an actual in-IME surface. */
internal fun PhormiKeyboardServiceV2.render(): View {
    val panelName = runCatching {
        javaClass.walkHierarchyFields("panel")?.apply { isAccessible = true }?.get(this)?.toString()
    }.getOrDefault("KEYBOARD")
    return when (panelName) {
        "EMOJI" -> invokePrivateBuilder("buildEmoji")
        "CLIPBOARD" -> invokePrivateBuilder("buildClipboard")
        "AI_EMOJI" -> invokePrivateBuilder("buildAiEmoji")
        "TOOLS" -> buildToolsSurface()
        "MEDIA" -> buildMediaSurface()
        else -> invokePrivateBuilder("buildKeyboard")
    }
}

private fun PhormiKeyboardServiceV2.invokePrivateBuilder(name: String): View = runCatching {
    javaClass.walkHierarchyMethods(name)?.apply { isAccessible = true }?.invoke(this) as View
}.getOrElse { throw IllegalStateException("Unable to render Phormi keyboard panel: $name", it) }

private fun PhormiKeyboardServiceV2.buildToolsSurface(): View {
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dpCompat(10), dpCompat(8), dpCompat(10), dpCompat(8))
        setBackgroundColor(Color.rgb(13, 18, 30))
    }
    val title = TextView(this).apply {
        text = "Phormi Keyboard Tools"
        textSize = 18f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.WHITE)
        setPadding(0, 0, 0, dpCompat(8))
    }
    root.addView(title)
    val scroll = ScrollView(this)
    val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    fun action(label: String, summary: String? = null, onClick: () -> Unit) {
        val b = Button(this).apply {
            text = label
            isAllCaps = false
            minHeight = 0
            setTextColor(Color.WHITE)
            setOnClickListener { onClick() }
        }
        content.addView(b, LinearLayout.LayoutParams(-1, dpCompat(48)).apply { setMargins(0, dpCompat(3), 0, dpCompat(3)) })
        if (summary != null) content.addView(TextView(this).apply {
            text = summary; textSize = 12f; setTextColor(Color.rgb(148, 163, 184)); setPadding(dpCompat(12), 0, dpCompat(12), dpCompat(5))
        })
    }

    action("⚙ Settings", "Open Phormi's full keyboard settings.") {
        startActivity(Intent(this, PhormiKeyboardSettingsActivity::class.java))
    }
    action(if (PhormiKeyboardPreferences.aiEmoji(this)) "✨ AI Emoji: ON" else "✨ AI Emoji: OFF", "Turn context-aware generated reactions on or off.") {
        PhormiKeyboardPreferences.set(this, PhormiKeyboardPreferences.KEY_AI_EMOJI, !PhormiKeyboardPreferences.aiEmoji(this))
        setInputView(render())
    }
    action("↕ Height −", "Reduce keyboard height one step.") {
        PhormiKeyboardPreferences.setHeight(this, PhormiKeyboardPreferences.height(this) - 1); setInputView(render())
    }
    action("↕ Height +", "Increase keyboard height one step.") {
        PhormiKeyboardPreferences.setHeight(this, PhormiKeyboardPreferences.height(this) + 1); setInputView(render())
    }
    action("😀 Emoji") { setPanelCompat("EMOJI"); prepareAiContextCompat(); setInputView(render()) }
    action("📋 Clipboard") { setPanelCompat("CLIPBOARD"); setInputView(render()) }
    action("🎙 Voice") { setPanelCompat("KEYBOARD"); setInputView(render()) }
    action("ABC Keyboard") { setPanelCompat("KEYBOARD"); setInputView(render()) }

    scroll.addView(content)
    root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
    return root
}

private fun PhormiKeyboardServiceV2.buildMediaSurface(): View {
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dpCompat(10), dpCompat(8), dpCompat(10), dpCompat(8))
        setBackgroundColor(Color.rgb(13, 18, 30))
    }
    root.addView(TextView(this).apply {
        text = "GIF & Stickers"
        textSize = 18f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.WHITE)
        setPadding(0, 0, 0, dpCompat(6))
    })
    root.addView(TextView(this).apply {
        text = "Choose media without changing the active keyboard. Android may display its protected document picker when importing a local file."
        textSize = 12f
        setTextColor(Color.rgb(148, 163, 184))
        setPadding(0, 0, 0, dpCompat(8))
    })
    fun button(label: String, mode: String) {
        root.addView(Button(this).apply {
            text = label; isAllCaps = false; minHeight = 0; setTextColor(Color.WHITE)
            setOnClickListener { startActivity(Intent(this@buildMediaSurface, PhormiKeyboardMediaActivity::class.java).putExtra(PhormiKeyboardMediaActivity.EXTRA_MODE, mode)) }
        }, LinearLayout.LayoutParams(-1, dpCompat(50)).apply { setMargins(0, dpCompat(4), 0, dpCompat(4)) })
    }
    button("GIF — import from device", "gif")
    button("Sticker — open Phormi sticker packs", "sticker")
    root.addView(Button(this).apply {
        text = "✨ Create AI Emoji"; isAllCaps = false; minHeight = 0; setTextColor(Color.WHITE)
        setOnClickListener { setPanelCompat("AI_EMOJI"); prepareAiContextCompat(); setInputView(render()) }
    }, LinearLayout.LayoutParams(-1, dpCompat(50)).apply { setMargins(0, dpCompat(4), 0, dpCompat(4)) })
    root.addView(Button(this).apply {
        text = "← Back to keyboard"; isAllCaps = false; minHeight = 0; setTextColor(Color.WHITE)
        setOnClickListener { setPanelCompat("KEYBOARD"); setInputView(render()) }
    }, LinearLayout.LayoutParams(-1, dpCompat(50)).apply { setMargins(0, dpCompat(4), 0, dpCompat(4)) })
    return root
}

private fun PhormiKeyboardServiceV2.dpCompat(value: Int): Int = (value * resources.displayMetrics.density).toInt().coerceAtLeast(1)

private fun PhormiKeyboardServiceV2.setPanelCompat(name: String) {
    runCatching {
        val field = javaClass.walkHierarchyFields("panel")?.apply { isAccessible = true } ?: return
        val enumClass = field.type
        val value = enumClass.enumConstants?.firstOrNull { it.toString() == name } ?: return
        field.set(this, value)
    }
}

private fun PhormiKeyboardServiceV2.prepareAiContextCompat() {
    runCatching { javaClass.walkHierarchyMethods("prepareAiContext")?.apply { isAccessible = true }?.invoke(this) }
}

private fun Class<*>.walkHierarchyFields(name: String): java.lang.reflect.Field? {
    var type: Class<*>? = this
    while (type != null) {
        type.declaredFields.firstOrNull { it.name == name }?.let { return it }
        type = type.superclass
    }
    return null
}

private fun Class<*>.walkHierarchyMethods(name: String): java.lang.reflect.Method? {
    var type: Class<*>? = this
    while (type != null) {
        type.declaredMethods.firstOrNull { it.name == name && it.parameterTypes.isEmpty() }?.let { return it }
        type = type.superclass
    }
    return null
}
