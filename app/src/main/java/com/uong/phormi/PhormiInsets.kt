package com.uong.phormi

import android.app.Activity
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/** Explicit Android 15/16 edge-to-edge and IME spacing, retaining each layout's own padding. */
internal object PhormiInsets {
    fun apply(activity: Activity, root: View, resizeForIme: Boolean = true) {
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        val left = root.paddingLeft; val top = root.paddingTop; val right = root.paddingRight; val bottom = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val keyboard = if (resizeForIme) insets.getInsets(WindowInsetsCompat.Type.ime()).bottom else 0
            view.setPadding(left + bars.left, top + bars.top, right + bars.right, bottom + maxOf(bars.bottom, keyboard))
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }
}
