package com.uong.phormi

import android.graphics.Rect
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import java.util.Locale

/**
 * Compatibility layer around the real IME service.
 * Every panel is routed to its real builder; this file only supplies geometry,
 * language-layout and gesture compatibility fixes.
 */
internal fun PhormiKeyboardServiceV2.render(): View {
    val panelName = runCatching {
        javaClass.walkHierarchyFields("panel")?.apply { isAccessible = true }?.get(this)?.toString()
    }.getOrDefault("KEYBOARD")

    val view = when (panelName) {
        "EMOJI" -> invokeBuilder("buildEmoji")
        "CLIPBOARD" -> invokeBuilder("buildClipboard")
        "AI_EMOJI" -> invokeBuilder("buildAiEmoji")
        "TOOLS" -> invokeBuilder("buildTools")
        "MEDIA" -> invokeBuilder("buildMedia")
        "SETTINGS" -> invokeBuilder("buildSettings")
        else -> invokeBuilder("buildKeyboard")
    }

    normalizeViewport(view)
    when (panelName) {
        "KEYBOARD" -> {
            applyLocaleLayout(view)
            decorateShiftState(view)
            installGlideCompat(view)
            installMultilingualLongPress(view)
        }
        "EMOJI" -> normalizeEmojiGrid(view)
    }
    return view
}

/** Do not manufacture a second navigation-bar area inside the IME. */
private fun PhormiKeyboardServiceV2.normalizeViewport(view: View) {
    val lp = view.layoutParams ?: LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT)
    lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
    view.layoutParams = lp
    view.minimumHeight = 0
    // IME windows are positioned by InputMethodService. Returning the insets unchanged avoids
    // the double bottom padding that produced the large dead strip in the previous build.
    view.setOnApplyWindowInsetsListener { _, insets -> insets }
    if (Build.VERSION.SDK_INT >= 23) view.requestApplyInsets()
}

/** Emoji cells have a stable physical size and the grid scrolls inside the keyboard viewport. */
private fun PhormiKeyboardServiceV2.normalizeEmojiGrid(root: View) {
    val scale = PhormiKeyboardPreferences.heightScale(this).coerceIn(.92f, 1.16f)
    val cell = (44f * resources.displayMetrics.density * scale).toInt().coerceAtLeast(1)
    fun visit(v: View) {
        if (v is Button) {
            val t = v.text?.toString().orEmpty()
            if (t.isNotBlank() && !t.contains(' ') && t != "ABC" && t != "✨" && t.length <= 4) {
                v.textSize = 20f
                v.layoutParams?.let { it.height = cell; v.layoutParams = it }
            }
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) visit(v.getChildAt(i))
    }
    visit(root)
}

/** Real Latin layout order for locales whose standard keyboard is not QWERTY. */
private fun PhormiKeyboardServiceV2.applyLocaleLayout(root: View) {
    val language = runCatching {
        currentInputMethodSubtype?.locale?.replace('_', '-')?.let(Locale::forLanguageTag)?.language
    }.getOrNull() ?: return
    val rows = when (language) {
        "fr" -> listOf("azertyuiop", "qsdfghjklm", "wxcvbn")
        "de", "cs" -> listOf("qwertzuiop", "asdfghjkl", "yxcvbnm")
        else -> return
    }
    val letters = ArrayList<Button>(26)
    fun collect(v: View) {
        if (v is Button && v.text?.toString()?.length == 1 && v.text.toString()[0].isLetter()) letters += v
        if (v is ViewGroup) for (i in 0 until v.childCount) collect(v.getChildAt(i))
    }
    collect(root)
    if (letters.size < 26) return
    rows.joinToString("").forEachIndexed { index, character ->
        val button = letters[index]
        button.text = character.toString()
        button.contentDescription = character.toString()
        button.setOnClickListener {
            val shift = javaClass.walkHierarchyFields("shift")?.apply { isAccessible = true }?.get(this) as? Boolean ?: false
            val caps = javaClass.walkHierarchyFields("capsLock")?.apply { isAccessible = true }?.get(this) as? Boolean ?: false
            val auto = javaClass.walkHierarchyFields("autoShift")?.apply { isAccessible = true }?.get(this) as? Boolean ?: false
            val out = if (shift || caps || auto) character.uppercaseChar().toString() else character.toString()
            invokePrivate("feedback", button)
            invokePrivate("commitTextToEditor", out)
            if (shift && !caps) javaClass.walkHierarchyFields("shift")?.apply { isAccessible = true }?.set(this, false)
            javaClass.walkHierarchyFields("autoShift")?.apply { isAccessible = true }?.set(this, false)
            invokePrivate("refreshPredictions", true)
            setInputView(render())
        }
    }
}

private fun PhormiKeyboardServiceV2.invokeBuilder(name: String): View = runCatching {
    val method = javaClass.walkHierarchyMethods(name, 0) ?: error("Builder $name not found")
    method.isAccessible = true
    method.invoke(this) as View
}.getOrElse { throw IllegalStateException("Unable to render Phormi keyboard panel: $name", it) }

private fun PhormiKeyboardServiceV2.invokePrivate(name: String, vararg args: Any?): Any? = runCatching {
    val method = javaClass.walkHierarchyMethods(name, args.size) ?: error("Method $name/${args.size} not found")
    method.isAccessible = true
    method.invoke(this, *args)
}.getOrNull()

private fun PhormiKeyboardServiceV2.decorateShiftState(root: View) {
    val shift = javaClass.walkHierarchyFields("shift")?.apply { isAccessible = true }?.get(this) as? Boolean ?: false
    val caps = javaClass.walkHierarchyFields("capsLock")?.apply { isAccessible = true }?.get(this) as? Boolean ?: false
    val auto = javaClass.walkHierarchyFields("autoShift")?.apply { isAccessible = true }?.get(this) as? Boolean ?: false
    fun visit(view: View) {
        if (view is Button) {
            when (view.text?.toString().orEmpty()) {
                "⇧", "⇧·", "⇧A", "⇧ LOCK" -> {
                    view.text = when { caps -> "⇧ LOCK"; shift -> "⇧·"; else -> "⇧" }
                    view.contentDescription = when { caps -> "Caps Lock on"; shift -> "Shift for next letter"; auto -> "Automatic capitalization"; else -> "Shift" }
                }
            }
        }
        if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i))
    }
    visit(root)
}

private fun PhormiKeyboardServiceV2.installMultilingualLongPress(root: View) {
    val locale = runCatching { currentInputMethodSubtype?.locale?.replace('_', '-')?.let(Locale::forLanguageTag) }.getOrNull() ?: Locale.getDefault()
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
        if (view is Button) {
            val base = view.text?.toString()?.lowercase(locale).orEmpty()
            alternatives[base]?.let { chars ->
                view.setOnLongClickListener {
                    val options = chars.map(Char::toString).toTypedArray()
                    AlertDialog.Builder(this).setTitle("$base — ${locale.displayLanguage}").setItems(options) { _, which ->
                        invokePrivate("commitTextToEditor", options[which])
                        invokePrivate("refreshPredictions", true)
                        setInputView(render())
                    }.show()
                    true
                }
            }
        }
        if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i))
    }
    visit(root)
}

/** Lightweight gesture compatibility; normal taps still use the service's click listeners. */
private fun PhormiKeyboardServiceV2.installGlideCompat(root: View) {
    fun isLetterButton(v: View): Boolean = v is Button && v.text?.toString()?.length == 1 && v.text?.toString()?.firstOrNull()?.isLetter() == true
    fun findLetter(parent: ViewGroup, rawX: Float, rawY: Float): Char? {
        val rect = Rect()
        for (i in parent.childCount - 1 downTo 0) {
            val child = parent.getChildAt(i); if (!child.isShown) continue; child.getGlobalVisibleRect(rect)
            if (!rect.contains(rawX.toInt(), rawY.toInt())) continue
            if (isLetterButton(child)) return child.text.toString()[0].lowercaseChar()
            if (child is ViewGroup) findLetter(child, rawX, rawY)?.let { return it }
        }
        return null
    }
    fun commitWord(word: String) { if (word.isNotBlank()) { invokePrivate("commitTextToEditor", word); invokePrivate("refreshPredictions", false); setInputView(render()) } }
    fun wire(view: View) {
        if (view is Button && isLetterButton(view)) {
            val base = view.text.toString()[0].lowercaseChar(); var downX = 0f; var downY = 0f; var gliding = false; val word = StringBuilder(); var last: Char? = null
            view.setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { downX = event.rawX; downY = event.rawY; gliding = false; word.setLength(0); word.append(base); last = base; false }
                    MotionEvent.ACTION_MOVE -> {
                        if (!gliding && (kotlin.math.abs(event.rawX - downX) > 18f * resources.displayMetrics.density || kotlin.math.abs(event.rawY - downY) > 18f * resources.displayMetrics.density)) gliding = true
                        if (gliding) { findLetter(root, event.rawX, event.rawY)?.let { if (it != last) { word.append(it); last = it } }; true } else false
                    }
                    MotionEvent.ACTION_UP -> { if (!gliding) view.performClick() else { findLetter(root, event.rawX, event.rawY)?.let { if (it != last) word.append(it) }; commitWord(word.toString()) }; true }
                    MotionEvent.ACTION_CANCEL -> { word.setLength(0); gliding = false; true }
                    else -> false
                }
            }
        }
        if (view is ViewGroup) for (i in 0 until view.childCount) wire(view.getChildAt(i))
    }
    wire(root)
}

private fun Class<*>.walkHierarchyFields(name: String): java.lang.reflect.Field? {
    var type: Class<*>? = this
    while (type != null) { type.declaredFields.firstOrNull { it.name == name }?.let { return it }; type = type.superclass }
    return null
}

private fun Class<*>.walkHierarchyMethods(name: String, arity: Int): java.lang.reflect.Method? {
    var type: Class<*>? = this
    while (type != null) { type.declaredMethods.firstOrNull { it.name == name && it.parameterTypes.size == arity }?.let { return it }; type = type.superclass }
    return null
}
