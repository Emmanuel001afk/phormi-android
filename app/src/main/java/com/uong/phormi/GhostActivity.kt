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
import android.widget.Toast
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

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
    private var ghostProfileName: String? = null
    private val sitePermissions by lazy { PhormiSitePermissions(this) { it === active() } }
    private var fileCallback: android.webkit.ValueCallback<Array<android.net.Uri>>? = null
    private var fullscreen: View? = null
    private var fullscreenCallback: android.webkit.WebChromeClient.CustomViewCallback? = null
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (android.os.Build.VERSION.SDK_INT < 28 || !PhormiRepairApplication.ghostStorageReady) {
            android.app.AlertDialog.Builder(this).setTitle("Ghost mode unavailable")
                .setMessage("This Android version cannot provide the separate private WebView storage required by Phormi. Normal browsing is unchanged.")
                .setPositiveButton("Close") { _, _ -> finish() }.setOnCancelListener { finish() }.show()
            return
        }
        buildUi()
        if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            val savedProfile = savedInstanceState?.getString("ghost_profile")?.takeIf { it.isNotBlank() }
            runCatching {
                ProfileStore.getInstance().getAllProfileNames()
                    .filter { it.startsWith("PhormiGhost_", ignoreCase = true) && it != savedProfile }
                    .forEach { ProfileStore.getInstance().deleteProfile(it) }
            }
            ghostProfileName = savedProfile
                ?: ("PhormiGhost_" + java.util.UUID.randomUUID().toString().replace("-", "").take(12))
            runCatching { ProfileStore.getInstance().getOrCreateProfile(ghostProfileName!!) }
        }
        val savedUrls = savedInstanceState?.getStringArrayList("ghost_urls").orEmpty()
        val savedActive = savedInstanceState?.getInt("ghost_active", 0) ?: 0
        if (ghostProfileName == null && savedInstanceState == null) {
            android.webkit.WebStorage.getInstance().deleteAllData()
            android.webkit.GeolocationPermissions.getInstance().clearAll()
            CookieManager.getInstance().removeAllCookies {
                if (!isFinishing && !isDestroyed) createTab(intent?.dataString?.takeIf { it.startsWith("https://") || it.startsWith("http://") } ?: "about:blank")
            }
            return
        }
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
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_GO or android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        }

        toolbar.addView(back, LinearLayout.LayoutParams(dp(48), dp(48)))
        toolbar.addView(url, LinearLayout.LayoutParams(0, dp(48), 1f))
        forward.visibility = View.GONE
        toolbar.addView(forward, LinearLayout.LayoutParams(dp(48), dp(48)))
        reload.visibility = View.GONE
        toolbar.addView(reload, LinearLayout.LayoutParams(dp(48), dp(48)))
        count.visibility = View.GONE
        toolbar.addView(count, LinearLayout.LayoutParams(dp(48), dp(48)))
        toolbar.addView(plus, LinearLayout.LayoutParams(dp(48), dp(48)))
        toolbar.addView(close, LinearLayout.LayoutParams(dp(48), dp(48)))

        strip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(6, 4, 6, 4)
        }
        host = FrameLayout(this)

        root.addView(toolbar, LinearLayout.LayoutParams(-1, dp(56)))
        root.addView(android.widget.HorizontalScrollView(this).apply { addView(strip) }, LinearLayout.LayoutParams(-1, dp(48)))
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
        val webView = object : WebView(this) {
            override fun onCreateInputConnection(outAttrs: android.view.inputmethod.EditorInfo): android.view.inputmethod.InputConnection? {
                val connection = super.onCreateInputConnection(outAttrs)
                outAttrs.imeOptions = outAttrs.imeOptions or android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                return connection
            }
        }
        if (ghostProfileName != null) {
            if (runCatching { WebViewCompat.setProfile(webView, ghostProfileName!!) }.isFailure) {
                webView.destroy()
                Toast.makeText(this, "Ghost isolation failed. No page was opened.", Toast.LENGTH_LONG).show()
                finish()
                return
            }
        }
        webView.settings.apply {
            allowFileAccess = false
            javaScriptEnabled = true
            domStorageEnabled = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            useWideViewPort = true
            loadWithOverviewMode = true
            if (android.os.Build.VERSION.SDK_INT >= 26) safeBrowsingEnabled = true
        }
        webView.webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onPermissionRequest(request: android.webkit.PermissionRequest) { sitePermissions.requestMedia(webView, request) }
            override fun onPermissionRequestCanceled(request: android.webkit.PermissionRequest) { sitePermissions.cancelled(request) }
            override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: android.webkit.GeolocationPermissions.Callback?) { sitePermissions.requestLocation(webView, origin, callback) }
            override fun onGeolocationPermissionsHidePrompt() { sitePermissions.cancel(webView) }
            override fun onShowFileChooser(view: WebView?, callback: android.webkit.ValueCallback<Array<android.net.Uri>>?, params: FileChooserParams?): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                return try {
                    startActivityForResult(params?.createIntent() ?: throw IllegalArgumentException(), 7042)
                    true
                } catch (_: Exception) { fileCallback = null; false }
            }
            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (view == null || fullscreen != null) { callback?.onCustomViewHidden(); return }
                fullscreen = view; fullscreenCallback = callback
                addContentView(view, android.view.ViewGroup.LayoutParams(-1, -1))
            }
            override fun onHideCustomView() { closeFullscreen() }
        }
        // Downloading outlives a private session, so make that consequence explicit.
        webView.setDownloadListener { target, _, _, _, _ ->
            android.app.AlertDialog.Builder(this).setTitle("Download outside Ghost mode?")
                .setMessage("This link will open in the normal browser and may remain in its history and downloads. Private cookies will not be transferred.")
                .setNegativeButton("Cancel", null).setPositiveButton("Open normally") { _, _ ->
                    if (target.startsWith("https://") || target.startsWith("http://")) startActivity(android.content.Intent(this, MainActivity::class.java).putExtra("open_url", target))
                    else Toast.makeText(this, "This private download cannot be transferred safely.", Toast.LENGTH_LONG).show()
                }.show()
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, target: String?, icon: android.graphics.Bitmap?) {
                sitePermissions.cancel(webView)
                if (id == activeId && !url.hasFocus()) url.setText(target.orEmpty())
            }
            override fun onPageFinished(view: WebView?, pageUrl: String?) {
                if (id == activeId) {
                    url.setText(pageUrl.orEmpty().takeIf { it != "about:blank" }.orEmpty())
                }
                super.onPageFinished(view, pageUrl)
            }
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false)

        val chip = TextView(this).apply {
            text = "Ghost $id"
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setOnClickListener { switchTo(id) }
        }
        tabs += GhostTab(id, webView, chip)
        strip.addView(chip, LinearLayout.LayoutParams(dp(112), dp(44)))
        host.addView(webView, FrameLayout.LayoutParams(-1, -1))
        switchTo(id)
        if (initial != "about:blank") webView.loadUrl(initial)
    }

    private fun switchTo(id: Int) {
        sitePermissions.cancel()
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
        ghostProfileName?.let { outState.putString("ghost_profile", it) }
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
        deleteGhostProfile()
        finish()
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingSuperCall")
    override fun onBackPressed() {
        if (fullscreen != null) { closeFullscreen(); return }
        if (active()?.canGoBack() == true) active()?.goBack() else finishAndClear()
    }

    private fun deleteGhostProfile() {
        val name = ghostProfileName ?: run {
            if (android.os.Build.VERSION.SDK_INT >= 28 && ::host.isInitialized) {
                CookieManager.getInstance().removeAllCookies { CookieManager.getInstance().flush() }
                android.webkit.WebStorage.getInstance().deleteAllData()
                android.webkit.GeolocationPermissions.getInstance().clearAll()
                android.webkit.WebViewDatabase.getInstance(this).clearHttpAuthUsernamePassword()
            }
            return
        }
        runCatching { ProfileStore.getInstance().deleteProfile(name) }
        ghostProfileName = null
    }

    private fun closeFullscreen() {
        fullscreen?.let { (it.parent as? android.view.ViewGroup)?.removeView(it) }
        fullscreen = null; fullscreenCallback?.onCustomViewHidden(); fullscreenCallback = null
    }
    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        sitePermissions.onResult(code)
    }
    override fun onActivityResult(code: Int, result: Int, data: android.content.Intent?) {
        super.onActivityResult(code, result, data)
        if (code == 7042) {
            fileCallback?.onReceiveValue(android.webkit.WebChromeClient.FileChooserParams.parseResult(result, data))
            fileCallback = null
        }
    }
    override fun onDestroy() {
        sitePermissions.cancel()
        closeFullscreen()
        fileCallback?.onReceiveValue(null); fileCallback = null
        tabs.forEach { runCatching { it.webView.destroy() } }
        tabs.clear()
        if (!isChangingConfigurations) deleteGhostProfile()
        super.onDestroy()
    }
}
