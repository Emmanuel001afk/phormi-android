from pathlib import Path
import re

root = Path(__file__).resolve().parents[1]
main = root / 'app/src/main/java/com/uong/phormi/MainActivity.kt'
s = main.read_text()

# Favorites belong in Bookmarks, not homepage Quick Access.
s = re.sub(
    r'        val favoriteContexts = .*?\n        BookmarksActivity\.getAll\(this\)\.forEach \{ bookmark ->.*?\n        \}\n',
    '', s, count=1, flags=re.S)

# Fixed pinned services open as their own tabs. Personal shortcuts and most-visited remain same-tab.
s = s.replace(
    '''                if (site.url.isBlank()) showAddShortcutDialog()
                else if (site.tabId != null && tabs.any { it.id == site.tabId }) switchToTab(site.tabId)
                else openShortcut(site.url)''',
    '''                if (site.url.isBlank()) showAddShortcutDialog()
                else if (site.tabId != null && tabs.any { it.id == site.tabId }) switchToTab(site.tabId)
                else if (site.tag.isBlank()) createNewTab(site.url)
                else openShortcut(site.url)''', 1)

# Automatic tab-retention enforcement while the app is foregrounded.
if 'private val retentionCheckRunnable' not in s:
    s = s.replace(
        '    private var tabSaveRunnable: Runnable? = null\n',
        '''    private var tabSaveRunnable: Runnable? = null
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
if 'startRetentionScheduler()' not in s:
    s = s.replace('            pruneExpiredTabs()\n            updateHomeNewsVisibility()', '            pruneExpiredTabs()\n            startRetentionScheduler()\n            updateHomeNewsVisibility()', 1)
    s = s.replace(
        '''    override fun onPause() {
        // Persist both browser state and website sessions when the app leaves the foreground.
        saveTabsImmediate(true)''',
        '''    private fun startRetentionScheduler() {
        retentionCheckHandler.removeCallbacks(retentionCheckRunnable)
        retentionCheckHandler.post(retentionCheckRunnable)
    }

    private fun stopRetentionScheduler() {
        retentionCheckHandler.removeCallbacks(retentionCheckRunnable)
    }

    override fun onPause() {
        stopRetentionScheduler()
        // Persist both browser state and website sessions when the app leaves the foreground.
        saveTabsImmediate(true)''', 1)
    s = s.replace('    override fun onDestroy() {\n        reloadRunnable?.let', '    override fun onDestroy() {\n        stopRetentionScheduler()\n        reloadRunnable?.let', 1)

# Desktop mode applies to all normal tabs, so the mode cannot appear to work only on one tab.
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
if old in s:
    s = s.replace(old, new, 1)

# Menu Browser Lock uses the same method selection and device-availability guard as Security Center.
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
                                    prefs.edit().putBoolean(KEY_BROWSER_LOCK, true).apply()
                                    promptCreatePin()
                                }
                            }
                            .setOnCancelListener { prefs.edit().putBoolean(KEY_BROWSER_LOCK, false).apply() }
                            .show()
                    }
                }'''
if old in s:
    s = s.replace(old, new, 1)

# Selecting a tab from the group screen can return to the existing MainActivity instance.
needle = '        val url = intent?.dataString?.trim()\n'
if 'select_tab_id' not in s:
    s = s.replace(needle, '''        val selectTabId = intent?.getIntExtra("select_tab_id", -1) ?: -1
        if (selectTabId > 0 && tabs.any { it.id == selectTabId }) {
            switchToTab(selectTabId)
            return
        }
        val url = intent?.dataString?.trim()
''', 1)

# Keep Help routed to the complete HelpActivity.
s = s.replace(
    'MenuActivity.ACTION_HELP -> AlertDialog.Builder(this).setTitle("Phormi Help").setMessage("Use the address bar to search or open a site. Tabs, Ghost mode, split view, downloads, keyboard tools, privacy, and browser settings are available from the menu.").setPositiveButton("OK", null).show()',
    'MenuActivity.ACTION_HELP -> startActivity(Intent(this, HelpActivity::class.java))'
)
main.write_text(s)

# Top-bar URL submit arrow is visually distinct from the dedicated straight refresh arrow.
layout = root / 'app/src/main/res/layout/activity_main.xml'
xml = layout.read_text()
idx = xml.find('android:id="@+id/btn_go"')
if idx >= 0:
    t = xml.find('android:text="→"', idx)
    if t >= 0:
        xml = xml[:t] + 'android:text="↵"' + xml[t + len('android:text="→"'):]
layout.write_text(xml)

print('Applied menu integration repairs')
