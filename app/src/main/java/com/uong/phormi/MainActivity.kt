package com.uong.phormi

import android.Manifest
import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.view.ContextMenu
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import org.json.JSONArray

/**
 * Phormi basic browser:
 * - Multi tabs (saved/restored), new tab = blank
 * - Downloads via DownloadManager → phone Downloads folder
 * - Long-press link/image → Open / Copy / Download
 * - Button to open Downloads folder (watch movies etc.)
 * - Keep screen on toggle (saved preference)
 */
class MainActivity : AppCompatActivity() {

    private data class Tab(
        val id: Int,
        val webView: WebView,
        var title: String,
        val chipView: View
    )

    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var webViewContainer: FrameLayout
    private lateinit var tabStripContainer: LinearLayout
    private lateinit var prefs: SharedPreferences
    private lateinit var switchKeepScreenOn: SwitchCompat

    private val tabs = mutableListOf<Tab>()
    private var activeTabId: Int = -1
    private var nextTabId = 1

    private var pendingPermissionRequest: PermissionRequest? = null
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    private var contextMenuUrl: String? = null
    private var contextMenuIsImage: Boolean = false

    companion object {
        private const val HOME_URL = "https://phormi.lovable.app"
        private const val NEW_TAB_URL = "about:blank"
        private const val PREFS_NAME = "phormi_tabs"
        private const val KEY_TAB_URLS = "tab_urls"
        private const val KEY_ACTIVE_INDEX = "active_index"
        private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        private const val REQ_MEDIA_PERMISSIONS = 1001
        private const val REQ_FILE_CHOOSER = 1002
        private const val REQ_STARTUP_PERMISSIONS = 1003
        private const val MAX_TABS = 5

        private const val MENU_OPEN = 1
        private const val MENU_COPY = 2
        private const val MENU_DOWNLOAD = 3

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        swipeRefresh = findViewById(R.id.swipe_refresh)
        webViewContainer = findViewById(R.id.webview_container)
        tabStripContainer = findViewById<View>(R.id.tab_strip).findViewById(R.id.tab_strip_container)
        switchKeepScreenOn = findViewById(R.id.switch_keep_screen_on)

        // Keep screen on toggle (remembered)
        val keepOn = prefs.getBoolean(KEY_KEEP_SCREEN_ON, false)
        switchKeepScreenOn.isChecked = keepOn
        applyKeepScreenOn(keepOn)
        switchKeepScreenOn.setOnCheckedChangeListener { _, isChecked ->
            applyKeepScreenOn(isChecked)
            prefs.edit().putBoolean(KEY_KEEP_SCREEN_ON, isChecked).apply()
            Toast.makeText(
                this,
                if (isChecked) "Screen will stay on" else "Screen can turn off normally",
                Toast.LENGTH_SHORT
            ).show()
        }

        // Open phone Downloads folder (where movies/files are saved)
        findViewById<TextView>(R.id.btn_open_downloads).setOnClickListener {
            openDownloadsFolder()
        }

        findViewById<com.google.android.material.floatingactionbutton.FloatingActionButton>(R.id.fab_ai)
            .setOnClickListener {
                startActivity(Intent(this, AiActivity::class.java))
            }

        findViewById<TextView>(R.id.btn_new_tab).setOnClickListener {
            if (tabs.size >= MAX_TABS) {
                Toast.makeText(this, "Tab limit reached ($MAX_TABS) — close one first", Toast.LENGTH_SHORT).show()
            } else {
                createNewTab(NEW_TAB_URL)
            }
        }

        requestStartupPermissions()
        swipeRefresh.setOnRefreshListener { activeWebView()?.reload() }

        val incomingUrl = intent?.dataString
        if (!incomingUrl.isNullOrBlank()) {
            createNewTab(incomingUrl)
        } else {
            restoreTabs()
        }
    }

    private fun applyKeepScreenOn(enabled: Boolean) {
        if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    /** Opens the phone's Downloads folder so user can find/play movies */
    private fun openDownloadsFolder() {
        try {
            // Try official Downloads intent first
            val intent = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(intent)
        } catch (e: Exception) {
            try {
                // Fallback: open Downloads via DocumentsUI
                val uri = Uri.parse("content://com.android.externalstorage.documents/document/primary:Download")
                val intent = Intent(Intent.ACTION_VIEW)
                intent.setDataAndType(uri, DocumentsContract.Document.MIME_TYPE_DIR)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                startActivity(intent)
            } catch (e2: Exception) {
                Toast.makeText(
                    this,
                    "Open Files app → Downloads folder to see your files",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        saveTabs()
    }

    override fun onStop() {
        super.onStop()
        saveTabs()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        val url = intent?.dataString
        if (!url.isNullOrBlank()) {
            if (tabs.size >= MAX_TABS) {
                activeWebView()?.loadUrl(url)
            } else {
                createNewTab(url)
            }
        }
    }

    private fun activeWebView(): WebView? = tabs.find { it.id == activeTabId }?.webView

    private fun createNewTab(url: String) {
        val id = nextTabId++
        val webView = WebView(this)
        webViewContainer.addView(
            webView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        configureWebView(webView)
        registerForContextMenu(webView)

        val chip = LayoutInflater.from(this).inflate(R.layout.tab_chip, tabStripContainer, false)
        val chipTitle = chip.findViewById<TextView>(R.id.tab_chip_title)
        val chipClose = chip.findViewById<TextView>(R.id.tab_chip_close)

        val tab = Tab(id, webView, getString(R.string.new_tab), chip)
        tabs.add(tab)
        tabStripContainer.addView(chip)

        chip.setOnClickListener { switchToTab(id) }
        chipClose.setOnClickListener { closeTab(id) }

        webView.webChromeClient = buildChromeClient { newTitle ->
            chipTitle.text = if (newTitle.isNullOrBlank()) getString(R.string.new_tab) else newTitle
        }

        webView.loadUrl(url)
        switchToTab(id)
        saveTabs()
    }

    private fun switchToTab(id: Int) {
        activeTabId = id
        tabs.forEach { tab ->
            val isActive = tab.id == id
            tab.webView.visibility = if (isActive) View.VISIBLE else View.GONE
            tab.chipView.alpha = if (isActive) 1f else 0.55f
        }
        saveTabs()
    }

    private fun closeTab(id: Int) {
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
        } else if (activeTabId == id) {
            val fallback = tabs.getOrNull(index - 1) ?: tabs.first()
            switchToTab(fallback.id)
        }
        saveTabs()
    }

    private fun saveTabs() {
        val urls = JSONArray()
        tabs.forEach { tab ->
            val url = tab.webView.url
            if (!url.isNullOrBlank()) urls.put(url)
        }
        if (urls.length() == 0) urls.put(HOME_URL)
        val activeIndex = tabs.indexOfFirst { it.id == activeTabId }.coerceAtLeast(0)
        prefs.edit()
            .putString(KEY_TAB_URLS, urls.toString())
            .putInt(KEY_ACTIVE_INDEX, activeIndex)
            .apply()
    }

    private fun restoreTabs() {
        val json = prefs.getString(KEY_TAB_URLS, null)
        if (json.isNullOrBlank()) {
            createNewTab(HOME_URL)
            return
        }
        try {
            val arr = JSONArray(json)
            if (arr.length() == 0) {
                createNewTab(HOME_URL)
                return
            }
            val activeIndex = prefs.getInt(KEY_ACTIVE_INDEX, 0).coerceIn(0, arr.length() - 1)
            for (i in 0 until arr.length()) {
                createNewTab(arr.optString(i, HOME_URL))
            }
            if (tabs.isNotEmpty()) {
                switchToTab((tabs.getOrNull(activeIndex) ?: tabs.first()).id)
            }
        } catch (e: Exception) {
            createNewTab(HOME_URL)
        }
    }

    // ─── DOWNLOADS ─────────────────────────────────────────────────────

    private fun startDownload(url: String, contentDisposition: String?, mimeType: String?) {
        if (url.isBlank() || url.startsWith("blob:") || url.startsWith("data:")) {
            Toast.makeText(this, "Cannot download this type of link", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val fileName = URLUtil.guessFileName(
                url,
                contentDisposition,
                mimeType ?: "application/octet-stream"
            )
            val request = DownloadManager.Request(Uri.parse(url))
            request.setMimeType(mimeType ?: "application/octet-stream")
            request.setTitle(fileName)
            request.setDescription("Downloading via Phormi")
            request.setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
            )
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            request.allowScanningByMediaScanner()
            request.setAllowedOverMetered(true)
            request.setAllowedOverRoaming(true)

            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)

            Toast.makeText(
                this,
                "Downloading $fileName\nOpen Downloads when finished",
                Toast.LENGTH_LONG
            ).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreateContextMenu(
        menu: ContextMenu,
        v: View,
        menuInfo: ContextMenu.ContextMenuInfo?
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
                menu.setHeaderTitle(contextMenuUrl?.take(60) ?: "Link")
                menu.add(0, MENU_OPEN, 0, "Open")
                menu.add(0, MENU_COPY, 1, "Copy link")
                menu.add(0, MENU_DOWNLOAD, 2, "Download")
            }
            WebView.HitTestResult.IMAGE_TYPE -> {
                contextMenuUrl = result.extra
                contextMenuIsImage = true
                menu.setHeaderTitle("Image")
                menu.add(0, MENU_OPEN, 0, "Open image")
                menu.add(0, MENU_COPY, 1, "Copy image URL")
                menu.add(0, MENU_DOWNLOAD, 2, "Download image")
            }
        }
    }

    override fun onContextItemSelected(item: MenuItem): Boolean {
        val url = contextMenuUrl ?: return super.onContextItemSelected(item)
        when (item.itemId) {
            MENU_OPEN -> {
                if (tabs.size >= MAX_TABS) activeWebView()?.loadUrl(url)
                else createNewTab(url)
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

    // ─── WebView ───────────────────────────────────────────────────────

    private fun requestStartupPermissions() {
        val notYetGranted = STARTUP_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (notYetGranted.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this, notYetGranted.toTypedArray(), REQ_STARTUP_PERMISSIONS
            )
        }
    }

    private fun configureWebView(webView: WebView) {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.setGeolocationEnabled(true)
        settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.setSupportMultipleWindows(false)
        settings.userAgentString = settings.userAgentString + " PhormiApp/1.0"

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (view == activeWebView()) swipeRefresh.isRefreshing = false
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
                else ActivityCompat.requestPermissions(
                    this@MainActivity, needed.toTypedArray(), REQ_MEDIA_PERMISSIONS
                )
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
                        REQ_MEDIA_PERMISSIONS
                    )
                }
            }

            override fun onShowFileChooser(
                webViewParam: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@MainActivity.filePathCallback = filePathCallback
                val intent = fileChooserParams?.createIntent() ?: return false
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
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MEDIA_PERMISSIONS) {
            val request = pendingPermissionRequest ?: return
            val allGranted = grantResults.isNotEmpty() &&
                grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (allGranted) request.grant(request.resources) else request.deny()
            pendingPermissionRequest = null
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ_FILE_CHOOSER) {
            val results: Array<Uri>? =
                if (resultCode == Activity.RESULT_OK && data?.data != null) arrayOf(data.data!!)
                else null
            filePathCallback?.onReceiveValue(results)
            filePathCallback = null
            return
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onBackPressed() {
        val webView = activeWebView()
        if (webView != null && webView.canGoBack()) webView.goBack()
        else super.onBackPressed()
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_POINTER_DOWN -> {
                if (ev.pointerCount == 2 && !twoFingerHoldActive) {
                    twoFingerHoldActive = true
                    val runnable = Runnable {
                        activeWebView()?.reload()
                        Toast.makeText(this, "Reloading…", Toast.LENGTH_SHORT).show()
                    }
                    reloadRunnable = runnable
                    reloadHandler.postDelayed(runnable, 1000)
                }
            }
            android.view.MotionEvent.ACTION_POINTER_UP,
            android.view.MotionEvent.ACTION_UP,
            android.view.MotionEvent.ACTION_CANCEL -> {
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
