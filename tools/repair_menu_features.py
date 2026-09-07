from pathlib import Path
import re

root = Path(__file__).resolve().parents[1]
main = root / 'app/src/main/java/com/uong/phormi/MainActivity.kt'
s = main.read_text()

# Favorites are a dedicated per-page feature, not homepage Quick Access content.
s = re.sub(r'        val favoriteContexts = .*?\n        BookmarksActivity\.getAll\(this\)\.forEach \{ bookmark ->.*?\n        \}\n', '', s, count=1, flags=re.S)
s = s.replace('''                if (site.url.isBlank()) showAddShortcutDialog()
                else if (site.tabId != null && tabs.any { it.id == site.tabId }) switchToTab(site.tabId)
                else openShortcut(site.url)''', '''                if (site.url.isBlank()) showAddShortcutDialog()
                else if (site.tabId != null && tabs.any { it.id == site.tabId }) switchToTab(site.tabId)
                else if (site.tag.isBlank()) createNewTab(site.url)
                else openShortcut(site.url)''', 1)

# Favorites appear only on real pages, never on the homepage.
s = s.replace('''        findViewById<View>(R.id.btn_refresh)?.visibility = if (onHome) View.GONE else View.VISIBLE
        findViewById<View>(R.id.bottom_toolbar)?.visibility''', '''        findViewById<View>(R.id.btn_refresh)?.visibility = if (onHome) View.GONE else View.VISIBLE
        findViewById<View>(R.id.btn_favorite)?.visibility = if (onHome) View.GONE else View.VISIBLE
        findViewById<View>(R.id.bottom_toolbar)?.visibility''', 1)
s = re.sub(r'    private fun updateFavoriteButton\(\) \{.*?\n    \}\n\n    private fun updateNavButtons', '''    private fun updateFavoriteButton() {
        val button = findViewById<TextView>(R.id.btn_favorite) ?: return
        val url = activeWebView()?.url.orEmpty()
        val favorite = url.startsWith("http") && PhormiFavorites.contains(this, url)
        button.alpha = if (favorite) 1f else 0.55f
        button.contentDescription = if (favorite) "Remove current page from favorites" else "Add current page to favorites"
    }

    private fun updateNavButtons''', s, count=1, flags=re.S)
s = re.sub(r'    private fun addCurrentPageToBookmarks\(\) \{.*?\n    \}\n\n    private fun toggleDesktopMode', '''    private fun addCurrentPageToBookmarks() {
        val view = activeWebView() ?: return
        val url = view.url.orEmpty()
        if (!url.startsWith("http")) return
        val added = PhormiFavorites.toggle(this, view.title.orEmpty(), url)
        Toast.makeText(this, if (added) "Added to favorites" else "Removed from favorites", Toast.LENGTH_SHORT).show()
        updateFavoriteButton()
    }

    private fun toggleDesktopMode''', s, count=1, flags=re.S)

# Desktop mode applies to every normal tab and to tabs created after the setting is enabled.
old = '''    private fun toggleDesktopMode() {
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
    }'''
new = '''    private fun toggleDesktopMode() {
        if (tabs.isEmpty()) return
        val desktop = !prefs.getBoolean("desktop_mode", false)
        prefs.edit().putBoolean("desktop_mode", desktop).apply()
        tabs.filterNot { it.isGhost }.forEach { tab ->
            applyDesktopMode(tab.webView, desktop)
            tab.webView.reload()
        }
        Toast.makeText(this, if (desktop) "Desktop mode: on" else "Desktop mode: off", Toast.LENGTH_SHORT).show()
    }

    private fun applyDesktopMode(webView: WebView, desktop: Boolean) {
        val settings = webView.settings
        settings.userAgentString = if (desktop) {
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
        } else WebSettings.getDefaultUserAgent(this)
        settings.useWideViewPort = desktop
        settings.loadWithOverviewMode = desktop
        webView.setInitialScale(if (desktop) 100 else 0)
    }'''
if old in s: s = s.replace(old, new, 1)
if 'applyDesktopMode(webView, prefs.getBoolean("desktop_mode", false))' not in s:
    s = s.replace('''        configureWebView(webView)
        webView.isClickable = true''', '''        configureWebView(webView)
        applyDesktopMode(webView, prefs.getBoolean("desktop_mode", false))
        webView.isClickable = true''', 1)

# Retention runs automatically while the browser is foregrounded.
if 'private val retentionCheckRunnable' not in s:
    s = s.replace('    private var tabSaveRunnable: Runnable? = null\n', '''    private var tabSaveRunnable: Runnable? = null
    private val retentionCheckHandler = Handler(Looper.getMainLooper())
    private val retentionCheckRunnable = object : Runnable {
        override fun run() {
            if (!isFinishing && !isDestroyed) {
                pruneExpiredTabs()
                retentionCheckHandler.postDelayed(this, 60_000L)
            }
        }
    }
''', 1)
    s = s.replace('            pruneExpiredTabs()\n            updateHomeNewsVisibility()', '            pruneExpiredTabs()\n            startRetentionScheduler()\n            updateHomeNewsVisibility()', 1)
    s = s.replace('''    override fun onPause() {
        // Persist both browser state and website sessions when the app leaves the foreground.
        saveTabsImmediate(true)''', '''    private fun startRetentionScheduler() { retentionCheckHandler.removeCallbacks(retentionCheckRunnable); retentionCheckHandler.post(retentionCheckRunnable) }
    private fun stopRetentionScheduler() { retentionCheckHandler.removeCallbacks(retentionCheckRunnable) }

    override fun onPause() {
        stopRetentionScheduler()
        // Persist both browser state and website sessions when the app leaves the foreground.
        saveTabsImmediate(true)''', 1)
    s = s.replace('    override fun onDestroy() {\n        reloadRunnable?.let', '    override fun onDestroy() {\n        stopRetentionScheduler()\n        reloadRunnable?.let', 1)

# Browser Lock is an app-style lock. Opening Phormi's own menu must not count as leaving the app.
s = re.sub(r'\n    override fun onUserLeaveHint\(\) \{.*?\n    \}\n', '\n', s, flags=re.S)
if 'private var internalActivityLaunch' not in s:
    s = s.replace('    private var browserUnlockedThisSession = false\n', '    private var browserUnlockedThisSession = false\n    private var internalActivityLaunch = false\n', 1)
# Mark the menu launch as internal before it moves MainActivity to the stopped state.
s = re.sub(r'(findViewById<View>\(R\.id\.btn_menu\)\?\.setOnClickListener \{)', r'\1\n            internalActivityLaunch = true', s, count=1)
# Clear the marker when MainActivity becomes visible again.
if 'if (internalActivityLaunch) internalActivityLaunch = false' not in s:
    s = s.replace('''    override fun onResume() {
        super.onResume()''', '''    override fun onResume() {
        if (internalActivityLaunch) internalActivityLaunch = false
        super.onResume()''', 1)
# Relock only when MainActivity actually goes to the background, not for the menu activity.
s = s.replace('''    override fun onStop() {
        if (::prefs.isInitialized) saveTabsImmediate(true)
        super.onStop()
    }''', '''    override fun onStop() {
        if (::prefs.isInitialized) {
            saveTabsImmediate(true)
            if (browserLockManager.isEnabled(prefs) && !internalActivityLaunch) browserUnlockedThisSession = false
        }
        super.onStop()
    }''', 1)

old = '''                MenuActivity.ACTION_BROWSER_LOCK -> {
                    val enabled = prefs.getBoolean(KEY_BROWSER_LOCK, false)
                    prefs.edit().putBoolean(KEY_BROWSER_LOCK, !enabled).apply()
                    browserUnlockedThisSession = !enabled
                    Toast.makeText(
                        this,
                        if (!enabled) "Browser Lock on" else "Browser Lock off",
                        Toast.LENGTH_SHORT
                    ).show()
                    if (!enabled) authenticateBrowserLock()
                }'''
new = '''                MenuActivity.ACTION_BROWSER_LOCK -> {
                    val enabled = prefs.getBoolean(KEY_BROWSER_LOCK, false)
                    if (enabled) {
                        prefs.edit().putBoolean(KEY_BROWSER_LOCK, false).apply()
                        browserUnlockedThisSession = true
                        removeBrowserLockOverlay()
                        Toast.makeText(this, "Browser Lock disabled", Toast.LENGTH_SHORT).show()
                    } else {
                        AlertDialog.Builder(this)
                            .setTitle("Browser Lock method")
                            .setItems(arrayOf("Device security / biometrics", "Create a Phormi PIN")) { _, which ->
                                if (which == 0) {
                                    if (!browserLockManager.canUseDeviceAuthentication()) {
                                        Toast.makeText(this, "No phone screen lock or usable biometric is configured. Use a Phormi PIN instead.", Toast.LENGTH_LONG).show()
                                        return@setItems
                                    }
                                    prefs.edit().putBoolean(KEY_BROWSER_LOCK, true).putString(BrowserLockManager.PREF_METHOD, BrowserLockManager.METHOD_DEVICE).apply()
                                    browserUnlockedThisSession = false
                                    authenticateBrowserLock()
                                } else {
                                    prefs.edit().putBoolean(KEY_BROWSER_LOCK, true).putString(BrowserLockManager.PREF_METHOD, BrowserLockManager.METHOD_PIN).apply()
                                    promptCreatePin()
                                }
                            }.setOnCancelListener { prefs.edit().putBoolean(KEY_BROWSER_LOCK, false).apply() }.show()
                    }
                }'''
if old in s: s = s.replace(old, new, 1)

s = s.replace('MenuActivity.ACTION_HELP -> AlertDialog.Builder(this).setTitle("Phormi Help").setMessage("Use the address bar to search or open a site. Tabs, Ghost mode, split view, downloads, keyboard tools, privacy, and browser settings are available from the menu.").setPositiveButton("OK", null).show()', 'MenuActivity.ACTION_HELP -> startActivity(Intent(this, HelpActivity::class.java))')
s = s.replace('MenuActivity.ACTION_FAVORITE -> addCurrentPageToBookmarks()', 'MenuActivity.ACTION_FAVORITE -> startActivity(Intent(this, FavoritesActivity::class.java))')
s = s.replace('MenuActivity.ACTION_KEYBOARD -> PhormiKeyboardController.showKeyboardPicker(this)', 'MenuActivity.ACTION_KEYBOARD -> startActivity(Intent(this, PhormiKeyboardSettingsActivity::class.java))')

# Return from TabGroupsActivity to an exact existing tab.
if 'val selectTabId = intent?.getIntExtra("select_tab_id", -1)' not in s:
    s = s.replace('''    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        val url = intent?.dataString?.trim()''', '''    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        val selectTabId = intent?.getIntExtra("select_tab_id", -1) ?: -1
        if (selectTabId > 0 && tabs.any { it.id == selectTabId }) {
            switchToTab(selectTabId)
            return
        }
        val url = intent?.dataString?.trim()''', 1)

# The retention dialog uses the choice list as its content; Android dialogs cannot show a message and list together.
s = re.sub(r'''    private fun showTabRetentionChooser\(\) \{.*?\n    \}\n\n    private fun showPullToRefreshChooser''', '''    private fun showTabRetentionChooser() {
        val values = arrayOf(RETENTION_NEVER, RETENTION_1_MONTH, RETENTION_3_MONTHS, RETENTION_1_YEAR)
        val labels = arrayOf("Never", "1 month", "3 months", "1 year")
        val current = prefs.getString(KEY_TAB_RETENTION, RETENTION_NEVER) ?: RETENTION_NEVER
        val checked = values.indexOf(current).coerceAtLeast(0)
        AlertDialog.Builder(this).setTitle("Tab retention")
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                prefs.edit().putString(KEY_TAB_RETENTION, values[which]).apply()
                pruneExpiredTabs()
                dialog.dismiss()
                Toast.makeText(this, "Tab retention: ${labels[which]}", Toast.LENGTH_SHORT).show()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun showPullToRefreshChooser''', s, count=1, flags=re.S)

main.write_text(s)
layout=root/'app/src/main/res/layout/activity_main.xml'; xml=layout.read_text(); idx=xml.find('android:id="@+id/btn_go"')
if idx>=0:
    t=xml.find('android:text="→"',idx)
    if t>=0: xml=xml[:t]+'android:text="↵"'+xml[t+len('android:text="→"'):]
layout.write_text(xml)
start_page=root/'app/src/main/res/layout/activity_start_page.xml'; start_xml=start_page.read_text().replace('Favorites and most-visited sites appear here automatically.','Pinned services and most-visited sites appear here automatically. Favorites stay in the current tab and Favorites menu.'); start_page.write_text(start_xml)

print('Applied menu, favorites, retention, browser-lock lifecycle, desktop, group return, and maintained download-manager repairs')
