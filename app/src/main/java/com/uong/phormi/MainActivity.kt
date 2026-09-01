package com.uong.phormi

import android.Manifest
import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Environment
import android.view.ContextMenu
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.widget.Button
import android.os.Looper
import android.text.Html
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.net.URLEncoder
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.Calendar
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : AppCompatActivity() {

    private data class Tab(
        val id: Int,
        val webView: WebView,
        var title: String,
        val chipView: View,
        val isGhost: Boolean = false,
        var lastUsed: Long = System.currentTimeMillis()
    )

    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var webViewContainer: FrameLayout
    private lateinit var tabStripContainer: LinearLayout
    private lateinit var prefs: SharedPreferences
    private lateinit var urlBar: EditText
    private lateinit var startPageContainer: View
    private lateinit var startPageSearch: EditText
    private lateinit var tabCountView: TextView
    private lateinit var splitContainer: FrameLayout
    private lateinit var splitTopHost: FrameLayout
    private lateinit var splitBottomHost: FrameLayout
    private lateinit var splitLockButton: TextView
    private lateinit var splitDivider: View
    private lateinit var browserLockManager: BrowserLockManager
    private var browserUnlockedThisSession = false
    private var browserLockOverlay: View? = null

    private val tabs = mutableListOf<Tab>()
    private var activeTabId: Int = -1
    private var nextTabId = 1
    private var splitMode = false
    private var splitTopTabId = -1
    private var splitBottomTabId = -1
    private var splitChromeLocked = false
    private var splitRatio = 0.5f

    private var pendingPermissionRequest: PermissionRequest? = null
    private var pendingPermissionPermissions: Array<out String> = emptyArray()
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var contextMenuUrl: String? = null
    private var contextMenuIsImage: Boolean = false

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var originalOrientation: Int = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    private var fullscreenContainer: FrameLayout? = null

    companion object {
        private const val NEW_TAB_URL = "about:blank"
        private const val SEARCH_URL = "https://www.google.com/search?q="
        private const val PHORMI_SEARCH = "Phormi Search"
        private const val KEY_SEARCH_PROVIDER = "search_provider"
        private const val PREFS_NAME = "phormi_tabs"
        private const val KEY_TAB_URLS = "tab_urls"
        private const val KEY_TAB_TITLES = "tab_titles"
        private const val KEY_ACTIVE_INDEX = "active_index"
        private const val KEY_TAB_LAST_USED = "tab_last_used"
        private const val KEY_TAB_RETENTION = "tab_retention"
        private const val RETENTION_NEVER = "never"
        private const val RETENTION_1_MONTH = "1_month"
        private const val RETENTION_3_MONTHS = "3_months"
        private const val RETENTION_1_YEAR = "1_year"
        private const val KEY_NEWS_ENABLED = "news_enabled"
        private const val KEY_NEWS_PREFS = "phormi_news_preferences"
        private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_DAILY_ACCENT = "daily_accent"
        private const val KEY_WALLPAPER_URI = "wallpaper_uri"
        private const val KEY_CUSTOM_SHORTCUTS = "custom_shortcuts"
        private const val KEY_PULL_TO_REFRESH = "pull_to_refresh"
        private const val KEY_TAB_VIEW_MODE = "tab_view_mode"
        private const val KEY_BROWSER_LOCK = BrowserLockManager.PREF_KEY
        private const val REQ_WALLPAPER = 1005
        private const val REQ_MEDIA_PERMISSIONS = 1001
        private const val REQ_FILE_CHOOSER = 1002
        private const val REQ_GEOLOCATION = 1006
        private const val REQ_STARTUP_PERMISSIONS = 1003
        private const val REQ_TABS_OVERVIEW = 1004
        private const val REQ_MENU = 1007
        private const val MENU_OPEN = 1
        private const val MENU_COPY = 2
        private const val MENU_DOWNLOAD = 3
        private const val REQ_NEWS = 1008

        private val STARTUP_PERMISSIONS = buildList {
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                add(Manifest.permission.POST_NOTIFICATIONS)
                add(Manifest.permission.READ_MEDIA_IMAGES)
                add(Manifest.permission.READ_MEDIA_VIDEO)
                add(Manifest.permission.READ_MEDIA_AUDIO)
            }
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (android.os.Build.VERSION.SDK_INT <= 28) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
    }

    private var pendingGeoOrigin: String? = null
    private var pendingGeoCallback: android.webkit.GeolocationPermissions.Callback? = null
    private val reloadHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var reloadRunnable: Runnable? = null
    private var twoFingerHoldActive = false
    private var threeFingerStartX = 0f
    private var threeFingerTracking = false
    private val unifiedSearchExecutor = Executors.newFixedThreadPool(11)
    private val aiController by lazy { AiController(applicationContext) }
    private val mainExecutor = java.util.concurrent.Executor { command -> Handler(Looper.getMainLooper()).post(command) }
    private val unifiedSearchGeneration = AtomicInteger(0)
    private val unifiedSearchLock = Any()
    private var unifiedSearchFutures = mutableListOf<java.util.concurrent.Future<*>>()
    private var localSearchPageActive = false

    override fun onResume() {
        super.onResume()
        if (::prefs.isInitialized) {
            applyStartPageAppearance()
            applyBrowserChromeAppearance()
            pruneExpiredTabs()
            updateHomeNewsVisibility()
            refreshHomeNews()
        }
    }

    override fun onStart() {
        super.onStart()
        if (::prefs.isInitialized && browserLockManager.isEnabled(prefs) &&
            !browserUnlockedThisSession && !browserLockManager.isPromptInProgress()) {
            if (browserLockManager.method(prefs) == BrowserLockManager.METHOD_PIN && browserLockManager.isPinConfigured(prefs)) {
                showBrowserLockOverlay()
            } else authenticateBrowserLock()
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        updateResponsiveChrome()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        CookieManager.getInstance().setAcceptCookie(true)

        swipeRefresh = findViewById(R.id.swipe_refresh)
        webViewContainer = findViewById(R.id.webview_container)
        tabStripContainer = findViewById<View>(R.id.tab_strip).findViewById(R.id.tab_strip_container)
        urlBar = findViewById(R.id.url_bar)
        startPageContainer = findViewById(R.id.start_page_container)
        startPageSearch = findViewById(R.id.start_page_search)
        tabCountView = findViewById(R.id.tab_count)
        splitContainer = findViewById(R.id.split_container)
        splitTopHost = findViewById(R.id.split_top_host)
        splitBottomHost = findViewById(R.id.split_bottom_host)
        splitLockButton = findViewById(R.id.split_lock_button)
        splitDivider = findViewById(R.id.split_divider)
        browserLockManager = BrowserLockManager(this, mainExecutor)
        findViewById<FrameLayout>(R.id.browser_lock_overlay_host)?.apply {
            visibility = View.GONE
            isClickable = false
            isFocusable = false
        }
        updateResponsiveChrome()

        // Appearance, wallpaper, and Browser Lock are menu features.
        applyStartPageAppearance()
        applyBrowserChromeAppearance()

        val selectedProvider = prefs.getString(KEY_SEARCH_PROVIDER, PHORMI_SEARCH) ?: PHORMI_SEARCH
        findViewById<TextView>(R.id.start_page_engine).text = "$selectedProvider  ▾"



        findViewById<TextView>(R.id.btn_menu).setOnClickListener {
            startActivityForResult(Intent(this, MenuActivity::class.java), REQ_MENU)
        }

        findViewById<TextView>(R.id.btn_home).setOnClickListener {
            showStartPage()
        }

        findViewById<TextView>(R.id.btn_back).setOnClickListener {
            activeWebView()?.let { if (it.canGoBack()) it.goBack() }
        }

        findViewById<TextView>(R.id.btn_forward).setOnClickListener {
            activeWebView()?.let { if (it.canGoForward()) it.goForward() }
        }
        updateNavButtons()

        startPageSearch.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)) {
                navigateFromStartPage()
                true
            } else false
        }
        findViewById<TextView>(R.id.start_page_go).setOnClickListener {
            navigateFromStartPage()
        }

        findViewById<TextView>(R.id.start_page_ai).setOnClickListener {
            startActivity(Intent(this, AiActivity::class.java).putExtra("auto_voice", true))
        }

        findViewById<TextView>(R.id.start_page_engine).setOnClickListener {
            showSearchProviderChooser()
        }
        findViewById<TextView>(R.id.start_page_news)?.setOnClickListener {
            val enabled = !prefs.getBoolean(KEY_NEWS_ENABLED, true)
            prefs.edit().putBoolean(KEY_NEWS_ENABLED, enabled).apply()
            updateHomeNewsVisibility()
            if (enabled) refreshHomeNews()
        }

        // Quick access is rendered as responsive rows. Fixed services are not removable;
        // personal favorites and most-visited entries can be long-pressed and removed.
        loadQuickAccessRows()
        updateHomeChromeVisibility()

        // + = new tab; tabs button = circular tab overview
        findViewById<TextView>(R.id.btn_new_tab).setOnClickListener {
            createNewTab(NEW_TAB_URL)
            urlBar.setText("")
            urlBar.requestFocus()
            showKeyboard()
        }
        // The circular count is the single combined Tab Overview + Counter control.
        findViewById<TextView>(R.id.tab_count).setOnClickListener {
            saveTabs()
            startActivityForResult(Intent(this, TabsOverviewActivity::class.java), REQ_TABS_OVERVIEW)
        }

        findViewById<TextView>(R.id.btn_refresh).setOnClickListener { activeWebView()?.reload() }
        splitLockButton.setOnClickListener { setSplitChromeLocked(!splitChromeLocked) }
        installSplitDividerResize()
        findViewById<TextView>(R.id.btn_go).setOnClickListener { navigateFromUrlBar() }
        urlBar.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO ||
                actionId == EditorInfo.IME_ACTION_DONE ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) {
                navigateFromUrlBar()
                true
            } else false
        }

        swipeRefresh.setOnRefreshListener { activeWebView()?.reload() }
        swipeRefresh.isEnabled = prefs.getBoolean(KEY_PULL_TO_REFRESH, true)
        swipeRefresh.setOnChildScrollUpCallback { _, _ ->
            val wv = activeWebView() ?: return@setOnChildScrollUpCallback false
            wv.canScrollVertically(-1)
        }

        requestStartupPermissions()

        // Keep WebView sessions (including website cookies/sign-ins) persisted across
        // Activity pauses and app restarts. This is the browser-style "sign in once"
        // behavior the app needs; each website still controls its own authentication.
        CookieManager.getInstance().flush()

        val incomingUrl = intent?.dataString?.trim()
        restoreTabs()
        if (!incomingUrl.isNullOrBlank() && (incomingUrl.startsWith("http://") || incomingUrl.startsWith("https://"))) {
            createNewTab(incomingUrl)
        }
    }

    private fun showTabRetentionChooser() {
        val values = arrayOf(RETENTION_NEVER, RETENTION_1_MONTH, RETENTION_3_MONTHS, RETENTION_1_YEAR)
        val labels = arrayOf("Never", "1 month", "3 months", "1 year")
        val current = prefs.getString(KEY_TAB_RETENTION, RETENTION_NEVER) ?: RETENTION_NEVER
        val checked = values.indexOf(current).coerceAtLeast(0)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Tab retention")
            .setMessage("Automatically close only tabs you have not used for the selected period. Your active/recent tabs are never removed.")
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                prefs.edit().putString(KEY_TAB_RETENTION, values[which]).apply()
                pruneExpiredTabs()
                dialog.dismiss()
                Toast.makeText(this, "Tab retention: ${labels[which]}", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun showPullToRefreshChooser() {
        val labels = arrayOf("Arrow refresh only", "Arrow + pull down to refresh")
        val current = if (prefs.getBoolean(KEY_PULL_TO_REFRESH, true)) 1 else 0
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Refresh gesture")
            .setSingleChoiceItems(labels, current) { dialog, which ->
                val enabled = which == 1
                prefs.edit().putBoolean(KEY_PULL_TO_REFRESH, enabled).apply()
                swipeRefresh.isEnabled = enabled
                dialog.dismiss()
                Toast.makeText(this, if (enabled) "Pull-to-refresh enabled" else "Arrow refresh only", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun showAppearanceChooser() {
        val modes = arrayOf("System", "Light", "Dark")
        val current = prefs.getString(KEY_THEME_MODE, "System") ?: "System"
        val checked = modes.indexOf(current).coerceAtLeast(0)
        val daily = prefs.getBoolean(KEY_DAILY_ACCENT, true)
        val labels = modes.map { mode ->
            if (mode == current && daily) "$mode  • Daily accent" else mode
        }.toTypedArray()

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Phormi appearance")
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                prefs.edit().putString(KEY_THEME_MODE, modes[which]).apply()
                applyStartPageAppearance()
                applyBrowserChromeAppearance()
                dialog.dismiss()
            }
            .setPositiveButton(if (daily) "Turn daily accent off" else "Turn daily accent on") { _, _ ->
                prefs.edit().putBoolean(KEY_DAILY_ACCENT, !daily).apply()
                applyStartPageAppearance()
                applyBrowserChromeAppearance()
            }
            .setNeutralButton("Choose wallpaper") { _, _ -> chooseWallpaper() }
            .setNegativeButton("Clear wallpaper") { _, _ -> clearStartPageWallpaper() }
            .show()
    }

    private fun chooseWallpaper() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        try {
            startActivityForResult(intent, REQ_WALLPAPER)
        } catch (_: Exception) {
            Toast.makeText(this, "No image picker is available", Toast.LENGTH_SHORT).show()
        }
    }

    private fun clearStartPageWallpaper() {
        prefs.edit().remove(KEY_WALLPAPER_URI).apply()
        findViewById<View>(R.id.start_page_wallpaper_image)?.let {
            it.visibility = View.GONE
        }
        applyStartPageAppearance()
    }

    private fun dailyAccent(): Int {
        val accents = intArrayOf(
            Color.rgb(56, 189, 248),
            Color.rgb(99, 102, 241),
            Color.rgb(16, 185, 129),
            Color.rgb(245, 158, 11),
            Color.rgb(236, 72, 153),
            Color.rgb(139, 92, 246),
            Color.rgb(14, 165, 233)
        )
        val day = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
        return accents[(day - 1) % accents.size]
    }

    private fun applyStartPageAppearance() {
        val root = findViewById<View>(R.id.start_page_container) ?: return
        val wallpaper = findViewById<View>(R.id.start_page_wallpaper_image)
        val dark = when (prefs.getString(KEY_THEME_MODE, "System")) {
            "Dark" -> true
            "Light" -> false
            else -> (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                    android.content.res.Configuration.UI_MODE_NIGHT_YES
        }

        val background = if (dark) Color.rgb(11, 18, 32) else Color.rgb(250, 247, 245)
        val primary = if (dark) Color.rgb(248, 250, 252) else Color.rgb(31, 31, 31)
        val secondary = if (dark) Color.rgb(148, 163, 184) else Color.rgb(92, 82, 78)
        val accent = if (prefs.getBoolean(KEY_DAILY_ACCENT, true)) dailyAccent() else Color.rgb(56, 189, 248)

        root.setBackgroundColor(background)
        findViewById<TextView>(R.id.start_page_title)?.setTextColor(primary)
        findViewById<TextView>(R.id.start_page_subtitle)?.setTextColor(secondary)
        findViewById<TextView>(R.id.start_page_quick_access)?.setTextColor(secondary)
        findViewById<TextView>(R.id.start_page_ai)?.setTextColor(primary)
        findViewById<TextView>(R.id.start_page_engine)?.setTextColor(secondary)
        findViewById<EditText>(R.id.start_page_search)?.setTextColor(primary)
        findViewById<EditText>(R.id.start_page_search)?.setHintTextColor(secondary)

        val surface = if (dark) Color.rgb(17, 24, 39) else Color.rgb(255, 255, 255)
        val surfaceSoft = if (dark) Color.rgb(23, 32, 51) else Color.rgb(248, 244, 241)
        val outline = if (dark) Color.rgb(51, 65, 85) else Color.rgb(224, 214, 208)
        styleSurface(findViewById(R.id.start_page_search_box), surface, outline, 28)
        styleSurface(findViewById(R.id.start_page_ai), surfaceSoft, outline, 22)

        val uri = prefs.getString(KEY_WALLPAPER_URI, null)
        if (!uri.isNullOrBlank()) {
            try {
                val image = findViewById<android.widget.ImageView>(R.id.start_page_wallpaper_image)
                image.setImageURI(Uri.parse(uri))
                image.alpha = if (dark) 0.30f else 0.48f
                image.visibility = View.VISIBLE
            } catch (_: Exception) {
                prefs.edit().remove(KEY_WALLPAPER_URI).apply()
                wallpaper.visibility = View.GONE
            }
        } else {
            wallpaper.visibility = View.GONE
        }
    }

    private fun applyBrowserChromeAppearance() {
        val dark = when (prefs.getString(KEY_THEME_MODE, "System")) {
            "Dark" -> true
            "Light" -> false
            else -> (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
        }
        val background = if (dark) Color.rgb(11, 18, 32) else Color.rgb(250, 247, 245)
        val toolbar = if (dark) Color.rgb(15, 23, 42) else Color.rgb(255, 255, 255)
        val text = if (dark) Color.rgb(226, 232, 240) else Color.rgb(31, 31, 31)
        val secondary = if (dark) Color.rgb(148, 163, 184) else Color.rgb(92, 82, 78)
        findViewById<View>(R.id.main_root)?.setBackgroundColor(background)
        findViewById<View>(R.id.top_toolbar)?.setBackgroundColor(toolbar)
        findViewById<View>(R.id.tab_strip)?.setBackgroundColor(background)
        findViewById<View>(R.id.url_toolbar)?.setBackgroundColor(toolbar)
        findViewById<View>(R.id.bottom_toolbar)?.setBackgroundColor(toolbar)
        listOf(R.id.btn_home, R.id.btn_new_tab, R.id.btn_menu, R.id.btn_refresh,
            R.id.btn_back, R.id.btn_forward, R.id.btn_bottom_ai, R.id.btn_bottom_downloads).forEach { id ->
            findViewById<TextView>(id)?.setTextColor(if (id == R.id.btn_new_tab || id == R.id.btn_bottom_ai)
                (if (prefs.getBoolean(KEY_DAILY_ACCENT, true)) dailyAccent() else Color.rgb(56, 189, 248)) else text)
        }
        findViewById<TextView>(R.id.tab_count)?.setTextColor(
            if (prefs.getBoolean(KEY_DAILY_ACCENT, true)) dailyAccent() else Color.rgb(56, 189, 248)
        )
        findViewById<EditText>(R.id.url_bar)?.apply {
            setTextColor(text)
            setHintTextColor(secondary)
        }
        findViewById<TextView>(R.id.btn_go)?.setTextColor(
            if (prefs.getBoolean(KEY_DAILY_ACCENT, true)) dailyAccent() else Color.rgb(56, 189, 248)
        )
    }

    private fun styleSurface(view: View?, fill: Int, stroke: Int, radiusDp: Int) {
        if (view == null) return
        val density = resources.displayMetrics.density
        val drawable = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            setColor(fill)
            setStroke((density).toInt().coerceAtLeast(1), stroke)
            cornerRadius = radiusDp * density
        }
        view.background = drawable
    }

    private fun searchUrlForProvider(query: String): String {
        val encoded = URLEncoder.encode(query, "UTF-8")
        return when (prefs.getString(KEY_SEARCH_PROVIDER, "Phormi Search")) {
            PHORMI_SEARCH -> "phormi-unified://search?q=$encoded"
            "Bing" -> "https://www.bing.com/search?q=$encoded"
            "DuckDuckGo" -> "https://duckduckgo.com/?q=$encoded"
            "YouTube" -> "https://www.youtube.com/results?search_query=$encoded"
            "Brave" -> "https://search.brave.com/search?q=$encoded"
            "Yahoo" -> "https://search.yahoo.com/search?p=$encoded"
            "Ecosia" -> "https://www.ecosia.org/search?q=$encoded"
            "Mojeek" -> "https://www.mojeek.com/search?q=$encoded"
            "Startpage" -> "https://www.startpage.com/sp/search?query=$encoded"
            "Qwant" -> "https://www.qwant.com/?q=$encoded&t=web"
            "Yandex" -> "https://yandex.com/search/?text=$encoded"
            "Swisscows" -> "https://swisscows.com/en/web?query=$encoded"
            else -> SEARCH_URL + encoded
        }
    }

    private data class QuickSite(val name: String, val url: String, val tag: String, val removable: Boolean)

    private fun loadQuickAccessRows() {
        val rows = findViewById<LinearLayout>(R.id.quick_access_rows) ?: return
        rows.removeAllViews()
        val fixed = listOf(
            QuickSite("Google", "https://www.google.com", "", false),
            QuickSite("Bing", "https://www.bing.com", "", false),
            QuickSite("YouTube", "https://www.youtube.com", "", false),
            QuickSite("Facebook", "https://www.facebook.com", "", false),
            QuickSite("Instagram", "https://www.instagram.com", "", false),
            QuickSite("GitHub", "https://github.com", "", false),
            QuickSite("Add", "", "+", false)
        )
        val personal = BookmarksActivity.getAll(this).take(8).map { QuickSite(it.title, it.url, "★", true) }.toMutableList()
        val visited = HistoryActivity.getTopSites(this, 8)
            .filter { b -> personal.none { it.url == b.url } }
            .map { QuickSite(it.title, it.url, "•", true) }
        personal += visited
        val custom = runCatching { JSONArray(prefs.getString(KEY_CUSTOM_SHORTCUTS, "[]") ?: "[]") }.getOrElse { JSONArray() }
        for (i in 0 until custom.length()) {
            val item = custom.optJSONObject(i) ?: continue
            val name = item.optString("name").trim(); val url = item.optString("url").trim()
            if (name.isNotBlank() && url.isNotBlank() && personal.none { it.url == url }) personal += QuickSite(name, url, "⌂", true)
        }
        (fixed + personal.take(12)).chunked(7).forEach { batch ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 50.dp())
            }
            batch.forEach { site -> row.addView(makeQuickSiteView(site)) }
            rows.addView(row)
        }
    }

    private fun showAddShortcutDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dp(), 4.dp(), 20.dp(), 0)
        }
        val nameInput = EditText(this).apply {
            hint = "Name"
            setSingleLine(true)
        }
        val urlInput = EditText(this).apply {
            hint = "https://example.com"
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        container.addView(nameInput)
        container.addView(urlInput)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Add website shortcut")
            .setView(container)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Add") { _, _ ->
                val rawUrl = urlInput.text.toString().trim()
                if (rawUrl.isBlank()) return@setPositiveButton
                val normalized = if (rawUrl.startsWith("http://") || rawUrl.startsWith("https://")) rawUrl else "https://$rawUrl"
                val name = nameInput.text.toString().trim().ifBlank {
                    Uri.parse(normalized).host?.removePrefix("www.")?.ifBlank { normalized } ?: normalized
                }
                val list = runCatching { JSONArray(prefs.getString(KEY_CUSTOM_SHORTCUTS, "[]") ?: "[]") }.getOrElse { JSONArray() }
                var exists = false
                for (i in 0 until list.length()) {
                    if (list.optJSONObject(i)?.optString("url") == normalized) { exists = true; break }
                }
                if (!exists) {
                    list.put(JSONObject().put("name", name).put("url", normalized))
                    prefs.edit().putString(KEY_CUSTOM_SHORTCUTS, list.toString()).apply()
                    loadQuickAccessRows()
                    Toast.makeText(this, "Shortcut added", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun makeQuickSiteView(site: QuickSite): TextView {
        val surfaceSoft = Color.rgb(23, 32, 51)
        val outline = Color.rgb(51, 65, 85)
        return TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(44.dp(), 44.dp()).apply { leftMargin = 3.dp(); rightMargin = 3.dp() }
            gravity = android.view.Gravity.CENTER
            text = if (site.tag.isNotBlank()) site.tag + "\n" + site.name.take(10) else site.name.take(10)
            setTextColor(if (site.tag == "+") Color.rgb(56,189,248) else Color.WHITE)
            textSize = 9f
            setBackgroundResource(R.drawable.bg_shortcut)
            isClickable = true
            isFocusable = true
            contentDescription = if (site.tag == "+") "Add a website shortcut" else "Open ${site.name}"
            setOnClickListener { if (site.url.isBlank()) showAddShortcutDialog() else openShortcut(site.url) }
            if (site.removable) setOnLongClickListener { confirmQuickSiteRemoval(site); true }
            if (site.url.isNotBlank()) loadFaviconInto(this, site.url)
            styleSurface(this, surfaceSoft, outline, 20)
        }
    }

    private fun confirmQuickSiteRemoval(site: QuickSite) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Remove from Quick access?")
            .setMessage(site.name)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Remove") { _, _ ->
                when (site.tag) {
                    "★" -> BookmarksActivity.remove(this, site.url)
                    "•" -> HistoryActivity.removeUrl(this, site.url)
                    else -> {
                        val list = runCatching { JSONArray(prefs.getString(KEY_CUSTOM_SHORTCUTS, "[]") ?: "[]") }.getOrElse { JSONArray() }
                        val kept = JSONArray()
                        for (i in 0 until list.length()) {
                            val item = list.optJSONObject(i) ?: continue
                            if (item.optString("url") != site.url) kept.put(item)
                        }
                        prefs.edit().putString(KEY_CUSTOM_SHORTCUTS, kept.toString()).apply()
                    }
                }
                loadQuickAccessRows()
            }.show()
    }

    private fun loadPersonalQuickAccess() = loadQuickAccessRows()

    private fun loadFaviconInto(view: TextView, url: String) {
        Thread {
            try {
                val host = URL(url).host
                val candidates = listOf(
                    "https://$host/favicon.ico",
                    "https://www.google.com/s2/favicons?sz=64&domain_url=${URLEncoder.encode(url, "UTF-8")}"
                )
                var bitmap: Bitmap? = null
                for (iconUrl in candidates) {
                    try {
                        val connection = (URL(iconUrl).openConnection() as HttpURLConnection).apply { connectTimeout = 3500; readTimeout = 3500; useCaches = true }
                        bitmap = connection.inputStream.use { BitmapFactory.decodeStream(it) }
                        connection.disconnect()
                        if (bitmap != null) break
                    } catch (_: Exception) { }
                }
                if (bitmap != null) runOnUiThread {
                    val drawable = BitmapDrawable(resources, bitmap)
                    val size = 20.dp(); drawable.setBounds(0, 0, size, size)
                    view.setCompoundDrawables(null, drawable, null, null)
                    view.compoundDrawablePadding = 1.dp()
                }
            } catch (_: Exception) { }
        }.start()
    }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()

    private fun showSearchProviderChooser() {
        val options = arrayOf(
            PHORMI_SEARCH,
            "Google",
            "Bing",
            "DuckDuckGo",
            "Brave",
            "Yahoo",
            "Ecosia",
            "Mojeek",
            "Startpage",
            "Qwant",
            "Yandex",
            "Swisscows",
            "YouTube"
        )
        val current = prefs.getString(KEY_SEARCH_PROVIDER, PHORMI_SEARCH) ?: PHORMI_SEARCH
        val checked = options.indexOf(current).coerceAtLeast(0)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Search")
            .setSingleChoiceItems(options, checked) { dialog, which ->
                prefs.edit().putString(KEY_SEARCH_PROVIDER, options[which]).apply()
                findViewById<TextView>(R.id.start_page_engine).text = options[which] + "  ▾"
                dialog.dismiss()
            }
            .show()
    }

    private data class UnifiedResult(
        val title: String,
        val url: String,
        val snippet: String,
        val source: String,
        val rank: Int
    )

    private data class SearchProvider(
        val name: String,
        val endpoint: (String) -> String
    )

    private fun runUnifiedSearch(query: String) {
        localSearchPageActive = true
        val webView = activeWebView() ?: run {
            createNewTab(NEW_TAB_URL)
            activeWebView()
        } ?: return

        val generation = unifiedSearchGeneration.incrementAndGet()
        // Stop work belonging to a previous query so an old search cannot consume
        // resources after the user has already started a new one.
        synchronized(unifiedSearchLock) {
            unifiedSearchFutures.forEach { it.cancel(true) }
            unifiedSearchFutures.clear()
        }
        startPageContainer.visibility = View.GONE
        startPageContainer.isClickable = false
        webView.visibility = View.VISIBLE
        webView.isClickable = true
        urlBar.setText(query)
        hideKeyboard()
        urlBar.clearFocus()

        val providers = listOf(
            SearchProvider("Google") { q -> "https://www.google.com/search?q=${URLEncoder.encode(q, "UTF-8")}&num=8" },
            SearchProvider("Bing") { q -> "https://www.bing.com/search?q=${URLEncoder.encode(q, "UTF-8")}&count=8" },
            SearchProvider("DuckDuckGo") { q -> "https://html.duckduckgo.com/html/?q=${URLEncoder.encode(q, "UTF-8")}" },
            SearchProvider("Brave") { q -> "https://search.brave.com/search?q=${URLEncoder.encode(q, "UTF-8")}" },
            SearchProvider("Yahoo") { q -> "https://search.yahoo.com/search?p=${URLEncoder.encode(q, "UTF-8")}" },
            SearchProvider("Ecosia") { q -> "https://www.ecosia.org/search?q=${URLEncoder.encode(q, "UTF-8")}" },
            SearchProvider("Mojeek") { q -> "https://www.mojeek.com/search?q=${URLEncoder.encode(q, "UTF-8")}" },
            SearchProvider("Startpage") { q -> "https://www.startpage.com/sp/search?query=${URLEncoder.encode(q, "UTF-8")}" },
            SearchProvider("Qwant") { q -> "https://www.qwant.com/?q=${URLEncoder.encode(q, "UTF-8")}&t=web" },
            SearchProvider("Yandex") { q -> "https://yandex.com/search/?text=${URLEncoder.encode(q, "UTF-8")}" },
            SearchProvider("Swisscows") { q -> "https://swisscows.com/en/web?query=${URLEncoder.encode(q, "UTF-8")}" }
        )

        val loadingHtml = unifiedSearchHtml(query, emptyList(), true, 0, providers.size)
        webView.loadDataWithBaseURL("https://phormi.local/", loadingHtml, "text/html", "UTF-8", null)

        val remaining = AtomicInteger(providers.size)
        val all = java.util.Collections.synchronizedList(mutableListOf<UnifiedResult>())
        val successfulEngines = java.util.Collections.synchronizedSet(mutableSetOf<String>())
        val finishedEngines = java.util.Collections.synchronizedSet(mutableSetOf<String>())

        providers.forEach { provider ->
            val future = unifiedSearchExecutor.submit {
                val results = fetchProviderResults(provider, query)
                synchronized(unifiedSearchLock) {
                    all.addAll(results)
                    finishedEngines.add(provider.name)
                    if (results.isNotEmpty()) successfulEngines.add(provider.name)
                }
                val done = providers.size - remaining.decrementAndGet()
                val snapshot = synchronized(unifiedSearchLock) { all.toList() }
                val successfulSnapshot = synchronized(unifiedSearchLock) { successfulEngines.toList() }
                val finishedSnapshot = synchronized(unifiedSearchLock) { finishedEngines.toList() }
                val mergedSnapshot = mergeUnifiedResults(snapshot)
                runOnUiThread {
                    if (generation != unifiedSearchGeneration.get()) return@runOnUiThread
                    if (activeWebView() === webView) {
                        webView.loadDataWithBaseURL(
                            "https://phormi.local/",
                            unifiedSearchHtml(
                                query,
                                mergedSnapshot,
                                done < providers.size,
                                done,
                                providers.size,
                                successfulSnapshot,
                                finishedSnapshot
                            ),
                            "text/html",
                            "UTF-8",
                            null
                        )
                    }
                    if (done == providers.size && mergedSnapshot.isNotEmpty() && aiController.hasAnyKey()) {
                        lifecycleScope.launch {
                            val evidence = mergedSnapshot.take(12).joinToString("\n") {
                                "${it.title}\n${it.url}\n${it.snippet}\nSources: ${it.source}"
                            }
                            val answer = aiController.synthesizeSearchAnswer(query, evidence)
                            if (generation != unifiedSearchGeneration.get()) return@launch
                            if (answer != null && activeWebView() === webView) {
                                webView.loadDataWithBaseURL(
                                    "https://phormi.local/",
                                    unifiedSearchHtml(query, mergedSnapshot, false, providers.size, providers.size, successfulSnapshot, finishedSnapshot, answer),
                                    "text/html", "UTF-8", null
                                )
                            }
                        }
                    }
                }
            }
            synchronized(unifiedSearchLock) { unifiedSearchFutures.add(future) }
        }
    }

    private fun fetchProviderResults(provider: SearchProvider, query: String): List<UnifiedResult> {
        return try {
            val connection = (URL(provider.endpoint(query)).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 6000
                readTimeout = 8000
                instanceFollowRedirects = true
                useCaches = false
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/126.0 Mobile Safari/537.36")
                setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                setRequestProperty("Accept-Language", "en-US,en;q=0.8")
            }
            connection.connect()
            if (Thread.currentThread().isInterrupted) {
                connection.disconnect()
                return emptyList()
            }
            if (connection.responseCode !in 200..399) {
                connection.disconnect()
                return emptyList()
            }
            // Keep a provider from consuming excessive memory if an endpoint returns a
            // very large page. Search result pages do not need the whole document.
            val html = connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val buffer = CharArray(8192)
                val out = StringBuilder()
                while (out.length < 2_000_000) {
                    if (Thread.currentThread().isInterrupted) return@use out.toString()
                    val count = reader.read(buffer)
                    if (count < 0) break
                    out.append(buffer, 0, count)
                }
                out.toString()
            }
            connection.disconnect()
            if (html.isBlank()) return emptyList()
            parseSearchHtml(provider.name, html).take(8)
        } catch (_: Exception) {
            // Every engine is queried independently and continuously. A failed response contributes no results,
            // but it does not cancel or replace the other engines. Phormi still waits for every engine task to finish.
            emptyList()
        }
    }

    private fun parseSearchHtml(source: String, html: String): List<UnifiedResult> {
        return when (source) {
            "DuckDuckGo" -> parseDuckDuckGo(html, source)
            "Bing" -> parseBing(html, source)
            "Brave" -> parseBrave(html, source)
            "Yahoo" -> parseYahoo(html, source)
            "Ecosia" -> parseGenericSearch(html, source)
            "Mojeek" -> parseGenericSearch(html, source)
            "Startpage" -> parseGenericSearch(html, source)
            "Qwant" -> parseGenericSearch(html, source)
            "Yandex" -> parseGenericSearch(html, source)
            "Swisscows" -> parseGenericSearch(html, source)
            "Google" -> parseGoogle(html, source)
            else -> parseGenericSearch(html, source)
        }
    }

    private fun parseDuckDuckGo(html: String, source: String): List<UnifiedResult> {
        val results = mutableListOf<UnifiedResult>()
        val pattern = Regex("""(?is)<a[^>]+class=[\"']result__a[\"'][^>]+href=[\"']([^\"']+)[\"'][^>]*>(.*?)</a>""")
        pattern.findAll(html).take(12).forEachIndexed { index, match ->
            val url = normalizeSearchUrl(match.groupValues[1])
            val title = cleanHtmlText(match.groupValues[2])
            if (isUsableSearchResult(url, title, source, results)) {
                results += UnifiedResult(title, url, "", source, index)
            }
        }
        return results
    }

    private fun parseBing(html: String, source: String): List<UnifiedResult> {
        val results = mutableListOf<UnifiedResult>()
        val blockPattern = Regex("""(?is)<li[^>]+class=[\"'][^\"']*b_algo[^\"']*[\"'][^>]*>(.*?)</li>""")
        val titlePattern = Regex("""(?is)<h2[^>]*>\s*<a[^>]+href=[\"']([^\"']+)[\"'][^>]*>(.*?)</a>""")
        blockPattern.findAll(html).take(12).forEachIndexed { index, block ->
            val m = titlePattern.find(block.groupValues[1]) ?: return@forEachIndexed
            val url = normalizeSearchUrl(m.groupValues[1])
            val title = cleanHtmlText(m.groupValues[2])
            val snippet = cleanHtmlText(Regex("""(?is)<p[^>]*>(.*?)</p>""").find(block.groupValues[1])?.groupValues?.get(1).orEmpty()).take(220)
            if (isUsableSearchResult(url, title, source, results)) results += UnifiedResult(title, url, snippet, source, index)
        }
        return results
    }

    private fun parseBrave(html: String, source: String): List<UnifiedResult> {
        val results = mutableListOf<UnifiedResult>()
        val pattern = Regex("""(?is)<a[^>]+href=[\"']([^\"']+)[\"'][^>]*class=[\"'][^\"']*(?:result-header|snippet-title)[^\"']*[\"'][^>]*>(.*?)</a>""")
        pattern.findAll(html).take(12).forEachIndexed { index, match ->
            val url = normalizeSearchUrl(match.groupValues[1])
            val title = cleanHtmlText(match.groupValues[2])
            if (isUsableSearchResult(url, title, source, results)) results += UnifiedResult(title, url, "", source, index)
        }
        return results.ifEmpty { parseGenericSearch(html, source) }
    }

    private fun parseYahoo(html: String, source: String): List<UnifiedResult> {
        val results = mutableListOf<UnifiedResult>()
        val pattern = Regex("""(?is)<h3[^>]*>\s*<a[^>]+href=[\"']([^\"']+)[\"'][^>]*>(.*?)</a>""")
        pattern.findAll(html).take(12).forEachIndexed { index, match ->
            val url = normalizeSearchUrl(match.groupValues[1])
            val title = cleanHtmlText(match.groupValues[2])
            if (isUsableSearchResult(url, title, source, results)) results += UnifiedResult(title, url, "", source, index)
        }
        return results.ifEmpty { parseGenericSearch(html, source) }
    }

    private fun parseGoogle(html: String, source: String): List<UnifiedResult> {
        val results = mutableListOf<UnifiedResult>()
        val pattern = Regex("""(?is)<a[^>]+href=[\"'](/url\?q=[^\"']+|https?://[^\"']+)[\"'][^>]*>(.*?)</a>""")
        pattern.findAll(html).take(30).forEach { match ->
            val url = normalizeSearchUrl(match.groupValues[1])
            val title = cleanHtmlText(match.groupValues[2])
            if (isUsableSearchResult(url, title, source, results)) {
                results += UnifiedResult(title, url, "", source, results.size)
            }
        }
        return results
    }

    private fun parseGenericSearch(html: String, source: String): List<UnifiedResult> {
        val results = mutableListOf<UnifiedResult>()
        val anchorRegex = Regex("""(?is)<a\b[^>]*href=[\"']([^\"']+)[\"'][^>]*>(.*?)</a>""")
        for (match in anchorRegex.findAll(html)) {
            if (results.size >= 12) break
            val rawUrl = Html.fromHtml(match.groupValues[1], Html.FROM_HTML_MODE_LEGACY).toString().trim()
            val title = cleanHtmlText(match.groupValues[2])
            val url = normalizeSearchUrl(rawUrl)
            if (isUsableSearchResult(url, title, source, results)) {
                val snippet = extractNearbySnippet(html, match.range.last)
                results += UnifiedResult(title, url, snippet, source, results.size)
            }
        }
        return results
    }

    private fun isUsableSearchResult(
        url: String,
        title: String,
        source: String,
        existing: List<UnifiedResult>
    ): Boolean {
        if (title.length < 3 || url.isBlank() || !url.startsWith("http")) return false
        val host = try { URL(url).host.lowercase(Locale.US).removePrefix("www.") } catch (_: Exception) { return false }
        if (host.isBlank()) return false
        val blocked = setOf(
            "google.com", "bing.com", "duckduckgo.com", "brave.com", "search.yahoo.com",
            "ecosia.org", "mojeek.com", "startpage.com", "qwant.com", "yandex.com", "swisscows.com"
        )
        if (blocked.any { host == it || host.endsWith(".$it") }) return false
        if (title.equals("Images", true) || title.equals("Videos", true) || title.equals("Maps", true)) return false
        if (existing.any { canonicalUrl(it.url) == canonicalUrl(url) }) return false
        return true
    }

    private fun canonicalUrl(raw: String): String {
        return try {
            URL(raw).run {
                val hostPart = host.lowercase(Locale.US).removePrefix("www.")
                val pathPart = path.ifBlank { "/" }.trimEnd('/').lowercase(Locale.US)
                val cleanQuery = query.orEmpty().split('&')
                    .filter { it.isNotBlank() }
                    .filterNot { it.substringBefore('=').lowercase(Locale.US) in setOf(
                        "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content",
                        "gclid", "fbclid", "msclkid", "ref", "ref_src"
                    ) }
                    .sorted()
                    .joinToString("&")
                buildString {
                    append(hostPart).append(pathPart)
                    if (cleanQuery.isNotBlank()) append('?').append(cleanQuery.lowercase(Locale.US))
                }
            }
        } catch (_: Exception) { raw.lowercase(Locale.US).substringBefore('#') }
    }

    private fun cleanHtmlText(value: String): String {
        return Html.fromHtml(value, Html.FROM_HTML_MODE_LEGACY).toString()
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun normalizeSearchUrl(raw: String): String {
        var value = Html.fromHtml(raw, Html.FROM_HTML_MODE_LEGACY).toString().trim()
        if (value.startsWith("//")) value = "https:$value"
        if (value.startsWith("/url?")) {
            val target = value.substringAfter("q=", "").substringBefore('&')
            if (target.isNotBlank()) value = try { java.net.URLDecoder.decode(target, "UTF-8") } catch (_: Exception) { value }
        }
        if (value.contains("uddg=")) {
            val part = value.substringAfter("uddg=").substringBefore('&')
            value = try { java.net.URLDecoder.decode(part, "UTF-8") } catch (_: Exception) { value }
        }
        return value.substringBefore('#')
    }

    private fun extractNearbySnippet(html: String, position: Int): String {
        val end = (position + 700).coerceAtMost(html.length)
        val text = cleanHtmlText(html.substring(position, end))
        return text.take(220)
    }

    private fun mergeUnifiedResults(raw: List<UnifiedResult>): List<UnifiedResult> {
        val grouped = linkedMapOf<String, MutableList<UnifiedResult>>()
        raw.forEach { result ->
            grouped.getOrPut(canonicalUrl(result.url)) { mutableListOf() }.add(result)
        }

        return grouped.values
            .map { group ->
                // Treat every search engine as a contributor, rather than using a fallback chain.
                // A page earns points from each engine that independently found it. Earlier
                // positions contribute more, while cross-engine agreement gives an additional
                // signal that the page is broadly relevant. No single engine is authoritative.
                val best = group.minByOrNull { it.rank } ?: group.first()
                val sourceNames = group.map { it.source }.distinct()
                val engineContribution = group.sumOf { result ->
                    (100 - (result.rank * 8)).coerceAtLeast(20)
                }
                val agreementBonus = ((sourceNames.size - 1) * 20)
                // Prefer useful results that are independently surfaced by several engines,
                // while also preventing a single domain from dominating the first page.
                val domain = try {
                    URL(best.url).host.lowercase(Locale.US).removePrefix("www.")
                } catch (_: Exception) { "" }
                val score = engineContribution + agreementBonus
                best.copy(
                    source = sourceNames.joinToString(" · "),
                    rank = score,
                    title = if (domain.isNotBlank()) best.title else best.title
                )
            }
            .sortedByDescending { it.rank }
            .take(30)
            .let { ranked ->
                // Apply a small diversity adjustment after relevance has been established.
                // This does not choose one engine over another; it only prevents repeated
                // results from the same site from crowding out independent answers.
                val domainCounts = mutableMapOf<String, Int>()
                ranked.sortedByDescending { result ->
                    val domain = try { URL(result.url).host.lowercase(Locale.US).removePrefix("www.") }
                    catch (_: Exception) { "" }
                    val seen = domainCounts.getOrDefault(domain, 0)
                    domainCounts[domain] = seen + 1
                    result.rank - (seen * 8)
                }
            }
            .mapIndexed { index, result -> result.copy(rank = index) }
    }

    private fun unifiedSearchHtml(
        query: String,
        results: List<UnifiedResult>,
        loading: Boolean,
        completed: Int,
        total: Int,
        successfulEngines: List<String> = emptyList(),
        finishedEngines: List<String> = emptyList(),
        aiAnswer: String? = null
    ): String {
        val q = Html.escapeHtml(query)
        val body = if (results.isEmpty() && loading) {
            "<div class='status'>Searching across $total engines…</div>"
        } else if (results.isEmpty()) {
            "<div class='status'>No results could be collected. You can open an engine directly below.</div>"
        } else {
            results.joinToString("\n") { result ->
                val title = Html.escapeHtml(result.title)
                val url = Html.escapeHtml(result.url)
                val snippet = Html.escapeHtml(result.snippet)
                """
                <article class='result'>
                  <a class='title' href='$url'>$title</a>
                  <div class='url'>$url</div>
                  <div class='snippet'>$snippet</div>
                  <button class='source-badge' aria-label='Search sources' onclick='toggleSources(this)'>${result.source.split(" · ").take(5).joinToString("") { it.firstOrNull()?.uppercase() ?: "?" }}</button>
                  <span class='source-details'>${Html.escapeHtml(result.source)}</span>
                </article>
                """.trimIndent()
            }
        }
        val aiSection = aiAnswer?.takeIf { it.isNotBlank() }?.let { answer ->
            val escaped = Html.escapeHtml(answer)
            "<section class='ai'><div class='ai-title'>AI synthesis</div><div>$escaped</div></section>"
        }.orEmpty()
        return """
            <!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'>
            <style>
            body{margin:0;background:#0b1220;color:#e5e7eb;font-family:sans-serif}
            .top{padding:18px 18px 12px;position:sticky;top:0;background:#0b1220;border-bottom:1px solid #263244}
            .brand{font-size:22px;font-weight:700}.query{margin-top:5px;color:#94a3b8;font-size:13px}.status{padding:28px 18px;color:#94a3b8}
            .result{padding:17px 18px;border-bottom:1px solid #202b3c;position:relative}.title{font-size:17px;color:#38bdf8;text-decoration:none;font-weight:600}.url{font-size:11px;color:#64748b;margin-top:5px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.snippet{font-size:13px;line-height:1.45;margin-top:7px;color:#cbd5e1}.source-badge{margin-top:8px;min-width:28px;height:24px;padding:0 7px;border:1px solid #334155;border-radius:12px;background:#111827;color:#94a3b8;font-size:9px;font-weight:700}.source-details{display:none;margin-left:7px;color:#64748b;font-size:10px;vertical-align:middle}.source-details.show{display:inline-block}
            .coverage{padding:8px 14px;color:#94a3b8;font-size:11px;border-bottom:1px solid #1e293b}.ai{margin:10px 14px;padding:12px;border:1px solid #334155;border-radius:14px;background:#111827;color:#e2e8f0}.ai-title{color:#38bdf8;font-weight:700}.ai div{margin-top:8px;line-height:1.5;font-size:13px}.contributors{display:none;margin-top:5px;color:#64748b}.footer{padding:18px;color:#94a3b8;font-size:12px}.engine{display:inline-block;margin:4px 4px 0 0;padding:8px 10px;border:1px solid #334155;border-radius:14px;color:#cbd5e1;text-decoration:none}
            </style></head><body>
            <div class='top'><div class='brand'>Phormi Search</div><div class='query'>$q</div><div class='query'>${if (loading) "Collecting $completed/$total engines…" else "Unified results · $total engines"}</div></div>
            <div class='coverage'>${if (loading) "${finishedEngines.size}/$total answered" else "${successfulEngines.size}/$total answered"}</div>
            $body
            <div class='footer'>
              Phormi queries all available independent search indexes together, merges matching pages, removes duplicates, and ranks results using the combined evidence from every engine that responds. No single engine is treated as a fallback or as the only authority.<br><br>
              <a class='engine' href='https://www.google.com/search?q=${URLEncoder.encode(query, "UTF-8")}'>Google</a>
              <a class='engine' href='https://www.bing.com/search?q=${URLEncoder.encode(query, "UTF-8")}'>Bing</a>
              <a class='engine' href='https://duckduckgo.com/?q=${URLEncoder.encode(query, "UTF-8")}'>DuckDuckGo</a>
              <a class='engine' href='https://search.brave.com/search?q=${URLEncoder.encode(query, "UTF-8")}'>Brave</a>
              <a class='engine' href='https://search.yahoo.com/search?p=${URLEncoder.encode(query, "UTF-8")}'>Yahoo</a>
              <a class='engine' href='https://www.ecosia.org/search?q=${URLEncoder.encode(query, "UTF-8")}'>Ecosia</a>
              <a class='engine' href='https://www.mojeek.com/search?q=${URLEncoder.encode(query, "UTF-8")}'>Mojeek</a>
              <a class='engine' href='https://www.startpage.com/sp/search?query=${URLEncoder.encode(query, "UTF-8")}'>Startpage</a>
              <a class='engine' href='https://www.qwant.com/?q=${URLEncoder.encode(query, "UTF-8")}&t=web'>Qwant</a>
              <a class='engine' href='https://yandex.com/search/?text=${URLEncoder.encode(query, "UTF-8")}'>Yandex</a>
              <a class='engine' href='https://swisscows.com/en/web?query=${URLEncoder.encode(query, "UTF-8")}'>Swisscows</a>
            </div>
            <script>function toggleSources(btn){var d=btn.nextElementSibling; if(d){d.classList.toggle('show');}}</script>
            </body></html>
        """.trimIndent()
    }

    private fun openShortcut(url: String) {
        localSearchPageActive = false
        val webView = activeWebView()
        if (webView == null) {
            createNewTab(url)
            return
        }
        startPageContainer.visibility = View.GONE
        webView.visibility = View.VISIBLE
        urlBar.setText(url)
        webView.loadUrl(url)
    }

    private fun navigateFromStartPage() {
        val raw = startPageSearch.text?.toString()?.trim().orEmpty()
        if (raw.isEmpty()) return
        if ((prefs.getString(KEY_SEARCH_PROVIDER, PHORMI_SEARCH) ?: PHORMI_SEARCH) == PHORMI_SEARCH &&
            !raw.startsWith("http://") && !raw.startsWith("https://") &&
            !(raw.contains(".") && !raw.contains(" "))
        ) {
            runUnifiedSearch(raw)
            return
        }
        urlBar.setText(raw)
        navigateFromUrlBar()
    }

    /** Keep the browser chrome to one horizontal layer. Phones use the tab overview button;
     * wide screens may expose the compact tab strip inside that same layer. */
    private fun updateResponsiveChrome() {
        val tabStrip = findViewById<View>(R.id.tab_strip) ?: return
        val wide = resources.configuration.screenWidthDp >= 600
        tabStrip.visibility = if (wide) View.VISIBLE else View.GONE
    }

    private fun showStartPage() {
        localSearchPageActive = false
        if (splitMode) setSplitMode(false)
        startPageContainer.visibility = View.VISIBLE
        activeWebView()?.visibility = View.GONE
        urlBar.setText("")
        startPageSearch.clearFocus()
        hideKeyboard()
        updateHomeChromeVisibility()
        loadQuickAccessRows()
    }

    private fun updateHomeChromeVisibility() {
        val onHome = startPageContainer.visibility == View.VISIBLE && !splitMode
        findViewById<View>(R.id.url_toolbar)?.visibility = if (onHome) View.GONE else View.VISIBLE
        findViewById<View>(R.id.btn_home)?.visibility = if (onHome) View.GONE else View.VISIBLE
        findViewById<View>(R.id.btn_refresh)?.visibility = if (onHome) View.GONE else View.VISIBLE
        findViewById<View>(R.id.bottom_toolbar)?.visibility = if (onHome) View.GONE else View.VISIBLE
        swipeRefresh.isEnabled = !onHome && prefs.getBoolean(KEY_PULL_TO_REFRESH, true)
    }

    private fun updateStartPageVisibility() {
        val url = activeWebView()?.url
        if (localSearchPageActive) {
            startPageContainer.visibility = View.GONE
            startPageContainer.isClickable = false
            activeWebView()?.visibility = View.VISIBLE
            activeWebView()?.isClickable = true
        } else if (url.isNullOrBlank() || url == NEW_TAB_URL) {
            startPageContainer.visibility = View.VISIBLE
            startPageContainer.isClickable = true
            activeWebView()?.visibility = View.GONE
        } else {
            startPageContainer.visibility = View.GONE
            startPageContainer.isClickable = false
            swipeRefresh.isEnabled = true
            activeWebView()?.visibility = View.VISIBLE
            activeWebView()?.isClickable = true
        }
        updateHomeChromeVisibility()
    }

    private fun updateTabCount() {
        tabCountView.text = tabs.size.toString()
    }

    private fun updateNavButtons() {
        val webView = activeWebView()
        val back = findViewById<TextView>(R.id.btn_back) ?: return
        val forward = findViewById<TextView>(R.id.btn_forward) ?: return
        back.isEnabled = webView?.canGoBack() == true
        forward.isEnabled = webView?.canGoForward() == true
        back.alpha = if (back.isEnabled) 1f else 0.35f
        forward.alpha = if (forward.isEnabled) 1f else 0.35f
    }


    private fun navigateFromUrlBar() {
        val raw = urlBar.text?.toString()?.trim().orEmpty()
        if (raw.isEmpty()) return
        if ((prefs.getString(KEY_SEARCH_PROVIDER, PHORMI_SEARCH) ?: PHORMI_SEARCH) == PHORMI_SEARCH &&
            !raw.startsWith("http://") && !raw.startsWith("https://") &&
            !(raw.contains(".") && !raw.contains(" "))
        ) {
            runUnifiedSearch(raw)
            return
        }
        val url = when {
            raw.startsWith("http://") || raw.startsWith("https://") -> raw
            raw.contains(".") && !raw.contains(" ") -> "https://$raw"
            else -> searchUrlForProvider(raw)
        }
        val webView = activeWebView()
        localSearchPageActive = false
        if (webView != null) {
            startPageContainer.visibility = View.GONE
            startPageContainer.isClickable = false
            swipeRefresh.isEnabled = true
            webView.visibility = View.VISIBLE
            webView.isClickable = true
            webView.loadUrl(url)
        } else createNewTab(url)
        hideKeyboard()
        urlBar.clearFocus()
    }

    private fun showKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(urlBar, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(urlBar.windowToken, 0)
    }

    private fun applyKeepScreenOn(enabled: Boolean) {
        if (enabled) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onStop() {
        if (::prefs.isInitialized) {
            saveTabs()
            // A biometric/device-credential prompt can temporarily move the
            // Activity through the stopped state. Do not relock during that
            // authentication transaction; relock only after a real exit to
            // another app/home screen.
            if (browserLockManager.isEnabled(prefs) &&
                !browserLockManager.isPromptInProgress()) {
                browserUnlockedThisSession = false
            }
        }
        super.onStop()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        val url = intent?.dataString?.trim()
        if (!url.isNullOrBlank() && (url.startsWith("http://") || url.startsWith("https://"))) {
            createNewTab(url)
        }
    }

    private fun installSplitDividerResize() {
        splitDivider.setOnTouchListener { _, event ->
            if (!splitMode || splitChromeLocked) return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val loc = IntArray(2)
                    splitContainer.getLocationOnScreen(loc)
                    val h = splitContainer.height.coerceAtLeast(1)
                    val rawRatio = (event.rawY - loc[1]).toFloat() / h
                    splitRatio = rawRatio.coerceIn(0.25f, 0.75f)
                    applySplitRatio()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
    }

    private fun applySplitRatio() {
        if (!splitMode) return
        val total = splitContainer.height
        if (total <= 0) return
        val dividerHeight = splitDivider.height.coerceAtLeast(1)
        val available = (total - dividerHeight).coerceAtLeast(2)
        val top = (available * splitRatio).toInt().coerceAtLeast(1)
        val bottom = (available - top).coerceAtLeast(1)
        splitTopHost.layoutParams = splitTopHost.layoutParams.apply { height = top; width = ViewGroup.LayoutParams.MATCH_PARENT }
        splitBottomHost.layoutParams = splitBottomHost.layoutParams.apply { height = bottom; width = ViewGroup.LayoutParams.MATCH_PARENT }
        splitTopHost.requestLayout()
        splitBottomHost.requestLayout()
    }

    private fun setSplitMode(enabled: Boolean) {
        if (enabled == splitMode) return
        if (enabled && tabs.size < 2) createNewTab(NEW_TAB_URL)
        if (enabled) {
            splitMode = true
            splitTopTabId = activeTabId
            splitBottomTabId = tabs.firstOrNull { it.id != splitTopTabId }?.id ?: -1
            if (splitBottomTabId == -1) { splitMode = false; return }
            moveTabWebViewToHost(splitTopTabId, splitTopHost)
            moveTabWebViewToHost(splitBottomTabId, splitBottomHost)
            splitContainer.visibility = View.VISIBLE
            startPageContainer.visibility = View.GONE
            setSplitChromeLocked(false)
            splitRatio = 0.5f
            splitContainer.post { applySplitRatio() }
        } else {
            splitMode = false
            listOf(splitTopTabId, splitBottomTabId).filter { it >= 0 }.forEach { moveTabWebViewToHost(it, webViewContainer) }
            splitTopTabId = -1; splitBottomTabId = -1
            splitRatio = 0.5f
            splitContainer.visibility = View.GONE
            updateStartPageVisibility()
            setSplitChromeLocked(false)
        }
        updateHomeChromeVisibility()
    }

    private fun moveTabWebViewToHost(tabId: Int, host: FrameLayout) {
        val webView = tabs.find { it.id == tabId }?.webView ?: return
        (webView.parent as? ViewGroup)?.removeView(webView)
        host.removeAllViews()
        host.addView(webView, FrameLayout.LayoutParams(-1, -1))
        webView.visibility = View.VISIBLE
    }

    private fun setSplitChromeLocked(locked: Boolean) {
        splitChromeLocked = locked
        if (splitMode) {
            findViewById<View>(R.id.top_toolbar)?.visibility = if (locked) View.GONE else View.VISIBLE
            findViewById<View>(R.id.bottom_toolbar)?.visibility = if (locked) View.GONE else View.VISIBLE
            splitLockButton.visibility = View.VISIBLE
            splitLockButton.text = if (locked) "🔓" else "🔒"
            splitLockButton.contentDescription = if (locked) "Show browser controls" else "Hide browser controls"
        } else {
            splitLockButton.visibility = View.GONE
        }
    }

    private fun activeWebView(): WebView? = tabs.find { it.id == activeTabId }?.webView

    private fun createNewTab(url: String) {
        val isGhost = prefs.getBoolean("ghost_next_tab", false)
        if (isGhost) prefs.edit().putBoolean("ghost_next_tab", false).apply()
        val id = nextTabId++
        val webView = WebView(this)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webViewContainer.addView(
            webView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        configureWebView(webView)
        webView.isClickable = true
        webView.isFocusable = true
        webView.isFocusableInTouchMode = true
        webView.setBackgroundColor(Color.TRANSPARENT)
        registerForContextMenu(webView)

        val chip = LayoutInflater.from(this).inflate(R.layout.tab_chip, tabStripContainer, false)
        val chipTitle = chip.findViewById<TextView>(R.id.tab_chip_title)
        val chipClose = chip.findViewById<TextView>(R.id.tab_chip_close)

        val tab = Tab(id, webView, getString(R.string.new_tab), chip, isGhost = isGhost, lastUsed = System.currentTimeMillis())
        tabs.add(tab)
        tabStripContainer.addView(chip)

        chip.setOnClickListener { switchToTab(id) }
        chipClose.setOnClickListener { closeTab(id) }

        webView.webChromeClient = buildChromeClient { newTitle ->
            val t = if (newTitle.isNullOrBlank()) getString(R.string.new_tab) else newTitle
            chipTitle.text = t
            tabs.find { it.id == id }?.title = t
            saveTabs()
        }

        if (url != NEW_TAB_URL) webView.loadUrl(url)
        switchToTab(id)
        updateTabCount()
        saveTabs()
    }

    private fun switchToTab(id: Int) {
        activeTabId = id
        tabs.find { it.id == id }?.lastUsed = System.currentTimeMillis()
        tabs.forEach { tab ->
            val isActive = tab.id == id
            tab.webView.visibility = if (isActive) View.VISIBLE else View.GONE
            tab.chipView.alpha = if (isActive) 1f else 0.55f
        }
        updateStartPageVisibility()
        updateTabCount()
        updateNavButtons()
        val current = tabs.find { it.id == id }?.webView?.url
        if (!current.isNullOrBlank() && current != "about:blank") {
            urlBar.setText(current)
        } else {
            urlBar.setText("")
        }
        saveTabs()
    }

    private fun closeTab(id: Int) {
        if (splitMode && (id == splitTopTabId || id == splitBottomTabId)) setSplitMode(false)
        val index = tabs.indexOfFirst { it.id == id }
        if (index == -1) return
        val tab = tabs[index]
        unregisterForContextMenu(tab.webView)
        webViewContainer.removeView(tab.webView)
        tabStripContainer.removeView(tab.chipView)
        tab.webView.destroy()
        tabs.removeAt(index)

        if (tabs.isEmpty()) {
            createNewTab(NEW_TAB_URL)
            urlBar.setText("")
        } else if (activeTabId == id) {
            switchToTab((tabs.getOrNull(index - 1) ?: tabs.first()).id)
        }
        updateTabCount()
        saveTabs()
    }

    private fun saveTabs() {
        val persistTabs = tabs.filterNot { it.isGhost }
        val urls = JSONArray()
        val titles = JSONArray()
        val lastUsed = JSONArray()
        persistTabs.forEach { tab ->
            val u = tab.webView.url
            if (!u.isNullOrBlank()) {
                urls.put(u)
                titles.put(tab.title.ifBlank { "Tab" })
                lastUsed.put(tab.lastUsed)
            }
        }
        if (urls.length() == 0) {
            urls.put(NEW_TAB_URL)
            titles.put(getString(R.string.new_tab))
            lastUsed.put(System.currentTimeMillis())
        }
        val activeIndex = persistTabs.indexOfFirst { it.id == activeTabId }.coerceAtLeast(0)
        prefs.edit()
            .putString(KEY_TAB_URLS, urls.toString())
            .putString(KEY_TAB_TITLES, titles.toString())
            .putString(KEY_TAB_LAST_USED, lastUsed.toString())
            .putString(KEY_TAB_RETENTION, prefs.getString(KEY_TAB_RETENTION, RETENTION_NEVER) ?: RETENTION_NEVER)
            .putInt(KEY_ACTIVE_INDEX, activeIndex)
            .apply()
    }

    private fun restoreTabs() {
        val json = prefs.getString(KEY_TAB_URLS, null)
        if (json.isNullOrBlank()) {
            createNewTab(NEW_TAB_URL)
            return
        }
        try {
            val arr = JSONArray(json)
            if (arr.length() == 0) {
                createNewTab(NEW_TAB_URL)
                return
            }
            val titles = runCatching {
                JSONArray(prefs.getString(KEY_TAB_TITLES, "[]") ?: "[]")
            }.getOrElse { JSONArray() }
            val lastUsed = runCatching {
                JSONArray(prefs.getString(KEY_TAB_LAST_USED, "[]") ?: "[]")
            }.getOrElse { JSONArray() }
            val count = arr.length()
            val activeIndex = prefs.getInt(KEY_ACTIVE_INDEX, 0).coerceIn(0, (count - 1).coerceAtLeast(0))
            for (i in 0 until count) {
                val restoredUrl = arr.optString(i, NEW_TAB_URL).trim().ifBlank { NEW_TAB_URL }
                createNewTab(restoredUrl)
                if (i < tabs.size && i < lastUsed.length()) tabs[i].lastUsed = lastUsed.optLong(i, System.currentTimeMillis())
                val restoredTitle = titles.optString(i).trim()
                if (restoredTitle.isNotBlank() && i < tabs.size) {
                    tabs[i].title = restoredTitle
                    tabs[i].chipView.findViewById<TextView>(R.id.tab_chip_title)?.text = restoredTitle
                }
            }
            if (tabs.isNotEmpty()) {
                switchToTab((tabs.getOrNull(activeIndex) ?: tabs.first()).id)
            }
        } catch (_: Exception) {
            createNewTab(NEW_TAB_URL)
        }
    }


    private fun retentionAgeMillis(): Long? = when (prefs.getString(KEY_TAB_RETENTION, RETENTION_NEVER)) {
        RETENTION_1_MONTH -> 30L * 24 * 60 * 60 * 1000
        RETENTION_3_MONTHS -> 90L * 24 * 60 * 60 * 1000
        RETENTION_1_YEAR -> 365L * 24 * 60 * 60 * 1000
        else -> null
    }

    private fun pruneExpiredTabs() {
        val age = retentionAgeMillis() ?: return
        val cutoff = System.currentTimeMillis() - age
        val expired = tabs.filter { !it.isGhost && it.lastUsed < cutoff }.map { it.id }
        expired.forEach { closeTab(it) }
    }

    private data class HomeStory(val title: String, val url: String, val source: String, val category: String)

    private fun updateHomeNewsVisibility() {
        val row = findViewById<View>(R.id.start_page_news_row) ?: return
        val enabled = prefs.getBoolean(KEY_NEWS_ENABLED, true)
        row.visibility = if (enabled) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.start_page_news)?.text = if (enabled) "News · On" else "News · Off"
        findViewById<TextView>(R.id.start_page_news)?.contentDescription = if (enabled) "Turn news off" else "Turn news on"
    }

    private fun refreshHomeNews() {
        if (!prefs.getBoolean(KEY_NEWS_ENABLED, true)) return
        val container = findViewById<LinearLayout>(R.id.start_page_news_container) ?: return
        Thread {
            val country = Locale.getDefault().country.uppercase(Locale.US).ifBlank { "US" }
            val localFeed = "https://news.google.com/rss?hl=en-$country&gl=$country&ceid=$country:en"
            val feeds = listOf(
                Triple(localFeed, "Local", "local"),
                Triple("https://feeds.bbci.co.uk/news/world/rss.xml", "BBC World", "world"),
                Triple("https://feeds.bbci.co.uk/news/africa/rss.xml", "BBC Africa", "africa"),
                Triple("https://feeds.bbci.co.uk/news/technology/rss.xml", "BBC Technology", "technology"),
                Triple("https://feeds.bbci.co.uk/news/business/rss.xml", "BBC Business", "business"),
                Triple("https://feeds.bbci.co.uk/news/entertainment_and_arts/rss.xml", "BBC Arts", "culture"),
                Triple("https://feeds.bbci.co.uk/sport/rss.xml", "BBC Sport", "sport")
            )
            val stories = mutableListOf<HomeStory>()
            feeds.forEach { (feed, source, category) -> stories += fetchHomeFeed(feed, source, category) }
            val weights = runCatching { JSONObject(prefs.getString(KEY_NEWS_PREFS, "{}") ?: "{}") }.getOrElse { JSONObject() }
            val ranked = stories.distinctBy { canonicalUrl(it.url) }
                .sortedByDescending {
                    val preference = weights.optInt(it.category, 0)
                    val scopeBonus = when (it.category) { "local" -> 80; "world", "africa" -> 50; else -> 0 }
                    preference * 1000 + scopeBonus + (it.title.length.coerceAtMost(120))
                }
                .take(12)
            runOnUiThread { renderHomeNews(container, ranked) }
        }.start()
    }

    private fun fetchHomeFeed(feedUrl: String, source: String, category: String): List<HomeStory> {
        return try {
            val connection = URL(feedUrl).openConnection() as HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 7000
            connection.setRequestProperty("User-Agent", "Phormi/1.0")
            val parser = android.util.Xml.newPullParser()
            val out = mutableListOf<HomeStory>()
            connection.inputStream.use { stream ->
                parser.setInput(stream, "UTF-8")
                var event = parser.eventType
                var title = ""
                var link = ""
                var inItem = false
                while (event != XmlPullParser.END_DOCUMENT && out.size < 12) {
                    when (event) {
                        XmlPullParser.START_TAG -> when {
                            parser.name.equals("item", true) || parser.name.equals("entry", true) -> inItem = true
                            inItem && parser.name.equals("title", true) -> title = parser.nextText().trim()
                            inItem && parser.name.equals("link", true) -> {
                                val href = parser.getAttributeValue(null, "href")
                                link = href?.takeIf { it.isNotBlank() } ?: parser.nextText().trim()
                            }
                        }
                        XmlPullParser.END_TAG -> if (parser.name.equals("item", true) || parser.name.equals("entry", true)) {
                            if (title.isNotBlank() && link.startsWith("http")) out += HomeStory(title, link, source, category)
                            title = ""; link = ""; inItem = false
                        }
                    }
                    event = parser.next()
                }
            }
            connection.disconnect()
            out
        } catch (_: Exception) { emptyList() }
    }

    private fun renderHomeNews(container: LinearLayout, stories: List<HomeStory>) {
        container.removeAllViews()
        stories.chunked(2).forEach { pair ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(-1, 76.dp())
            }
            pair.forEach { story ->
                val card = TextView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, 72.dp(), 1f).apply { leftMargin = 4.dp(); rightMargin = 4.dp() }
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(10.dp(), 8.dp(), 10.dp(), 8.dp())
                    text = "${story.title}\n${story.source} · ${story.category}"
                    setTextColor(Color.rgb(226, 232, 240))
                    textSize = 11f
                    maxLines = 3
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setBackgroundResource(R.drawable.bg_shortcut)
                    setOnClickListener {
                        val prefsJson = runCatching { JSONObject(prefs.getString(KEY_NEWS_PREFS, "{}") ?: "{}") }.getOrElse { JSONObject() }
                        prefsJson.put(story.category, prefsJson.optInt(story.category, 0) + 1)
                        prefs.edit().putString(KEY_NEWS_PREFS, prefsJson.toString()).apply()
                        createNewTab(story.url)
                    }
                }
                row.addView(card)
            }
            if (pair.size == 1) row.addView(View(this), LinearLayout.LayoutParams(0, 72.dp(), 1f))
            container.addView(row)
        }
        if (stories.isEmpty()) container.addView(TextView(this).apply {
            text = "News unavailable right now"; setTextColor(Color.rgb(100,116,139)); textSize = 9f; setPadding(8.dp(),8.dp(),8.dp(),8.dp())
        })
    }

    private fun startDownload(url: String, contentDisposition: String?, mimeType: String?) {
        if (url.isBlank()) {
            Toast.makeText(this, "Cannot download this link", Toast.LENGTH_SHORT).show()
            return
        }

        if (url.startsWith("blob:", ignoreCase = true)) {
            downloadBlobUrl(url, contentDisposition, mimeType)
            return
        }

        if (url.startsWith("data:", ignoreCase = true)) {
            downloadDataUrl(url, contentDisposition, mimeType)
            return
        }
        try {
            var fileName = URLUtil.guessFileName(
                url, contentDisposition, mimeType ?: "application/octet-stream"
            )
            if (fileName.endsWith(".bin", ignoreCase = true)) {
                val lowerMime = (mimeType ?: "").lowercase()
                when {
                    lowerMime.contains("mp4") || lowerMime.contains("video") ->
                        fileName = fileName.removeSuffix(".bin").removeSuffix(".BIN") + ".mp4"
                    lowerMime.contains("webm") ->
                        fileName = fileName.removeSuffix(".bin").removeSuffix(".BIN") + ".webm"
                    url.contains(".mp4", ignoreCase = true) ->
                        fileName = fileName.removeSuffix(".bin").removeSuffix(".BIN") + ".mp4"
                    url.contains(".apk", ignoreCase = true) ->
                        fileName = fileName.removeSuffix(".bin").removeSuffix(".BIN") + ".apk"
                }
            }

            val webView = activeWebView()
            val userAgent = webView?.settings?.userAgentString
                ?: "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36"
            val referer = webView?.url ?: url
            val cookies = CookieManager.getInstance().getCookie(url)
                ?: CookieManager.getInstance().getCookie(referer)

            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setMimeType(mimeType ?: "application/octet-stream")
                setTitle(fileName)
                setDescription("Phormi download")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                allowScanningByMediaScanner()
                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)
                addRequestHeader("User-Agent", userAgent)
                addRequestHeader("Referer", referer)
                if (!cookies.isNullOrBlank()) addRequestHeader("Cookie", cookies)
            }
            (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
            Toast.makeText(this, "Downloading $fileName\nOpen Downloads in app when done", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun downloadBlobUrl(url: String, contentDisposition: String?, mimeType: String?) {
        val webView = activeWebView() ?: run {
            Toast.makeText(this, "No active page", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(this, "Preparing download…", Toast.LENGTH_SHORT).show()
        val quotedUrl = JSONObject.quote(url)
        val script = """(async function(){
            try {
                const response = await fetch($quotedUrl);
                const blob = await response.blob();
                const reader = new FileReader();
                return await new Promise((resolve, reject) => {
                    reader.onloadend = () => resolve(reader.result);
                    reader.onerror = () => reject(reader.error);
                    reader.readAsDataURL(blob);
                });
            } catch (e) {
                return "ERROR:" + (e && e.message ? e.message : String(e));
            }
        })()"""

        webView.evaluateJavascript(script) { result ->
            try {
                val value = org.json.JSONTokener(result).nextValue() as? String
                if (value.isNullOrBlank() || value.startsWith("ERROR:")) {
                    Toast.makeText(this, "Download failed", Toast.LENGTH_LONG).show()
                    return@evaluateJavascript
                }
                saveDataUrl(value, contentDisposition, mimeType)
            } catch (e: Exception) {
                Toast.makeText(this, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun downloadDataUrl(url: String, contentDisposition: String?, mimeType: String?) {
        saveDataUrl(url, contentDisposition, mimeType)
    }

    private fun saveDataUrl(dataUrl: String, contentDisposition: String?, mimeType: String?) {
        try {
            val comma = dataUrl.indexOf(',')
            if (comma <= 0) throw IllegalArgumentException("Invalid data URL")

            val metadata = dataUrl.substring(5, comma)
            val payload = dataUrl.substring(comma + 1)
            val baseMime = metadata.substringBefore(';').ifBlank {
                mimeType ?: "application/octet-stream"
            }
            val bytes = if (metadata.contains(";base64", ignoreCase = true)) {
                android.util.Base64.decode(payload, android.util.Base64.DEFAULT)
            } else {
                Uri.decode(payload).toByteArray(Charsets.UTF_8)
            }

            val extension = android.webkit.MimeTypeMap.getSingleton()
                .getExtensionFromMimeType(baseMime)
            val suggestedName = URLUtil.guessFileName(
                "https://phormi.local/download" +
                    if (!extension.isNullOrBlank()) ".${extension}" else "",
                contentDisposition,
                baseMime
            )
            var fileName = suggestedName
            if (fileName.isBlank() || fileName == "downloadfile") {
                fileName = "phormi_download" +
                    if (!extension.isNullOrBlank()) ".${extension}" else ""
            }

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(android.provider.MediaStore.Downloads.MIME_TYPE, baseMime)
                    put(android.provider.MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
                }
                val resolver = contentResolver
                val uri = resolver.insert(
                    android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    values
                ) ?: throw IllegalStateException("Could not create download")

                try {
                    resolver.openOutputStream(uri)?.use { it.write(bytes) }
                        ?: throw IllegalStateException("Could not open download")
                    values.clear()
                    values.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                } catch (e: Exception) {
                    resolver.delete(uri, null, null)
                    throw e
                }
            } else {
                val downloads = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS
                )
                if (!downloads.exists()) downloads.mkdirs()
                var target = java.io.File(downloads, fileName)
                var n = 1
                while (target.exists()) {
                    val dot = fileName.lastIndexOf('.')
                    val base = if (dot > 0) fileName.substring(0, dot) else fileName
                    val ext = if (dot > 0) fileName.substring(dot) else ""
                    target = java.io.File(downloads, "$base ($n)$ext")
                    n++
                }
                target.outputStream().use { it.write(bytes) }
                android.media.MediaScannerConnection.scanFile(
                    this, arrayOf(target.absolutePath), arrayOf(baseMime), null
                )
            }

            Toast.makeText(this, "Download complete: $fileName", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreateContextMenu(
        menu: ContextMenu, v: View, menuInfo: ContextMenu.ContextMenuInfo?
    ) {
        super.onCreateContextMenu(menu, v, menuInfo)
        if (v !is WebView) return
        val result = v.hitTestResult
        contextMenuUrl = null
        contextMenuIsImage = false
        when (result.type) {
            WebView.HitTestResult.SRC_ANCHOR_TYPE,
            WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                contextMenuUrl = result.extra
                menu.setHeaderTitle(contextMenuUrl?.take(50) ?: "Link")
                menu.add(0, MENU_OPEN, 0, "Open")
                menu.add(0, MENU_COPY, 1, "Copy link")
                menu.add(0, MENU_DOWNLOAD, 2, "Download")
            }
            WebView.HitTestResult.IMAGE_TYPE -> {
                contextMenuUrl = result.extra
                contextMenuIsImage = true
                menu.setHeaderTitle("Image")
                menu.add(0, MENU_OPEN, 0, "Open")
                menu.add(0, MENU_COPY, 1, "Copy URL")
                menu.add(0, MENU_DOWNLOAD, 2, "Download image")
            }
        }
    }

    override fun onContextItemSelected(item: MenuItem): Boolean {
        val url = contextMenuUrl ?: return super.onContextItemSelected(item)
        when (item.itemId) {
            MENU_OPEN -> {
                createNewTab(url)
                return true
            }
            MENU_COPY -> {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("url", url))
                Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
                return true
            }
            MENU_DOWNLOAD -> {
                startDownload(url, null, if (contextMenuIsImage) "image/*" else null)
                return true
            }
        }
        return super.onContextItemSelected(item)
    }

    private fun updateBrowserLockLabel(view: TextView) {
        val enabled = ::prefs.isInitialized && prefs.getBoolean(KEY_BROWSER_LOCK, false)
        view.text = if (enabled) "🔒 Browser Lock: On" else "🔓 Browser Lock: Off"
    }

    private fun toggleBrowserLock(view: TextView) {
        val enabled = prefs.getBoolean(KEY_BROWSER_LOCK, false)
        if (enabled) {
            prefs.edit().putBoolean(KEY_BROWSER_LOCK, false).apply()
            browserUnlockedThisSession = true
            updateBrowserLockLabel(view)
            Toast.makeText(this, "Browser Lock disabled", Toast.LENGTH_SHORT).show()
            return
        }

        prefs.edit().putBoolean(KEY_BROWSER_LOCK, true).apply()
        updateBrowserLockLabel(view)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Browser Lock method")
            .setItems(arrayOf("Device security / biometrics", "Create a Phormi PIN")) { _, which ->
                if (which == 0) {
                    prefs.edit().putString(BrowserLockManager.PREF_METHOD, BrowserLockManager.METHOD_DEVICE).apply()
                    authenticateBrowserLock()
                } else {
                    promptCreatePin()
                }
            }
            .setOnCancelListener {
                prefs.edit().putBoolean(KEY_BROWSER_LOCK, false).apply()
                updateBrowserLockLabel(view)
            }
            .show()
    }

    private fun authenticateBrowserLock() {
        browserLockManager.authenticate(
            onSuccess = {
                browserUnlockedThisSession = true
                runOnUiThread { removeBrowserLockOverlay() }
            },
            onFailure = {
                browserUnlockedThisSession = false
                runOnUiThread { showBrowserLockOverlay() }
            }
        )
    }

    private fun showBrowserLockOverlay() {
        if (browserLockOverlay != null || isFinishing) return
        val root = findViewById<FrameLayout>(R.id.browser_lock_overlay_host) ?: return
        root.visibility = View.VISIBLE
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        root.isClickable = true
        root.isFocusable = true
        val overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            setPadding(36.dp(), 36.dp(), 36.dp(), 36.dp())
            setBackgroundColor(Color.rgb(11, 18, 32))
        }
        val title = TextView(this).apply {
            text = "Phormi is locked"
            setTextColor(Color.WHITE)
            textSize = 24f
            gravity = android.view.Gravity.CENTER
        }
        val message = TextView(this).apply {
            text = "Authenticate with your device security to continue."
            setTextColor(Color.rgb(148, 163, 184))
            textSize = 14f
            gravity = android.view.Gravity.CENTER
            setPadding(0, 12.dp(), 0, 24.dp())
        }
        val retry = TextView(this).apply {
            text = "Unlock with device security"
            gravity = android.view.Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 16f
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.rgb(56, 189, 248))
                cornerRadius = 24.dp().toFloat()
            }
            setPadding(24.dp(), 14.dp(), 24.dp(), 14.dp())
            setOnClickListener { authenticateBrowserLock() }
        }
        val pin = TextView(this).apply {
            text = "Unlock with Phormi PIN"
            gravity = android.view.Gravity.CENTER
            setTextColor(Color.rgb(56, 189, 248))
            textSize = 14f
            setPadding(24.dp(), 18.dp(), 24.dp(), 10.dp())
            setOnClickListener { promptPinUnlock() }
        }
        overlay.addView(title, LinearLayout.LayoutParams(-1, -2))
        overlay.addView(message, LinearLayout.LayoutParams(-1, -2))
        overlay.addView(retry, LinearLayout.LayoutParams(-2, -2))
        overlay.addView(pin, LinearLayout.LayoutParams(-2, -2))
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        browserLockOverlay = overlay
    }

    private fun promptPinUnlock() {
        if (!browserLockManager.isPinConfigured(prefs)) {
            promptCreatePin()
            return
        }
        val input = EditText(this).apply {
            hint = "Phormi PIN"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setSingleLine(true)
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Unlock Phormi")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Unlock") { _, _ ->
                if (browserLockManager.verifyPin(prefs, input.text.toString())) {
                    browserUnlockedThisSession = true
                    removeBrowserLockOverlay()
                } else Toast.makeText(this, "Incorrect PIN", Toast.LENGTH_SHORT).show()
            }.show()
    }

    private fun promptCreatePin() {
        val input = EditText(this).apply {
            hint = "4+ digit PIN"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setSingleLine(true)
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Create Phormi PIN")
            .setMessage("This PIN is stored locally as a one-way hash. Device security remains available as the recovery method.")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                if (browserLockManager.setPin(prefs, input.text.toString())) {
                    browserUnlockedThisSession = true
                    removeBrowserLockOverlay()
                    Toast.makeText(this, "Phormi PIN enabled", Toast.LENGTH_SHORT).show()
                } else Toast.makeText(this, "Use at least 4 digits", Toast.LENGTH_SHORT).show()
            }.show()
    }

    private fun removeBrowserLockOverlay() {
        val root = findViewById<FrameLayout>(R.id.browser_lock_overlay_host) ?: return
        browserLockOverlay?.let { root.removeView(it) }
        browserLockOverlay = null
        root.visibility = View.GONE
        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        root.isClickable = false
        root.isFocusable = false
    }

    private fun requestStartupPermissions() {
        val needed = STARTUP_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), REQ_STARTUP_PERMISSIONS)
        }
    }


    override fun onPause() {
        // Persist both browser state and website sessions when the app leaves the foreground.
        saveTabs()
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onDestroy() {
        synchronized(unifiedSearchLock) {
            unifiedSearchGeneration.incrementAndGet()
            unifiedSearchFutures.forEach { it.cancel(true) }
            unifiedSearchFutures.clear()
        }
        unifiedSearchExecutor.shutdownNow()
        CookieManager.getInstance().flush()
        super.onDestroy()
    }

    private fun configureWebView(webView: WebView) {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            setGeolocationEnabled(true)
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            // Let sites that use target="_blank" behave like a real browser tab.
            setSupportMultipleWindows(true)
            loadWithOverviewMode = true
            useWideViewPort = true
            setAllowFileAccess(false)
            setAllowContentAccess(true)
            builtInZoomControls = true
            displayZoomControls = false
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                    val t = view?.title ?: ""
                    val u = view?.url ?: url ?: ""
                    val tab = tabs.find { it.webView === view }
                    if (tab != null && !tab.isGhost) {
                        HistoryActivity.record(this@MainActivity, t, u)
                    }

                super.onPageFinished(view, url)
                if (view == activeWebView()) {
                    swipeRefresh.isRefreshing = false
                    updateStartPageVisibility()
                    if (!url.isNullOrBlank() && url != "about:blank") {
                        urlBar.setText(url)
                    }
                    updateNavButtons()
                }
                saveTabs()
            }
        }

        webView.setDownloadListener { url, _, contentDisposition, mimeType, _ ->
            startDownload(url, contentDisposition, mimeType)
        }
    }

    private fun buildChromeClient(onTitleChanged: (String?) -> Unit): WebChromeClient {
        return object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView?, title: String?) {
                super.onReceivedTitle(view, title)
                onTitleChanged(title)
            }

            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message?
            ): Boolean {
                // A new-window request becomes a normal Phormi tab instead of an
                // external browser window. This keeps the browser architecture
                // consistent with the circular tab overview.
                createNewTab(NEW_TAB_URL)
                val newWebView = activeWebView() ?: return false
                val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                transport.webView = newWebView
                resultMsg.sendToTarget()
                return true
            }

            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (customView != null) {
                    callback?.onCustomViewHidden()
                    return
                }
                customView = view
                customViewCallback = callback
                originalOrientation = requestedOrientation

                val container = FrameLayout(this@MainActivity).apply {
                    setBackgroundColor(Color.BLACK)
                    addView(
                        view,
                        FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT
                        )
                    )
                }
                fullscreenContainer = container
                (window.decorView as ViewGroup).addView(
                    container,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
                window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                swipeRefresh.visibility = View.GONE
            }

            override fun onHideCustomView() {
                val container = fullscreenContainer ?: return
                (window.decorView as ViewGroup).removeView(container)
                fullscreenContainer = null
                customView = null
                customViewCallback?.onCustomViewHidden()
                customViewCallback = null
                window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
                requestedOrientation = originalOrientation
                swipeRefresh.visibility = View.VISIBLE
            }

            override fun getDefaultVideoPoster(): Bitmap? {
                return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                pendingPermissionRequest = request
                val needed = mutableListOf<String>()
                if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA)
                    != PackageManager.PERMISSION_GRANTED
                ) needed.add(Manifest.permission.CAMERA)
                if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED
                ) needed.add(Manifest.permission.RECORD_AUDIO)
                if (needed.isEmpty()) request.grant(request.resources)
                else {
                    pendingPermissionPermissions = needed.toTypedArray()
                    ActivityCompat.requestPermissions(
                        this@MainActivity, pendingPermissionPermissions, REQ_MEDIA_PERMISSIONS
                    )
                }
            }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: android.webkit.GeolocationPermissions.Callback?
            ) {
                if (ContextCompat.checkSelfPermission(
                        this@MainActivity, Manifest.permission.ACCESS_FINE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    callback?.invoke(origin, true, false)
                } else {
                    pendingGeoOrigin = origin
                    pendingGeoCallback = callback
                    ActivityCompat.requestPermissions(
                        this@MainActivity,
                        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                        REQ_GEOLOCATION
                    )
                }
            }

            override fun onShowFileChooser(
                webViewParam: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@MainActivity.filePathCallback = filePathCallback
                val params = fileChooserParams ?: return false
                val intent = try {
                    params.createIntent().apply {
                        if (params.mode == FileChooserParams.MODE_OPEN_MULTIPLE) {
                            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                        }
                    }
                } catch (_: Exception) {
                    this@MainActivity.filePathCallback = null
                    return false
                }
                return try {
                    startActivityForResult(intent, REQ_FILE_CHOOSER)
                    true
                } catch (e: Exception) {
                    this@MainActivity.filePathCallback = null
                    false
                }
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQ_MEDIA_PERMISSIONS -> {
                val request = pendingPermissionRequest
                if (request != null) {
                    val granted = grantResults.isNotEmpty() &&
                        grantResults.all { it == PackageManager.PERMISSION_GRANTED }
                    if (granted) request.grant(request.resources) else request.deny()
                }
                pendingPermissionRequest = null
                pendingPermissionPermissions = emptyArray()
            }
            REQ_GEOLOCATION -> {
                val granted = grantResults.isNotEmpty() &&
                    grantResults.all { it == PackageManager.PERMISSION_GRANTED }
                pendingGeoCallback?.invoke(pendingGeoOrigin, granted, false)
                pendingGeoCallback = null
                pendingGeoOrigin = null
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ_WALLPAPER) {
            if (resultCode == Activity.RESULT_OK && data?.data != null) {
                val uri = data.data!!
                try {
                    contentResolver.takePersistableUriPermission(uri, data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
                } catch (_: SecurityException) { }
                prefs.edit().putString(KEY_WALLPAPER_URI, uri.toString()).apply()
                applyStartPageAppearance()
            }
            return
        }
        if (requestCode == REQ_FILE_CHOOSER) {
            val results = if (resultCode == Activity.RESULT_OK && data != null) {
                val uris = mutableListOf<Uri>()
                data.data?.let { uris.add(it) }
                val clip = data.clipData
                if (clip != null) {
                    for (i in 0 until clip.itemCount) {
                        clip.getItemAt(i).uri?.let { if (!uris.contains(it)) uris.add(it) }
                    }
                }
                uris.takeIf { it.isNotEmpty() }?.toTypedArray()
            } else null
            filePathCallback?.onReceiveValue(results)
            filePathCallback = null
            return
        }
        if (requestCode == REQ_TABS_OVERVIEW && resultCode == Activity.RESULT_OK && data != null) {
            when (data.getStringExtra("action")) {
                "new_tab" -> {
                    createNewTab(NEW_TAB_URL)
                    urlBar.setText("")
                    urlBar.requestFocus()
                    showKeyboard()
                }
                "select" -> {
                    val index = data.getIntExtra("index", -1)
                    tabs.getOrNull(index)?.let { switchToTab(it.id) }
                }
                "close" -> {
                    val index = data.getIntExtra("index", -1)
                    tabs.getOrNull(index)?.let { closeTab(it.id) }
                }
                "toggle_split" -> setSplitMode(!splitMode)
            }
            return
        }
        
        if (requestCode == REQ_MENU && resultCode == RESULT_OK) {
            when (data?.getStringExtra(MenuActivity.EXTRA_ACTION)) {
                MenuActivity.ACTION_NEW_TAB -> {
                    createNewTab(NEW_TAB_URL)
                    urlBar.setText("")
                    urlBar.requestFocus()
                    showKeyboard()
                }
                MenuActivity.ACTION_GHOST -> {
                    startActivity(Intent(this, GhostActivity::class.java))
                }
                MenuActivity.ACTION_BROWSER_LOCK -> {
                    val enabled = prefs.getBoolean(KEY_BROWSER_LOCK, false)
                    prefs.edit().putBoolean(KEY_BROWSER_LOCK, !enabled).apply()
                    browserUnlockedThisSession = !enabled
                    Toast.makeText(
                        this,
                        if (!enabled) "Browser Lock on" else "Browser Lock off",
                        Toast.LENGTH_SHORT
                    ).show()
                    if (!enabled) authenticateBrowserLock()
                }
                MenuActivity.ACTION_THEME -> showAppearanceChooser()
                MenuActivity.ACTION_TAB_RETENTION -> showTabRetentionChooser()
                MenuActivity.ACTION_PULL_TO_REFRESH -> showPullToRefreshChooser()
                MenuActivity.ACTION_SETTINGS -> showAppearanceChooser()
                else -> {
                    val openUrl = data?.getStringExtra("open_url")
                    if (!openUrl.isNullOrBlank()) createNewTab(openUrl)
                }
            }
            return
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    /** Handles versioned commands arriving through the local Central Hub bridge. */
    fun handleCentralHubCommand(command: JSONObject): JSONObject {
        val name = command.optString("command").ifBlank { command.optString("action") }.lowercase()
        return try {
            when (name) {
                "state" -> JSONObject().put("ok", true).put("active_tab_id", activeTabId).put("tab_count", tabs.size).put("split_mode", splitMode).put("split_locked", splitChromeLocked).put("split_ratio", splitRatio).put("url", activeWebView()?.url ?: "")
                "new_tab" -> { createNewTab(command.optString("url", NEW_TAB_URL)); JSONObject().put("ok", true) }
                "open_url", "navigate" -> {
                    val url = command.optString("url").trim()
                    if (url.isBlank()) JSONObject().put("ok", false).put("error", "missing_url")
                    else { openShortcut(url); JSONObject().put("ok", true).put("url", url) }
                }
                "reload", "refresh" -> { activeWebView()?.reload(); JSONObject().put("ok", true) }
                "back" -> { val handled = activeWebView()?.let { if (it.canGoBack()) { it.goBack(); true } else false } ?: false; JSONObject().put("ok", true).put("handled", handled) }
                "forward" -> { val handled = activeWebView()?.let { if (it.canGoForward()) { it.goForward(); true } else false } ?: false; JSONObject().put("ok", true).put("handled", handled) }
                "close_tab" -> { closeTab(command.optInt("tab_id", activeTabId)); JSONObject().put("ok", true) }
                "switch_tab" -> { val id = command.optInt("tab_id", -1); val exists = tabs.any { it.id == id }; if (exists) switchToTab(id); JSONObject().put("ok", exists) }
                "toggle_split" -> { setSplitMode(!splitMode); JSONObject().put("ok", true).put("split_mode", splitMode) }
                "set_split" -> { setSplitMode(command.optBoolean("enabled", true)); JSONObject().put("ok", true).put("split_mode", splitMode) }
                "set_split_ratio", "resize_split" -> {
                    if (!splitMode) JSONObject().put("ok", false).put("error", "split_mode_inactive")
                    else { splitRatio = command.optDouble("ratio", splitRatio.toDouble()).toFloat().coerceIn(0.25f, 0.75f); applySplitRatio(); JSONObject().put("ok", true).put("split_ratio", splitRatio) }
                }
                "set_split_lock", "lock_split" -> { setSplitChromeLocked(command.optBoolean("locked", true)); JSONObject().put("ok", true).put("split_locked", splitChromeLocked) }
                else -> JSONObject().put("ok", false).put("error", "unsupported_command").put("command", name)
            }
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", "command_failed").put("message", e.message ?: "unknown error")
        }
    }

    fun openAiFromBottom(view: View) {
        startActivity(Intent(this, AiActivity::class.java).putExtra("auto_voice", true))
    }

    fun openDownloadsFromBottom(view: View) {
        startActivity(Intent(this, DownloadsActivity::class.java))
    }

    override fun onBackPressed() {
        if (customView != null) {
            val container = fullscreenContainer
            if (container != null) {
                (window.decorView as ViewGroup).removeView(container)
                fullscreenContainer = null
                customView = null
                customViewCallback?.onCustomViewHidden()
                customViewCallback = null
                window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
                requestedOrientation = originalOrientation
                swipeRefresh.visibility = View.VISIBLE
            }
            return
        }
        val webView = activeWebView()
        if (webView != null && webView.canGoBack()) webView.goBack()
        else super.onBackPressed()
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (splitMode && ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
            val topLoc = IntArray(2)
            val bottomLoc = IntArray(2)
            splitTopHost.getLocationOnScreen(topLoc)
            splitBottomHost.getLocationOnScreen(bottomLoc)
            when {
                ev.rawY >= topLoc[1] && ev.rawY < topLoc[1] + splitTopHost.height -> {
                    activeTabId = splitTopTabId
                    updateNavButtons()
                }
                ev.rawY >= bottomLoc[1] && ev.rawY < bottomLoc[1] + splitBottomHost.height -> {
                    activeTabId = splitBottomTabId
                    updateNavButtons()
                }
            }
        }
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_POINTER_DOWN -> {
                if (ev.pointerCount == 2 && !twoFingerHoldActive) {
                    twoFingerHoldActive = true
                    val r = Runnable {
                        activeWebView()?.reload()
                        Toast.makeText(this, "Reloading…", Toast.LENGTH_SHORT).show()
                    }
                    reloadRunnable = r
                    reloadHandler.postDelayed(r, 1000)
                }
                if (ev.pointerCount == 3) {
                    threeFingerTracking = true
                    threeFingerStartX = ev.getX(0)
                    reloadRunnable?.let { reloadHandler.removeCallbacks(it) }
                    reloadRunnable = null
                }
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                if (threeFingerTracking && ev.pointerCount >= 3) {
                    val dx = ev.getX(0) - threeFingerStartX
                    if (kotlin.math.abs(dx) >= 120f) {
                        val current = tabs.indexOfFirst { it.id == activeTabId }
                        val target = if (dx < 0) current + 1 else current - 1
                        tabs.getOrNull(target)?.let { switchToTab(it.id) }
                        threeFingerTracking = false
                    }
                }
            }
            android.view.MotionEvent.ACTION_POINTER_UP,
            android.view.MotionEvent.ACTION_UP,
            android.view.MotionEvent.ACTION_CANCEL -> {
                if (ev.pointerCount <= 2) {
                    threeFingerTracking = false
                }
                if (ev.pointerCount != 2) {
                    twoFingerHoldActive = false
                    reloadRunnable?.let { reloadHandler.removeCallbacks(it) }
                    reloadRunnable = null
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }
} 
