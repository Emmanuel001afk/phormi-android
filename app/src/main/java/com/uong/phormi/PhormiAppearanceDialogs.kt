package com.uong.phormi

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Shared, touch-sized appearance pickers instead of eight squeezed buttons in one row. */
internal object PhormiAppearanceDialogs {
    fun accent(activity: Activity, changed: () -> Unit) {
        val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(activity, 16), dp(activity, 8), dp(activity, 16), dp(activity, 8)) }
        val dialog = AlertDialog.Builder(activity).setTitle("Browser + keyboard accent").setView(content).setNegativeButton("Cancel", null).create()
        PhormiAppearance.accents.forEach { accent ->
            content.addView(Button(activity).apply {
                text = accent.label + if (PhormiAppearance.selected(activity) == accent.id) "  ✓" else ""
                isAllCaps = false; setTextColor(PhormiAppearance.onAccent(accent.color))
                backgroundTintList = android.content.res.ColorStateList.valueOf(accent.color)
                setOnClickListener { PhormiAppearance.setAccent(activity, accent.id); changed(); dialog.dismiss() }
            }, LinearLayout.LayoutParams(-1, dp(activity, 54)))
        }
        dialog.show()
    }

    fun wallpaper(activity: Activity, keyboard: Boolean, device: () -> Unit, changed: () -> Unit) {
        val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(activity, 12), dp(activity, 8), dp(activity, 12), dp(activity, 8)) }
        val scroll = ScrollView(activity).apply { addView(content) }
        val dialog = AlertDialog.Builder(activity).setTitle(if (keyboard) "Keyboard wallpapers" else "Browser wallpapers")
            .setView(scroll).setPositiveButton("Use my photo") { _, _ -> device() }.setNegativeButton("Cancel", null).create()
        PhormiWallpapers.styles.chunked(2).forEach { pair ->
            val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEach { style ->
                row.addView(TextView(activity).apply {
                    text = style.label + if (PhormiWallpapers.selected(activity, keyboard) == style.id) "  ✓" else ""
                    gravity = Gravity.BOTTOM or Gravity.START; textSize = 15f
                    setTextColor(Color.WHITE); setShadowLayer(6f, 0f, 1f, Color.BLACK)
                    setPadding(dp(activity, 12), dp(activity, 10), dp(activity, 12), dp(activity, 12))
                    background = PhormiWallpapers.drawable(style.id, true) ?: android.graphics.drawable.ColorDrawable(Color.rgb(24, 31, 48))
                    contentDescription = "Use ${style.label} wallpaper"
                    isClickable = true; isFocusable = true
                    setOnClickListener { PhormiWallpapers.select(activity, style.id, keyboard); changed(); dialog.dismiss() }
                }, LinearLayout.LayoutParams(0, dp(activity, 110), 1f).apply { setMargins(dp(activity, 4), dp(activity, 4), dp(activity, 4), dp(activity, 4)) })
            }
            content.addView(row)
        }
        dialog.show()
    }
    private fun dp(activity: Activity, value: Int) = (value * activity.resources.displayMetrics.density).toInt()
}
