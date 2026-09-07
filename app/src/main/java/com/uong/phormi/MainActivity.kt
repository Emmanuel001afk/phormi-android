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
import android.webkit.WebSettings
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.widget.Button
import android.widget.Space
import android.os.Looper
import android.text.Html
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
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
        var webView: WebView,
        var title: String,
        val chipView: View,
        val isGhost: Boolean = false,
        var lastUsed: Long = System.currentTimeMillis(),
        var createdAt: Long = System.currentTimeMillis(),
        var profileName: String = DEFAULT_PROFILE_NAME
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
    private var pendingLockedNavigationUrl: String? = null

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
        private const val KEY_TAB_CREATED_AT = "tab_created_at"
        private const val KEY_TAB_IDS = "tab_ids"
        private const val KEY_FAVORITE_CONTEXTS = "favorite_contexts"
        private const val KEY_TAB_PROFILES = "tab_profiles"
        private const val KEY_TAB_ENVIRONMENT = "tab_environment"
        private const val KEY_TAB_WEBVIEW_STATES = "tab_webview_states"
        private const val WEBVIEW_STATE_MAX_BYTES = 64 * 1024
        private const val DEFAULT_PROFILE_NAME = "Default"
        private const val GHOST_PROFILE_NAME = "Ghost"
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
    private val tabSaveHandler = Handler(Looper.getMainLooper())
    private var tabSaveRunnable: Runnable? = null
    private var twoFingerHoldActive = false
    private var twoFingerStartX = 0f
    private var twoFingerStartY = 0f
    private var threeFingerStartX = 0f
    private var threeFingerTracking = false
    private val unifiedSearchExecutor = Executors.newFixedThreadPool(11)
    private val aiController by lazy { AiController(applicationContext) }
    private val mainExecutor = java.util.concurrent.Executor { command -> Handler(Looper.getMainLooper()).post(command) }
    private val unifiedSearchGeneration = AtomicInteger(0)
    private val unifiedSearchLock = Any()
    private var unifiedSearchFutures = mutableListOf<java.util.concurrent.Future<*>>()
    private var localSearchPageActive = false
    private val rendererCrashCounts = mutableMapOf<Int, Int>()
    private val rendererCrashTimes = mutableMapOf<Int, Long>()

    override fun onResume() {
        super.onResume()
        if (::prefs.isInitialized) {
            applyStartPageAppearance()
            applyBrowserChromeAppearance()
            applyKeepScreenOn(prefs.getBoolean(KEY_KEEP_SCREEN_ON, false))
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
        if (PhormiEnvironmentManager.isSupported()) {
            PhormiEnvironmentManager.ensure(GHOST_PROFILE_NAME)
        }
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
        findViewById<TextView>(R.id.btn_favorite).setOnClickListener { addCurrentPageToBookmarks() }
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

        // Sensitive permissions are requested only when a site actually needs them.

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
            .setMessage("Close each normal tab when its age reaches the selected period. Tab use does not reset the age clock.")
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

    private data class QuickSite(val name: String, val url: String, val tag: String, val removable: Boolean, val tabId: Int? = null)

    private fun loadQuickAccessRows() {
        val rows = findViewById<LinearLayout>(R.id.quick_access_rows) ?: return
        rows.removeAllViews()
        val fixed = listOf(
            QuickSite("Google", "https://www.google.com", "", false),
            QuickSite("Bing", "https://www.bing.com", "", false),
            QuickSite("YouTube", "https://www.youtube.com", "", false),
            QuickSite("Facebook", "https://www.facebook.com", "", false),
            QuickSite("Instagram", "https://www.instagram.com", "", false),
            QuickSite("GitHub", "https://github.com", "", false)
        )
        val personal = mutableListOf<QuickSite>()
        val custom = runCatching { JSONArray(prefs.getString(KEY_CUSTOM_SHORTCUTS, "[]") ?: "[]") }.getOrElse { JSONArray() }
        for (i in custom.length() - 1 downTo 0) {
            val item = custom.optJSONObject(i) ?: continue
            val name = item.optString("name").trim(); val url = item.optString("url").trim()
            if (name.isNotBlank() && url.isNotBlank() && personal.none { it.url == url }) personal += QuickSite(name, url, "⌂", true)
        }
        val favoriteContexts = runCatching { JSONObject(prefs.getString(KEY_FAVORITE_CONTEXTS, "{}") ?: "{}") }.getOrElse { JSONObject() }
        BookmarksActivity.getAll(this).forEach { bookmark ->
            if (personal.none { it.url == bookmark.url }) {
                val tabId = favoriteContexts.optInt(bookmark.url, -1).takeIf { it > 0 }
                personal += QuickSite(bookmark.title, bookmark.url, "◇", true, tabId)
            }
        }
        HistoryActivity.getMostVisited(this, 8, 10).forEach { visited ->
            if (personal.none { it.url == visited.url }) personal += QuickSite(visited.title, visited.url, "•", true)
        }
        val all = fixed + personal.take(18) + QuickSite("Add", "", "+", false)
        val columns = when { resources.displayMetrics.widthPixels >= 900 -> 8; resources.displayMetrics.widthPixels >= 600 -> 7; else -> 6 }
        all.chunked(columns).forEach { batch ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 60.dp())
            }
            batch.forEach { site ->
                row.addView(makeQuickSiteView(site), LinearLayout.LayoutParams(0, 56.dp(), 1f).apply { leftMargin = 2.dp(); rightMargin = 2.dp() })
            }
            repeat(columns - batch.size) { row.addView(Space(this), LinearLayout.LayoutParams(0, 56.dp(), 1f)) }
            rows.addView(row)
        }
    }

    private fun makeQuickSiteView(site: QuickSite): TextView {
        val surfaceSoft = Color.rgb(23, 32, 51)
        val outline = Color.rgb(51, 65, 85)
        return TextView(this).apply {
            gravity = android.view.Gravity.CENTER
            text = if (site.tag.isNotBlank()) site.tag + "\n" + site.name.take(9) else site.name.take(9)
            setTextColor(if (site.tag == "+") Color.rgb(56,189,248) else Color.WHITE)
            textSize = 8.5f
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setBackgroundResource(R.drawable.bg_shortcut)
            isClickable = true
            isFocusable = true
            contentDescription = if (site.tag == "+") "Add a website shortcut" else "Open ${site.name}"
            setOnClickListener {
                if (site.url.isBlank()) showAddShortcutDialog()
                else if (site.tabId != null && tabs.any { it.id == site.tabId }) switchToTab(site.tabId)
                else openShortcut(site.url)
            }
            if (site.removable) setOnLongClickListener { confirmQuickSiteRemoval(site); true }
            if (site.url.isNotBlank()) loadFaviconInto(this, site.url)
            styleSurface(this, surfaceSoft, outline, 28)
        }
    }

    private fun confirmQuickSiteRemoval(site: QuickSite) {
        AlertDialog.Builder(this).setTitle("Remove from Quick access?").setMessage(site.name)
            .setNegativeButton("Cancel", null).setPositiveButton("Remove") { _, _ ->
                when (site.tag) {
                    "◇" -> BookmarksActivity.remove(this, site.url)
                    "•" -> HistoryActivity.removeUrl(this, site.url)
                    else -> {
                        val list = runCatching { JSONArray(prefs.getString(KEY_CUSTOM_SHORTCUTS, "[]") ?: "[]") }.getOrElse { JSONArray() }
                        val kept = JSONArray()
                        for (i in 0 until list.length()) { val item = list.optJSONObject(i) ?: continue; if (item.optString("url") != site.url) kept.put(item) }
                        prefs.edit().putString(KEY_CUSTOM_SHORTCUTS, kept.toString()).apply()
                    }
                }
                loadQuickAccessRows()
            }.show()
    }


    private fun showAddShortcutDialog() {
        val nameInput = EditText(this).apply {
            hint = "Name"
            setSingleLine()
        }
        val urlInput = EditText(this).apply {
            hint = "https://example.com"
            setSingleLine()
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
            addView(nameInput)
            addView(urlInput)
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Add shortcut")
            .setView(box)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val name = nameInput.text.toString().trim()
                var url = urlInput.text.toString().trim()
                if (name.isBlank() || url.isBlank()) return@setPositiveButton
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    url = "https://$url"
                }
                val list = runCatching {
                    JSONArray(prefs.getString(KEY_CUSTOM_SHORTCUTS, "[]") ?: "[]")
                }.getOrElse { JSONArray() }
                val kept = JSONArray()
                for (i in 0 until list.length()) {
                    val item = list.optJSONObject(i) ?: continue
                    if (!item.optString("url").trim().equals(url, ignoreCase = true)) kept.put(item)
                }
                // Store the newest shortcut last; the renderer intentionally reads custom
                // shortcuts newest-first so this one appears immediately.
                kept.put(JSONObject().put("name", name).put("url", url))
                prefs.edit().putString(KEY_CUSTOM_SHORTCUTS, kept.toString()).apply()
                loadQuickAccessRows()
            }
            .show()
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

    private fun updateFavoriteButton() {
        val button = findViewById<TextView>(R.id.btn_favorite) ?: return
        val url = activeWebView()?.url.orEmpty()
        val favorite = url.startsWith("http") && BookmarksActivity.getAll(this).any { it.url == url }
        button.alpha = if (favorite) 1f else 0.55f
        button.contentDescription = if (favorite) "Remove current page from favorites" else "Add current page to favorites"
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
            saveTabsImmediate(true)
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

    private fun setActiveSplitPane(tabId: Int) {
        if (!splitMode || tabs.none { it.id == tabId }) return
        activeTabId = tabId
        tabs.find { it.id == tabId }?.lastUsed = System.currentTimeMillis()
        updateNavButtons()
        updateFavoriteButton()
        val url = tabs.find { it.id == tabId }?.webView?.url.orEmpty()
        urlBar.setText(url.takeIf { it.isNotBlank() && it != NEW_TAB_URL } ?: "")
        tabs.forEach { it.chipView.alpha = if (it.id == tabId) 1f else 0.55f }
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
            if (tabs.none { it.id == splitTopTabId }) splitTopTabId = activeTabId
            if (tabs.none { it.id == splitBottomTabId } || splitBottomTabId == splitTopTabId) {
                splitBottomTabId = tabs.firstOrNull { it.id != splitTopTabId }?.id ?: -1
            }
            if (splitTopTabId == -1 || splitBottomTabId == -1 || splitBottomTabId == splitTopTabId) { splitMode = false; return }
            moveTabWebViewToHost(splitTopTabId, splitTopHost)
            moveTabWebViewToHost(splitBottomTabId, splitBottomHost)
            tabs.filter { it.id == splitTopTabId || it.id == splitBottomTabId }.forEach { it.webView.onResume() }
            setActiveSplitPane(splitTopTabId)
            splitContainer.visibility = View.VISIBLE
            startPageContainer.visibility = View.GONE
            setSplitChromeLocked(false)
            splitRatio = 0.5f
            splitContainer.post { applySplitRatio() }
        } else {
            splitMode = false
            listOf(splitTopTabId, splitBottomTabId).filter { it >= 0 }.forEach { moveTabWebViewToHost(it, webViewContainer) }
            splitTopTabId = -1; splitBottomTabId = -1
            tabs.filter { it.id != activeTabId }.forEach { it.webView.onPause(); it.webView.visibility = View.GONE }
            splitRatio = 0.5f
            splitContainer.visibility = View.GONE
            updateStartPageVisibility()
            setSplitChromeLocked(false)
        }
        updateHomeChromeVisibility()
    }

    private fun reassignTabEnvironment(tabId: Int, requestedProfile: String?) {
        val tab = tabs.find { it.id == tabId } ?: return
        val target = requestedProfile?.trim().takeIf { !it.isNullOrBlank() } ?: return
        if (tab.isGhost || target == GHOST_PROFILE_NAME) {
            Toast.makeText(this, "Ghost tabs use the Ghost environment", Toast.LENGTH_SHORT).show()
            return
        }
        if (!isMultiProfileSupported()) {
            Toast.makeText(this, "Named environments are unavailable on this WebView", Toast.LENGTH_LONG).show()
            return
        }
        val old = tab.webView
        val url = old.url.orEmpty()
        val newWebView = WebView(this)
        if (!PhormiEnvironmentManager.apply(newWebView, target)) {
            newWebView.destroy()
            Toast.makeText(this, "Environment could not be applied", Toast.LENGTH_LONG).show()
            return
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(newWebView, true)
        configureWebView(newWebView)
        newWebView.isClickable = true
        newWebView.isFocusable = true
        newWebView.isFocusableInTouchMode = true
        newWebView.setBackgroundColor(Color.TRANSPARENT)
        registerForContextMenu(newWebView)
        val parent = old.parent as? ViewGroup
        parent?.removeView(old)
        parent?.addView(newWebView, 0, FrameLayout.LayoutParams(-1, -1))
        old.destroy()
        tab.webView = newWebView
        tab.profileName = target
        tab.chipView.findViewById<TextView>(R.id.tab_chip_title)?.text =
            if (tab.title.isBlank()) target else "${tab.title} · $target"
        if (url.isBlank() || url == NEW_TAB_URL) updateStartPageVisibility() else newWebView.loadUrl(url)
        saveTabs()
        if (tab.id == activeTabId) {
            updateNavButtons()
            updateStartPageVisibility()
        }
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

    private fun createNewTab(url: String, requestedProfile: String? = null, forceGhost: Boolean = false, requestedId: Int? = null, requestedCreatedAt: Long? = null, restoredStateBase64: String? = null) {
        val ghostRequested = forceGhost || prefs.getBoolean("ghost_next_tab", false)
        if (ghostRequested) prefs.edit().putBoolean("ghost_next_tab", false).apply()
        val id = requestedId?.takeIf { it > 0 } ?: nextTabId++
        if (id >= nextTabId) nextTabId = id + 1
        val requested = requestedProfile?.trim().takeIf { !it.isNullOrBlank() }
            ?: if (ghostRequested) GHOST_PROFILE_NAME else selectedTabEnvironment()
        val webView = WebView(this)
        var effectiveProfile = DEFAULT_PROFILE_NAME
        if (requested != DEFAULT_PROFILE_NAME && isMultiProfileSupported()) {
            if (PhormiEnvironmentManager.apply(webView, requested)) effectiveProfile = requested
        }
        val isGhost = ghostRequested && effectiveProfile == GHOST_PROFILE_NAME
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
        val chipIcon = chip.findViewById<android.widget.ImageView>(R.id.tab_chip_icon)

        val now = System.currentTimeMillis()
        val tab = Tab(id, webView, if (isGhost) "Ghost" else getString(R.string.new_tab), chip, isGhost = isGhost, lastUsed = now, createdAt = requestedCreatedAt ?: now, profileName = effectiveProfile)
        if (isGhost) {
            chipIcon.setImageResource(R.drawable.ic_phormi_ghost)
            chipIcon.visibility = View.VISIBLE
        }
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

        var restored = false
        if (!restoredStateBase64.isNullOrBlank() && WebViewFeature.isFeatureSupported(WebViewFeature.SAVE_STATE)) {
            restored = runCatching {
                restoreWebViewState(webView, restoredStateBase64)
            }.getOrDefault(false)
        }
        if (!restored && url != NEW_TAB_URL) webView.loadUrl(url)
        switchToTab(id)
        updateTabCount()
        saveTabs()
    }

    private fun createGhostTab(url: String = NEW_TAB_URL) {
        val intent = Intent(this, GhostActivity::class.java)
        if (url != NEW_TAB_URL) intent.data = Uri.parse(url)
        startActivity(intent)
    }

    private fun switchToTab(id: Int) {
        val candidate = tabs.find { it.id == id }
        if (candidate != null && candidate.id != activeTabId && isSiteLocked(candidate.webView.url)) {
            requestSiteUnlock(candidate.webView.url.orEmpty()) { switchToTabUnlocked(id) }
            return
        }
        switchToTabUnlocked(id)
    }

    private fun switchToTabUnlocked(id: Int) {
        activeTabId = id
        tabs.find { it.id == id }?.lastUsed = System.currentTimeMillis()
        val liveIds = if (splitMode) setOf(splitTopTabId, splitBottomTabId) else setOf(id)
        tabs.forEach { tab ->
            val isActive = tab.id == id
            val isLive = tab.id in liveIds
            tab.webView.visibility = if (isLive) View.VISIBLE else View.GONE
            if (isLive) tab.webView.onResume() else tab.webView.onPause()
            tab.chipView.alpha = if (isActive) 1f else 0.55f
        }
        updateStartPageVisibility()
        updateTabCount()
        updateNavButtons()
        updateFavoriteButton()
        val current = tabs.find { it.id == id }?.webView?.url
        if (!current.isNullOrBlank() && current != "about:blank") {
            urlBar.setText(current)
        } else {
            urlBar.setText("")
        }
        saveTabs()
    }

    private fun isSiteLocked(url: String?): Boolean = PhormiSiteLockManager.isLocked(this, url)

    private fun requestSiteUnlock(url: String, onSuccess: () -> Unit) {
        val host = runCatching { Uri.parse(url).host.orEmpty() }.getOrDefault("protected site")
        if (browserLockManager.method(prefs) == BrowserLockManager.METHOD_PIN && browserLockManager.isPinConfigured(prefs)) {
            val input = EditText(this).apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD }
            AlertDialog.Builder(this).setTitle("Unlock $host").setView(input)
                .setPositiveButton("Unlock") { _, _ ->
                    if (browserLockManager.verifyPin(prefs, input.text.toString())) onSuccess()
                    else Toast.makeText(this, "Incorrect PIN", Toast.LENGTH_SHORT).show()
                }.setNegativeButton("Cancel", null).show()
        } else {
            browserLockManager.authenticate(onSuccess) { }
        }
    }

    private fun closeTab(id: Int) {
        if (splitMode && (id == splitTopTabId || id == splitBottomTabId)) setSplitMode(false)
        val index = tabs.indexOfFirst { it.id == id }
        if (index == -1) return
        val tab = tabs[index]
        unregisterForContextMenu(tab.webView)
        webViewContainer.removeView(tab.webView)
        tabStripContainer.removeView(tab.chipView)
        PhormiBrowserPerformance.clear(tab.id)
        tab.webView.destroy()
        tabs.removeAt(index)
        if (tab.isGhost && tabs.none { it.isGhost }) {
            PhormiEnvironmentManager.delete(GHOST_PROFILE_NAME)
        }

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
        tabSaveRunnable?.let { tabSaveHandler.removeCallbacks(it) }
        val runnable = Runnable { saveTabsImmediate(false) }
        tabSaveRunnable = runnable
        tabSaveHandler.postDelayed(runnable, 250L)
    }

    private fun saveTabsImmediate(captureWebViewState: Boolean = false) {
        tabSaveRunnable?.let { tabSaveHandler.removeCallbacks(it) }
        tabSaveRunnable = null
        val persistTabs = tabs.filterNot { it.isGhost }
        val urls = JSONArray()
        val titles = JSONArray()
        val lastUsed = JSONArray()
        val createdAt = JSONArray()
        val profiles = JSONArray()
        val ids = JSONArray()
        val webViewStates = JSONArray()
        persistTabs.forEach { tab ->
            val u = tab.webView.url?.takeIf { it.isNotBlank() } ?: NEW_TAB_URL
            urls.put(u)
            titles.put(tab.title.ifBlank { "Tab" })
            lastUsed.put(tab.lastUsed)
            createdAt.put(tab.createdAt)
            profiles.put(tab.profileName)
            ids.put(tab.id)
            webViewStates.put(if (captureWebViewState) (saveWebViewState(tab.webView) ?: "") else "")
        }
        if (urls.length() == 0) {
            urls.put(NEW_TAB_URL); titles.put(getString(R.string.new_tab)); lastUsed.put(System.currentTimeMillis()); createdAt.put(System.currentTimeMillis()); profiles.put(DEFAULT_PROFILE_NAME); ids.put(nextTabId++); webViewStates.put("")
        }
        val activeIndex = persistTabs.indexOfFirst { it.id == activeTabId }.coerceAtLeast(0)
        prefs.edit()
            .putString(KEY_TAB_URLS, urls.toString())
            .putString(KEY_TAB_TITLES, titles.toString())
            .putString(KEY_TAB_LAST_USED, lastUsed.toString())
            .putString(KEY_TAB_CREATED_AT, createdAt.toString())
            .putString(KEY_TAB_PROFILES, profiles.toString())
            .putString(KEY_TAB_IDS, ids.toString())
            .putString(KEY_TAB_WEBVIEW_STATES, webViewStates.toString())
            .putString(KEY_TAB_RETENTION, prefs.getString(KEY_TAB_RETENTION, RETENTION_NEVER) ?: RETENTION_NEVER)
            .putString(KEY_TAB_ENVIRONMENT, selectedTabEnvironment())
            .putInt(KEY_ACTIVE_INDEX, activeIndex)
            .apply()
    }

    private fun selectedTabEnvironment(): String =
        prefs.getString(KEY_TAB_ENVIRONMENT, DEFAULT_PROFILE_NAME)
            ?.trim()?.takeIf { it.isNotBlank() } ?: DEFAULT_PROFILE_NAME

    private fun isMultiProfileSupported(): Boolean =
        WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)

    private fun availableTabEnvironments(): List<String> {
        if (!isMultiProfileSupported()) return listOf(DEFAULT_PROFILE_NAME)
        return runCatching {
            ProfileStore.getInstance().getAllProfileNames()
                .filter { it.isNotBlank() && !it.equals(GHOST_PROFILE_NAME, ignoreCase = true) }.distinct()
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it })
                .ifEmpty { listOf(DEFAULT_PROFILE_NAME) }
        }.getOrDefault(listOf(DEFAULT_PROFILE_NAME))
    }

    private fun showTabEnvironmentChooser() {
        if (!isMultiProfileSupported()) {
            Toast.makeText(this, "Separate tab environments are not supported by this WebView.", Toast.LENGTH_LONG).show()
            return
        }
        val values = availableTabEnvironments().toMutableList()
        val current = selectedTabEnvironment()
        if (!values.contains(current)) values.add(current)
        val checked = values.indexOf(current).coerceAtLeast(0)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Tab environment")
            .setMessage("Choose the browsing identity for new tabs. Existing tabs keep their own environment.")
            .setSingleChoiceItems(values.toTypedArray(), checked) { dialog, which ->
                val selected = values[which]
                prefs.edit().putString(KEY_TAB_ENVIRONMENT, selected).apply()
                dialog.dismiss()
                Toast.makeText(this, "New tabs: $selected", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("New environment") { _, _ ->
                val input = EditText(this).apply { hint = "Environment name"; setSingleLine(true) }
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Create environment").setView(input)
                    .setPositiveButton("Create") { _, _ ->
                        val name = input.text.toString().trim()
                        if (name.isBlank() || name.equals(DEFAULT_PROFILE_NAME, true)) {
                            Toast.makeText(this, "Choose a different name.", Toast.LENGTH_SHORT).show()
                        } else runCatching {
                            ProfileStore.getInstance().getOrCreateProfile(name)
                            prefs.edit().putString(KEY_TAB_ENVIRONMENT, name).apply()
                            Toast.makeText(this, "New tabs: $name", Toast.LENGTH_SHORT).show()
                        }.onFailure {
                            Toast.makeText(this, "Could not create environment.", Toast.LENGTH_SHORT).show()
                        }
                    }.setNegativeButton("Cancel", null).show()
            }.show()
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
            val profiles = runCatching { JSONArray(prefs.getString(KEY_TAB_PROFILES, "[]") ?: "[]") }.getOrElse { JSONArray() }
            val createdAt = runCatching { JSONArray(prefs.getString(KEY_TAB_CREATED_AT, "[]") ?: "[]") }.getOrElse { JSONArray() }
            val ids = runCatching { JSONArray(prefs.getString(KEY_TAB_IDS, "[]") ?: "[]") }.getOrElse { JSONArray() }
            val webViewStates = runCatching { JSONArray(prefs.getString(KEY_TAB_WEBVIEW_STATES, "[]") ?: "[]") }.getOrElse { JSONArray() }
            val count = arr.length()
            val activeIndex = prefs.getInt(KEY_ACTIVE_INDEX, 0).coerceIn(0, (count - 1).coerceAtLeast(0))
            for (i in 0 until count) {
                val restoredUrl = arr.optString(i, NEW_TAB_URL).trim().ifBlank { NEW_TAB_URL }
                val restoredProfile = profiles.optString(i, DEFAULT_PROFILE_NAME).trim().ifBlank { DEFAULT_PROFILE_NAME }
                val restoredId = ids.optInt(i, 0).takeIf { it > 0 }
                val restoredCreatedAt = createdAt.optLong(i, 0L).takeIf { it > 0L } ?: lastUsed.optLong(i, System.currentTimeMillis()).takeIf { it > 0L }
                val restoredState = webViewStates.optString(i, "").trim().takeIf { it.isNotBlank() }
                createNewTab(restoredUrl, restoredProfile, requestedId = restoredId, requestedCreatedAt = restoredCreatedAt, restoredStateBase64 = restoredState)
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


    // PHORMI_ROUND5_STATE_RESTORE_V1
    // WebViewCompat.saveState() preserves the navigation stack and page state while
    // enforcing a hard size limit. The serialized Bundle is stored per tab so the
    // browser can rebuild WebViews after process death instead of only reloading URLs.
    private fun saveWebViewState(webView: WebView): String? {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.SAVE_STATE)) return null
        return runCatching {
            val state = Bundle()
            WebViewCompat.saveState(webView, state, WEBVIEW_STATE_MAX_BYTES, false)
            val parcel = android.os.Parcel.obtain()
            try {
                state.writeToParcel(parcel, 0)
                android.util.Base64.encodeToString(parcel.marshall(), android.util.Base64.NO_WRAP)
            } finally {
                parcel.recycle()
            }
        }.getOrNull()
    }

    private fun restoreWebViewState(webView: WebView, encoded: String): Boolean {
        if (encoded.isBlank()) return false
        return runCatching {
            val bytes = android.util.Base64.decode(encoded, android.util.Base64.DEFAULT)
            val parcel = android.os.Parcel.obtain()
            try {
                parcel.unmarshall(bytes, 0, bytes.size)
                parcel.setDataPosition(0)
                val state = Bundle.CREATOR.createFromParcel(parcel)
                state.classLoader = MainActivity::class.java.classLoader
                val history = webView.restoreState(state)
                history != null && history.size > 0
            } finally {
                parcel.recycle()
            }
        }.getOrDefault(false)
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
        val expired = tabs.filter { !it.isGhost && it.createdAt <= cutoff }.map { it.id }
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
            val webView = activeWebView()
            val userAgent = webView?.settings?.userAgentString
                ?: WebSettings.getDefaultUserAgent(this)
            val referer = webView?.url ?: url
            val cookies = CookieManager.getInstance().getCookie(url)
                ?: CookieManager.getInstance().getCookie(referer)
            val info = PhormiDownloadSupport.resolve(url, contentDisposition, mimeType, userAgent, referer, cookies)
            val request = DownloadManager.Request(Uri.parse(info.sourceUrl)).apply {
                setMimeType(info.mimeType)
                setTitle(info.fileName)
                setDescription("Phormi · ${info.category} · ${info.sourceUrl}")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, info.fileName)
                allowScanningByMediaScanner()
                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)
                info.headers.forEach { (key, value) -> addRequestHeader(key, value) }
            }
            (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
            Toast.makeText(this, "Downloading ${info.fileName}\n${info.sourceUrl}", Toast.LENGTH_LONG).show()
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
        saveTabsImmediate(true)
        CookieManager.getInstance().flush()
        super.onPause()
    }


    override fun onSaveInstanceState(outState: Bundle) {
        saveTabsImmediate(true)
        super.onSaveInstanceState(outState)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (android.os.Build.VERSION.SDK_INT >= 26 && customView != null && !isInPictureInPictureMode) {
            runCatching { enterPictureInPictureMode(android.app.PictureInPictureParams.Builder().build()) }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            findViewById<View>(R.id.top_toolbar)?.visibility = View.GONE
            findViewById<View>(R.id.bottom_toolbar)?.visibility = View.GONE
        } else if (customView == null && !splitChromeLocked) {
            findViewById<View>(R.id.top_toolbar)?.visibility = View.VISIBLE
            findViewById<View>(R.id.bottom_toolbar)?.visibility = View.VISIBLE
        }
    }
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        val keep = if (splitMode) setOf(splitTopTabId, splitBottomTabId) else setOf(activeTabId)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            tabs.filter { it.id !in keep }.forEach { tab ->
                // Hiding inactive WebViews reduces view work without pretending that
                // onPause() stops page JavaScript; renderer recovery remains authoritative.
                tab.webView.visibility = View.GONE
            }
        }
    }

    override fun onDestroy() {
        reloadRunnable?.let(reloadHandler::removeCallbacks)
        reloadRunnable = null
        tabSaveRunnable?.let(tabSaveHandler::removeCallbacks)
        tabSaveRunnable = null
        synchronized(unifiedSearchLock) {
            unifiedSearchGeneration.incrementAndGet()
            unifiedSearchFutures.forEach { it.cancel(true) }
            unifiedSearchFutures.clear()
        }
        unifiedSearchExecutor.shutdownNow()
        rendererCrashCounts.clear()
        rendererCrashTimes.clear()
        pendingPermissionRequest?.deny()
        pendingPermissionRequest = null
        pendingGeoCallback?.invoke(pendingGeoOrigin, false, false)
        pendingGeoCallback = null
        filePathCallback?.onReceiveValue(null)
        filePathCallback = null
        runCatching { exitFullscreenVideo() }
        tabs.toList().forEach { tab ->
            (tab.webView.parent as? ViewGroup)?.removeView(tab.webView)
            runCatching { unregisterForContextMenu(tab.webView) }
            runCatching { tab.webView.stopLoading(); tab.webView.destroy() }
        }
        tabs.clear()
        PhormiBrowserPerformance.clearAll()
        CookieManager.getInstance().flush()
        super.onDestroy()
    }

    private fun configureWebView(webView: WebView) {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
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
            val desktop = prefs.getBoolean("desktop_mode", false)
            userAgentString = if (desktop) "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36" else WebSettings.getDefaultUserAgent(this@MainActivity)
            loadWithOverviewMode = desktop
            useWideViewPort = desktop
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?): Boolean {
                val targetUri = request?.url ?: return false
                val target = targetUri.toString()
                if (target.isBlank()) return false
                val host = PhormiSiteLockManager.normalizeHost(target)
                if (host != null && PhormiSiteLockManager.isLocked(this@MainActivity, target)) {
                    pendingLockedNavigationUrl = target
                    showSiteUnlockDialog(host)
                    return true
                }
                val scheme = targetUri.scheme?.lowercase(Locale.US).orEmpty()
                if (scheme.isBlank() || scheme == "http" || scheme == "https" ||
                    scheme == "javascript" || scheme == "data" || scheme == "blob" || scheme == "file") return false

                // Browser schemes such as mailto:, tel:, geo:, intent:, and custom
                // app links belong to Android's external intent system, not WebView.
                return try {
                    val external = if (scheme == "intent") {
                        Intent.parseUri(target, Intent.URI_INTENT_SCHEME).apply {
                            addCategory(Intent.CATEGORY_BROWSABLE)
                        }
                    } else {
                        Intent(Intent.ACTION_VIEW, targetUri).apply { addCategory(Intent.CATEGORY_BROWSABLE) }
                    }
                    startActivity(external)
                    true
                } catch (_: Exception) {
                    Toast.makeText(this@MainActivity, "No app can open this link.", Toast.LENGTH_SHORT).show()
                    true
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: android.webkit.WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true && view == activeWebView()) {
                    swipeRefresh.isRefreshing = false
                    val description = error?.description?.toString()?.trim().orEmpty()
                    if (description.isNotBlank()) {
                        Toast.makeText(this@MainActivity, "Page could not load: $description", Toast.LENGTH_SHORT).show()
                    }
                    updateNavButtons()
                }
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: android.webkit.WebResourceResponse?
            ) {
                super.onReceivedHttpError(view, request, errorResponse)
                if (request?.isForMainFrame == true && view == activeWebView()) {
                    swipeRefresh.isRefreshing = false
                    val code = errorResponse?.statusCode ?: 0
                    if (code >= 400) Toast.makeText(this@MainActivity, "Page returned HTTP $code.", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                tabs.find { it.webView === view }?.let { PhormiBrowserPerformance.start(it.id) }
                super.onPageStarted(view, url, favicon)
            }

            override fun onReceivedSslError(
                view: WebView?,
                handler: android.webkit.SslErrorHandler?,
                error: android.net.http.SslError?
            ) {
                handler?.cancel()
                if (view == activeWebView()) Toast.makeText(this@MainActivity, "Secure connection could not be verified.", Toast.LENGTH_LONG).show()
            }

            override fun onSafeBrowsingHit(
                view: WebView?,
                request: WebResourceRequest?,
                threatType: Int,
                callback: android.webkit.SafeBrowsingResponse?
            ) {
                if (android.os.Build.VERSION.SDK_INT >= 27) callback?.backToSafety(true)
                else callback?.showInterstitial(true)
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Phormi blocked an unsafe page.", Toast.LENGTH_LONG).show()
                }
            }

            override fun onRenderProcessGone(view: WebView?, detail: android.webkit.RenderProcessGoneDetail?): Boolean {
                if (view == null) return true
                val tab = tabs.firstOrNull { it.webView === view } ?: return true
                val tabId = tab.id
                val oldUrl = view.url?.takeIf { it.isNotBlank() && it != "about:blank" }
                val profile = tab.profileName
                val ghost = tab.isGhost
                val created = tab.createdAt
                val index = tabs.indexOf(tab)
                val previousActiveId = activeTabId
                val now = System.currentTimeMillis()
                val previousCrash = rendererCrashTimes[tabId] ?: 0L
                val crashCount = if (detail?.didCrash() == true && now - previousCrash < 60_000L) {
                    (rendererCrashCounts[tabId] ?: 0) + 1
                } else 1
                rendererCrashTimes[tabId] = now
                rendererCrashCounts[tabId] = crashCount

                val wasInSplit = splitMode && (splitTopTabId == tabId || splitBottomTabId == tabId)
                if (wasInSplit) {
                    splitMode = false
                    splitTopTabId = -1
                    splitBottomTabId = -1
                    splitContainer.visibility = View.GONE
                    moveTabWebViewToHost(previousActiveId, webViewContainer)
                }
                (view.parent as? ViewGroup)?.removeView(view)
                runCatching { unregisterForContextMenu(view) }
                runCatching { view.stopLoading(); view.destroy() }
                tabStripContainer.removeView(tab.chipView)
                tabs.remove(tab)
                PhormiBrowserPerformance.clear(tabId)

                // Android explicitly warns against immediately reloading a page that
                // just crashed a renderer. After repeated crashes, keep the tab alive
                // but fall back to a blank page instead of creating a crash loop.
                val safeRecoveryUrl = if (detail?.didCrash() == true && crashCount >= 2) NEW_TAB_URL else (oldUrl ?: NEW_TAB_URL)
                createNewTab(safeRecoveryUrl, profile, forceGhost = ghost, requestedId = tabId, requestedCreatedAt = created)
                val replacement = tabs.lastOrNull { it.id == tabId }
                if (replacement != null) {
                    tabs.remove(replacement)
                    tabs.add(index.coerceIn(0, tabs.size), replacement)
                }
                if (previousActiveId != tabId && tabs.any { it.id == previousActiveId }) switchToTab(previousActiveId)
                if (safeRecoveryUrl == NEW_TAB_URL && oldUrl != null) {
                    Toast.makeText(this@MainActivity, "The page renderer crashed repeatedly; the tab was reset safely.", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this@MainActivity, "A page renderer stopped unexpectedly; the tab was recovered.", Toast.LENGTH_LONG).show()
                }
                return true
            }

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
                    updateFavoriteButton()
                }
                tabs.find { it.webView === view }?.let { PhormiBrowserPerformance.finish(it.id, u) }
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
                val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                val sourceTabId = tabs.firstOrNull { it.webView === view }?.id ?: activeTabId
                val wasSplit = splitMode
                val targetPane = when {
                    wasSplit && sourceTabId == splitTopTabId -> splitTopHost
                    wasSplit && sourceTabId == splitBottomTabId -> splitBottomHost
                    else -> null
                }
                val oldPaneTabId = when {
                    wasSplit && sourceTabId == splitTopTabId -> splitTopTabId
                    wasSplit && sourceTabId == splitBottomTabId -> splitBottomTabId
                    else -> -1
                }

                // Popup/new-window requests become normal Phormi tabs. In split view,
                // the new tab replaces the requesting pane rather than being created
                // invisibly in the normal host or stealing the other pane.
                createNewTab(NEW_TAB_URL, requestedProfile = tabs.firstOrNull { it.id == sourceTabId }?.profileName)
                val newTab = tabs.lastOrNull() ?: return false
                val newWebView = newTab.webView

                if (wasSplit && targetPane != null && oldPaneTabId > 0) {
                    val oldPaneWebView = tabs.firstOrNull { it.id == oldPaneTabId }?.webView
                    oldPaneWebView?.let { old ->
                        (old.parent as? ViewGroup)?.removeView(old)
                        if (webViewContainer.indexOfChild(old) < 0) {
                            webViewContainer.addView(old, FrameLayout.LayoutParams(-1, -1))
                            old.visibility = View.GONE
                        }
                    }
                    if (sourceTabId == splitTopTabId) splitTopTabId = newTab.id
                    if (sourceTabId == splitBottomTabId) splitBottomTabId = newTab.id
                    moveTabWebViewToHost(newTab.id, targetPane)
                    activeTabId = newTab.id
                    setActiveSplitPane(newTab.id)
                }

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
                findViewById<View>(R.id.top_toolbar)?.visibility = View.GONE
                findViewById<View>(R.id.bottom_toolbar)?.visibility = View.GONE
                swipeRefresh.visibility = View.GONE
            }

            override fun onHideCustomView() {
                if (customView != null) exitFullscreenVideo()
            }

            override fun getDefaultVideoPoster(): Bitmap? {
                return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                val allowed = request.resources.filter {
                    it == PermissionRequest.RESOURCE_AUDIO_CAPTURE || it == PermissionRequest.RESOURCE_VIDEO_CAPTURE
                }.toTypedArray()
                if (allowed.isEmpty()) { request.deny(); return }
                pendingPermissionRequest?.deny()
                pendingPermissionRequest = request
                val needed = mutableListOf<String>()
                if (PermissionRequest.RESOURCE_VIDEO_CAPTURE in allowed &&
                    ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                    needed.add(Manifest.permission.CAMERA)
                }
                if (PermissionRequest.RESOURCE_AUDIO_CAPTURE in allowed &&
                    ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    needed.add(Manifest.permission.RECORD_AUDIO)
                }
                if (needed.isEmpty()) {
                    request.grant(allowed)
                    pendingPermissionRequest = null
                } else {
                    pendingPermissionPermissions = needed.toTypedArray()
                    ActivityCompat.requestPermissions(this@MainActivity, pendingPermissionPermissions, REQ_MEDIA_PERMISSIONS)
                }
            }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: android.webkit.GeolocationPermissions.Callback?
            ) {
                this@MainActivity.pendingGeoCallback?.invoke(this@MainActivity.pendingGeoOrigin, false, false)
                this@MainActivity.pendingGeoCallback = null
                this@MainActivity.pendingGeoOrigin = null
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
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback
                val params = fileChooserParams ?: run {
                    this@MainActivity.filePathCallback = null
                    return false
                }
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
                "assign_group" -> {
                    val groupId = data.getStringExtra("group_id").orEmpty()
                    val tabId = data.getIntExtra("tab_id", -1)
                    val index = data.getIntExtra("index", -1)
                    val resolvedId = if (tabId > 0) tabId else tabs.getOrNull(index)?.id ?: -1
                    if (groupId.isNotBlank() && resolvedId > 0) TabGroupManager(this).assignTab(groupId, resolvedId, tabs.find { it.id == resolvedId }?.webView?.url.orEmpty())
                }
                "reassign_env" -> {
                    val index = data.getIntExtra("index", -1); val profile = data.getStringExtra("profile").orEmpty()
                    val old = tabs.getOrNull(index)
                    if (old != null && profile.isNotBlank()) {
                        reassignTabEnvironment(old.id, profile)
                    }
                }
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
                    createGhostTab()
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
                MenuActivity.ACTION_TAB_ENVIRONMENT -> showTabEnvironmentChooser()
                MenuActivity.ACTION_NOTIFICATIONS -> {
                    val intent = Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                        putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName)
                    }
                    runCatching { startActivity(intent) }.onFailure {
                        startActivity(Intent(android.provider.Settings.ACTION_SETTINGS))
                    }
                }
                MenuActivity.ACTION_SECURITY -> startActivity(Intent(this, PhormiSecurityCenterActivity::class.java))
                MenuActivity.ACTION_SITE_LOCK -> showSiteLockDialog()
                MenuActivity.ACTION_FIND -> showFindInPage()
                MenuActivity.ACTION_SHARE -> shareCurrentPage()
                MenuActivity.ACTION_NAVIGATION_LENS -> showNavigationLens()
                MenuActivity.ACTION_OBJECT_ANCHORS -> showObjectAnchors()
                MenuActivity.ACTION_SAME_PAGE_SPLIT -> openSamePageSplit()
                MenuActivity.ACTION_DESKTOP_MODE -> toggleDesktopMode()
                MenuActivity.ACTION_FAVORITE -> addCurrentPageToBookmarks()
                MenuActivity.ACTION_HELP -> showPhormiHelp()
                MenuActivity.ACTION_KEEP_SCREEN_ON -> {
                    val enabled = prefs.getBoolean(KEY_KEEP_SCREEN_ON, false)
                    prefs.edit().putBoolean(KEY_KEEP_SCREEN_ON, !enabled).apply()
                    applyKeepScreenOn(!enabled)
                    Toast.makeText(
                        this,
                        if (!enabled) "Keep screen on: on" else "Keep screen on: off",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                MenuActivity.ACTION_THEME -> showAppearanceChooser()
                MenuActivity.ACTION_TAB_RETENTION -> showTabRetentionChooser()
                MenuActivity.ACTION_PULL_TO_REFRESH -> showPullToRefreshChooser()
                MenuActivity.ACTION_SETTINGS -> showAppearanceChooser()
                MenuActivity.ACTION_KEYBOARD -> PhormiKeyboardController(this).showKeyboardPicker()
                MenuActivity.ACTION_DEFAULT_BROWSER -> PhormiDefaultBrowserController.request(this)
                MenuActivity.ACTION_TAB_GROUPS -> startActivity(Intent(this, TabGroupsActivity::class.java))
                else -> {
                    val openUrl = data?.getStringExtra("open_url")
                    if (!openUrl.isNullOrBlank()) createNewTab(openUrl)
                }
            }
            return
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    fun openAiFromBottom(view: View) {
        startActivity(Intent(this, AiActivity::class.java).putExtra("auto_voice", true))
    }

    fun openDownloadsFromBottom(view: View) {
        startActivity(Intent(this, DownloadsActivity::class.java))
    }

    private fun exitFullscreenVideo() {
        val container = fullscreenContainer
        if (container != null) (window.decorView as ViewGroup).removeView(container)
        fullscreenContainer = null
        customView = null
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        requestedOrientation = originalOrientation
        swipeRefresh.visibility = View.VISIBLE
        if (!isInPictureInPictureMode && !splitChromeLocked) {
            findViewById<View>(R.id.top_toolbar)?.visibility = View.VISIBLE
            findViewById<View>(R.id.bottom_toolbar)?.visibility = View.VISIBLE
        }
    }

    override fun onBackPressed() {
        if (customView != null) {
            exitFullscreenVideo()
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
                ev.rawY >= topLoc[1] && ev.rawY < topLoc[1] + splitTopHost.height -> setActiveSplitPane(splitTopTabId)
                ev.rawY >= bottomLoc[1] && ev.rawY < bottomLoc[1] + splitBottomHost.height -> setActiveSplitPane(splitBottomTabId)
            }
        }
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_POINTER_DOWN -> {
                if (ev.pointerCount == 2 && !twoFingerHoldActive && customView == null) {
                    twoFingerHoldActive = true
                    twoFingerStartX = (ev.getX(0) + ev.getX(1)) / 2f
                    twoFingerStartY = (ev.getY(0) + ev.getY(1)) / 2f
                    val r = Runnable {
                        if (twoFingerHoldActive) {
                            activeWebView()?.reload()
                            Toast.makeText(this, "Reloading…", Toast.LENGTH_SHORT).show()
                        }
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
                if (twoFingerHoldActive && ev.pointerCount >= 2) {
                    val cx = (ev.getX(0) + ev.getX(1)) / 2f
                    val cy = (ev.getY(0) + ev.getY(1)) / 2f
                    if (kotlin.math.hypot(cx - twoFingerStartX, cy - twoFingerStartY) > 36f) {
                        twoFingerHoldActive = false
                        reloadRunnable?.let { reloadHandler.removeCallbacks(it) }
                        reloadRunnable = null
                    }
                }
                if (threeFingerTracking && ev.pointerCount >= 3) {
                    val dx = ev.getX(0) - threeFingerStartX
                    if (kotlin.math.abs(dx) >= 120f && customView == null) {
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
    private fun showNavigationLens() {
        val view = activeWebView() ?: return
        PhormiNavigationLens.inspect(view) { objects ->
            runOnUiThread {
                if (objects.isEmpty()) {
                    Toast.makeText(this, "Navigation Lens found no navigable objects.", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                val labels = objects.map { "${it.kind}: ${it.label.ifBlank { "(unnamed)" }}" }.toTypedArray()
                AlertDialog.Builder(this)
                    .setTitle("Navigation Lens")
                    .setItems(labels) { _, which ->
                        val selected = objects[which]
                        PhormiNavigationLens.focus(view, selected.locator)
                        AlertDialog.Builder(this)
                            .setTitle(selected.label.ifBlank { "Anchored object" })
                            .setMessage("${selected.kind}\n${selected.href.ifBlank { "Current page" }}")
                            .setPositiveButton("Anchor") { _, _ ->
                                val url = view.url.orEmpty()
                                if (url.isNotBlank()) {
                                    PhormiObjectAnchorStore.add(this, selected.label, url, selected.locator, selected.kind, tabs.find { it.webView === view }?.profileName ?: DEFAULT_PROFILE_NAME)
                                    Toast.makeText(this, "Object anchored", Toast.LENGTH_SHORT).show()
                                }
                            }
                            .setNegativeButton("Close", null)
                            .show()
                    }.show()
            }
        }
    }

    private fun showObjectAnchors() {
        val anchors = PhormiObjectAnchorStore.list(this)
        if (anchors.isEmpty()) {
            AlertDialog.Builder(this).setTitle("Object Anchors").setMessage("No anchors saved yet. Open Navigation Lens and anchor a page object.").setPositiveButton("Close", null).show()
            return
        }
        val labels = anchors.map { "${it.label}\n${it.url}" }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Object Anchors").setItems(labels) { _, which ->
            val anchor = anchors[which]
            createNewTab(anchor.url, anchor.profileName)
            val anchorWebView = activeWebView()
            anchorWebView?.postDelayed({
                if (anchorWebView.url == anchor.url || anchor.url.isNotBlank()) {
                    PhormiNavigationLens.focus(anchorWebView, anchor.locator)
                }
            }, 900)
        }.setNegativeButton("Close", null).show()
    }

    private fun openSamePageSplit() {
        if (splitMode) {
            Toast.makeText(this, "Already in split view", Toast.LENGTH_SHORT).show()
            return
        }
        val source = tabs.find { it.id == activeTabId } ?: return
        val currentUrl = source.webView.url?.takeIf { it.startsWith("http") } ?: return
        val originalId = source.id
        createNewTab(currentUrl, source.profileName, forceGhost = false)
        val duplicateId = activeTabId
        if (duplicateId == originalId || tabs.none { it.id == duplicateId }) return
        splitTopTabId = originalId
        splitBottomTabId = duplicateId
        setSplitMode(true)
    }

    private fun showSiteLockDialog() {
        val view = activeWebView() ?: return
        val url = view.url.orEmpty()
        if (!url.startsWith("http")) return
        val host = runCatching { Uri.parse(url).host.orEmpty() }.getOrDefault("")
        if (host.isBlank()) return
        val options = arrayOf("15 minutes", "1 hour", "Until removed")
        AlertDialog.Builder(this)
            .setTitle("Protect $host")
            .setItems(options) { _, which ->
                val expiry = when (which) {
                    0 -> System.currentTimeMillis() + 15 * 60 * 1000L
                    1 -> System.currentTimeMillis() + 60 * 60 * 1000L
                    else -> Long.MAX_VALUE
                }
                PhormiSiteLockManager.lock(this, host, expiry)
                Toast.makeText(this, "Site protection enabled for $host", Toast.LENGTH_SHORT).show()
            }.show()
    }

    private fun showSiteUnlockDialog(host: String) {
        val unlock = {
            PhormiSiteLockManager.unlock(this, host)
            val url = pendingLockedNavigationUrl
            pendingLockedNavigationUrl = null
            if (!url.isNullOrBlank()) activeWebView()?.loadUrl(url)
        }

        if (browserLockManager.isPinConfigured(prefs)) {
            val input = EditText(this).apply {
                hint = "Phormi PIN"
                inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                    android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
                setSingleLine(true)
            }
            AlertDialog.Builder(this)
                .setTitle("Site protected")
                .setMessage("$host is currently protected. Enter your Phormi PIN to continue.")
                .setView(input)
                .setNegativeButton("Cancel") { _, _ -> pendingLockedNavigationUrl = null }
                .setPositiveButton("Unlock once") { _, _ ->
                    if (browserLockManager.verifyPin(prefs, input.text.toString())) {
                        unlock()
                    } else {
                        pendingLockedNavigationUrl = null
                        Toast.makeText(this, "Incorrect PIN", Toast.LENGTH_SHORT).show()
                    }
                }.show()
        } else {
            AlertDialog.Builder(this)
                .setTitle("Site protected")
                .setMessage("$host is currently protected. Use device authentication to continue.")
                .setNegativeButton("Cancel") { _, _ -> pendingLockedNavigationUrl = null }
                .setPositiveButton("Authenticate") { _, _ ->
                    browserLockManager.authenticate(unlock) { pendingLockedNavigationUrl = null }
                }.show()
        }
    }

    private fun showFindInPage() {
        val view = activeWebView() ?: return
        val input = EditText(this).apply { hint = "Find text"; setSingleLine(true) }
        AlertDialog.Builder(this)
            .setTitle("Find in page")
            .setView(input)
            .setNegativeButton("Close") { _, _ -> view.clearMatches() }
            .setPositiveButton("Find") { _, _ ->
                val q = input.text.toString()
                if (q.isNotBlank()) view.findAllAsync(q)
            }.show()
    }

    private fun shareCurrentPage() {
        val view = activeWebView() ?: return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, view.url.orEmpty())
            putExtra(Intent.EXTRA_TITLE, view.title.orEmpty())
        }
        startActivity(Intent.createChooser(send, "Share page"))
    }

    private fun addCurrentPageToBookmarks() {
        val view = activeWebView() ?: return
        val url = view.url.orEmpty()
        if (!url.startsWith("http")) return
        val bookmarks = BookmarksActivity.getAll(this)
        if (bookmarks.any { it.url == url }) {
            BookmarksActivity.remove(this, url)
            val contexts = runCatching { JSONObject(prefs.getString(KEY_FAVORITE_CONTEXTS, "{}") ?: "{}") }.getOrElse { JSONObject() }
            contexts.remove(url)
            prefs.edit().putString(KEY_FAVORITE_CONTEXTS, contexts.toString()).apply()
            Toast.makeText(this, "Removed from favorites", Toast.LENGTH_SHORT).show()
        } else {
            BookmarksActivity.add(this, view.title.orEmpty(), url)
            val contexts = runCatching { JSONObject(prefs.getString(KEY_FAVORITE_CONTEXTS, "{}") ?: "{}") }.getOrElse { JSONObject() }
            contexts.put(url, activeTabId)
            prefs.edit().putString(KEY_FAVORITE_CONTEXTS, contexts.toString()).apply()
            Toast.makeText(this, "Added to favorites", Toast.LENGTH_SHORT).show()
        }
        loadQuickAccessRows()
        updateFavoriteButton()
    }

    private fun toggleDesktopMode() {
        val view = activeWebView() ?: return
        val desktop = !prefs.getBoolean("desktop_mode", false)
        prefs.edit().putBoolean("desktop_mode", desktop).apply()
        val settings = view.settings
        settings.userAgentString = if (desktop) {
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
        } else WebSettings.getDefaultUserAgent(this)
        settings.useWideViewPort = desktop
        settings.loadWithOverviewMode = desktop
        if (desktop) view.setInitialScale(100) else view.setInitialScale(0)
        view.reload()
        Toast.makeText(this, if (desktop) "Desktop mode: on" else "Desktop mode: off", Toast.LENGTH_SHORT).show()
    }

    fun handleCentralHubCommand(command: JSONObject): JSONObject {
        return try {
            when (command.optString("command")) {
                "state" -> JSONObject().put("ok", true)
                    .put("tabs", tabs.size)
                    .put("ghostTabs", tabs.count { it.isGhost })
                    .put("activeUrl", activeWebView()?.url ?: "")
                    .put("title", tabs.find { it.id == activeTabId }?.title ?: "")
                    .put("profile", tabs.find { it.id == activeTabId }?.profileName ?: DEFAULT_PROFILE_NAME)
                "open_url" -> {
                    val url = command.optString("url").trim()
                    if (url.startsWith("http")) { createNewTab(url); JSONObject().put("ok", true) }
                    else JSONObject().put("ok", false).put("error", "bad_url")
                }
                "new_tab" -> { createNewTab(NEW_TAB_URL); JSONObject().put("ok", true) }
                "ghost_tab" -> { createGhostTab(command.optString("url", NEW_TAB_URL)); JSONObject().put("ok", true) }
                "back" -> { activeWebView()?.let { if (it.canGoBack()) it.goBack() }; JSONObject().put("ok", true) }
                "forward" -> { activeWebView()?.let { if (it.canGoForward()) it.goForward() }; JSONObject().put("ok", true) }
                "reload" -> { activeWebView()?.reload(); JSONObject().put("ok", true) }
                "read_page" -> {
                    val view = activeWebView() ?: return JSONObject().put("ok", false).put("error", "no_active_tab")
                    val latch = java.util.concurrent.CountDownLatch(1)
                    var payload = JSONObject().put("ok", false).put("error", "read_timeout")
                    view.evaluateJavascript("JSON.stringify({title:document.title,url:location.href,text:(document.body?.innerText||'').slice(0,20000),links:Array.from(document.querySelectorAll('a[href]')).slice(0,120).map(a=>({text:(a.innerText||'').trim().slice(0,120),href:a.href}))})") { raw ->
                        payload = runCatching { JSONObject(org.json.JSONTokener(raw).nextValue().toString()) }
                            .getOrElse { JSONObject() }
                            .put("ok", true)
                        latch.countDown()
                    }
                    latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
                    payload
                }
                "click_text" -> {
                    val text = JSONObject.quote(command.optString("text"))
                    activeWebView()?.evaluateJavascript("(()=>{const t=$text;const e=[...document.querySelectorAll('button,a,[role=button],input[type=submit]')].find(x=>(x.innerText||x.value||x.getAttribute('aria-label')||'').includes(t));if(e){e.click();return true}return false})()") { }
                    JSONObject().put("ok", true)
                }
                "type_text" -> {
                    val text = JSONObject.quote(command.optString("text"))
                    val selector = command.optString("selector").trim()
                    val script = if (selector.isBlank()) "document.activeElement?.value!==undefined?(document.activeElement.value=$text,document.activeElement.dispatchEvent(new Event('input',{bubbles:true})),true):false" else "(()=>{const e=document.querySelector(${JSONObject.quote(selector)});if(!e)return false;e.focus();e.value=$text;e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));return true})()"
                    activeWebView()?.evaluateJavascript(script) { }
                    JSONObject().put("ok", true)
                }
                "scroll" -> {
                    val dy = command.optInt("dy", 600)
                    activeWebView()?.evaluateJavascript("window.scrollBy(0,$dy);true") { }
                    JSONObject().put("ok", true)
                }
                "screenshot", "frame" -> {
                    val view = activeWebView() ?: return JSONObject().put("ok", false).put("error", "no_active_tab")
                    JSONObject().put("ok", true).put("image", PhormiViewportCapture.toBase64Jpeg(view, 1280, 62))
                }
                "webgpu_diagnostics" -> {
                    val view = activeWebView() ?: return JSONObject().put("ok", false).put("error", "no_active_tab")
                    val latch = java.util.concurrent.CountDownLatch(1)
                    var result = JSONObject().put("ok", false).put("error", "diagnostics_timeout")
                    PhormiWebGpuDiagnostics.run(view) { json -> result = json; latch.countDown() }
                    latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
                    result
                }
                else -> JSONObject().put("ok", false).put("error", "unknown_command")
            }
        } catch (e: Exception) { JSONObject().put("ok", false).put("error", e.message ?: "error") }
    }

} 
