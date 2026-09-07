from pathlib import Path
import re

root = Path(__file__).resolve().parents[1]
main = root / 'app/src/main/java/com/uong/phormi/MainActivity.kt'
s = main.read_text()

# Favorites are a dedicated per-page feature, not homepage Quick Access content.
s = re.sub(r'        val favoriteContexts = .*?\n        BookmarksActivity\.getAll\(this\)\.forEach \{ bookmark ->.*?\n        \}\n', '', s, count=1, flags=re.S)

# Fixed pinned services open in their own tabs. Personal shortcuts and most-visited remain same-tab.
s = s.replace('''                if (site.url.isBlank()) showAddShortcutDialog()
                else if (site.tabId != null && tabs.any { it.id == site.tabId }) switchToTab(site.tabId)
                else openShortcut(site.url)''', '''                if (site.url.isBlank()) showAddShortcutDialog()
                else if (site.tabId != null && tabs.any { it.id == site.tabId }) switchToTab(site.tabId)
                else if (site.tag.isBlank()) createNewTab(site.url)
                else openShortcut(site.url)''', 1)

# Hide the favorite control on the home/start page. It is a page/tab action only.
if 'R.id.btn_favorite)?.visibility' not in s:
    s = s.replace('''        findViewById<View>(R.id.btn_refresh)?.visibility = if (onHome) View.GONE else View.VISIBLE
        findViewById<View>(R.id.bottom_toolbar)?.visibility''', '''        findViewById<View>(R.id.btn_refresh)?.visibility = if (onHome) View.GONE else View.VISIBLE
        findViewById<View>(R.id.btn_favorite)?.visibility = if (onHome) View.GONE else View.VISIBLE
        findViewById<View>(R.id.bottom_toolbar)?.visibility''', 1)

# Favorites use their own store and the menu opens the list instead of toggling the current page.
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

# Desktop mode applies to every normal tab, not only the active WebView.
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
        saveTabsImmediate(true)''', '''    private fun startRetentionScheduler() {
        retentionCheckHandler.removeCallbacks(retentionCheckRunnable)
        retentionCheckHandler.post(retentionCheckRunnable)
    }

    private fun stopRetentionScheduler() { retentionCheckHandler.removeCallbacks(retentionCheckRunnable) }

    override fun onPause() {
        stopRetentionScheduler()
        // Persist both browser state and website sessions when the app leaves the foreground.
        saveTabsImmediate(true)''', 1)
    s = s.replace('    override fun onDestroy() {\n        reloadRunnable?.let', '    override fun onDestroy() {\n        stopRetentionScheduler()\n        reloadRunnable?.let', 1)

# Menu Browser Lock selects a real authentication method and refuses a fake/unavailable device lock.
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
                            }
                            .setOnCancelListener { prefs.edit().putBoolean(KEY_BROWSER_LOCK, false).apply() }
                            .show()
                    }
                }'''
if old in s: s = s.replace(old, new, 1)

# Menu Help opens the complete HelpActivity.
s = s.replace('MenuActivity.ACTION_HELP -> AlertDialog.Builder(this).setTitle("Phormi Help").setMessage("Use the address bar to search or open a site. Tabs, Ghost mode, split view, downloads, keyboard tools, privacy, and browser settings are available from the menu.").setPositiveButton("OK", null).show()', 'MenuActivity.ACTION_HELP -> startActivity(Intent(this, HelpActivity::class.java))')

# Selecting a group member returns to the exact normal tab.
if 'select_tab_id' not in s:
    s = s.replace('''        val incomingUrl = intent?.dataString?.trim()
        restoreTabs()''', '''        val incomingUrl = intent?.dataString?.trim()
        restoreTabs()
        intent?.getIntExtra("select_tab_id", -1)?.takeIf { it > 0 }?.let { id ->
            if (tabs.any { it.id == id }) switchToTab(id)
        }''', 1)

# Menu tab-overview results are forwarded by MenuActivity. MainActivity handles the same action set already.
main.write_text(s)

# Make the URL submit control distinct from the dedicated straight refresh control.
layout = root / 'app/src/main/res/layout/activity_main.xml'
xml = layout.read_text()
idx = xml.find('android:id="@+id/btn_go"')
if idx >= 0:
    t = xml.find('android:text="→"', idx)
    if t >= 0: xml = xml[:t] + 'android:text="↵"' + xml[t + len('android:text="→"'):]
layout.write_text(xml)

# The homepage has no Favorites surface.
start_page = root / 'app/src/main/res/layout/activity_start_page.xml'
start_xml = start_page.read_text().replace('Favorites and most-visited sites appear here automatically.', 'Pinned services and most-visited sites appear here automatically. Favorites stay in the current tab and Favorites menu.')
start_page.write_text(start_xml)

# Live download manager surface.
downloads = root / 'app/src/main/java/com/uong/phormi/DownloadsActivity.kt'
downloads.write_text('''package com.uong.phormi

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class DownloadsActivity : AppCompatActivity() {
    data class DownloadItem(val id: Long,val title: String,val status: Int,val localUri: String?,val sourceUrl: String?,val size: Long,val downloaded: Long,val mimeType: String?)
    private val items=mutableListOf<DownloadItem>(); private lateinit var adapter:BaseAdapter; private lateinit var empty:TextView
    private val handler=Handler(Looper.getMainLooper()); private val poll=object:Runnable{override fun run(){if(!isFinishing&&!isDestroyed){loadDownloads();handler.postDelayed(this,700)}}}
    override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);setContentView(R.layout.activity_downloads);findViewById<TextView>(R.id.btn_downloads_back).setOnClickListener{finish()};empty=findViewById(R.id.downloads_empty)
        adapter=object:BaseAdapter(){override fun getCount()=items.size;override fun getItem(p:Int)=items[p];override fun getItemId(p:Int)=items[p].id;override fun getView(p:Int,c:View?,parent:ViewGroup?):View{val v=c?:layoutInflater.inflate(R.layout.item_download,parent,false);val i=items[p];v.findViewById<TextView>(R.id.download_title).text=i.title;v.findViewById<TextView>(R.id.download_status).text=statusText(i);v.setOnClickListener{openDownload(i)};v.setOnLongClickListener{cancelDownload(i);true};return v}}
        findViewById<ListView>(R.id.downloads_list).adapter=adapter;PhormiNotificationCenter.ensureChannels(this);loadDownloads()}
    override fun onResume(){super.onResume();handler.removeCallbacks(poll);handler.post(poll)};override fun onPause(){handler.removeCallbacks(poll);super.onPause()}
    private fun loadDownloads(){items.clear();val m=getSystemService(Context.DOWNLOAD_SERVICE)as DownloadManager;try{m.query(DownloadManager.Query()).use{c->val id=c.getColumnIndex(DownloadManager.COLUMN_ID);val title=c.getColumnIndex(DownloadManager.COLUMN_TITLE);val st=c.getColumnIndex(DownloadManager.COLUMN_STATUS);val uri=c.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI);val src=c.getColumnIndex(DownloadManager.COLUMN_URI);val size=c.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES);val done=c.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR);val mime=c.getColumnIndex(DownloadManager.COLUMN_MEDIA_TYPE);while(c.moveToNext()){val local=if(uri>=0)c.getString(uri)else null;val i=DownloadItem(c.getLong(id),c.getString(title)?.takeIf{it.isNotBlank()}?:local?.let{PhormiFileOpener.displayName(this,Uri.parse(it),"Download")}?:"Download",c.getInt(st),local,if(src>=0)c.getString(src)else null,if(size>=0)c.getLong(size)else-1L,if(done>=0)c.getLong(done)else 0L,if(mime>=0)c.getString(mime)else null);items+=i;if(i.status==DownloadManager.STATUS_SUCCESSFUL)PhormiNotificationCenter.postDownloadEvent(this,i.id,i.title,true)else if(i.status==DownloadManager.STATUS_FAILED)PhormiNotificationCenter.postDownloadEvent(this,i.id,i.title,false)}}}}catch(e:Exception){Toast.makeText(this,"Could not read downloads: ${e.message}",Toast.LENGTH_SHORT).show()};empty.visibility=if(items.isEmpty())View.VISIBLE else View.GONE;adapter.notifyDataSetChanged()}
    private fun statusText(i:DownloadItem):String{val src=i.sourceUrl?.takeIf{it.startsWith("http",true)}?.let{"\\n$it"}.orEmpty();return when(i.status){DownloadManager.STATUS_RUNNING->"Downloading · ${progress(i)} · ${bytes(i.downloaded)} of ${if(i.size>0)bytes(i.size) else "unknown size"}$src";DownloadManager.STATUS_PAUSED->"Paused · ${progress(i)} · ${bytes(i.downloaded)}$src";DownloadManager.STATUS_PENDING->"Waiting to download$src";DownloadManager.STATUS_SUCCESSFUL->"${category(i)} · Completed · ${if(i.size>0)bytes(i.size) else "Completed"}$src";DownloadManager.STATUS_FAILED->"Download failed · ${reason(i)}$src";else->"${category(i)} · Status unavailable$src"}}
    private fun progress(i:DownloadItem)=if(i.size<=0)"Progress unavailable" else "${((i.downloaded*100L)/i.size).coerceIn(0L,100L)}%"
    private fun bytes(v:Long)=when{v<1024->"$v B";v<1024*1024->"${v/1024} KB";v<1024*1024*1024->"${v/(1024*1024)} MB";else->"${v/(1024*1024*1024)} GB"}
    private fun reason(i:DownloadItem)=runCatching{val q=(getSystemService(Context.DOWNLOAD_SERVICE)as DownloadManager).query(DownloadManager.Query().setFilterById(i.id));q.use{if(!it.moveToFirst())return@runCatching"unknown reason";val c=it.getColumnIndex(DownloadManager.COLUMN_REASON);if(c<0)return@runCatching"unknown reason";when(it.getInt(c)){DownloadManager.ERROR_HTTP_DATA_ERROR->"HTTP data error";DownloadManager.ERROR_UNHANDLED_HTTP_CODE->"server HTTP error";DownloadManager.ERROR_INSUFFICIENT_SPACE->"not enough storage";else->"code ${it.getInt(c)}}}}.getOrDefault("unknown reason")
    private fun category(i:DownloadItem)=when{ i.mimeType.orEmpty().startsWith("video/")->"Video";i.mimeType.orEmpty().startsWith("image/")->"Image";i.mimeType.orEmpty().startsWith("audio/")->"Audio";i.mimeType=="application/pdf"||i.title.endsWith(".pdf",true)->"PDF";i.title.endsWith(".zip",true)||i.title.endsWith(".rar",true)||i.title.endsWith(".7z",true)->"Archive";else->"Other" }
    private fun openDownload(i:DownloadItem){if(i.status!=DownloadManager.STATUS_SUCCESSFUL||i.localUri.isNullOrBlank()){Toast.makeText(this,statusText(i),Toast.LENGTH_SHORT).show();return};if(!PhormiFileOpener.open(this,Uri.parse(i.localUri),i.mimeType))Toast.makeText(this,"No installed app can open ${i.title}",Toast.LENGTH_LONG).show()}
    private fun cancelDownload(i:DownloadItem){if(i.status==DownloadManager.STATUS_RUNNING||i.status==DownloadManager.STATUS_PENDING||i.status==DownloadManager.STATUS_PAUSED){(getSystemService(Context.DOWNLOAD_SERVICE)as DownloadManager).remove(i.id);loadDownloads()}}
}
''')
print('Applied menu integration, favorites, retention, desktop, and download-manager repairs')
