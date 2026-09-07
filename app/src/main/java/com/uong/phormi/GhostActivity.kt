package com.uong.phormi

import android.content.pm.ActivityInfo
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Separate private-browsing task. Ghost has its own WebView data directory and
 * never persists a private session to normal browser preferences.
 */
class GhostActivity : AppCompatActivity() {
    private data class GhostTab(val id: Int, val webView: WebView, val chip: TextView)
    private val tabs = mutableListOf<GhostTab>()
    private var nextId = 1
    private var activeId = -1
    private lateinit var host: FrameLayout
    private lateinit var strip: LinearLayout
    private lateinit var url: EditText
    private lateinit var count: TextView
    private var restoredFromRotation = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            // Ghost runs in :ghost, so this suffix gives its WebView its own disk profile.
            runCatching { WebView.setDataDirectorySuffix("phormi_ghost") }
        }
        buildUi()

        val savedUrls = savedInstanceState?.getStringArrayList("ghost_urls").orEmpty()
        val savedActive = savedInstanceState?.getInt("ghost_active", 0) ?: 0
        if (savedUrls.isNotEmpty()) {
            restoredFromRotation = true
            savedUrls.forEach { createTab(it.ifBlank { "about:blank" }) }
            tabs.getOrNull(savedActive.coerceIn(0, tabs.lastIndex))?.let { switchTo(it.id) }
        } else {
            val incoming = intent?.dataString?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            createTab(incoming ?: "about:blank")
        }
    }

    private fun buildUi() {
        val background = Color.rgb(7, 12, 20)
        val toolbar = Color.rgb(20, 29, 46)
        val field = Color.rgb(34, 47, 70)
        val accent = Color.rgb(56, 189, 248)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(background)
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(6, 6, 6, 6)
            setBackgroundColor(toolbar)
        }
        fun button(text: String, description: String) = TextView(this).apply {
            this.text = text
            contentDescription = description
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 18f
            setPadding(10, 0, 10, 0)
            isClickable = true
            isFocusable = true
        }
        val back = button("‹", "Back")
        val forward = button("›", "Forward")
        val reload = button("→", "Refresh")
        val plus = button("+", "New Ghost tab")
        val close = button("×", "Close Ghost mode")
        url = EditText(this).apply {
            hint = "Ghost search or address"
            setSingleLine(true)
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_GO
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(203, 213, 225))
            textSize = 15f
            setPadding(18, 0, 18, 0)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(field)
                setStroke(1, Color.rgb(71, 85, 105))
                cornerRadius = 26f
            }
        }
        count = button("0", "Ghost tab count")
        count.setTextColor(accent)
        top.addView(back, LinearLayout.LayoutParams(42, 44))
        top.addView(url, LinearLayout.LayoutParams(0, 44, 1f).apply { leftMargin = 4; rightMargin = 4 })
        top.addView(forward, LinearLayout.LayoutParams(42, 44))
        top.addView(reload, LinearLayout.LayoutParams(42, 44))
        top.addView(count, LinearLayout.LayoutParams(44, 44))
        top.addView(plus, LinearLayout.LayoutParams(42, 44))
        top.addView(close, LinearLayout.LayoutParams(42, 44))

        strip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(6, 4, 6, 4)
            setBackgroundColor(Color.rgb(11, 18, 32))
        }
        host = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        root.addView(top, LinearLayout.LayoutParams(-1, 56))
        root.addView(strip, LinearLayout.LayoutParams(-1, 44))
        root.addView(host, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        back.setOnClickListener { active()?.let { if (it.canGoBack()) it.goBack() } }
        forward.setOnClickListener { active()?.let { if (it.canGoForward()) it.goForward() } }
        reload.setOnClickListener { active()?.reload() }
        plus.setOnClickListener { createTab("about:blank") }
        close.setOnClickListener { finishAndClear() }
        url.setOnEditorActionListener { _, action, event ->
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_GO ||
                action == android.view.inputmethod.EditorInfo.IME_ACTION_DONE ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)) {
                navigate()
                true
            } else false
        }
    }

    private fun createTab(initial: String) {
        val id = nextId++
        val w = WebView(this)
        w.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            setSupportMultipleWindows(true)
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
        }
        w.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, pageUrl: String?) {
                if (id == activeId) {
                    url.setText(pageUrl.orEmpty().takeIf { it != "about:blank" } ?: "")
                    url.setSelection(url.text.length)
                }
                super.onPageFinished(view, pageUrl)
            }
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, false)
        val chip = TextView(this).apply {
            text = "Ghost $id"
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(14, 0, 14, 0)
            isClickable = true
            isFocusable = true
            setOnClickListener { switchTo(id) }
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.rgb(30, 41, 59))
                cornerRadius = 18f
            }
        }
        val tab = GhostTab(id, w, chip)
        tabs += tab
        strip.addView(chip, LinearLayout.LayoutParams(0, 36, 1f).apply { rightMargin = 4 })
        host.addView(w, FrameLayout.LayoutParams(-1, -1))
        switchTo(id)
        if (initial != "about:blank") w.loadUrl(initial)
    }

    private fun switchTo(id: Int) {
        activeId = id
        tabs.forEach {
            val selected = it.id == id
            it.webView.visibility = if (selected) View.VISIBLE else View.GONE
            it.chip.alpha = if (selected) 1f else 0.55f
        }
        count.text = tabs.size.toString()
        active()?.url?.let { current -> url.setText(if (current == "about:blank") "" else current) }
    }

    private fun active(): WebView? = tabs.firstOrNull { it.id == activeId }?.webView

    private fun navigate() {
        val raw = url.text.toString().trim()
        if (raw.isBlank()) return
        val target = when {
            raw.startsWith("http://") || raw.startsWith("https://") -> raw
            raw.contains(".") && !raw.contains(" ") -> "https://$raw"
            else -> "https://www.google.com/search?q=" + java.net.URLEncoder.encode(raw, "UTF-8")
        }
        active()?.loadUrl(target)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putStringArrayList("ghost_urls", ArrayList(tabs.map { it.webView.url ?: "about:blank" }))
        outState.putInt("ghost_active", tabs.indexOfFirst { it.id == activeId }.coerceAtLeast(0))
        super.onSaveInstanceState(outState)
    }

    private fun finishAndClear() {
        tabs.forEach { it.webView.stopLoading(); it.webView.clearHistory(); it.webView.clearCache(true); it.webView.destroy() }
        tabs.clear()
        strip.removeAllViews()
        finish()
    }

    override fun onBackPressed() {
        if (active()?.canGoBack() == true) active()?.goBack() else finishAndClear()
    }

    override fun onDestroy() {
        tabs.forEach { runCatching { it.webView.destroy() } }
        tabs.clear()
        super.onDestroy()
    }
}
