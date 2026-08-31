package com.uong.phormi

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * True ephemeral browser session.
 *
 * This activity keeps its own temporary WebView state and explicitly clears
 * cookies, cache, history and form data when it closes. It does not write
 * browser tabs/history through MainActivity.
 *
 * The layout is built here so the activity does not depend on a missing
 * activity_ghost.xml resource.
 */
class GhostActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var urlBar: EditText

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        runCatching {
            WebView.setDataDirectorySuffix("ghost")
        }

        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(11, 18, 32))
        }

        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
            setBackgroundColor(Color.rgb(15, 23, 42))
        }

        fun button(label: String): TextView =
            TextView(this).apply {
                text = label
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                textSize = 13f
                minWidth = dp(42)
                minHeight = dp(40)
                setPadding(dp(6), 0, dp(6), 0)
                isClickable = true
                isFocusable = true
            }

        val back = button("‹")
        val forward = button("›")
        val reload = button("↻")
        val close = button("×")

        urlBar = EditText(this).apply {
            hint = "Search or enter address"
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_GO
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(148, 163, 184))
            setPadding(dp(12), 0, dp(12), 0)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.rgb(23, 32, 51))
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), Color.rgb(51, 65, 85))
            }
        }

        toolbar.addView(back)
        toolbar.addView(
            urlBar,
            LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                leftMargin = dp(4)
                rightMargin = dp(4)
            }
        )
        toolbar.addView(forward)
        toolbar.addView(reload)
        toolbar.addView(close)

        webView = WebView(this).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                cacheMode = WebSettings.LOAD_NO_CACHE
                setSaveFormData(false)
                setSupportMultipleWindows(true)
                builtInZoomControls = true
                displayZoomControls = false
                setGeolocationEnabled(false)
            }

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    if (!url.isNullOrBlank()) urlBar.setText(url)
                }
            }

            webChromeClient = WebChromeClient()
            setBackgroundColor(Color.rgb(11, 18, 32))
        }

        root.addView(
            toolbar,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(56)
            )
        )
        root.addView(
            webView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        setContentView(root)

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(
            webView,
            true
        )

        back.setOnClickListener {
            if (webView.canGoBack()) webView.goBack()
        }
        forward.setOnClickListener {
            if (webView.canGoForward()) webView.goForward()
        }
        reload.setOnClickListener { webView.reload() }
        close.setOnClickListener { finishAndClear() }

        urlBar.setOnEditorActionListener { _, actionId, event ->
            if (
                actionId == EditorInfo.IME_ACTION_GO ||
                actionId == EditorInfo.IME_ACTION_DONE ||
                (
                    event != null &&
                        event.keyCode == KeyEvent.KEYCODE_ENTER &&
                        event.action == KeyEvent.ACTION_DOWN
                    )
            ) {
                navigate()
                true
            } else {
                false
            }
        }

        val initial = intent?.dataString
        webView.loadUrl(
            initial?.takeIf {
                it.startsWith("http://") ||
                    it.startsWith("https://")
            } ?: "https://www.google.com"
        )
    }

    private fun navigate() {
        val raw = urlBar.text.toString().trim()
        if (raw.isBlank()) return

        val url = when {
            raw.startsWith("http://") ||
                raw.startsWith("https://") -> raw

            raw.contains(".") && !raw.contains(" ") ->
                "https://$raw"

            else ->
                "https://www.google.com/search?q=" +
                    java.net.URLEncoder.encode(raw, "UTF-8")
        }

        webView.loadUrl(url)
    }

    private fun finishAndClear() {
        webView.stopLoading()
        webView.clearCache(true)
        webView.clearHistory()
        webView.clearFormData()
        CookieManager.getInstance().removeAllCookies {
            runOnUiThread { finish() }
        }
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            finishAndClear()
        }
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
