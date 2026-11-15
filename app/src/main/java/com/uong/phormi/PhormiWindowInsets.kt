package com.uong.phormi

import android.app.Activity
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/** Keep interactive app content clear of Android 15/16 system bars and cutouts. */
object PhormiWindowInsets {
    fun apply(activity: Activity) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val root = content.getChildAt(0) ?: return
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        val left = root.paddingLeft; val top = root.paddingTop
        val right = root.paddingRight; val bottom = root.paddingBottom
        // A floating keyboard must not reserve docked-sized space underneath it; pad only
        // for the system bars. A docked keyboard uses the ime() inset like before.
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val reserveIme = if (PhormiKeyboardPreferences.floating(view.context)) 0 else ime.bottom
            view.setPadding(left + bars.left, top + bars.top, right + bars.right, bottom + maxOf(bars.bottom, reserveIme))
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }
}
