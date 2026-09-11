package com.uong.phormi

import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Renderer bridge for V2. Every panel must resolve to an actual in-IME surface. */
internal fun PhormiKeyboardServiceV2.render(): View {
    val panelName = runCatching { javaClass.walkHierarchyFields("panel")?.apply { isAccessible = true }?.get(this)?.toString() }.getOrDefault("KEYBOARD")
    val view = when (panelName) {
        "EMOJI" -> invokePrivateBuilder("buildEmoji")
        "CLIPBOARD" -> invokePrivateBuilder("buildClipboard")
        "AI_EMOJI" -> invokePrivateBuilder("buildAiEmoji")
        "TOOLS" -> invokePrivateBuilder("buildTools")
        "MEDIA" -> invokePrivateBuilder("buildMedia")
        "SETTINGS" -> invokePrivateBuilder("buildSettings")
        else -> invokePrivateBuilder("buildKeyboard")
    }
    if (panelName == "KEYBOARD") installGlideCompat(view)
    return view
}

private fun PhormiKeyboardServiceV2.invokePrivateBuilder(name: String): View = runCatching {
    javaClass.walkHierarchyMethods(name)?.apply { isAccessible = true }?.invoke(this) as View
}.getOrElse { throw IllegalStateException("Unable to render Phormi keyboard panel: $name", it) }

/** Adds swipe typing without changing the stable V2 renderer. Ordinary taps remain ordinary taps. */
private fun PhormiKeyboardServiceV2.installGlideCompat(root: View) {
    fun isLetterButton(v: View): Boolean = v is Button && v.text?.toString()?.length == 1 && v.text.toString()[0].isLetter() && v.height >= (resources.displayMetrics.density * 40f)
    fun findLetter(parent: ViewGroup, rawX: Float, rawY: Float): Char? {
        val rect = Rect()
        for (i in parent.childCount - 1 downTo 0) {
            val child = parent.getChildAt(i)
            if (!child.isShown) continue
            child.getGlobalVisibleRect(rect)
            if (!rect.contains(rawX.toInt(), rawY.toInt())) continue
            if (isLetterButton(child)) return child.text.toString()[0].lowercaseChar()
            if (child is ViewGroup) findLetter(child, rawX, rawY)?.let { return it }
        }
        return null
    }
    fun commitWord(word: String) {
        runCatching {
            javaClass.walkHierarchyMethods("commitTextToEditor")?.apply { isAccessible = true }?.invoke(this, word)
            javaClass.walkHierarchyMethods("updateAutoCaps")?.apply { isAccessible = true }?.invoke(this)
            javaClass.walkHierarchyMethods("refreshPredictions")?.apply { isAccessible = true }?.invoke(this)
        }
        setInputView(render())
    }

    fun wire(view: View) {
        if (view is Button && isLetterButton(view)) {
            val base = view.text.toString()[0].lowercaseChar()
            var downX = 0f; var downY = 0f; var gliding = false; val word = StringBuilder(); var last: Char? = null
            view.setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { downX = event.rawX; downY = event.rawY; gliding = false; word.setLength(0); word.append(base); last = base; false }
                    MotionEvent.ACTION_MOVE -> {
                        if (kotlin.math.abs(event.rawX - downX) > 18f * resources.displayMetrics.density || kotlin.math.abs(event.rawY - downY) > 18f * resources.displayMetrics.density) {
                            gliding = true
                            (root as? ViewGroup)?.let { findLetter(it, event.rawX, event.rawY) }?.let { if (it != last) { word.append(it); last = it } }
                            true
                        } else false
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!gliding) { view.performClick(); true } else {
                            (root as? ViewGroup)?.let { findLetter(it, event.rawX, event.rawY) }?.let { if (it != last) word.append(it) }
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
    val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dpCompat(10), dpCompat(8), dpCompat(10), dpCompat(8)); setBackgroundColor(Color.rgb(13,18,30)) }
    root.addView(TextView(this).apply { text="Phormi Keyboard Tools"; textSize=18f; typeface=Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE); setPadding(0,0,0,dpCompat(8)) })
    val scroll=ScrollView(this); val content=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
    fun action(label:String,summary:String?=null,onClick:()->Unit){val b=Button(this).apply{text=label;isAllCaps=false;minHeight=0;setTextColor(Color.WHITE);setOnClickListener{onClick()}};content.addView(b,LinearLayout.LayoutParams(-1,dpCompat(48)).apply{setMargins(0,dpCompat(3),0,dpCompat(3))});if(summary!=null)content.addView(TextView(this).apply{text=summary;textSize=12f;setTextColor(Color.rgb(148,163,184));setPadding(dpCompat(12),0,dpCompat(12),dpCompat(5))})}
    action("⚙ Settings","Open Phormi's full keyboard settings."){startActivity(Intent(this,PhormiKeyboardSettingsActivity::class.java))}
    action(if(PhormiKeyboardPreferences.aiEmoji(this))"✨ AI Emoji: ON"else"✨ AI Emoji: OFF","Turn context-aware generated reactions on or off."){PhormiKeyboardPreferences.set(this,PhormiKeyboardPreferences.KEY_AI_EMOJI,!PhormiKeyboardPreferences.aiEmoji(this));setInputView(render())}
    action("↕ Height −","Reduce keyboard height one step."){PhormiKeyboardPreferences.setHeight(this,PhormiKeyboardPreferences.height(this)-1);setInputView(render())}
    action("↕ Height +","Increase keyboard height one step."){PhormiKeyboardPreferences.setHeight(this,PhormiKeyboardPreferences.height(this)+1);setInputView(render())}
    action("😀 Emoji"){setPanelCompat("EMOJI");prepareAiContextCompat();setInputView(render())};action("📋 Clipboard"){setPanelCompat("CLIPBOARD");setInputView(render())};action("🎙 Voice"){setPanelCompat("KEYBOARD");setInputView(render())};action("ABC Keyboard"){setPanelCompat("KEYBOARD");setInputView(render())}
    scroll.addView(content);root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f));return root
}

private fun PhormiKeyboardServiceV2.buildMediaSurface(): View {
    val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dpCompat(10),dpCompat(8),dpCompat(10),dpCompat(8));setBackgroundColor(Color.rgb(13,18,30))}
    root.addView(TextView(this).apply{text="GIF & Stickers";textSize=18f;typeface=Typeface.DEFAULT_BOLD;setTextColor(Color.WHITE);setPadding(0,0,0,dpCompat(6))})
    root.addView(TextView(this).apply{text="Choose media without changing the active keyboard. Android may display its protected document picker when importing a local file.";textSize=12f;setTextColor(Color.rgb(148,163,184));setPadding(0,0,0,dpCompat(8))})
    fun button(label:String,mode:String){root.addView(Button(this).apply{text=label;isAllCaps=false;minHeight=0;setTextColor(Color.WHITE);setOnClickListener{startActivity(Intent(this@buildMediaSurface,PhormiKeyboardMediaActivity::class.java).putExtra(PhormiKeyboardMediaActivity.EXTRA_MODE,mode))}},LinearLayout.LayoutParams(-1,dpCompat(50)).apply{setMargins(0,dpCompat(4),0,dpCompat(4))})}
    button("GIF — import from device","gif");button("Sticker — open Phormi sticker packs","sticker")
    root.addView(Button(this).apply{text="✨ Create AI Emoji";isAllCaps=false;minHeight=0;setTextColor(Color.WHITE);setOnClickListener{setPanelCompat("AI_EMOJI");prepareAiContextCompat();setInputView(render())}},LinearLayout.LayoutParams(-1,dpCompat(50)).apply{setMargins(0,dpCompat(4),0,dpCompat(4))})
    root.addView(Button(this).apply{text="← Back to keyboard";isAllCaps=false;minHeight=0;setTextColor(Color.WHITE);setOnClickListener{setPanelCompat("KEYBOARD");setInputView(render())}},LinearLayout.LayoutParams(-1,dpCompat(50)).apply{setMargins(0,dpCompat(4),0,dpCompat(4))});return root
}

private fun PhormiKeyboardServiceV2.dpCompat(value:Int):Int=(value*resources.displayMetrics.density).toInt().coerceAtLeast(1)
private fun PhormiKeyboardServiceV2.setPanelCompat(name:String){runCatching{val field=javaClass.walkHierarchyFields("panel")?.apply{isAccessible=true}?:return;val value=field.type.enumConstants?.firstOrNull{it.toString()==name}?:return;field.set(this,value)}}
private fun PhormiKeyboardServiceV2.prepareAiContextCompat(){runCatching{javaClass.walkHierarchyMethods("prepareAiContext")?.apply{isAccessible=true}?.invoke(this)}}
private fun Class<*>.walkHierarchyFields(name:String):java.lang.reflect.Field?{var type:Class<*>?=this;while(type!=null){type.declaredFields.firstOrNull{it.name==name}?.let{return it};type=type.superclass};return null}
private fun Class<*>.walkHierarchyMethods(name:String):java.lang.reflect.Method?{var type:Class<*>?=this;while(type!=null){type.declaredMethods.firstOrNull{it.name==name&&it.parameterTypes.isEmpty()}?.let{return it};type=type.superclass};return null}
