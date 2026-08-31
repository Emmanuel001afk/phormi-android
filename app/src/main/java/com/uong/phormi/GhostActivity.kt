package com.uong.phormi

import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * True ephemeral browser session. This activity runs in its own Android process/data directory,
 * so its WebView cookies/storage are separated from the normal Phormi browser profile.
 * Nothing is persisted by Phormi when this activity is closed.
 */
class GhostActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private lateinit var urlBar: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        // Must happen before the first WebView is created in this process.
        runCatching { WebView.setDataDirectorySuffix("ghost") }
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.activity_ghost)

        urlBar = findViewById(R.id.ghost_url)
        webView = findViewById(R.id.ghost_webview)
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            cacheMode = WebSettings.LOAD_NO_CACHE
            setSaveFormData(false)
            setSupportMultipleWindows(true)
            builtInZoomControls = true
            displayZoomControls = false
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                if (!url.isNullOrBlank()) urlBar.setText(url)
            }
        }
        webView.webChromeClient = WebChromeClient()
        findViewById<TextView>(R.id.ghost_back).setOnClickListener { if (webView.canGoBack()) webView.goBack() }
        findViewById<TextView>(R.id.ghost_forward).setOnClickListener { if (webView.canGoForward()) webView.goForward() }
        findViewById<TextView>(R.id.ghost_close).setOnClickListener { finishAndClear() }
        findViewById<TextView>(R.id.ghost_reload).setOnClickListener { webView.reload() }
        urlBar.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)) {
                navigate()
                true
            } else false
        }
        val initial = intent?.dataString
        webView.loadUrl(initial?.takeIf { it.startsWith("http://") || it.startsWith("https://") } ?: "https://www.google.com")
    }

    private fun navigate() {
        val raw = urlBar.text.toString().trim()
        if (raw.isBlank()) return
        val url = when {
            raw.startsWith("http://") || raw.startsWith("https://") -> raw
            raw.contains(".") && !raw.contains(" ") -> "https://$raw"
            else -> "https://www.google.com/search?q=" + java.net.URLEncoder.encode(raw, "UTF-8")
        }
        webView.loadUrl(url)
    }

    private fun finishAndClear() {
        webView.stopLoading()
        webView.clearCache(true)
        webView.clearHistory()
        webView.clearFormData()
        CookieManager.getInstance().removeAllCookies { finish() }
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else finishAndClear()
    }

    override fun onDestroy() {
        runCatching {
            webView.stopLoading()
            webView.clearCache(true)
            webView.clearHistory()
            webView.clearFormData()
            CookieManager.getInstance().removeAllCookies(null)
            webView.destroy()
        }
        super.onDestroy()
    }
}
