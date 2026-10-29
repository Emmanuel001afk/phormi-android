package com.uong.phormi

import android.content.Context

/** Browser appearance and gesture settings shared by the chrome and settings screen. */
object PhormiBrowserPreferences {
    const val STORE = "phormi_tabs"
    const val TEXT_ZOOM = "browser_text_zoom"
    const val BOTTOM_TOOLBAR = "browser_bottom_toolbar"
    const val PULL_TO_REFRESH = "pull_to_refresh"
    const val TAB_RETENTION = "tab_retention"

    fun textZoom(context: Context): Int = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
        .getInt(TEXT_ZOOM, 100).coerceIn(80, 200)

    fun showBottomToolbar(context: Context): Boolean = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
        .getBoolean(BOTTOM_TOOLBAR, true)

    // An accidental swipe on a long page should not reload and discard form state.
    fun pullToRefresh(context: Context): Boolean = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
        .getBoolean(PULL_TO_REFRESH, false)
}
