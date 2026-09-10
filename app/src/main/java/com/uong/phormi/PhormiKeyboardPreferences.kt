package com.uong.phormi

import android.content.Context

/** Local-only keyboard preferences. */
object PhormiKeyboardPreferences {
    private const val PREFS="phormi_keyboard"
    private const val SUGGESTIONS="suggestions";private const val AUTOCORRECT="autocorrect";private const val AUTO_CAPS="auto_caps";private const val HAPTIC="haptic";private const val SOUND="sound";private const val SIZE="size"
    private fun prefs(context:Context)=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
    fun suggestions(context:Context)=prefs(context).getBoolean(SUGGESTIONS,true);fun autocorrect(context:Context)=prefs(context).getBoolean(AUTOCORRECT,true);fun autoCaps(context:Context)=prefs(context).getBoolean(AUTO_CAPS,true);fun haptic(context:Context)=prefs(context).getBoolean(HAPTIC,true);fun sound(context:Context)=prefs(context).getBoolean(SOUND,false)
    fun size(context:Context)=prefs(context).getString(SIZE,"standard")?:"standard"
    fun set(context:Context,key:String,value:Boolean)=prefs(context).edit().putBoolean(key,value).apply()
    fun setSize(context:Context,value:String){val safe=if(value in setOf("compact","standard","large"))value else "standard";prefs(context).edit().putString(SIZE,safe).apply()}
    const val KEY_SUGGESTIONS=SUGGESTIONS;const val KEY_AUTOCORRECT=AUTOCORRECT;const val KEY_AUTO_CAPS=AUTO_CAPS;const val KEY_HAPTIC=HAPTIC;const val KEY_SOUND=SOUND
}
