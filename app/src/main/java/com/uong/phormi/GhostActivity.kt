package com.uong.phormi

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * Separate private-browsing task with session-only tabs.
 *
 * Every colour here is set explicitly. The previous version left the toolbar,
 * tab strip and chips without backgrounds, so their text inherited the dark
 * window background and became unreadable on several WebView/theme combinations.
 * Tabs now also keep their own address/title instead of sharing one URL bar
 * value across the whole window.
 */
class GhostActivity : Activity() {
    private data class GhostTab(
        val id: Int,
        val webView: WebView,
        val chip: LinearLayout,
        val label: TextView,
        val close: TextView,
        var address: String = "",
        var title: String = ""
    )

    private val tabs = mutableListOf<GhostTab>()
    private var nextId = 1
    private var activeId = -1
    private lateinit var host: FrameLayout
    private lateinit var strip: LinearLayout
    private lateinit var stripScroll: HorizontalScrollView
    private lateinit var url: EditText
    private lateinit var count: TextView
    private lateinit var selectionBar: LinearLayout
    private lateinit var selectAllButton: TextView
    private lateinit var closeSelectedButton: TextView
    private var ghostProfileName: String? = null
    private var selectionMode = false
    private val selectedIds = mutableSetOf<Int>()

    private val windowBgColor = Color.parseColor("#0B1220")
    private val toolbarColor = Color.parseColor("#111C2E")
    private val stripColor = Color.parseColor("#0F1A2B")
    private val surfaceColor = Color.parseColor("#1E293B")
    private val activeColor = Color.parseColor("#1D4ED8")
    private val selectedColor = Color.parseColor("#F59E0B")
    private val textColor = Color.parseColor("#F8FAFC")
    private val mutedTextColor = Color.parseColor("#CBD5E1")
    private val hintColor = Color.parseColor("#94A3B8")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
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
        } else {
            // Never silently degrade to the ordinary default WebView profile while
            // still calling the screen "Ghost". Tell the user plainly instead.
            Toast.makeText(
                this,
                "Warning: this WebView cannot create an isolated profile. Ghost mode is NOT private on this device — cookies and site data may persist.",
                Toast.LENGTH_LONG
            ).show()
        }
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

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(windowBgColor)
        }
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(toolbarColor)
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }

        fun button(label: String, description: String): TextView = TextView(this).apply {
            text = label
            contentDescription = description
            gravity = Gravity.CENTER
            setTextColor(textColor)
            textSize = 18f
            isClickable = true
            isFocusable = true
            background = GradientDrawable().apply {
                setColor(surfaceColor)
                cornerRadius = dp(10).toFloat()
            }
        }

        val back = button("‹", "Back")
        val forward = button("›", "Forward")
        val reload = button("↻", "Refresh")
        val plus = button("+", "New Ghost tab")
        val close = button("×", "Close Ghost mode")
        count = button("0", "Ghost tabs: open tab list")

        url = EditText(this).apply {
            hint = "Ghost search or address"
            setSingleLine(true)
            setTextColor(textColor)
            setHintTextColor(hintColor)
            textSize = 14f
            setPadding(dp(12), dp(4), dp(12), dp(4))
            background = GradientDrawable().apply {
                setColor(surfaceColor)
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), Color.parseColor("#475569"))
            }
            imeOptions = EditorInfo.IME_ACTION_GO
        }

        toolbar.addView(back, LinearLayout.LayoutParams(dp(40), dp(44)).apply { rightMargin = dp(4) })
        toolbar.addView(url, LinearLayout.LayoutParams(0, dp(44), 1f))
        toolbar.addView(forward, LinearLayout.LayoutParams(dp(40), dp(44)).apply { leftMargin = dp(4) })
        toolbar.addView(reload, LinearLayout.LayoutParams(dp(40), dp(44)).apply { leftMargin = dp(4) })
        toolbar.addView(count, LinearLayout.LayoutParams(dp(40), dp(44)).apply { leftMargin = dp(4) })
        toolbar.addView(plus, LinearLayout.LayoutParams(dp(40), dp(44)).apply { leftMargin = dp(4) })
        toolbar.addView(close, LinearLayout.LayoutParams(dp(40), dp(44)).apply { leftMargin = dp(4) })

        // Bulk-close bar. Hidden unless the user explicitly long-presses a tab.
        selectionBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#3F1D24"))
            setPadding(dp(8), dp(4), dp(8), dp(4))
            visibility = View.GONE
        }
        selectAllButton = TextView(this).apply {
            text = "All"
            setTextColor(textColor)
            textSize = 13f
            gravity = Gravity.CENTER
            isClickable = true
        }
        closeSelectedButton = TextView(this).apply {
            text = "Close (0)"
            setTextColor(Color.parseColor("#FCA5A5"))
            textSize = 13f
            gravity = Gravity.CENTER
            isClickable = true
        }
        val cancelSelection = TextView(this).apply {
            text = "Cancel"
            setTextColor(mutedTextColor)
            textSize = 13f
            gravity = Gravity.CENTER
            isClickable = true
            setOnClickListener { endSelection() }
        }
        selectionBar.addView(selectAllButton, LinearLayout.LayoutParams(0, dp(38), 1f))
        selectionBar.addView(closeSelectedButton, LinearLayout.LayoutParams(0, dp(38), 1f))
        selectionBar.addView(cancelSelection, LinearLayout.LayoutParams(0, dp(38), 1f))
        selectAllButton.setOnClickListener {
            if (selectedIds.size == tabs.size) selectedIds.clear()
            else tabs.forEach { selectedIds.add(it.id) }
            refreshChips()
        }
        closeSelectedButton.setOnClickListener { confirmCloseSelected() }

        strip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(stripColor)
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        stripScroll = HorizontalScrollView(this).apply {
            setBackgroundColor(stripColor)
            isHorizontalScrollBarEnabled = false
        }
        stripScroll.addView(strip, FrameLayout.LayoutParams(-2, -1))
        host = FrameLayout(this).apply { setBackgroundColor(Color.WHITE) }

        root.addView(toolbar, LinearLayout.LayoutParams(-1, dp(56)))
        root.addView(selectionBar, LinearLayout.LayoutParams(-1, dp(44)))
        root.addView(stripScroll, LinearLayout.LayoutParams(-1, dp(52)))
        root.addView(host, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        back.setOnClickListener { active()?.let { if (it.canGoBack()) it.goBack() } }
        forward.setOnClickListener { active()?.let { if (it.canGoForward()) it.goForward() } }
        reload.setOnClickListener { active()?.reload() }
        plus.setOnClickListener { createTab("about:blank") }
        close.setOnClickListener { finishAndClear() }
        count.setOnClickListener { showTabList() }
        url.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_GO || action == EditorInfo.IME_ACTION_DONE) {
                navigate()
                true
            } else false
        }
    }

    private fun createTab(initial: String) {
        val id = nextId++
        val webView = WebView(this).apply {
            // A transparent WebView over a dark window renders dark page text on
            // a dark background. Pin a light content surface for readability.
            setBackgroundColor(Color.WHITE)
        }
        if (ghostProfileName != null) {
            runCatching { WebViewCompat.setProfile(webView, ghostProfileName!!) }
                .onFailure { Toast.makeText(this, "Ghost mode could not create an isolated session.", Toast.LENGTH_LONG).show() }
        }
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            useWideViewPort = true
            loadWithOverviewMode = true
            if (android.os.Build.VERSION.SDK_INT >= 26) safeBrowsingEnabled = true
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, pageUrl: String?, favicon: android.graphics.Bitmap?) {
                val tab = tabs.firstOrNull { it.id == id } ?: return
                tab.address = pageUrl.orEmpty().takeIf { it != "about:blank" }.orEmpty()
                if (id == activeId) showActiveAddress()
                refreshChipLabel(tab)
            }

            override fun onPageFinished(view: WebView?, pageUrl: String?) {
                val tab = tabs.firstOrNull { it.id == id } ?: return
                tab.address = pageUrl.orEmpty().takeIf { it != "about:blank" }.orEmpty()
                tab.title = view?.title.orEmpty().takeIf { it.isNotBlank() && it != "about:blank" }.orEmpty()
                if (id == activeId) showActiveAddress()
                refreshChipLabel(tab)
                super.onPageFinished(view, pageUrl)
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView?, title: String?) {
                val tab = tabs.firstOrNull { it.id == id } ?: return
                tab.title = title.orEmpty().takeIf { it != "about:blank" }.orEmpty()
                refreshChipLabel(tab)
            }
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false)

        val label = TextView(this).apply {
            text = "New tab"
            setTextColor(mutedTextColor)
            textSize = 12f
            maxLines = 1
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(4), 0)
        }
        val close = TextView(this).apply {
            text = "×"
            contentDescription = "Close this Ghost tab"
            setTextColor(textColor)
            textSize = 16f
            gravity = Gravity.CENTER
        }
        val chip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isLongClickable = true
            setPadding(dp(4), 0, dp(4), 0)
        }
        chip.addView(label, LinearLayout.LayoutParams(0, dp(38), 1f))
        chip.addView(close, LinearLayout.LayoutParams(dp(26), dp(38)))
        chip.setOnClickListener {
            if (selectionMode) toggleSelection(id) else switchTo(id)
        }
        chip.setOnLongClickListener {
            if (!selectionMode) {
                selectionMode = true
                selectedIds.add(id)
            } else toggleSelection(id)
            refreshChips()
            true
        }
        close.setOnClickListener { closeTab(id) }

        val tab = GhostTab(id, webView, chip, label, close, address = initial.takeIf { it != "about:blank" }.orEmpty())
        tabs += tab
        strip.addView(chip, LinearLayout.LayoutParams(dp(150), dp(38)).apply {
            leftMargin = dp(3); rightMargin = dp(3)
        })
        host.addView(webView, FrameLayout.LayoutParams(-1, -1))
        refreshChipLabel(tab)
        switchTo(id)
        if (initial != "about:blank") webView.loadUrl(initial)
    }

    private fun refreshChipLabel(tab: GhostTab) {
        val hostName = runCatching { android.net.Uri.parse(tab.address).host }.getOrNull().orEmpty()
        tab.label.text = when {
            tab.title.isNotBlank() -> tab.title
            hostName.isNotBlank() -> hostName
            else -> "Ghost tab ${tab.id}"
        }
        tab.label.contentDescription = "${tab.label.text} — ${tab.address.ifBlank { "new tab" }}"
    }

    private fun showActiveAddress() {
        val address = tabs.firstOrNull { it.id == activeId }?.address.orEmpty()
        url.setText(address)
        url.setSelection(url.text.length)
    }

    private fun switchTo(id: Int) {
        activeId = id
        tabs.forEach {
            it.webView.visibility = if (it.id == id) View.VISIBLE else View.GONE
        }
        // Always overwrite the omnibox. The earlier version only updated it when
        // the incoming tab had a non-null URL, so a new blank tab inherited the
        // previous tab's address and looked like a shared/"unified" tab.
        showActiveAddress()
        refreshChips()
        stripScroll.post {
            val chip = tabs.firstOrNull { it.id == id }?.chip ?: return@post
            stripScroll.smoothScrollTo(chip.left, 0)
        }
    }

    private fun refreshChips() {
        count.text = tabs.size.toString()
        selectionBar.visibility = if (selectionMode) View.VISIBLE else View.GONE
        closeSelectedButton.text = "Close (${selectedIds.size})"
        closeSelectedButton.alpha = if (selectedIds.isEmpty()) 0.5f else 1f
        selectAllButton.text = if (selectedIds.size == tabs.size && tabs.isNotEmpty()) "None" else "All"
        tabs.forEach { tab ->
            val selected = selectionMode && tab.id in selectedIds
            val active = tab.id == activeId
            val color = when {
                selected -> selectedColor
                active -> activeColor
                else -> surfaceColor
            }
            tab.chip.background = GradientDrawable().apply {
                setColor(color)
                cornerRadius = dp(10).toFloat()
                if (selected || active) setStroke(dp(2), textColor)
            }
            tab.label.setTextColor(
                when {
                    selected -> Color.parseColor("#111827")
                    active -> textColor
                    else -> mutedTextColor
                }
            )
            tab.close.setTextColor(if (selected) Color.parseColor("#111827") else textColor)
            tab.chip.alpha = if (active || selected || selectionMode) 1f else 0.85f
        }
    }

    private fun toggleSelection(id: Int) {
        if (id in selectedIds) selectedIds.remove(id) else selectedIds.add(id)
        if (selectedIds.isEmpty()) endSelection() else refreshChips()
    }

    private fun endSelection() {
        selectionMode = false
        selectedIds.clear()
        refreshChips()
    }

    private fun confirmCloseSelected() {
        if (selectedIds.isEmpty()) return
        val amount = selectedIds.size
        AlertDialog.Builder(this)
            .setTitle("Close $amount tab${if (amount == 1) "" else "s"}?")
            .setMessage("Selected Ghost tabs and their session data will be discarded.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Close $amount") { _, _ ->
                val ids = selectedIds.toList()
                endSelection()
                ids.forEach { closeTab(it) }
            }.show()
    }

    private fun closeTab(id: Int) {
        val index = tabs.indexOfFirst { it.id == id }
        if (index == -1) return
        val tab = tabs[index]
        tab.webView.stopLoading()
        tab.webView.clearHistory()
        tab.webView.clearCache(true)
        host.removeView(tab.webView)
        tab.webView.destroy()
        strip.removeView(tab.chip)
        tabs.removeAt(index)
        selectedIds.remove(id)
        if (tabs.isEmpty()) {
            createTab("about:blank")
        } else if (activeId == id) {
            switchTo((tabs.getOrNull(index - 1) ?: tabs.first()).id)
        } else {
            refreshChips()
        }
    }

    private fun showTabList() {
        if (tabs.isEmpty()) return
        val labels = tabs.map { tab ->
            val title = tab.title.ifBlank { tab.label.text.toString() }
            val address = tab.address.ifBlank { "New tab" }
            "$title\n$address"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Ghost tabs (${tabs.size})")
            .setItems(labels) { _, which -> tabs.getOrNull(which)?.let { switchTo(it.id) } }
            .setNeutralButton("Select tabs") { _, _ ->
                selectionMode = true
                refreshChips()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun active(): WebView? = tabs.firstOrNull { it.id == activeId }?.webView

    private fun navigate() {
        val raw = url.text.toString().trim()
        val target = PhormiNavigation.resolve(raw) ?: return
        val destination = if (target.kind == PhormiNavigation.Kind.SEARCH) {
            "https://www.google.com/search?q=" + java.net.URLEncoder.encode(target.value, "UTF-8")
        } else target.value
        active()?.loadUrl(destination)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putStringArrayList("ghost_urls", ArrayList(tabs.map { it.address.ifBlank { "about:blank" } }))
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
        if (selectionMode) {
            endSelection()
            return
        }
        if (active()?.canGoBack() == true) active()?.goBack() else finishAndClear()
    }

    private fun deleteGhostProfile() {
        val name = ghostProfileName ?: return
        runCatching { ProfileStore.getInstance().deleteProfile(name) }
        ghostProfileName = null
    }

    override fun onDestroy() {
        tabs.forEach { runCatching { it.webView.destroy() } }
        tabs.clear()
        deleteGhostProfile()
        super.onDestroy()
    }
}
