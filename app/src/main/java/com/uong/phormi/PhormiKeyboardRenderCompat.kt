package com.uong.phormi

import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale

internal fun PhormiKeyboardServiceV2.render(): View {
    val panelName = runCatching { javaClass.walkHierarchyFields("panel")?.apply { isAccessible = true }?.get(this)?.toString() }.getOrDefault("KEYBOARD")
    val view = when (panelName) {
        "EMOJI" -> invokePrivateBuilder("buildEmoji")
        "CLIPBOARD" -> invokePrivateBuilder("buildClipboard")
        "AI_EMOJI" -> buildAiEmojiSurface()
        "TOOLS" -> buildToolsSurface()
        "MEDIA" -> buildMediaSurface()
        "SETTINGS" -> buildSettingsSurface()
        else -> invokePrivateBuilder("buildKeyboard")
    }
    installImeInsets(view)
    if (panelName == "KEYBOARD") { installGlideCompat(view); decorateShiftState(view); installMultilingualLongPress(view) }
    return view
}

private fun PhormiKeyboardServiceV2.installImeInsets(view: View) {
    if (Build.VERSION.SDK_INT < 23) return
    view.setOnApplyWindowInsetsListener { v, insets ->
        val bottom = if (Build.VERSION.SDK_INT >= 30) insets.getInsets(WindowInsets.Type.navigationBars()).bottom else insets.systemWindowInsetBottom
        if (bottom > 0) v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, bottom)
        insets
    }
    view.requestApplyInsets()
}

private fun PhormiKeyboardServiceV2.invokePrivateBuilder(name: String): View = runCatching { javaClass.walkHierarchyMethods(name)?.apply { isAccessible = true }?.invoke(this) as View }.getOrElse { throw IllegalStateException("Unable to render Phormi keyboard panel: $name", it) }

private fun PhormiKeyboardServiceV2.decorateShiftState(root: View) {
    val shift = javaClass.walkHierarchyFields("shift")?.apply { isAccessible = true }?.get(this) as? Boolean ?: false
    val caps = javaClass.walkHierarchyFields("capsLock")?.apply { isAccessible = true }?.get(this) as? Boolean ?: false
    val auto = javaClass.walkHierarchyFields("autoShift")?.apply { isAccessible = true }?.get(this) as? Boolean ?: false
    fun visit(view: View) {
        if (view is Button) {
            val text = view.text?.toString().orEmpty()
            if (text == "⇧" || text == "⇧·" || text == "⇧A" || text == "⇧ LOCK") { view.text = when { caps -> "⇧ LOCK"; shift -> "⇧·"; auto -> "⇧"; else -> "⇧" }; view.contentDescription = when { caps -> "Caps Lock on"; shift -> "Shift for next letter"; auto -> "Automatic capitalization"; else -> "Shift" } }
        }
        if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i))
    }
    visit(root)
}

private fun PhormiKeyboardServiceV2.installMultilingualLongPress(root: View) {
    val locale = runCatching { currentInputMethodSubtype?.locale?.replace('_', '-')?.let(Locale::forLanguageTag) }.getOrElse { Locale.getDefault() } ?: Locale.getDefault()
    val alternatives = when (locale.language) {
        "yo" -> mapOf("a" to "àáā", "e" to "ẹéè", "i" to "íì", "o" to "ọóò", "s" to "ṣśš", "n" to "ńñ")
        "ig" -> mapOf("a" to "áà", "e" to "ẹéè", "i" to "ịíì", "o" to "ọóò", "u" to "ụúù", "n" to "ṅñ")
        "fr" -> mapOf("a" to "àâäæ", "c" to "ç", "e" to "éèêë", "i" to "îï", "o" to "ôöœ", "u" to "ùûü", "y" to "ÿ")
        "es" -> mapOf("a" to "áà", "e" to "éè", "i" to "íì", "o" to "óò", "u" to "úü", "n" to "ñ")
        "pt" -> mapOf("a" to "áàâãä", "c" to "ç", "e" to "éêë", "i" to "í", "o" to "óôõö", "u" to "úü")
        "de" -> mapOf("a" to "äá", "o" to "öó", "u" to "üú", "s" to "ß")
        "it" -> mapOf("a" to "àá", "e" to "èé", "i" to "ìí", "o" to "òó", "u" to "ùú")
        "tr" -> mapOf("c" to "ç", "g" to "ğ", "i" to "ıİ", "o" to "ö", "s" to "ş", "u" to "ü")
        "vi" -> mapOf("a" to "áàảãạâă", "e" to "éèẻẽẹê", "i" to "íìỉĩị", "o" to "óòỏõọôơ", "u" to "úùủũụư", "d" to "đ")
        "pl" -> mapOf("a" to "ąá", "c" to "ć", "e" to "ęé", "l" to "ł", "n" to "ń", "o" to "ó", "s" to "ś", "z" to "źż")
        "cs" -> mapOf("a" to "á", "c" to "č", "d" to "ď", "e" to "éě", "i" to "í", "n" to "ň", "o" to "ó", "r" to "ř", "s" to "š", "t" to "ť", "u" to "úů", "z" to "ž")
        "ro" -> mapOf("a" to "ăâ", "i" to "î", "s" to "ș", "t" to "ț")
        "hu" -> mapOf("a" to "á", "e" to "é", "i" to "í", "o" to "óöő", "u" to "úüű")
        else -> emptyMap()
    }
    if (alternatives.isEmpty()) return
    fun visit(view: View) {
        if (view is Button) { val base = view.text?.toString()?.lowercase(locale).orEmpty(); val chars = alternatives[base]; if (!chars.isNullOrBlank()) view.setOnLongClickListener { val options = chars.map { it.toString() }.toTypedArray(); androidx.appcompat.app.AlertDialog.Builder(this).setTitle("$base — ${locale.displayLanguage}").setItems(options) { _, which -> javaClass.walkHierarchyMethods("commitTextToEditor")?.apply { isAccessible = true }?.invoke(this, options[which]); javaClass.walkHierarchyMethods("refreshPredictions")?.apply { isAccessible = true }?.invoke(this, true); setInputView(render()) }.show(); true } }
        if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i))
    }
    visit(root)
}

private fun PhormiKeyboardServiceV2.installGlideCompat(root: View) {
    fun isLetterButton(v: View): Boolean = v is Button && v.text?.toString()?.length == 1 && v.text?.toString()?.firstOrNull()?.isLetter() == true && v.height >= resources.displayMetrics.density * 40f
    fun findLetter(parent: ViewGroup, rawX: Float, rawY: Float): Char? { val rect = Rect(); for (i in parent.childCount - 1 downTo 0) { val child = parent.getChildAt(i); if (!child.isShown) continue; child.getGlobalVisibleRect(rect); if (!rect.contains(rawX.toInt(), rawY.toInt())) continue; if (isLetterButton(child)) return child.text.toString()[0].lowercaseChar(); if (child is ViewGroup) findLetter(child, rawX, rawY)?.let { return it } }; return null }
    fun commitWord(word: String) { runCatching { javaClass.walkHierarchyMethods("commitTextToEditor")?.apply { isAccessible = true }?.invoke(this, word); javaClass.walkHierarchyMethods("refreshPredictions")?.apply { isAccessible = true }?.invoke(this) }; setInputView(render()) }
    fun wire(view: View) { if (view is Button && isLetterButton(view)) { val base = view.text.toString()[0].lowercaseChar(); var downX = 0f; var downY = 0f; var gliding = false; val word = StringBuilder(); var last: Char? = null; view.setOnTouchListener { _, event -> when (event.actionMasked) { MotionEvent.ACTION_DOWN -> { downX = event.rawX; downY = event.rawY; gliding = false; word.setLength(0); word.append(base); last = base; false }; MotionEvent.ACTION_MOVE -> { if (kotlin.math.abs(event.rawX - downX) > 18f * resources.displayMetrics.density || kotlin.math.abs(event.rawY - downY) > 18f * resources.displayMetrics.density) { gliding = true; findLetter(root, event.rawX, event.rawY)?.let { if (it != last) { word.append(it); last = it } }; true } else false }; MotionEvent.ACTION_UP -> { if (!gliding) view.performClick() else { findLetter(root, event.rawX, event.rawY)?.let { if (it != last) word.append(it) }; if (word.isNotEmpty()) commitWord(word.toString()) }; true }; MotionEvent.ACTION_CANCEL -> { word.setLength(0); gliding = false; true }; else -> false } } } if (view is ViewGroup) for (i in 0 until view.childCount) wire(view.getChildAt(i)) }
    wire(root)
}

private fun PhormiKeyboardServiceV2.buildToolsSurface(): View {
    val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dpCompat(10), dpCompat(8), dpCompat(10), dpCompat(8)); setBackgroundColor(Color.rgb(13,18,30)) }
    root.addView(TextView(this).apply { text = "Phormi Keyboard Tools"; textSize = 18f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE); setPadding(0, 0, 0, dpCompat(8)) })
    val scroll = ScrollView(this); val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    fun action(label: String, summary: String? = null, onClick: () -> Unit) { val b = Button(this).apply { text = label; isAllCaps = false; minHeight = 0; setTextColor(Color.WHITE); setOnClickListener { onClick() } }; content.addView(b, LinearLayout.LayoutParams(-1, dpCompat(48)).apply { setMargins(0, dpCompat(3), 0, dpCompat(3)) }); if (summary != null) content.addView(TextView(this).apply { text = summary; textSize = 12f; setTextColor(Color.rgb(148, 163, 184)); setPadding(dpCompat(12), 0, dpCompat(12), dpCompat(5)) }) }
    action("⚙ Settings", "Open keyboard language, size, appearance and behavior settings.") { startActivity(Intent(this, PhormiKeyboardSettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    action(if (PhormiKeyboardPreferences.aiEmoji(this)) "✨ AI Emoji: ON" else "✨ AI Emoji: OFF", "Turn context-aware reactions on or off.") { PhormiKeyboardPreferences.set(this, PhormiKeyboardPreferences.KEY_AI_EMOJI, !PhormiKeyboardPreferences.aiEmoji(this)); setInputView(render()) }
    action("↕ Height −") { PhormiKeyboardPreferences.setHeight(this, PhormiKeyboardPreferences.height(this) - 1); setInputView(render()) }
    action("↕ Height +") { PhormiKeyboardPreferences.setHeight(this, PhormiKeyboardPreferences.height(this) + 1); setInputView(render()) }
    action("🌐 Languages") { startActivity(Intent(android.provider.Settings.ACTION_INPUT_METHOD_SUBTYPE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    action("😀 Emoji") { setPanelCompat("EMOJI"); setInputView(render()) }
    action("📋 Clipboard") { setPanelCompat("CLIPBOARD"); setInputView(render()) }
    action("🎙 Voice") { setPanelCompat("KEYBOARD"); setInputView(render()) }
    action("ABC Keyboard") { setPanelCompat("KEYBOARD"); setInputView(render()) }
    scroll.addView(content); root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f)); return root
}

private fun PhormiKeyboardServiceV2.buildMediaSurface(): View {
    val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dpCompat(10), dpCompat(8), dpCompat(10), dpCompat(8)); setBackgroundColor(Color.rgb(13,18,30)) }
    root.addView(TextView(this).apply { text = "GIF & Stickers"; textSize = 18f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE); setPadding(0, 0, 0, dpCompat(6)) })
    fun button(label: String, mode: String) { root.addView(Button(this).apply { text = label; isAllCaps = false; minHeight = 0; setTextColor(Color.WHITE); setOnClickListener { startActivity(Intent(this@buildMediaSurface, PhormiKeyboardMediaActivity::class.java).putExtra(PhormiKeyboardMediaActivity.EXTRA_MODE, mode).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }, LinearLayout.LayoutParams(-1, dpCompat(50)).apply { setMargins(0, dpCompat(4), 0, dpCompat(4)) }) }
    button("GIF — import from device", "gif"); button("Sticker — open Phormi sticker packs", "sticker")
    root.addView(Button(this).apply { text = "✨ Create AI Emoji"; isAllCaps = false; minHeight = 0; setTextColor(Color.WHITE); setOnClickListener { setPanelCompat("AI_EMOJI"); setInputView(render()) } }, LinearLayout.LayoutParams(-1, dpCompat(50)).apply { setMargins(0, dpCompat(4), 0, dpCompat(4)) })
    root.addView(Button(this).apply { text = "← Back to keyboard"; isAllCaps = false; minHeight = 0; setTextColor(Color.WHITE); setOnClickListener { setPanelCompat("KEYBOARD"); setInputView(render()) } }, LinearLayout.LayoutParams(-1, dpCompat(50)).apply { setMargins(0, dpCompat(4), 0, dpCompat(4)) }); return root
}

private fun PhormiKeyboardServiceV2.buildAiEmojiSurface(): View {
    val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dpCompat(16), dpCompat(12), dpCompat(16), dpCompat(12)); setBackgroundColor(Color.rgb(13,18,30)) }
    root.addView(TextView(this).apply { text = "✨ AI Emoji"; textSize = 20f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE); gravity = Gravity.CENTER; setPadding(0, 0, 0, dpCompat(8)) })
    root.addView(TextView(this).apply { text = "Create or choose a context-aware reaction without leaving the text field."; textSize = 13f; setTextColor(Color.rgb(203, 213, 225)); gravity = Gravity.CENTER; setPadding(0, 0, 0, dpCompat(12)) })
    root.addView(Button(this).apply { text = "Open AI Emoji Studio"; isAllCaps = false; minHeight = 0; setOnClickListener { startActivity(Intent(this@buildAiEmojiSurface, PhormiAiEmojiActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }, LinearLayout.LayoutParams(-1, dpCompat(52)))
    root.addView(Button(this).apply { text = "← Back"; isAllCaps = false; minHeight = 0; setOnClickListener { setPanelCompat("KEYBOARD"); setInputView(render()) } }, LinearLayout.LayoutParams(-1, dpCompat(52)).apply { topMargin = dpCompat(8) })
    return root
}

private fun PhormiKeyboardServiceV2.buildSettingsSurface(): View {
    val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dpCompat(16), dpCompat(12), dpCompat(16), dpCompat(12)); setBackgroundColor(Color.rgb(13,18,30)) }
    root.addView(TextView(this).apply { text = "⚙ Keyboard Settings"; textSize = 20f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE); gravity = Gravity.CENTER; setPadding(0, 0, 0, dpCompat(8)) })
    root.addView(TextView(this).apply { text = "Language, keyboard height, appearance, suggestions, autocorrect, haptics and AI Emoji are managed here."; textSize = 13f; setTextColor(Color.rgb(203, 213, 225)); gravity = Gravity.CENTER; setPadding(0, 0, 0, dpCompat(12)) })
    root.addView(Button(this).apply { text = "Open full keyboard settings"; isAllCaps = false; minHeight = 0; setOnClickListener { startActivity(Intent(this@buildSettingsSurface, PhormiKeyboardSettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }, LinearLayout.LayoutParams(-1, dpCompat(52)))
    root.addView(Button(this).apply { text = "← Back"; isAllCaps = false; minHeight = 0; setOnClickListener { setPanelCompat("KEYBOARD"); setInputView(render()) } }, LinearLayout.LayoutParams(-1, dpCompat(52)).apply { topMargin = dpCompat(8) })
    return root
}

private fun PhormiKeyboardServiceV2.dpCompat(value: Int): Int = (value * resources.displayMetrics.density).toInt().coerceAtLeast(1)
private fun PhormiKeyboardServiceV2.setPanelCompat(name: String) { runCatching { val field = javaClass.walkHierarchyFields("panel")?.apply { isAccessible = true } ?: return; val value = field.type.enumConstants?.firstOrNull { it.toString() == name } ?: return; field.set(this, value) } }
private fun Class<*>.walkHierarchyFields(name: String): java.lang.reflect.Field? { var type: Class<*>? = this; while (type != null) { type.declaredFields.firstOrNull { it.name == name }?.let { return it }; type = type.superclass }; return null }
private fun Class<*>.walkHierarchyMethods(name: String): java.lang.reflect.Method? { var type: Class<*>? = this; while (type != null) { type.declaredMethods.firstOrNull { it.name == name && it.parameterTypes.size == 0 }?.let { return it }; type = type.superclass }; return null }
