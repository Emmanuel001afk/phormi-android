package com.uong.phormi

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView

/** One scrollable, expandable menu over the live tab (never a second browser page). */
internal class BrowserMenuPopup(
    private val activity: Activity,
    private val onAction: (String) -> Unit,
    private val onDismiss: () -> Unit
) {
    data class State(
        val title: String,
        val site: String,
        val desktop: Boolean,
        val canGoBack: Boolean,
        val canGoForward: Boolean,
        val hasPage: Boolean
    )

    private val ink = Color.rgb(235, 243, 250)
    private val muted = Color.rgb(154, 174, 190)
    private val accent = Color.rgb(75, 213, 207)
    private var popup: PopupWindow? = null
    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()

    fun dismiss() { popup?.dismiss(); popup = null }

    fun show(anchor: View, state: State) {
        dismiss()
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.rgb(15, 29, 44))
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), Color.rgb(46, 73, 90))
            }
            isFocusableInTouchMode = true
        }
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(9))
        }
        header.addView(label("Phormi · ${state.site}", 13f, accent).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        })
        header.addView(label(state.title.ifBlank { "New tab" }, 16f, ink).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(3), 0, 0)
        })
        root.addView(header)

        val quick = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(10), 0, dp(10), dp(8))
        }
        quickAction(quick, "＋ New tab", BrowserMenuActions.NEW_TAB)
        quickAction(quick, "✦ AI", BrowserMenuActions.AI)
        quickAction(quick, "↓ Downloads", BrowserMenuActions.DOWNLOADS)
        root.addView(quick)

        val scroll = ScrollView(activity).apply {
            isFillViewport = false
            isVerticalScrollBarEnabled = true
        }
        val sections = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, dp(10), dp(12))
        }
        scroll.addView(sections)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        group(sections, "Browsing", initiallyOpen = true) { items ->
            item(items, "Ghost mode", "Open a separate private session", BrowserMenuActions.GHOST)
            item(items, "Tab overview", "Find and switch tabs", BrowserMenuActions.TABS)
            item(items, if (state.desktop) "Desktop site  ·  On" else "Desktop site  ·  Off",
                "Keep this tab's mode during navigation", BrowserMenuActions.DESKTOP_MODE, state.hasPage)
            item(items, "Back", null, BrowserMenuActions.BACK, state.canGoBack)
            item(items, "Forward", null, BrowserMenuActions.FORWARD, state.canGoForward)
            item(items, "Refresh page", null, BrowserMenuActions.REFRESH, state.hasPage)
            item(items, "Find in page", null, BrowserMenuActions.FIND, state.hasPage)
            item(items, "Share page", null, BrowserMenuActions.SHARE, state.hasPage)
            item(items, "Favorite this page", null, BrowserMenuActions.FAVORITE, state.hasPage)
            item(items, "Print / save PDF", null, BrowserMenuActions.SAVE_PDF, state.hasPage)
            item(items, "Add site to home screen", null, BrowserMenuActions.INSTALL_SITE, state.hasPage)
        }
        group(sections, "Library & tabs", initiallyOpen = false) { items ->
            item(items, "Bookmarks", null, BrowserMenuActions.BOOKMARKS)
            item(items, "Favorites", null, BrowserMenuActions.FAVORITES)
            item(items, "History", null, BrowserMenuActions.HISTORY)
            item(items, "Tab groups", null, BrowserMenuActions.TAB_GROUPS)
            item(items, "Browsing environments", "Separate accounts", BrowserMenuActions.TAB_ENVIRONMENT)
            item(items, "Split screen", null, BrowserMenuActions.SPLIT_SCREEN)
            item(items, "Split this page", null, BrowserMenuActions.SAME_PAGE_SPLIT, state.hasPage)
        }
        group(sections, "Tools & AI", initiallyOpen = false) { items ->
            item(items, "AI assistant", "Ask about this tab or other tabs", BrowserMenuActions.AI)
            item(items, "Navigation lens", null, BrowserMenuActions.NAVIGATION_LENS, state.hasPage)
            item(items, "Object anchors", null, BrowserMenuActions.OBJECT_ANCHORS, state.hasPage)
            item(items, "Save page position", null, BrowserMenuActions.CAPSULE, state.hasPage)
            item(items, "Disposable email", null, BrowserMenuActions.DISPOSABLE_EMAIL)
            item(items, "Keyboard settings", "Suggestions and dictionary", BrowserMenuActions.KEYBOARD)
        }
        group(sections, "Privacy & protection", initiallyOpen = false) { items ->
            item(items, "Security center", null, BrowserMenuActions.SECURITY)
            item(items, "Lock this site", null, BrowserMenuActions.SITE_LOCK, state.hasPage)
            item(items, "Browser lock", null, BrowserMenuActions.BROWSER_LOCK)
            item(items, "VPN", null, BrowserMenuActions.VPN)
        }

        // Unlike the old Browse/Features/Settings pages, Settings is a persistent
        // command inside the same overlay. It leads to the dedicated settings screen.
        val settings = label("⚙    Settings                                      ›", 16f, ink).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), 0, dp(18), 0)
            isClickable = true; isFocusable = true
            contentDescription = "Settings"
            setOnClickListener { dispatch(BrowserMenuActions.SETTINGS) }
            setBackgroundColor(Color.rgb(23, 46, 61))
        }
        root.addView(settings, LinearLayout.LayoutParams(-1, dp(52)))

        val metrics = activity.resources.displayMetrics
        val width = minOf(dp(370), metrics.widthPixels - dp(16)).coerceAtLeast(dp(230))
        val location = IntArray(2)
        anchor.getLocationOnScreen(location)
        val height = (metrics.heightPixels - location[1] - anchor.height - dp(24))
            .coerceAtLeast(dp(180)).coerceAtMost(dp(690))
        popup = PopupWindow(root, width, height, true).apply {
            isOutsideTouchable = true
            inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            elevation = dp(12).toFloat()
            setOnDismissListener { popup = null; onDismiss() }
            showAsDropDown(anchor, -dp(4), dp(4), Gravity.END)
        }
        root.requestFocus()
    }

    private fun label(text: String, size: Float, color: Int) = TextView(activity).apply {
        this.text = text; textSize = size; setTextColor(color)
    }

    private fun dispatch(action: String) {
        dismiss()
        onAction(action)
    }

    private fun quickAction(parent: LinearLayout, text: String, action: String) {
        parent.addView(label(text, 13f, ink).apply {
            gravity = Gravity.CENTER
            isClickable = true; isFocusable = true
            contentDescription = text
            background = GradientDrawable().apply {
                setColor(Color.rgb(29, 55, 71)); cornerRadius = dp(12).toFloat()
            }
            setOnClickListener { dispatch(action) }
        }, LinearLayout.LayoutParams(0, dp(42), 1f).apply {
            marginStart = dp(3); marginEnd = dp(3)
        })
    }

    private fun group(parent: LinearLayout, title: String, initiallyOpen: Boolean,
        populate: (LinearLayout) -> Unit
    ) {
        val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        var open = initiallyOpen
        val heading = label("${if (open) "⌄" else "›"}   $title", 15f, accent).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(12), 0)
            isClickable = true; isFocusable = true
            contentDescription = "$title, ${if (open) "expanded" else "collapsed"}"
            background = GradientDrawable().apply {
                setColor(Color.rgb(22, 42, 59)); cornerRadius = dp(12).toFloat()
            }
            setOnClickListener {
                open = !open
                text = "${if (open) "⌄" else "›"}   $title"
                contentDescription = "$title, ${if (open) "expanded" else "collapsed"}"
                body.visibility = if (open) View.VISIBLE else View.GONE
            }
        }
        parent.addView(heading, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(5) })
        populate(body)
        body.visibility = if (open) View.VISIBLE else View.GONE
        parent.addView(body)
    }

    private fun item(parent: LinearLayout, title: String, detail: String?,
        action: String, enabled: Boolean = true
    ) {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(7), dp(10), dp(7))
            minimumHeight = dp(if (detail == null) 44 else 56)
            isEnabled = enabled
            isClickable = enabled; isFocusable = enabled
            contentDescription = if (detail == null) title else "$title. $detail"
            if (enabled) setOnClickListener { dispatch(action) }
        }
        row.addView(label(title, 14f, if (enabled) ink else muted))
        if (detail != null) row.addView(label(detail, 11f, muted))
        parent.addView(row, LinearLayout.LayoutParams(-1, -2))
    }
}
