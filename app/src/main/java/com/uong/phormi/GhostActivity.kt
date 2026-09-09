package com.uong.phormi

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
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

/** Separate private-browsing task with session-only tabs and normal browser controls. */
class GhostActivity : Activity() {
    private data class GhostTab(val id: Int, val webView: WebView, val chip: TextView)
    private val tabs = mutableListOf<GhostTab>()
    private var nextId = 1
    private var activeId = -1
    private lateinit var host: FrameLayout
    private lateinit var strip: LinearLayout
    private lateinit var url: EditText
    private lateinit var count: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        buildUi()
        val savedUrls = savedInstanceState?.getStringArrayList("ghost_urls").orEmpty()
        val savedActive = savedInstanceState?.getInt("ghost_active", 0) ?: 0
        if (savedUrls.isEmpty()) {
            val incoming = intent?.dataString?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            createTab(incoming ?: "about:blank")
        } else {
            savedUrls.forEach { createTab(it.ifBlank { "about:blank" }) }
            tabs.getOrNull(savedActive.coerceIn(0, tabs.lastIndex))?.let { switchTo(it.id) }
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(7, 12, 20))
        }
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(6, 6, 6, 6)
        }

        fun button(label: String, description: String): TextView = TextView(this).apply {
            text = label
            contentDescription = description
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 18f
            isClickable = true
            isFocusable = true
        }

        val back = button("‹", "Back")
        val forward = button("›", "Forward")
        val reload = button("↻", "Refresh")
        val plus = button("+", "New Ghost tab")
        val close = button("×", "Close Ghost mode")
        count = button("0", "Ghost tab count")
        url = EditText(this).apply {
            hint = "Ghost search or address"
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY)
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_GO
        }

        toolbar.addView(back, LinearLayout.LayoutParams(42, 44))
        toolbar.addView(url, LinearLayout.LayoutParams(0, 44, 1f))
        toolbar.addView(forward, LinearLayout.LayoutParams(42, 44))
        toolbar.addView(reload, LinearLayout.LayoutParams(42, 44))
        toolbar.addView(count, LinearLayout.LayoutParams(44, 44))
        toolbar.addView(plus, LinearLayout.LayoutParams(42, 44))
        toolbar.addView(close, LinearLayout.LayoutParams(42, 44))

        strip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(6, 4, 6, 4)
        }
        host = FrameLayout(this)

        root.addView(toolbar, LinearLayout.LayoutParams(-1, 56))
        root.addView(strip, LinearLayout.LayoutParams(-1, 44))
        root.addView(host, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        back.setOnClickListener { active()?.let { if (it.canGoBack()) it.goBack() } }
        forward.setOnClickListener { active()?.let { if (it.canGoForward()) it.goForward() } }
        reload.setOnClickListener { active()?.reload() }
        plus.setOnClickListener { createTab("about:blank") }
        close.setOnClickListener { finishAndClear() }
        url.setOnEditorActionListener { _, action, _ ->
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_GO || action == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                navigate()
                true
            } else false
        }
    }

    private fun createTab(initial: String) {
        val id = nextId++
        val webView = WebView(this)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            useWideViewPort = true
            loadWithOverviewMode = true
            if (android.os.Build.VERSION.SDK_INT >= 26) safeBrowsingEnabled = true
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, pageUrl: String?) {
                if (id == activeId) {
                    url.setText(pageUrl.orEmpty().takeIf { it != "about:blank" }.orEmpty())
                }
                super.onPageFinished(view, pageUrl)
            }
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false)

        val chip = TextView(this).apply {
            text = "Ghost $id"
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setOnClickListener { switchTo(id) }
        }
        tabs += GhostTab(id, webView, chip)
        strip.addView(chip, LinearLayout.LayoutParams(0, 36, 1f))
        host.addView(webView, FrameLayout.LayoutParams(-1, -1))
        switchTo(id)
        if (initial != "about:blank") webView.loadUrl(initial)
    }

    private fun switchTo(id: Int) {
        activeId = id
        tabs.forEach {
            it.webView.visibility = if (it.id == id) View.VISIBLE else View.GONE
            it.chip.alpha = if (it.id == id) 1f else 0.55f
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
        tabs.forEach {
            it.webView.stopLoading()
            it.webView.clearHistory()
            it.webView.clearCache(true)
            it.webView.destroy()
        }
        tabs.clear()
        strip.removeAllViews()
        finish()
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingSuperCall")
    override fun onBackPressed() {
        if (active()?.canGoBack() == true) active()?.goBack() else finishAndClear()
    }

    override fun onDestroy() {
        tabs.forEach { runCatching { it.webView.destroy() } }
        tabs.clear()
        super.onDestroy()
    }
}
