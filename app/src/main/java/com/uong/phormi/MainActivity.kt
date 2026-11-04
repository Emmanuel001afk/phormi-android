package com.uong.phormi

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.ComponentName
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Icon
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
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListPopupWindow
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import android.widget.Button
import android.widget.Space
import android.os.Looper
import android.text.Editable
import android.text.Html
import android.text.TextWatcher
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import androidx.webkit.WebSettingsCompat
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
import kotlin.coroutines.resume

@androidx.media3.common.util.UnstableApi
class MainActivity : AppCompatActivity() {

    private data class Tab(
        val id: Int,
        var webView: WebView?,
        var title: String,
        val chipView: View,
        val isGhost: Boolean = false,
        var lastUsed: Long = System.currentTimeMillis(),
        var createdAt: Long = System.currentTimeMillis(),
        var profileName: String = DEFAULT_PROFILE_NAME,
        var searchQuery: String? = null,
        var lastSearchHtml: String? = null,
        var renderedSearchHtml: String? = null,
        val searchDocuments: PhormiSearchDocumentCache = PhormiSearchDocumentCache(),
        var deferredUrl: String? = null,
        var savedState: String? = null,
        var savedScrollY: Int = 0,
        var lastKnownUrl: String = NEW_TAB_URL,
        var documentEpoch: Long = 0,
        var hasDeviceAccess: Boolean = false,
        var discardReason: String? = null
    ) {
        val currentUrl: String get() = PhormiTabOverview.navigationUrl(deferredUrl, lastKnownUrl, webView?.url)
    }

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

    private val sitePermissions by lazy { PhormiSitePermissions(this,
        isActive = { it === activeWebView() },
        onAccessGranted = { view -> tabs.firstOrNull { it.webView === view }?.hasDeviceAccess = true }, locationStore = sitePermissionStore) }
    private val sitePermissionStore by lazy { PhormiSitePermissions.SharedPreferencesStore(this) }
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var fileChooserOwner: Int = -1
    private var fullscreenOwner: Int = -1
    private var contextMenuUrl: String? = null
    private var contextMenuIsImage: Boolean = false
    private var pendingLockedNavigationUrl: String? = null
    private var pendingLockedScopeType: String = PhormiSiteLockManager.SCOPE_TAB
    private var pendingLockedScopeId: String = ""

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
        private const val KEY_TAB_SEARCH_QUERIES = "tab_search_queries"
        private const val WEBVIEW_STATE_MAX_BYTES = 512 * 1024
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
        private const val KEY_DESKTOP_SITES = PhormiDesktopMode.KEY_DESKTOP_HOSTS
        private const val KEY_CUSTOM_SHORTCUTS = "custom_shortcuts"
        private const val KEY_PULL_TO_REFRESH = "pull_to_refresh"
        private const val KEY_TAB_VIEW_MODE = "tab_view_mode"
        private const val KEY_BROWSER_LOCK = BrowserLockManager.PREF_KEY
        private const val REQ_WALLPAPER = 1005
        private const val REQ_MEDIA_PERMISSIONS = 1001
        private const val REQ_FILE_CHOOSER = 1002
        private const val REQ_GEOLOCATION = 1006
        private const val REQ_TABS_OVERVIEW = 1004
        private const val REQ_MENU = 1007
        private const val MENU_OPEN = 1
        private const val MENU_COPY = 2
        private const val MENU_DOWNLOAD = 3
        private const val MENU_OPEN_NEW_TAB = 4
        private const val MENU_OPEN_BACKGROUND_TAB = 5
        private const val MENU_OPEN_GHOST_TAB = 6
        private const val KEY_DESKTOP_ALL = PhormiDesktopMode.KEY_GLOBAL
        private const val KEY_DESKTOP_SITES_OFF = PhormiDesktopMode.KEY_MOBILE_HOSTS
        private const val REQ_NEWS = 1008

        // Sensitive permissions are deliberately requested at the moment a feature
        // needs them (camera/mic for WebView, location for a site prompt, media for
        // picker/notification flows), not as one alarming startup prompt. The
        // previous STARTUP_PERMISSIONS list was dead code that was never requested.
    }

    private val downloadBadgeHandler = Handler(Looper.getMainLooper())
    private val downloadBadgePoll = object : Runnable {
        override fun run() {
            updateDownloadBadge()
            if (browserAiForeground) downloadBadgeHandler.postDelayed(this, 2000L)
        }
    }
    private val tabSaveHandler = Handler(Looper.getMainLooper())
    private var tabSaveRunnable: Runnable? = null
    private var threeFingerStartX = 0f
    private var threeFingerTracking = false
    private val unifiedSearchExecutor = Executors.newFixedThreadPool(11)
    private val aiController by lazy { AiController(applicationContext) }
    private var aiTaskRunning = false
    private var browserAiForeground = false
    private var aiReviewOpen = false
    /** Tabs currently executing AI work. They stay rendered/resumed but transparent to the user. */
    private val aiBackgroundTabIds = java.util.Collections.synchronizedSet(mutableSetOf<Int>())
    private val aiRunningTaskIds = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val mainExecutor = java.util.concurrent.Executor { command -> Handler(Looper.getMainLooper()).post(command) }
    private val unifiedSearchGeneration = AtomicInteger(0)
    private val unifiedSearchLock = Any()
    private val unifiedSearchFutures = mutableMapOf<Int, MutableList<java.util.concurrent.Future<*>>>()
    private val unifiedSearchGenerations = mutableMapOf<Int, Int>()
    private val rendererCrashCounts = mutableMapOf<Int, Int>()
    private var lastHttpErrorKey: String = ""
    private var lastHttpErrorAt: Long = 0L
    private val rendererCrashTimes = mutableMapOf<Int, Long>()

    override fun onResume() {
        super.onResume()
        browserAiForeground = true
        if (::prefs.isInitialized) {
            applyStartPageAppearance()
            applyBrowserChromeAppearance()
            scheduleTabBudget()
            tabs.forEach { it.webView?.let(::applyLiveWebSettings) }
            updateHomeChromeVisibility()
            downloadBadgeHandler.removeCallbacks(downloadBadgePoll)
            downloadBadgeHandler.post(downloadBadgePoll)
            applyKeepScreenOn(prefs.getBoolean(KEY_KEEP_SCREEN_ON, false))
            if (prefs.getBoolean(PhormiDesktopMode.KEY_POLICY_DIRTY, false)) {
                prefs.edit().putBoolean(PhormiDesktopMode.KEY_POLICY_DIRTY, false).apply()
                applyDesktopModeToAllTabs(reloadLiveSites = true)
            }
            PhormiEnvironmentManager.cleanupExpired(this, tabs.map { it.profileName }.toSet())
            pruneExpiredTabs()
            runPendingAiTask()
            updateHomeNewsVisibility()
            refreshHomeNews()
        }
    }

    private fun runPendingAiTask() {
        if (!browserAiForeground || aiTaskRunning || !::prefs.isInitialized) return
        val next = PhormiAiPendingTask.takeImmediate(applicationContext, 1).firstOrNull()
        if (next == null) {
            reviewScheduledAiTasks()
            return
        }
        runAiTask(next)
    }

    private fun reviewScheduledAiTasks() {
        if (aiReviewOpen || isFinishing || isDestroyed) return
        val task = PhormiAiPendingTask.dueForReview(this).firstOrNull() ?: return
        aiReviewOpen = true
        AlertDialog.Builder(this).setTitle("Scheduled AI task is ready")
            .setMessage("${task.instruction.take(280)}\n\nAI will inspect browser page text using your selected provider. Run it now in the open browser?")
            .setPositiveButton("Run now") { _, _ ->
                PhormiAiPendingTask.takeScheduled(this, task.id)?.let(::runAiTask)
            }
            .setNeutralButton("Later", null)
            .setNegativeButton("Cancel task") { _, _ -> PhormiAiPendingTask.cancel(this, task.id) }
            .setOnDismissListener { aiReviewOpen = false }
            .show()
    }

    private fun runAiTask(task: PhormiAiPendingTask.Task) {
        if (aiTaskRunning) return
        aiTaskRunning = true
        updateAiStatusBadge()
        PhormiNotificationManager.post(this, PhormiNotificationManager.CHANNEL_AI, 7101,
            "Phormi AI working", task.instruction.take(75), Intent(this, MainActivity::class.java),
            ongoing = true, autoCancel = false)
        lifecycleScope.launch {
            try {
                runBrowserAiTask(task)
            } catch (t: Throwable) {
                PhormiAiPendingTask.saveStatus(applicationContext,
                    "Browser task stopped: ${t.message?.take(150) ?: "unexpected error"}")
            } finally {
                aiBackgroundTabIds.clear()
                updateAiBackgroundTabVisibility()
                aiTaskRunning = false
                updateAiStatusBadge()
                PhormiNotificationManager.cancel(this@MainActivity, 7101)
                val result = PhormiAiPendingTask.lastStatus(this@MainActivity)
                PhormiNotificationManager.post(this@MainActivity, PhormiNotificationManager.CHANNEL_AI,
                    task.id.hashCode(), "Phormi AI task update", result.take(150),
                    Intent(this@MainActivity, AiActivity::class.java))
                if (browserAiForeground) {
                    Toast.makeText(this@MainActivity, "AI update: ${result.take(100)}", Toast.LENGTH_LONG).show()
                    runPendingAiTask()
                }
            }
        }
    }

    private suspend fun runBrowserAiTask(task: PhormiAiPendingTask.Task) {
        if (!aiController.isActive()) {
            PhormiAiPendingTask.saveStatus(this, "AI is inactive. Configure a provider before running tasks.")
            return
        }
        var targetId = task.target.removePrefix("tab:").toIntOrNull()
            ?: activeTabId
        if (tabs.none { it.id == targetId && !it.isGhost }) {
            PhormiAiPendingTask.saveStatus(this, "The selected normal tab is no longer available.")
            return
        }
        val goal = task.instruction + if (task.target.startsWith("tab:"))
            "\nStart in tab $targetId; you may work across other normal tabs if needed." else ""
        var previous = ""
        for (step in 1..30) {
            if (!browserAiForeground) {
                PhormiAiPendingTask.saveStatus(this, "Task paused when the browser left the foreground. Review and run it again if needed.")
                return
            }
            val tab = tabs.firstOrNull { it.id == targetId && !it.isGhost } ?: run {
                PhormiAiPendingTask.saveStatus(this, "AI tab was closed; task stopped.")
                return
            }
            aiBackgroundTabIds.clear()
            if (tab.id != activeTabId) aiBackgroundTabIds.add(tab.id)
            updateAiBackgroundTabVisibility()
            var action = aiController.planBrowserAction(goal, readAiTabCatalog(), readAiTabScreen(tab.id), previous)
            if (action == null) {
                PhormiAiPendingTask.saveStatus(this, "AI could not plan the next action. Check your connection.")
                return
            }
            var actionName = action.optString("action").lowercase(Locale.US)
            if (actionName == "open_tab") {
                val url = action.optString("url").trim()
                if (!PhormiBrowserAiSafety.allowedUrl(url)) {
                    PhormiAiPendingTask.saveStatus(this, "AI supplied an unsafe or invalid URL.")
                    return
                }
                if (tabs.count { !it.isGhost } >= 20) {
                    PhormiAiPendingTask.saveStatus(this, "AI tab limit reached (20). Close a tab and retry.")
                    return
                }
                createNewTab(url, activate = false)
                targetId = tabs.last().id
                previous = "Opened tab $targetId for ${Uri.parse(url).host.orEmpty()}."
                continue
            }
            val proposed = action.optInt("tabId", targetId)
            if (proposed != targetId && tabs.any { it.id == proposed && !it.isGhost }) {
                targetId = proposed
                // Inspect the destination before acting; a plan based on the previous tab
                // must never click or type in a different tab without seeing its page.
                action = aiController.planBrowserAction(goal, readAiTabCatalog(), readAiTabScreen(targetId), previous)
                if (action == null || action.optInt("tabId", targetId) != targetId) {
                    PhormiAiPendingTask.saveStatus(this, "AI could not safely inspect the chosen tab.")
                    return
                }
                actionName = action.optString("action").lowercase(Locale.US)
            } else if (proposed != targetId) {
                PhormiAiPendingTask.saveStatus(this, "AI requested a tab that is not available.")
                return
            }
            if (actionName == "done" || actionName == "stuck") {
                val summary = PhormiBrowserAiSafety.safeSummary(action.optString("summary",
                    if (actionName == "done") "Task completed." else "Task could not continue."))
                PhormiAiPendingTask.saveStatus(this, summary)
                return
            }
            if (actionName == "switch_tab") {
                previous = "Now inspecting tab $targetId."
                continue
            }
            if (!browserAiForeground) return
            val maySubmitForm = actionName == "click" && aiClickMaySubmit(targetId, action.optString("text"))
            if ((PhormiBrowserAiSafety.requiresConfirmation(actionName, action.optString("text")) || maySubmitForm) &&
                !confirmAiAction(targetId, actionName, action.optString("text"))) {
                PhormiAiPendingTask.saveStatus(this, "Action not approved; no further browser actions were taken.")
                return
            }
            val ok = executeAiBrowserAction(targetId, action)
            previous = (previous + "\nStep $step: $actionName on tab $targetId (${if (ok) "ok" else "failed"}).").takeLast(900)
            PhormiAiPendingTask.saveStatus(this, previous.takeLast(160))
            if (!ok) return
            kotlinx.coroutines.delay(if (actionName in setOf("navigate", "click", "press_enter")) 900L else 350L)
        }
        PhormiAiPendingTask.saveStatus(this, "AI stopped after 30 actions. Review the page before starting again.")
    }

    /** A harmless-looking button label may still submit a payment, message or account form. */
    private suspend fun aiClickMaySubmit(tabId: Int, label: String): Boolean {
        val view = tabs.firstOrNull { it.id == tabId && !it.isGhost }?.webView ?: return true
        val quoted = JSONObject.quote(label.trim().take(160))
        return kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            view.evaluateJavascript("""(function(){var q=$quoted.toLowerCase();
                var es=Array.from(document.querySelectorAll('button,a,[role=button],input[type=button],input[type=submit],label'))
                    .filter(function(e){return e.getClientRects().length && !e.disabled});
                function text(e){return ((e.innerText||e.value||e.getAttribute('aria-label')||e.title||'')+'').trim().toLowerCase()}
                var e=es.find(function(x){return text(x)===q})||es.find(function(x){return text(x).includes(q)&&q.length>=3});
                return !e || !!e.closest('form') || e.type==='submit' || e.getAttribute('role')==='button' && !!e.closest('[role=dialog]')})()""") { raw ->
                if (continuation.isActive) continuation.resume(raw != "false") {}
            }
        }
    }

    private suspend fun confirmAiAction(tabId: Int, action: String, label: String): Boolean =
        kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            val title = tabs.firstOrNull { it.id == tabId }?.title.orEmpty().take(45)
            val dialog = AlertDialog.Builder(this)
                .setTitle("Allow AI to ${action.replace('_', ' ')}?")
                .setMessage("Tab: $title\nControl: ${label.take(100).ifBlank { "focused field / form" }}\n\nSensitive actions require your approval each time.")
                .setPositiveButton("Allow once") { _, _ -> if (continuation.isActive) continuation.resume(true) {} }
                .setNegativeButton("Stop task") { _, _ -> if (continuation.isActive) continuation.resume(false) {} }
                .setOnCancelListener { if (continuation.isActive) continuation.resume(false) {} }
                .create()
            continuation.invokeOnCancellation { dialog.dismiss() }
            dialog.show()
        }

    private fun readAiTabCatalog(): String = tabs.filterNot { it.isGhost }.take(20).joinToString("\n") { tab ->
        // Keep other tabs' page contents and URL query tokens out of the planner prompt.
        val host = Uri.parse(tab.currentUrl).host.orEmpty()
        "TAB ${tab.id} | title=${tab.title.take(90)} | host=$host"
    }

    private suspend fun readAiTabScreen(tabId: Int): String {
        val tab = tabs.firstOrNull { it.id == tabId && !it.isGhost } ?: return "TAB_NOT_FOUND"
        if (tab.deferredUrl != null) { resumeDeferredTab(tab); return "TAB_LOADING: retry reading after the page has loaded" }
        val view = tab.webView ?: return "TAB_LOADING"
        return kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            view.onResume()
            view.evaluateJavascript(
                """(function(){
                    function privateField(e){
                      var hint=((e.type||'')+' '+(e.name||'')+' '+(e.autocomplete||'')).toLowerCase();
                      return /password|one.time.code|otp|pin|credit.card|cc.number|cc.csc|cvv|secret|token/.test(hint);
                    }
                    var text=(document.body&&document.body.innerText||'').slice(0,12000)
                      .replace(/\\b(?:\\d[ -]?){12,19}\\b/g,'[private number]');
                    return JSON.stringify({title:document.title||'',url:location.href.split(/[?#]/)[0],text:text,
                      controls:Array.from(document.querySelectorAll('input,textarea,button,a,select,[role=button],[contenteditable=true]'))
                        .filter(function(e){return !privateField(e)})
                        .slice(0,120).map(function(e){return {tag:e.tagName,
                          text:(e.tagName==='INPUT'||e.tagName==='TEXTAREA'?'':e.innerText||'').trim().slice(0,100),
                          label:(e.getAttribute('aria-label')||e.getAttribute('placeholder')||e.name||'').slice(0,100),
                          editable:!!(e.isContentEditable||e.tagName==='INPUT'||e.tagName==='TEXTAREA')}})})
                })()""".trimIndent()
            ) { raw ->
                val decoded = runCatching { org.json.JSONTokener(raw).nextValue().toString() }.getOrDefault(raw)
                if (continuation.isActive) continuation.resume(decoded) {}
            }
        }
    }

    private suspend fun executeAiBrowserAction(tabId: Int, action: JSONObject): Boolean {
        val tab = tabs.firstOrNull { it.id == tabId && !it.isGhost } ?: return false
        if (tab.deferredUrl != null) { resumeDeferredTab(tab); return false }
        val view = tab.webView ?: return false
        return kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            val script = when (action.optString("action").lowercase(Locale.US)) {
                "navigate" -> {
                    val url = action.optString("url").trim()
                    if (!PhormiBrowserAiSafety.allowedUrl(url)) {
                        continuation.resume(false) {}; return@suspendCancellableCoroutine
                    }
                    view.loadUrl(url)
                    continuation.resume(true) {}; return@suspendCancellableCoroutine
                }
                "click" -> {
                    val label = JSONObject.quote(action.optString("text").trim().take(160))
                    """(function(){var q=$label.toLowerCase();if(!q)return false;
                       var es=Array.from(document.querySelectorAll('button,a,[role=button],input[type=button],input[type=submit],label'))
                         .filter(function(e){return e.getClientRects().length && !e.disabled});
                       function text(e){return ((e.innerText||e.value||e.getAttribute('aria-label')||e.title||'')+'').trim().toLowerCase()}
                       var e=es.find(function(x){return text(x)===q})||es.find(function(x){return text(x).includes(q)&&q.length>=3});
                       if(!e||e.closest('form')&&e.closest('form').querySelector('input[type=password]'))return false;
                       e.click();return true})()""".trimIndent()
                }
                "type" -> {
                    val value = JSONObject.quote(action.optString("text").take(3000))
                    val field = JSONObject.quote(action.optString("field").trim().take(100))
                    """(function(){var name=$field.toLowerCase();var es=Array.from(document.querySelectorAll('textarea,input,[contenteditable=true]'));
                       var e=name?es.find(function(x){return ((x.getAttribute('aria-label')||'')+' '+(x.placeholder||'')+' '+(x.name||'')).toLowerCase().includes(name)}):document.activeElement;
                       if(!e||!es.includes(e))e=es.find(function(x){return x.getClientRects().length&&!x.disabled});
                       if(!e||!e.getClientRects().length||e.disabled||e.readOnly)return false;
                       var kind=((e.type||'')+' '+(e.name||'')+' '+(e.autocomplete||'')).toLowerCase();
                       if(/password|otp|one.time.code|pin|cc.number|cc.csc|cvv|credit.card|secret|token/.test(kind))return false;
                       e.focus();var v=$value;
                       if(e.isContentEditable){e.textContent=v}else{var setter=Object.getOwnPropertyDescriptor(e.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype,'value');
                         if(setter&&setter.set)setter.set.call(e,v);else e.value=v}
                       e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));return true})()""".trimIndent()
                }
                "press_enter" -> """(function(){var e=document.activeElement;if(!e)return false;
                    if(e.form){if(e.form.querySelector('input[type=password]'))return false;
                      if(e.form.requestSubmit)e.form.requestSubmit();else e.form.submit();return true}
                    e.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',code:'Enter',bubbles:true}));return true})()"""
                "scroll" -> if (action.optString("direction", "down").equals("up", true))
                    "window.scrollBy(0,-650);true" else "window.scrollBy(0,650);true"
                "back" -> {
                    if (view.canGoBack()) view.goBack() else { continuation.resume(false) {}; return@suspendCancellableCoroutine }
                    continuation.resume(true) {}; return@suspendCancellableCoroutine
                }
                "wait" -> { continuation.resume(true) {}; return@suspendCancellableCoroutine }
                else -> { continuation.resume(false) {}; return@suspendCancellableCoroutine }
            }
            view.onResume()
            view.evaluateJavascript(script) { raw ->
                if (continuation.isActive) continuation.resume(raw == "true" || raw == "1") {}
            }
        }
    }

    private fun updateAiStatusBadge() {
        if (!::prefs.isInitialized) return
        findViewById<TextView>(R.id.btn_bottom_ai)?.apply {
            text = if (aiTaskRunning) "✦•" else "✦"
            contentDescription = if (aiTaskRunning) "Browser AI working · tap for task status" else "Ask Phormi AI"
        }
        findViewById<TextView>(R.id.btn_top_ai)?.apply {
            text = if (aiTaskRunning) "✦•" else "✦"
            contentDescription = if (aiTaskRunning) "Browser AI working · tap for task status" else "Ask Phormi AI"
        }
    }

    private fun updateAiBackgroundTabVisibility() {
        if (!::webViewContainer.isInitialized) return
        val visible = if (splitMode) setOf(activeTabId, splitTopTabId, splitBottomTabId) else setOf(activeTabId)
        tabs.forEach { tab ->
            val background = tab.id in aiBackgroundTabIds && tab.id !in visible
            val live = tab.id in visible || background
            if (live) resumeDeferredTab(tab)
            val view = tab.webView ?: return@forEach
            view.visibility = if (live) View.VISIBLE else View.GONE
            view.alpha = if (background) 0f else 1f
            view.isClickable = !background
            view.isFocusable = tab.id == activeTabId
            if (live) view.onResume() else view.onPause()
        }
        // An invisible background WebView must never cover the user's current tab.
        activeWebView()?.takeIf { it.parent === webViewContainer }?.bringToFront()
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
        PhormiNotificationManager.ensureChannels(this)

        swipeRefresh = findViewById(R.id.swipe_refresh)
        webViewContainer = findViewById(R.id.webview_container)
        tabStripContainer = findViewById<View>(R.id.tab_strip).findViewById(R.id.tab_strip_container)
        urlBar = findViewById(R.id.url_bar)
        configureOmnibox()
        findViewById<View>(R.id.btn_site_info).setOnClickListener { showSiteInfo() }
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

        val selectedProvider = prefs.getString(KEY_SEARCH_PROVIDER, "Google") ?: "Google"
        findViewById<TextView>(R.id.start_page_engine).text = "$selectedProvider  ▾"



        findViewById<TextView>(R.id.btn_menu).setOnClickListener {
            val menuPage = activeWebView()
            val menuUrl = menuPage?.url.orEmpty()
            startActivityForResult(Intent(this, MenuActivity::class.java).apply {
                putExtra(MenuActivity.EXTRA_PAGE_URL, menuUrl)
                putExtra(MenuActivity.EXTRA_DESKTOP_ENABLED, isDesktopModeFor(menuUrl))
                putExtra(MenuActivity.EXTRA_CAN_GO_BACK, menuPage?.canGoBack() == true)
                putExtra(MenuActivity.EXTRA_CAN_GO_FORWARD, menuPage?.canGoForward() == true)
            }, REQ_MENU)
        }

        findViewById<TextView>(R.id.btn_home).setOnClickListener {
            showStartPage()
        }

        findViewById<TextView>(R.id.btn_back).setOnClickListener {
            activeWebView()?.let { navigateBack(it) }
        }

        findViewById<TextView>(R.id.btn_forward).setOnClickListener {
            activeWebView()?.let { navigateForward(it) }
        }
        findViewById<TextView>(R.id.btn_top_back).setOnClickListener {
            activeWebView()?.let { navigateBack(it) }
        }
        findViewById<TextView>(R.id.btn_top_forward).setOnClickListener {
            activeWebView()?.let { navigateForward(it) }
        }
        findViewById<TextView>(R.id.btn_top_ai).setOnClickListener { openAiFromBottom(it) }
        findViewById<TextView>(R.id.btn_top_downloads).setOnClickListener { openDownloadsFromBottom(it) }
        findViewById<TextView>(R.id.download_status_chip).setOnClickListener { openDownloadsFromBottom(it) }
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
            startActivity(Intent(this, AiActivity::class.java).putExtra("target_tab_id", activeTabId))
        }

        findViewById<TextView>(R.id.start_page_action_tabs).setOnClickListener {
            saveTabsImmediate(true)
            startActivityForResult(Intent(this, TabsOverviewActivity::class.java), REQ_TABS_OVERVIEW)
        }
        findViewById<TextView>(R.id.start_page_action_bookmarks).setOnClickListener {
            startActivityForResult(Intent(this, BookmarksActivity::class.java), REQ_MENU)
        }
        findViewById<TextView>(R.id.start_page_action_downloads).setOnClickListener { openDownloadsFromBottom(it) }
        findViewById<TextView>(R.id.start_page_action_ai).setOnClickListener { openAiFromBottom(it) }

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
        // Personal Quick Access contains only user-created shortcuts; Favorites and Most Visited stay in their own sections.
        loadQuickAccessRows()
        updateHomeChromeVisibility()

        // + = new tab; tabs button = circular tab overview
        findViewById<TextView>(R.id.btn_new_tab).setOnClickListener {
            createNewTab(NEW_TAB_URL)
            focusNewTabSearch()
        }
        // The circular count is the single combined Tab Overview + Counter control.
        findViewById<TextView>(R.id.tab_count).setOnClickListener {
            saveTabsImmediate(true)
            startActivityForResult(Intent(this, TabsOverviewActivity::class.java), REQ_TABS_OVERVIEW)
        }

        findViewById<TextView>(R.id.btn_refresh).setOnClickListener {
            val page = activeWebView()
            if (page != null && page.progress < 100) { page.stopLoading(); updatePageProgress(page, 100) } else refreshActivePage()
        }
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

        swipeRefresh.setOnRefreshListener { refreshActivePage() }
        swipeRefresh.setDistanceToTriggerSync(160.dp())
        swipeRefresh.isEnabled = PhormiBrowserPreferences.pullToRefresh(this)
        swipeRefresh.setOnChildScrollUpCallback { _, _ ->
            val wv = activeWebView() ?: return@setOnChildScrollUpCallback false
            wv.canScrollVertically(-1)
        }

        // Sensitive permissions are requested only when a site actually needs them.

        // Keep WebView sessions (including website cookies/sign-ins) persisted across
        // Activity pauses and app restarts. This is the browser-style "sign in once"
        // behavior the app needs; each website still controls its own authentication.
        CookieManager.getInstance().flush()

        val startupAction = intent?.getStringExtra("action").orEmpty()
        val startupTabId = intent?.getIntExtra("tab_id", -1) ?: -1
        val incomingUrl = intent?.getStringExtra("open_url")?.trim().takeIf { !it.isNullOrBlank() }
            ?: intent?.dataString?.trim()
        val incomingProfile = intent?.getStringExtra("open_profile")?.trim().takeIf { !it.isNullOrBlank() }
        restoreTabs()

        // Handle browser-routing intents here as well as in onNewIntent(). This matters
        // when MainActivity has been recreated rather than receiving an existing-instance intent.
        when (startupAction) {
            "new_tab" -> createNewTab(NEW_TAB_URL, requestedProfile = incomingProfile)
            "select" -> if (startupTabId > 0) tabs.firstOrNull { it.id == startupTabId }?.let { switchToTab(it.id) }
            "close" -> if (startupTabId > 0) tabs.firstOrNull { it.id == startupTabId }?.let { closeTab(it.id) }
            "open_group" -> openGroupById(intent?.getStringExtra("group_id").orEmpty())
            "close_group" -> closeGroupById(intent?.getStringExtra("group_id").orEmpty())
        }
        if (incomingUrl != null && (incomingUrl.startsWith("http://") || incomingUrl.startsWith("https://"))) {
            createNewTab(incomingUrl, requestedProfile = incomingProfile)
        }
    }

    private fun showTabRetentionChooser() {
        val values = arrayOf(RETENTION_1_MONTH, RETENTION_3_MONTHS, RETENTION_1_YEAR, RETENTION_NEVER)
        val labels = arrayOf("1 month", "3 months", "1 year", "Off / Never")
        val current = prefs.getString(KEY_TAB_RETENTION, RETENTION_NEVER) ?: RETENTION_NEVER
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 8, 24, 8)
        }
        content.addView(TextView(this).apply {
            text = "Automatically close normal tabs after they have been inactive for the selected period. Opening a tab resets its inactivity clock."
            setTextColor(PhormiUiColors.secondary(this@MainActivity))
            setPadding(0, 0, 0, 12)
        })
        val dialog = AlertDialog.Builder(this).setTitle("Tab retention").setView(content).create()
        labels.forEachIndexed { index, label ->
            val button = Button(this).apply {
                text = if (values[index] == current) "✓ " + label else label
                isAllCaps = false
                setOnClickListener {
                    prefs.edit().putString(KEY_TAB_RETENTION, values[index]).apply()
                    pruneExpiredTabs()
                    dialog.dismiss()
                    Toast.makeText(this@MainActivity, "Tab retention: " + label, Toast.LENGTH_SHORT).show()
                }
            }
            content.addView(button, LinearLayout.LayoutParams(-1, 52).apply { topMargin = 4; bottomMargin = 4 })
        }
        dialog.show()
    }

    private fun showPullToRefreshChooser() {
        val labels = arrayOf("Arrow refresh only", "Arrow + pull down to refresh")
        val current = if (PhormiBrowserPreferences.pullToRefresh(this)) 1 else 0
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
        val daily = prefs.getBoolean(KEY_DAILY_ACCENT, false)
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
            PhormiUiColors.accent(this),
            PhormiUiColors.coralText(this),
            PhormiUiColors.success(this),
            PhormiUiColors.warning(this)
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

        val background = PhormiUiColors.background(this)
        val primary = PhormiUiColors.text(this)
        val secondary = PhormiUiColors.secondary(this)
        val accent = if (prefs.getBoolean(KEY_DAILY_ACCENT, false)) dailyAccent() else PhormiUiColors.accent(this)

        root.setBackgroundColor(background)
        findViewById<TextView>(R.id.start_page_title)?.setTextColor(primary)
        findViewById<TextView>(R.id.start_page_subtitle)?.setTextColor(secondary)
        findViewById<TextView>(R.id.start_page_quick_access)?.setTextColor(secondary)
        findViewById<TextView>(R.id.start_page_ai)?.setTextColor(primary)
        findViewById<TextView>(R.id.start_page_engine)?.setTextColor(secondary)
        findViewById<EditText>(R.id.start_page_search)?.setTextColor(primary)
        findViewById<EditText>(R.id.start_page_search)?.setHintTextColor(secondary)

        val surface = PhormiUiColors.surface(this)
        val surfaceSoft = PhormiUiColors.surfaceSoft(this)
        val outline = PhormiUiColors.border(this)
        styleSurface(findViewById(R.id.start_page_search_box), surface, outline, 28)
        styleSurface(findViewById(R.id.start_page_ai), surfaceSoft, outline, 22)
        listOf(R.id.start_page_action_tabs, R.id.start_page_action_bookmarks,
            R.id.start_page_action_downloads, R.id.start_page_action_ai).forEach { id ->
            styleSurface(findViewById(id), surfaceSoft, outline, 16)
            findViewById<TextView>(id)?.setTextColor(if (id == R.id.start_page_action_ai) accent else primary)
        }

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
        val background = PhormiUiColors.background(this)
        val toolbar = PhormiUiColors.surface(this)
        val text = PhormiUiColors.text(this)
        val secondary = PhormiUiColors.secondary(this)
        findViewById<View>(R.id.main_root)?.setBackgroundColor(background)
        findViewById<View>(R.id.top_toolbar)?.setBackgroundColor(toolbar)
        findViewById<View>(R.id.tab_strip)?.setBackgroundColor(background)
        styleSurface(findViewById(R.id.url_toolbar), PhormiUiColors.surfaceSoft(this), PhormiUiColors.border(this), 24)
        findViewById<View>(R.id.bottom_toolbar)?.setBackgroundColor(toolbar)
        listOf(R.id.btn_home, R.id.btn_new_tab, R.id.btn_menu, R.id.btn_refresh,
            R.id.btn_back, R.id.btn_forward, R.id.btn_top_back, R.id.btn_top_forward,
            R.id.btn_top_ai, R.id.btn_bottom_ai, R.id.btn_bottom_downloads).forEach { id ->
            findViewById<TextView>(id)?.setTextColor(if (id == R.id.btn_new_tab || id == R.id.btn_bottom_ai || id == R.id.btn_top_ai)
                (if (prefs.getBoolean(KEY_DAILY_ACCENT, false)) dailyAccent() else PhormiUiColors.accent(this)) else text)
        }
        findViewById<TextView>(R.id.tab_count)?.setTextColor(
            if (prefs.getBoolean(KEY_DAILY_ACCENT, false)) dailyAccent() else PhormiUiColors.accent(this)
        )
        findViewById<EditText>(R.id.url_bar)?.apply {
            setTextColor(text)
            setHintTextColor(secondary)
        }
        findViewById<TextView>(R.id.btn_go)?.setTextColor(
            if (prefs.getBoolean(KEY_DAILY_ACCENT, false)) dailyAccent() else PhormiUiColors.accent(this)
        )
    }

    private fun cssColor(color: Int): String = String.format(java.util.Locale.ROOT, "#%06X", 0xFFFFFF and color)

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
        return when (prefs.getString(KEY_SEARCH_PROVIDER, "Google")) {
            PHORMI_SEARCH -> SEARCH_URL + encoded
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

        // Quick Access is intentionally user-controlled. Do not inject Favorites,
        // Most Visited, browsing history, or a fixed list of websites here.
        // Entries come only from the user's saved/pinned shortcuts plus the Add action.
        val personal = mutableListOf<QuickSite>()
        val custom = runCatching {
            JSONArray(prefs.getString(KEY_CUSTOM_SHORTCUTS, "[]") ?: "[]")
        }.getOrElse { JSONArray() }

        for (i in custom.length() - 1 downTo 0) {
            val item = custom.optJSONObject(i) ?: continue
            val name = item.optString("name").trim()
            val url = item.optString("url").trim()
            if (name.isNotBlank() && url.isNotBlank() &&
                personal.none { it.url.equals(url, ignoreCase = true) }) {
                personal += QuickSite(name, url, "⌂", true)
            }
        }

        val all = personal.take(18) + QuickSite("Add", "", "+", false)
        val columns = when {
            resources.displayMetrics.widthPixels >= 900 -> 8
            resources.displayMetrics.widthPixels >= 600 -> 7
            else -> 6
        }

        all.chunked(columns).forEach { batch ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    60.dp()
                )
            }
            batch.forEach { site ->
                row.addView(
                    makeQuickSiteView(site),
                    LinearLayout.LayoutParams(0, 56.dp(), 1f).apply {
                        leftMargin = 2.dp()
                        rightMargin = 2.dp()
                    }
                )
            }
            repeat(columns - batch.size) {
                row.addView(Space(this), LinearLayout.LayoutParams(0, 56.dp(), 1f))
            }
            rows.addView(row)
        }
    }

    private fun makeQuickSiteView(site: QuickSite): TextView {
        val surfaceSoft = PhormiUiColors.surfaceSoft(this)
        val outline = PhormiUiColors.border(this)
        return TextView(this).apply {
            gravity = android.view.Gravity.CENTER
            text = if (site.tag.isNotBlank()) site.tag + "\n" + site.name.take(9) else site.name.take(9)
            setTextColor(if (site.tag == "+") PhormiUiColors.accent(this@MainActivity) else PhormiUiColors.text(this@MainActivity))
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
                    "◇" -> PhormiQuickAccessState.hideFavorite(this, site.url)
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
                // Fetch the icon only from the site itself. Sending every shortcut host
                // to Google's s2 favicon service leaked browsing interests to a third
                // party; there is no reason a browser needs a favicon proxy.
                val candidates = listOf("https://$host/favicon.ico")
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
        val current = prefs.getString(KEY_SEARCH_PROVIDER, "Google") ?: "Google"
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

    private fun runUnifiedSearch(query: String) {
        if (!prefs.getBoolean("multi_engine_consent_v1", false)) {
            AlertDialog.Builder(this).setTitle("Use multi-engine search?")
                .setMessage("Phormi Search sends your query to ${PhormiSearchProviders.all.size} providers, including Google, Bing and DuckDuckGo. This uses more data and providers may block automated results. You can change engines from the home search selector.")
                .setNegativeButton("Use Google") { _, _ ->
                    prefs.edit().putString(KEY_SEARCH_PROVIDER, "Google").apply()
                    findViewById<TextView>(R.id.start_page_engine).text = "Google  ▾"
                    urlBar.setText(query); navigateFromUrlBar()
                }
                .setPositiveButton("Use Phormi Search") { _, _ -> prefs.edit().putBoolean("multi_engine_consent_v1", true).apply(); runUnifiedSearch(query) }.show()
            return
        }
        if (activeWebView() == null) createNewTab(NEW_TAB_URL)
        val tab = tabs.firstOrNull { it.id == activeTabId } ?: return
        resumeDeferredTab(tab)
        val webView = tab.webView ?: return
        tab.searchQuery = query
        val generation = unifiedSearchGeneration.incrementAndGet()
        // Each tab owns its own search and results. Starting another search in a new tab
        // must not cancel the first tab's work or overwrite its address bar.
        synchronized(unifiedSearchLock) {
            unifiedSearchFutures.remove(tab.id)?.forEach { it.cancel(true) }
            unifiedSearchGenerations[tab.id] = generation
        }
        startPageContainer.visibility = View.GONE
        startPageContainer.isClickable = false
        webView.visibility = View.VISIBLE
        webView.isClickable = true
        urlBar.setText(query)
        hideKeyboard()
        urlBar.clearFocus()

        val providers = PhormiSearchProviders.all

        val searchUrl = PhormiSearchHistory.searchUrl(query)
        val loadingHtml = unifiedSearchHtml(query, emptyList(), true, 0, providers.size)
        tab.searchDocuments.put(searchUrl, loadingHtml)
        tab.lastSearchHtml = loadingHtml
        recordTabNavigation(tab, searchUrl)
        updateHomeChromeVisibility()
        saveTabsImmediate(false)
        // A real local history URL lets Back/Forward restore this exact search entry.
        // The document itself is served from the per-tab cache by shouldInterceptRequest;
        // phormi.local is never resolved on the network.
        if (webView.url == searchUrl) webView.reload() else webView.loadUrl(searchUrl)

        val remaining = AtomicInteger(providers.size)
        val all = java.util.Collections.synchronizedList(mutableListOf<PhormiSearchResult>())
        val successfulEngines = java.util.Collections.synchronizedSet(mutableSetOf<String>())
        val finishedEngines = java.util.Collections.synchronizedSet(mutableSetOf<String>())
        // The old code reloaded the whole document after every provider finished (up to
        // 11 times), resetting scroll position and in-page state each time. Render one
        // interim result set as soon as anything arrives, then once at the end.
        val renderedInterim = java.util.concurrent.atomic.AtomicBoolean(false)

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
                val mergedSnapshot = mergePhormiSearchResults(snapshot, query)
                runOnUiThread {
                    if (unifiedSearchGenerations[tab.id] != generation || tabs.none { it.webView === webView } || tab.searchQuery != query) return@runOnUiThread
                    val finalPass = done == providers.size
                    val firstResults = mergedSnapshot.isNotEmpty() && renderedInterim.compareAndSet(false, true)
                    if (!finalPass && !firstResults) return@runOnUiThread
                    val html = unifiedSearchHtml(
                        query, mergedSnapshot, !finalPass, done, providers.size,
                        successfulSnapshot, finishedSnapshot
                    )
                    tab.searchDocuments.put(searchUrl, html)
                    tab.lastSearchHtml = html
                    // Reload the same history entry instead of pushing a new one for
                    // every interim/final render. If the reader already opened a result,
                    // keep the cache warm for Back without stealing the current page.
                    if (webView.url == searchUrl && tab.searchQuery == query) webView.reload()
                    if (finalPass && mergedSnapshot.isNotEmpty() && aiController.isActive()) {
                        lifecycleScope.launch {
                            val evidence = mergedSnapshot.take(12).joinToString("\n") {
                                "${it.title}\n${it.url}\n${it.snippet}\nSources: ${it.source}"
                            }
                            val answer = aiController.synthesizeSearchAnswer(query, evidence)
                            if (unifiedSearchGenerations[tab.id] != generation || tabs.none { it.webView === webView } || tab.searchQuery != query) return@launch
                            if (answer != null) {
                                val aiHtml = unifiedSearchHtml(query, mergedSnapshot, false, providers.size, providers.size, successfulSnapshot, finishedSnapshot, answer)
                                tab.searchDocuments.put(searchUrl, aiHtml)
                                tab.lastSearchHtml = aiHtml
                                webView.evaluateJavascript("""
                                    (function(){var section=document.getElementById('ai-answer');var text=document.getElementById('ai-answer-text');if(section&&text){text.textContent=${JSONObject.quote(answer)};section.hidden=false;}})();
                                """.trimIndent(), null)
                            }
                        }
                    }
                }
            }
            synchronized(unifiedSearchLock) { unifiedSearchFutures.getOrPut(tab.id) { mutableListOf() }.add(future) }
        }
    }

    private fun fetchProviderResults(provider: PhormiSearchProvider, query: String): List<PhormiSearchResult> {
        return try {
            val connection = (URL(provider.fetchUrl(query)).openConnection() as HttpURLConnection).apply {
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

    private fun parseSearchHtml(source: String, html: String): List<PhormiSearchResult> {
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

    private fun parseDuckDuckGo(html: String, source: String): List<PhormiSearchResult> {
        val results = mutableListOf<PhormiSearchResult>()
        val pattern = Regex("""(?is)<a[^>]+class=["']result__a["'][^>]+href=["']([^"']+)["'][^>]*>(.*?)</a>""")
        val snippetPattern = Regex("""(?is)<(?:a|div|p)[^>]+class=["'][^"']*result__snippet[^"']*["'][^>]*>(.*?)</(?:a|div|p)>""")
        pattern.findAll(html).take(12).forEachIndexed { index, match ->
            val url = normalizeSearchUrl(match.groupValues[1])
            val title = cleanHtmlText(match.groupValues[2])
            if (isUsableSearchResult(url, title, source, results)) {
                val tail = html.substring(match.range.last + 1, (match.range.last + 1800).coerceAtMost(html.length))
                val snippet = PhormiSearchText.cleanSnippet(
                    cleanHtmlText(snippetPattern.find(tail)?.groupValues?.get(1).orEmpty()), title
                )
                results += PhormiSearchResult(title, url, snippet, source, index)
            }
        }
        return results
    }

    private fun parseBing(html: String, source: String): List<PhormiSearchResult> {
        val results = mutableListOf<PhormiSearchResult>()
        val blockPattern = Regex("""(?is)<li[^>]+class=["'][^"']*b_algo[^"']*["'][^>]*>(.*?)</li>""")
        val titlePattern = Regex("""(?is)<h2[^>]*>\s*<a[^>]+href=["']([^"']+)["'][^>]*>(.*?)</a>""")
        blockPattern.findAll(html).take(12).forEachIndexed { index, block ->
            val m = titlePattern.find(block.groupValues[1]) ?: return@forEachIndexed
            val url = normalizeSearchUrl(m.groupValues[1])
            val title = cleanHtmlText(m.groupValues[2])
            val snippet = PhormiSearchText.cleanSnippet(
                cleanHtmlText(Regex("""(?is)<p[^>]*>(.*?)</p>""").find(block.groupValues[1])?.groupValues?.get(1).orEmpty()),
                title
            )
            if (isUsableSearchResult(url, title, source, results)) results += PhormiSearchResult(title, url, snippet, source, index)
        }
        return results
    }

    private fun parseBrave(html: String, source: String): List<PhormiSearchResult> {
        val results = mutableListOf<PhormiSearchResult>()
        val pattern = Regex("""(?is)<a[^>]+href=["']([^"']+)["'][^>]*class=["'][^"']*(?:result-header|snippet-title)[^"']*["'][^>]*>(.*?)</a>""")
        val snippetPattern = Regex("""(?is)<(?:div|p|span)[^>]+class=["'][^"']*(?:snippet-description|snippet-content)[^"']*["'][^>]*>(.*?)</(?:div|p|span)>""")
        pattern.findAll(html).take(12).forEachIndexed { index, match ->
            val url = normalizeSearchUrl(match.groupValues[1])
            val title = cleanHtmlText(match.groupValues[2])
            if (isUsableSearchResult(url, title, source, results)) {
                val tail = html.substring(match.range.last + 1, (match.range.last + 2200).coerceAtMost(html.length))
                val snippet = PhormiSearchText.cleanSnippet(
                    cleanHtmlText(snippetPattern.find(tail)?.groupValues?.get(1).orEmpty()), title
                )
                results += PhormiSearchResult(title, url, snippet, source, index)
            }
        }
        return results.ifEmpty { parseGenericSearch(html, source) }
    }

    private fun parseYahoo(html: String, source: String): List<PhormiSearchResult> {
        val results = mutableListOf<PhormiSearchResult>()
        val pattern = Regex("""(?is)<h3[^>]*>\s*<a[^>]+href=["']([^"']+)["'][^>]*>(.*?)</a>""")
        val snippetPattern = Regex("""(?is)<(?:div|p|span)[^>]+class=["'][^"']*(?:compText|c-line-clamp)[^"']*["'][^>]*>(.*?)</(?:div|p|span)>""")
        pattern.findAll(html).take(12).forEachIndexed { index, match ->
            val url = normalizeSearchUrl(match.groupValues[1])
            val title = cleanHtmlText(match.groupValues[2])
            if (isUsableSearchResult(url, title, source, results)) {
                val tail = html.substring(match.range.last + 1, (match.range.last + 2200).coerceAtMost(html.length))
                val snippet = PhormiSearchText.cleanSnippet(
                    cleanHtmlText(snippetPattern.find(tail)?.groupValues?.get(1).orEmpty()), title
                )
                results += PhormiSearchResult(title, url, snippet, source, index)
            }
        }
        return results.ifEmpty { parseGenericSearch(html, source) }
    }

    private fun parseGoogle(html: String, source: String): List<PhormiSearchResult> {
        val results = mutableListOf<PhormiSearchResult>()
        val pattern = Regex("""(?is)<a[^>]+href=["'](/url\?q=[^"']+|https?://[^"']+)["'][^>]*>(.*?)</a>""")
        pattern.findAll(html).take(30).forEach { match ->
            val url = normalizeSearchUrl(match.groupValues[1])
            val title = cleanHtmlText(match.groupValues[2])
            if (isUsableSearchResult(url, title, source, results)) {
                val snippet = extractNearbySnippet(html, match.range.last + 1, title)
                results += PhormiSearchResult(title, url, snippet, source, results.size)
            }
        }
        return results
    }

    private fun parseGenericSearch(html: String, source: String): List<PhormiSearchResult> {
        val results = mutableListOf<PhormiSearchResult>()
        val anchorRegex = Regex("""(?is)<a\b[^>]*href=["']([^"']+)["'][^>]*>(.*?)</a>""")
        for (match in anchorRegex.findAll(html)) {
            if (results.size >= 12) break
            val rawUrl = Html.fromHtml(match.groupValues[1], Html.FROM_HTML_MODE_LEGACY).toString().trim()
            val title = cleanHtmlText(match.groupValues[2])
            val url = normalizeSearchUrl(rawUrl)
            if (isUsableSearchResult(url, title, source, results)) {
                val snippet = extractNearbySnippet(html, match.range.last + 1, title)
                results += PhormiSearchResult(title, url, snippet, source, results.size)
            }
        }
        return results
    }

    private fun isUsableSearchResult(
        url: String,
        title: String,
        source: String,
        existing: List<PhormiSearchResult>
    ): Boolean {
        val safeUrl = PhormiSearchUrls.webUrlOrNull(url) ?: return false
        if (PhormiSearchText.isNavigationTitle(title)) return false
        if (PhormiSearchUrls.isProviderNavigation(safeUrl)) return false
        if (existing.any { PhormiSearchUrls.canonical(it.url) == PhormiSearchUrls.canonical(safeUrl) }) return false
        return true
    }

    private fun canonicalUrl(raw: String): String = PhormiSearchUrls.canonical(raw)

    private fun cleanHtmlText(value: String): String =
        PhormiSearchText.collapseWhitespace(Html.fromHtml(value, Html.FROM_HTML_MODE_LEGACY).toString())

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

    /** Extract a short description after a result anchor without swallowing the next result. */
    private fun extractNearbySnippet(html: String, position: Int, title: String): String {
        if (position >= html.length) return ""
        val end = (position + 1400).coerceAtMost(html.length)
        var tail = html.substring(position, end)
        val nextResult = Regex("""(?is)<(?:a\b[^>]+href|h[1-3]\b)""").find(tail)
        if (nextResult != null && nextResult.range.first > 0) tail = tail.substring(0, nextResult.range.first)
        tail = tail.replace(Regex("""(?is)<(script|style|noscript|template|svg)\b[^>]*>.*?</\1>"""), " ")
        return PhormiSearchText.cleanSnippet(cleanHtmlText(tail), title)
    }

    private fun mergePhormiSearchResults(raw: List<PhormiSearchResult>, query: String): List<PhormiSearchResult> =
        PhormiSearchRanking.merge(raw, query)

    private val searchAssets by lazy {
        PhormiSearchAssets(
            template = assets.open("search/results.html").bufferedReader(Charsets.UTF_8).use { it.readText() },
            css = assets.open("search/results.css").bufferedReader(Charsets.UTF_8).use { it.readText() },
            script = assets.open("search/results.js").bufferedReader(Charsets.UTF_8).use { it.readText() }
        )
    }

    private fun searchPalette(): PhormiSearchPalette = PhormiSearchPalette(
        background = cssColor(PhormiUiColors.background(this)),
        surface = cssColor(PhormiUiColors.surface(this)),
        surfaceSoft = cssColor(PhormiUiColors.surfaceSoft(this)),
        text = cssColor(PhormiUiColors.text(this)),
        secondary = cssColor(PhormiUiColors.secondary(this)),
        accent = cssColor(PhormiUiColors.accent(this)),
        accentSoft = cssColor(PhormiUiColors.accentSoft(this)),
        onAccent = cssColor(PhormiUiColors.onAccent(this)),
        coral = cssColor(PhormiUiColors.coral(this)),
        border = cssColor(PhormiUiColors.border(this))
    )

    private fun unifiedSearchHtml(
        query: String,
        results: List<PhormiSearchResult>,
        loading: Boolean,
        completed: Int,
        total: Int,
        successfulEngines: List<String> = emptyList(),
        finishedEngines: List<String> = emptyList(),
        aiAnswer: String? = null
    ): String = PhormiSearchRenderer.render(
        PhormiSearchRenderState(
            query = query,
            results = results,
            loading = loading,
            completed = completed,
            total = total,
            successfulEngines = successfulEngines,
            finishedEngines = finishedEngines,
            aiAnswer = aiAnswer
        ),
        searchPalette(),
        searchAssets
    )

    private fun openShortcut(url: String) {
        createNewTab(url)
    }

    private fun navigateFromStartPage() {
        val raw = startPageSearch.text?.toString()?.trim().orEmpty()
        if (raw.isBlank()) return
        urlBar.setText(raw)
        navigateFromUrlBar()
    }

    /** Keep the browser chrome to one horizontal layer. Phones use the tab overview button;
     * wide screens may expose the compact tab strip inside that same layer. */
    private fun updateResponsiveChrome() {
        val tabStrip = findViewById<View>(R.id.tab_strip) ?: return
        val wide = resources.configuration.screenWidthDp >= 900
        val chromeVisible = customView == null && !isInPictureInPictureMode && !splitChromeLocked
        tabStrip.visibility = if (wide && chromeVisible) View.VISIBLE else View.GONE
    }

    private fun showStartPage() {
        tabs.firstOrNull { it.id == activeTabId }?.let { tab ->
            tab.searchQuery = null
            tab.lastSearchHtml = null
            tab.lastKnownUrl = NEW_TAB_URL
            tab.deferredUrl = null
            tab.savedState = null
            tab.title = getString(R.string.new_tab)
            refreshTabIdentity(tab)
        }
        if (splitMode) setSplitMode(false)
        activeWebView()?.loadUrl(NEW_TAB_URL)
        startPageContainer.visibility = View.VISIBLE
        activeWebView()?.visibility = View.GONE
        urlBar.setText("")
        startPageSearch.clearFocus()
        hideKeyboard()
        updateHomeChromeVisibility()
        loadQuickAccessRows()
        saveTabsImmediate(false)
    }

    private fun currentChromeState(): PhormiBrowserChrome.State {
        val onHome = startPageContainer.visibility == View.VISIBLE && !splitMode
        val wide = resources.configuration.screenWidthDp >= 900
        val fullscreen = customView != null || isInPictureInPictureMode
        val activeDownloads = if (::prefs.isInitialized) PhormiDownloadEngine.items(this).count {
            it.state == PhormiDownloadEngine.State.RUNNING || it.state == PhormiDownloadEngine.State.QUEUED
        } else 0
        return PhormiBrowserChrome.State(
            onHome = onHome,
            wide = wide,
            bottomEnabled = PhormiBrowserPreferences.showBottomToolbar(this),
            fullscreen = fullscreen,
            splitLocked = splitChromeLocked,
            activeDownloads = activeDownloads
        )
    }

    private fun updateHomeChromeVisibility() {
        val state = currentChromeState()
        val visibility = PhormiBrowserChrome.resolve(state)
        findViewById<View>(R.id.home_toolbar_spacer)?.visibility = if (state.onHome && visibility.top) View.VISIBLE else View.GONE
        findViewById<View>(R.id.btn_new_tab)?.visibility = if (visibility.newTab) View.VISIBLE else View.GONE
        findViewById<View>(R.id.top_toolbar)?.visibility = if (visibility.top) View.VISIBLE else View.GONE
        findViewById<View>(R.id.url_toolbar)?.visibility = if (visibility.url) View.VISIBLE else View.GONE
        // Home is a primary escape hatch on every screen size, not a tablet-only control.
        findViewById<View>(R.id.btn_home)?.visibility = if (visibility.home) View.VISIBLE else View.GONE
        findViewById<View>(R.id.btn_refresh)?.visibility = if (visibility.refresh) View.VISIBLE else View.GONE
        findViewById<View>(R.id.btn_favorite)?.visibility = if (visibility.favorite) View.VISIBLE else View.GONE
        findViewById<View>(R.id.bottom_toolbar)?.visibility = if (visibility.bottom) View.VISIBLE else View.GONE
        findViewById<View>(R.id.tab_strip)?.visibility = if (visibility.tabStrip) View.VISIBLE else View.GONE
        findViewById<View>(R.id.btn_top_ai)?.visibility = if (visibility.topAi) View.VISIBLE else View.GONE
        findViewById<View>(R.id.btn_top_back)?.visibility = if (visibility.topBack) View.VISIBLE else View.GONE
        findViewById<View>(R.id.btn_top_forward)?.visibility = if (visibility.topForward) View.VISIBLE else View.GONE
        findViewById<View>(R.id.btn_top_downloads)?.visibility = if (visibility.topDownloads) View.VISIBLE else View.GONE
        updateDownloadBadge()
        swipeRefresh.isEnabled = !state.onHome && PhormiBrowserPreferences.pullToRefresh(this)
    }

    private fun updateStartPageVisibility() {
        val url = tabs.firstOrNull { it.id == activeTabId }?.currentUrl ?: activeWebView()?.url
        if (tabs.firstOrNull { it.id == activeTabId }?.searchQuery != null) {
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
            activeWebView()?.visibility = View.VISIBLE
            activeWebView()?.isClickable = true
        }
        updateHomeChromeVisibility()
    }

    private fun refreshActivePage() {
        val tab = tabs.firstOrNull { it.id == activeTabId }
        val query = tab?.searchQuery
        val page = activeWebView()
        if (query != null) {
            // Reloading the cached result document keeps the user's scroll position
            // and avoids firing the whole provider fan-out again for a simple refresh.
            // The in-page "Refresh results" link explicitly requests new network data.
            val cached = tab.lastSearchHtml
            if (cached != null && page != null) {
                val searchUrl = PhormiSearchHistory.searchUrl(query)
                tab.searchDocuments.put(searchUrl, cached)
                if (page.url == searchUrl) page.reload() else page.loadUrl(searchUrl)
            } else runUnifiedSearch(query)
        } else {
            if (page == null || page.url.isNullOrBlank() || page.url == NEW_TAB_URL) {
                swipeRefresh.isRefreshing = false
            } else page.reload()
        }
    }

    private fun updateTabCount() {
        tabCountView.text = tabs.size.toString()
    }

    private fun saveCurrentPageAsPdf() {
        val page = activeWebView()
        if (page == null || startPageContainer.visibility == View.VISIBLE || page.url.isNullOrBlank()) {
            Toast.makeText(this, "Open a page to save it as PDF", Toast.LENGTH_SHORT).show()
            return
        }
        // Android's native print UI offers Save as PDF without copying the page or
        // granting Phormi storage access. The active tab remains where it is.
        val title = page.title.orEmpty().ifBlank { "Phormi page" }.take(60)
        runCatching {
            val printManager = getSystemService(Context.PRINT_SERVICE) as android.print.PrintManager
            val adapter = page.createPrintDocumentAdapter(title)
            printManager.print(title, adapter, android.print.PrintAttributes.Builder().build())
        }.onFailure {
            Toast.makeText(this, "PDF export is unavailable on this device", Toast.LENGTH_LONG).show()
        }
    }

    private fun updateDownloadBadge() {
        if (!::prefs.isInitialized) return
        val active = PhormiDownloadEngine.items(this).filter {
            it.state == PhormiDownloadEngine.State.RUNNING || it.state == PhormiDownloadEngine.State.QUEUED
        }
        val count = active.size
        findViewById<TextView>(R.id.btn_bottom_downloads)?.apply {
            text = if (count == 0) "↓" else "↓ $count"
            contentDescription = if (count == 0) "Downloads" else "$count downloads in progress"
            setTextColor(if (count == 0) PhormiUiColors.text(this@MainActivity) else PhormiUiColors.accent(this@MainActivity))
        }
        findViewById<TextView>(R.id.btn_top_downloads)?.apply {
            visibility = if (PhormiBrowserChrome.resolve(currentChromeState()).topDownloads) View.VISIBLE else View.GONE
            contentDescription = "$count downloads in progress"
        }
        findViewById<TextView>(R.id.download_status_chip)?.apply {
            visibility = if (count > 0 && customView == null && !isInPictureInPictureMode &&
                !splitChromeLocked) View.VISIBLE else View.GONE
            (active.firstOrNull { it.state == PhormiDownloadEngine.State.RUNNING }
                ?: active.firstOrNull())?.let { item ->
                val progress = when {
                    item.total > 0L -> "${(item.downloaded * 100.0 / item.total).toInt().coerceIn(0, 100)}%"
                    item.downloaded > 0L -> "${item.downloaded / 1024} KB"
                    else -> "starting"
                }
                text = "↓ ${item.title.ifBlank { "Download" }.take(22)} · $progress" +
                    if (count > 1) "  +${count - 1}" else ""
                contentDescription = "${item.title}, $progress; $count downloads in progress. Open downloads"
            }
        }
    }

    private fun updateFavoriteButton() {
        val button = findViewById<TextView>(R.id.btn_favorite) ?: return
        val url = activeWebView()?.url.orEmpty()
        val favorite = url.startsWith("http") && PhormiFavorites.contains(this, url)
        val icon = button.compoundDrawables.getOrNull(1)?.mutate()
        if (icon != null) {
            icon.setTint(if (favorite) PhormiUiColors.accent(this) else PhormiUiColors.secondary(this))
            button.setCompoundDrawablesWithIntrinsicBounds(null, icon, null, null)
        }
        button.alpha = if (favorite) 1f else 0.72f
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
        findViewById<TextView>(R.id.btn_top_back)?.apply { isEnabled = back.isEnabled; alpha = back.alpha }
        findViewById<TextView>(R.id.btn_top_forward)?.apply { isEnabled = forward.isEnabled; alpha = forward.alpha }
    }


    private data class AddressSuggestion(val title: String, val url: String) {
        override fun toString() = "$title\n$url"
    }
    private fun configureOmnibox() {
        val input = urlBar as? android.widget.AutoCompleteTextView ?: return
        input.threshold = 1
        input.setSelectAllOnFocus(true)
        input.setOnFocusChangeListener { _, focused ->
            if (focused) {
                val saved = PhormiFavorites.getAll(this).map { AddressSuggestion(it.title, it.url) }
                val recent = runCatching {
                    val array = JSONArray(getSharedPreferences("phormi_history", MODE_PRIVATE).getString("items", "[]"))
                    (array.length() - 1 downTo maxOf(0, array.length() - 100)).mapNotNull { index ->
                        array.optJSONObject(index)?.let { AddressSuggestion(it.optString("title"), it.optString("url")) }
                    }
                }.getOrDefault(emptyList())
                input.setAdapter(android.widget.ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, (saved + recent).distinctBy { it.url }))
            }
        }
        input.setOnItemClickListener { parent, _, position, _ ->
            val suggestion = parent.getItemAtPosition(position) as? AddressSuggestion ?: return@setOnItemClickListener
            input.setText(suggestion.url, false)
            navigateFromUrlBar()
        }
    }
    private fun updatePageProgress(view: WebView?, progress: Int) {
        if (view !== activeWebView()) return
        findViewById<android.widget.ProgressBar>(R.id.page_progress)?.apply {
            this.progress = progress
            visibility = if (progress in 1..99 && startPageContainer.visibility != View.VISIBLE) View.VISIBLE else View.GONE
        }
        findViewById<TextView>(R.id.btn_refresh)?.apply {
            text = if (progress in 1..99) "×" else "↻"
            contentDescription = if (progress in 1..99) "Stop loading" else "Refresh page"
        }
    }
    private fun navigateFromUrlBar() {
        val raw = urlBar.text?.toString()?.trim().orEmpty()
        if (raw.isEmpty()) return
        val direct = PhormiNavigationInput.webUrl(raw)
        if (direct == null && (prefs.getString(KEY_SEARCH_PROVIDER, "Google") == PHORMI_SEARCH)) {
            runUnifiedSearch(raw)
            return
        }
        val url = direct ?: searchUrlForProvider(raw)
        val webView = activeWebView()
        tabs.firstOrNull { it.id == activeTabId }?.let { tab ->
            tab.searchQuery = null; tab.lastSearchHtml = null
            recordTabNavigation(tab, url)
        }
        if (webView != null) {
            // Desktop/normal mode resolved before load to prevent flicker/reload loop
            val desktopBefore = isDesktopModeFor(url)
            if (webView.settings.userAgentString != desktopUserAgent(desktopBefore)) applyDesktopMode(webView, desktopBefore)
            startPageContainer.visibility = View.GONE
            startPageContainer.isClickable = false
            webView.visibility = View.VISIBLE
            webView.isClickable = true
            webView.loadUrl(url)
        } else createNewTab(url)
        updateHomeChromeVisibility()
        saveTabsImmediate(false)
        hideKeyboard()
        urlBar.clearFocus()
    }

    private fun focusNewTabSearch() {
        urlBar.setText("")
        startPageSearch.setText("")
        val input = if (startPageContainer.visibility == View.VISIBLE) startPageSearch else urlBar
        input.requestFocus()
        input.post { (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .showSoftInput(input, InputMethodManager.SHOW_IMPLICIT) }
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
        val action = intent?.getStringExtra("action").orEmpty()
        val openUrl = intent?.getStringExtra("open_url")?.trim().orEmpty()
        val profile = intent?.getStringExtra("open_profile")?.trim().takeIf { !it.isNullOrBlank() }
        when (action) {
            "new_tab" -> createNewTab(NEW_TAB_URL, requestedProfile = profile)
            "select" -> intent?.getIntExtra("tab_id", -1)?.let { id -> tabs.firstOrNull { it.id == id }?.let { switchToTab(it.id) } }
            "close" -> intent?.getIntExtra("tab_id", -1)?.let { id -> if (id > 0) closeTab(id) }
            "open_group" -> openGroupById(intent?.getStringExtra("group_id").orEmpty())
            "close_group" -> closeGroupById(intent?.getStringExtra("group_id").orEmpty())
        }
        if (openUrl.startsWith("http://") || openUrl.startsWith("https://")) createNewTab(openUrl, requestedProfile = profile)
        else intent?.dataString?.trim()?.takeIf { it.startsWith("http://") || it.startsWith("https://") }?.let { createNewTab(it, requestedProfile = profile) }
    }

    private fun setActiveSplitPane(tabId: Int) {
        if (!splitMode || tabs.none { it.id == tabId }) return
        activeTabId = tabId
        tabs.find { it.id == tabId }?.lastUsed = System.currentTimeMillis()
        updateNavButtons()
        updateFavoriteButton()
        val url = tabs.find { it.id == tabId }?.currentUrl.orEmpty()
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
            tabs.filter { it.id == splitTopTabId || it.id == splitBottomTabId }.forEach { it.webView?.onResume() }
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
            tabs.filter { it.id != activeTabId }.forEach { it.webView?.apply { onPause(); visibility = View.GONE } }
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
        val url = tab.currentUrl
        old?.let { sitePermissions.cancel(it) }
        val newWebView = WebView(this)
        if (!PhormiEnvironmentManager.apply(newWebView, target)) {
            newWebView.destroy()
            Toast.makeText(this, "Environment could not be applied", Toast.LENGTH_LONG).show()
            return
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(
            newWebView,
            prefs.getBoolean("security_third_party_cookies", true)
        )
        configureWebView(newWebView)
        newWebView.isClickable = true
        newWebView.isFocusable = true
        newWebView.isFocusableInTouchMode = true
        newWebView.setBackgroundColor(Color.WHITE)
        registerForContextMenu(newWebView)
        val parent = old?.parent as? ViewGroup ?: webViewContainer
        old?.let { parent.removeView(it) }
        parent.addView(newWebView, 0, FrameLayout.LayoutParams(-1, -1))
        old?.destroy()
        tab.webView = newWebView
        tab.savedState = null
        tab.deferredUrl = null
        tab.hasDeviceAccess = false
        tab.documentEpoch++
        newWebView.webChromeClient = buildChromeClient(newWebView) { title ->
            tab.title = PhormiTabOverview.displayTitle(title.orEmpty().ifBlank { tab.title }, tab.currentUrl)
            refreshTabIdentity(tab)
            saveTabs()
        }
        tab.profileName = target
        tab.lastKnownUrl = url
        refreshTabIdentity(tab)
        if (url.isBlank() || url == NEW_TAB_URL) updateStartPageVisibility() else newWebView.loadUrl(url)
        saveTabs()
        if (tab.id == activeTabId) {
            updateNavButtons()
            updateStartPageVisibility()
        }
    }

    private fun moveTabWebViewToHost(tabId: Int, host: FrameLayout) {
        tabs.firstOrNull { it.id == tabId }?.let { resumeDeferredTab(it) }
        val webView = tabs.find { it.id == tabId }?.webView ?: return
        (webView.parent as? ViewGroup)?.removeView(webView)
        if (host !== webViewContainer) host.removeAllViews()
        host.addView(webView, FrameLayout.LayoutParams(-1, -1))
        webView.visibility = View.VISIBLE
    }

    private fun setSplitChromeLocked(locked: Boolean) {
        splitChromeLocked = locked
        if (splitMode) {
            findViewById<View>(R.id.top_toolbar)?.visibility = if (locked) View.GONE else View.VISIBLE
            findViewById<View>(R.id.bottom_toolbar)?.visibility =
                if (locked || !PhormiBrowserPreferences.showBottomToolbar(this)) View.GONE else View.VISIBLE
            splitLockButton.visibility = View.VISIBLE
            splitLockButton.text = if (locked) "🔓" else "🔒"
            splitLockButton.contentDescription = if (locked) "Show browser controls" else "Hide browser controls"
        } else {
            splitLockButton.visibility = View.GONE
        }
        updateDownloadBadge()
    }

    private fun activeWebView(): WebView? = tabs.find { it.id == activeTabId }?.webView

    private fun createNewTab(url: String, requestedProfile: String? = null, forceGhost: Boolean = false, requestedId: Int? = null, requestedCreatedAt: Long? = null, restoredStateBase64: String? = null, activate: Boolean = true, deferLoad: Boolean = false) {
        val ghostRequested = forceGhost || prefs.getBoolean("ghost_next_tab", false)
        if (ghostRequested) { prefs.edit().putBoolean("ghost_next_tab", false).apply(); createGhostTab(url); return }
        val id = requestedId?.takeIf { it > 0 } ?: nextTabId++
        if (id >= nextTabId) nextTabId = id + 1
        val requested = requestedProfile?.trim().takeIf { !it.isNullOrBlank() }
            ?: if (ghostRequested) GHOST_PROFILE_NAME else selectedTabEnvironment()
        val chip = LayoutInflater.from(this).inflate(R.layout.tab_chip, tabStripContainer, false)
        val tab = Tab(id, null, PhormiTabOverview.displayTitle(getString(R.string.new_tab), url), chip,
            isGhost = ghostRequested, createdAt = requestedCreatedAt ?: System.currentTimeMillis(),
            profileName = if (isMultiProfileSupported()) requested else DEFAULT_PROFILE_NAME,
            deferredUrl = url, savedState = restoredStateBase64, lastKnownUrl = url)
        tabs.add(tab); tabStripContainer.addView(chip)
        refreshTabIdentity(tab)
        chip.setOnClickListener { switchToTab(id) }
        chip.findViewById<TextView>(R.id.tab_chip_close).setOnClickListener { closeTab(id) }
        if (activate) switchToTab(id) else if (!deferLoad) {
            resumeDeferredTab(tab)
            tab.webView?.apply { visibility = View.GONE; onPause() }
        }
        updateTabCount(); saveTabs(); scheduleTabBudget()
    }

    /** Record the requested destination before WebView commits it, so pausing or
     * opening the overview cannot turn an in-flight website into a blank New Tab. */
    private fun recordTabNavigation(tab: Tab, url: String) {
        tab.lastKnownUrl = url.trim().ifBlank { NEW_TAB_URL }
        tab.title = PhormiTabOverview.displayTitle(null, tab.lastKnownUrl)
        refreshTabIdentity(tab)
    }

    private fun refreshTabIdentity(tab: Tab) {
        tab.title = PhormiTabOverview.displayTitle(tab.title, tab.currentUrl)
        tab.chipView.findViewById<TextView>(R.id.tab_chip_title)?.text = tab.title
        tab.chipView.contentDescription = "${tab.title}, ${PhormiTabOverview.address(tab.currentUrl)}"
    }

    /** Recreate an evicted renderer on demand; dormant tabs contain metadata only. */
    private fun resumeDeferredTab(tab: Tab) {
        if (tab.webView != null && tab.deferredUrl == null) return
        val target = tab.deferredUrl ?: NEW_TAB_URL
        val view = tab.webView ?: WebView(this).also { created ->
            if (tab.profileName != DEFAULT_PROFILE_NAME && !PhormiEnvironmentManager.apply(created, tab.profileName)) {
                created.destroy()
                Toast.makeText(this, "Could not restore this browsing environment. Tab kept suspended.", Toast.LENGTH_LONG).show()
                return
            }
            tab.webView = created
            configureWebView(created)
            applyLiveWebSettings(created)
            created.isClickable = true; created.isFocusable = true; created.isFocusableInTouchMode = true
            created.setBackgroundColor(Color.WHITE)
            registerForContextMenu(created)
            webViewContainer.addView(created, FrameLayout.LayoutParams(-1, -1))
            created.webChromeClient = buildChromeClient(created) { title ->
                if (tab.webView !== created) return@buildChromeClient
                tab.title = PhormiTabOverview.displayTitle(title.orEmpty().ifBlank { tab.title }, tab.currentUrl)
                refreshTabIdentity(tab)
                saveTabs()
            }
        }
        tab.deferredUrl = null; tab.discardReason = null; tab.lastKnownUrl = target
        applyDesktopMode(view, isDesktopModeFor(target))
        val restored = tab.savedState?.let { restoreWebViewState(view, it) } == true
        if (!restored && target != NEW_TAB_URL) view.loadUrl(target)
        refreshTabIdentity(tab)
    }

    private val memoryHandler = Handler(Looper.getMainLooper())
    private val discardChecks = mutableSetOf<Int>()
    private var budgetWork: Runnable? = null
    private fun protectedTabIds(): Set<Int> = setOf(activeTabId, splitTopTabId, splitBottomTabId,
        if (filePathCallback != null) fileChooserOwner else -1,
        if (customView != null) fullscreenOwner else -1) + aiBackgroundTabIds
    private fun scheduleTabBudget() {
        budgetWork?.let(memoryHandler::removeCallbacks)
        budgetWork = Runnable { enforceTabBudget() }.also { memoryHandler.postDelayed(it, 800) }
    }
    private fun enforceTabBudget(limit: Int = PhormiBrowserPreferences.liveTabBudget(this)) {
        val keep = protectedTabIds()
        val candidates = PhormiTabMemoryPolicy.candidates(tabs.map {
            PhormiTabMemoryPolicy.Entry(it.id, it.webView != null, it.lastUsed,
                it.id in keep || it.hasDeviceAccess || it.isGhost || it.searchQuery != null)
        }, limit)
        candidates.forEach { id ->
            val tab = tabs.firstOrNull { it.id == id } ?: return@forEach
            val view = tab.webView ?: return@forEach
            if (!discardChecks.add(id)) return@forEach
            val epoch = tab.documentEpoch
            fun finish(safe: Boolean) {
                discardChecks.remove(id)
                if (tabs.none { it === tab } || tab.webView !== view || tab.documentEpoch != epoch || id in protectedTabIds() || tab.hasDeviceAccess) return
                if (!safe) { tab.discardReason = "Protected: loading, playing media or unsaved edits"; return }
                if (tabs.count { it.webView != null } <= limit) return
                val target = tab.currentUrl
                val snapshot = saveWebViewState(view)
                if (snapshot == null && target != NEW_TAB_URL) { tab.discardReason = "Protected: navigation snapshot unavailable"; return }
                tab.savedState = snapshot; tab.savedScrollY = view.scrollY; tab.deferredUrl = target
                sitePermissions.cancel(view)
                tab.webView = null // Detach ownership before callbacks from stop/destroy.
                (view.parent as? ViewGroup)?.removeView(view)
                unregisterForContextMenu(view)
                view.stopLoading(); view.destroy()
                tab.discardReason = "Suspended to release page memory"
                saveTabs()
            }
            if (tab.currentUrl == NEW_TAB_URL) finish(true)
            else view.evaluateJavascript(PhormiTabMemoryPolicy.CAN_DISCARD_SCRIPT) { finish(it == "true") }
        }
    }

    fun tabMemoryDiagnostics(): JSONObject = JSONObject()
        .put("total", tabs.size).put("live", tabs.count { it.webView != null })
        .put("budget", PhormiBrowserPreferences.liveTabBudget(this))
        .put("tabs", JSONArray().apply { tabs.forEach { put(JSONObject().put("id", it.id).put("live", it.webView != null).put("reason", it.discardReason.orEmpty())) } })

    private fun createGhostTab(url: String = NEW_TAB_URL) {
        val intent = Intent(this, GhostActivity::class.java)
        if (url != NEW_TAB_URL) intent.data = Uri.parse(url)
        startActivity(intent)
    }

    private fun switchToTab(id: Int) {
        val candidate = tabs.find { it.id == id }
        if (candidate != null && candidate.id != activeTabId && isSiteLocked(candidate.currentUrl, candidate.id)) {
            requestSiteUnlock((candidate.currentUrl).orEmpty()) { switchToTabUnlocked(id) }
            return
        }
        switchToTabUnlocked(id)
    }

    private fun switchToTabUnlocked(id: Int) {
        if (id != activeTabId) sitePermissions.cancel()
        activeTabId = id
        tabs.find { it.id == id }?.lastUsed = System.currentTimeMillis()
        val liveIds = if (splitMode) setOf(splitTopTabId, splitBottomTabId) else setOf(id)
        tabs.forEach { tab ->
            val isActive = tab.id == id
            val isBackgroundAi = tab.id in aiBackgroundTabIds && !isActive
            val isLive = tab.id in liveIds || isBackgroundAi
            if (isLive) resumeDeferredTab(tab)
            val view = tab.webView
            if (view != null) {
            view.visibility = if (isLive) View.VISIBLE else View.GONE
            if (isBackgroundAi) {
                view.alpha = 0f
                view.isClickable = false
                view.isFocusable = false
                view.onResume()
            } else {
                view.alpha = 1f
                view.isClickable = true
                view.isFocusable = isActive
                if (isLive) view.onResume() else view.onPause()
            }
            }
            tab.chipView.alpha = if (isActive) 1f else 0.55f
        }
        if (!splitMode) activeWebView()?.let { webViewContainer.bringChildToFront(it) }
        updateStartPageVisibility()
        updateTabCount()
        updateNavButtons()
        updateFavoriteButton()
        scheduleTabBudget()
        updateSiteSecurityIndicator()
        updatePageProgress(activeWebView(), activeWebView()?.progress ?: 100)
        val current = tabs.find { it.id == id }?.currentUrl
        val query = tabs.firstOrNull { it.id == id }?.searchQuery
        if (query != null) urlBar.setText(query)
        else if (!current.isNullOrBlank() && current != NEW_TAB_URL) urlBar.setText(current)
        else urlBar.setText("")
        saveTabs()
        val searchTab = tabs.firstOrNull { it.id == id }
        val restoredSearchUrl = searchTab?.searchQuery?.let(PhormiSearchHistory::searchUrl)
        if (searchTab?.searchQuery != null && restoredSearchUrl != null) {
            val currentSearchUrl = searchTab.webView?.url ?: NEW_TAB_URL
            val needsRestore = currentSearchUrl == NEW_TAB_URL ||
                (currentSearchUrl == restoredSearchUrl && searchTab.searchDocuments.get(restoredSearchUrl) == null)
            if (needsRestore) runUnifiedSearch(searchTab.searchQuery.orEmpty())
        }
    }

    private fun siteLockScopesForTab(tabId: Int): List<Pair<String, String>> {
        val out = mutableListOf(PhormiSiteLockManager.SCOPE_TAB to tabId.toString())
        val group = TabGroupManager(this).groupForTab(tabId)
        if (group != null) out += PhormiSiteLockManager.SCOPE_GROUP to group.id
        val profile = tabs.firstOrNull { it.id == tabId }?.profileName
        if (!profile.isNullOrBlank()) {
            out += PhormiSiteLockManager.SCOPE_ENVIRONMENT to profile
        }
        return out
    }

    private fun isSiteLocked(url: String?, tabId: Int = activeTabId): Boolean =
        siteLockScopesForTab(tabId).any { (type, id) -> PhormiSiteLockManager.isLocked(this, url, type, id) }

    private fun firstSiteLock(url: String?, tabId: Int): PhormiSiteLockManager.LockState? =
        siteLockScopesForTab(tabId).asSequence().mapNotNull { (type, id) ->
            PhormiSiteLockManager.normalizeHost(url)?.let { host -> PhormiSiteLockManager.get(this, host, type, id) }
        }.firstOrNull()

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
        tabs.firstOrNull { it.id == id }?.webView?.let { sitePermissions.cancel(it) }
        if (splitMode && (id == splitTopTabId || id == splitBottomTabId)) setSplitMode(false)
        val index = tabs.indexOfFirst { it.id == id }
        if (index == -1) return
        val tab = tabs[index]
        synchronized(unifiedSearchLock) {
            unifiedSearchFutures.remove(tab.id)?.forEach { it.cancel(true) }
            unifiedSearchGenerations.remove(tab.id)
        }
        tab.webView?.let { view ->
            unregisterForContextMenu(view)
            (view.parent as? ViewGroup)?.removeView(view)
            view.destroy()
        }
        tabStripContainer.removeView(tab.chipView)
        PhormiBrowserPerformance.clear(tab.id)
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
        TabGroupManager(this).prune(tabs.map { it.id })
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
        val scrollPositions = JSONArray()
        val searchQueries = JSONArray()
        persistTabs.forEach { tab ->
            val u = if (tab.searchQuery != null) PhormiSearchHistory.searchUrl(tab.searchQuery.orEmpty()) else tab.currentUrl
            urls.put(u)
            titles.put(PhormiTabOverview.displayTitle(tab.title, u))
            lastUsed.put(tab.lastUsed)
            createdAt.put(tab.createdAt)
            profiles.put(tab.profileName)
            ids.put(tab.id)
            if (captureWebViewState && tab.deferredUrl == null) {
                tab.webView?.let(::saveWebViewState)?.let { tab.savedState = it }
            }
            webViewStates.put(tab.savedState.orEmpty())
            scrollPositions.put(tab.webView?.scrollY ?: tab.savedScrollY)
            searchQueries.put(tab.searchQuery.orEmpty())
        }
        if (urls.length() == 0) {
            urls.put(NEW_TAB_URL); titles.put(getString(R.string.new_tab)); lastUsed.put(System.currentTimeMillis()); createdAt.put(System.currentTimeMillis()); profiles.put(DEFAULT_PROFILE_NAME); ids.put(nextTabId++); webViewStates.put(""); searchQueries.put("")
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
            .putString("tab_scroll_positions", scrollPositions.toString())
            .putString(KEY_TAB_SEARCH_QUERIES, searchQueries.toString())
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
            (listOf(DEFAULT_PROFILE_NAME) + ProfileStore.getInstance().getAllProfileNames()
                .filter { it.isNotBlank() && !it.equals(GHOST_PROFILE_NAME, ignoreCase = true) })
                .distinct()
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it })
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
            val searchQueries = runCatching { JSONArray(prefs.getString(KEY_TAB_SEARCH_QUERIES, "[]") ?: "[]") }.getOrElse { JSONArray() }
            val scrollPositions = runCatching { JSONArray(prefs.getString("tab_scroll_positions", "[]")) }.getOrElse { JSONArray() }
            val count = arr.length()
            val activeIndex = prefs.getInt(KEY_ACTIVE_INDEX, 0).coerceIn(0, (count - 1).coerceAtLeast(0))
            for (i in 0 until count) {
                val restoredUrl = arr.optString(i, NEW_TAB_URL).trim().ifBlank { NEW_TAB_URL }
                val restoredProfile = profiles.optString(i, DEFAULT_PROFILE_NAME).trim().ifBlank { DEFAULT_PROFILE_NAME }
                val restoredId = ids.optInt(i, 0).takeIf { it > 0 }
                val restoredCreatedAt = createdAt.optLong(i, 0L).takeIf { it > 0L } ?: lastUsed.optLong(i, System.currentTimeMillis()).takeIf { it > 0L }
                val restoredState = webViewStates.optString(i, "").trim().takeIf { it.isNotBlank() }
                createNewTab(restoredUrl, restoredProfile, requestedId = restoredId, requestedCreatedAt = restoredCreatedAt, restoredStateBase64 = restoredState, activate = false, deferLoad = true)
                tabs.getOrNull(i)?.savedScrollY = scrollPositions.optInt(i, 0)
                tabs.getOrNull(i)?.searchQuery = searchQueries.optString(i).takeIf { it.isNotBlank() }
                if (i < tabs.size && i < lastUsed.length()) tabs[i].lastUsed = lastUsed.optLong(i, System.currentTimeMillis())
                val restoredTitle = titles.optString(i).trim()
                if (restoredTitle.isNotBlank() && i < tabs.size) {
                    tabs[i].title = PhormiTabOverview.displayTitle(restoredTitle, tabs[i].currentUrl)
                    refreshTabIdentity(tabs[i])
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
        return runCatching {
            val state = Bundle()
            if (WebViewFeature.isFeatureSupported(WebViewFeature.SAVE_STATE)) WebViewCompat.saveState(webView, state, WEBVIEW_STATE_MAX_BYTES, false)
            else webView.saveState(state) ?: return null
            val parcel = android.os.Parcel.obtain()
            try {
                state.writeToParcel(parcel, 0)
                val bytes = parcel.marshall()
                if (bytes.size > WEBVIEW_STATE_MAX_BYTES) return null
                android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
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
        val expired = tabs.filter { !it.isGhost && it.lastUsed <= cutoff }.map { it.id }
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
                    setTextColor(PhormiUiColors.text(this@MainActivity))
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
            text = "News unavailable right now"; setTextColor(PhormiUiColors.secondary(this@MainActivity)); textSize = 9f; setPadding(8.dp(),8.dp(),8.dp(),8.dp())
        })
    }

    private fun startDownload(
        url: String,
        contentDisposition: String?,
        mimeType: String?,
        userAgentOverride: String? = null,
        sourceWebView: WebView? = null,
        contentLength: Long = -1L
    ) {
        if (url.isBlank()) {
            Toast.makeText(this, "Cannot download this link", Toast.LENGTH_SHORT).show()
            return
        }

        val webView = sourceWebView ?: activeWebView()

        if (url.startsWith("blob:", ignoreCase = true)) {
            downloadBlobUrl(url, contentDisposition, mimeType, webView)
            return
        }

        if (url.startsWith("data:", ignoreCase = true)) {
            downloadDataUrl(url, contentDisposition, mimeType)
            return
        }
        if (webView == null) {
            Toast.makeText(this, "No active browser tab", Toast.LENGTH_SHORT).show()
            return
        }

        PhormiDownloadEngine.enqueue(
            context = this,
            webView = webView,
            url = url,
            contentDisposition = contentDisposition,
            mimeType = mimeType,
            userAgentOverride = userAgentOverride,
            contentLength = contentLength
        )
        updateDownloadBadge()
        Toast.makeText(this, "Download queued · tap ↓ to follow progress", Toast.LENGTH_LONG).show()
    }

    private fun downloadBlobUrl(url: String, contentDisposition: String?, mimeType: String?, sourceWebView: WebView?) {
        val webView = sourceWebView ?: run {
            Toast.makeText(this, "No active page", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(this, "Preparing download…", Toast.LENGTH_SHORT).show()
        val quotedUrl = JSONObject.quote(url)
        val script = """(async function(){
            try {
                const response = await fetch($quotedUrl);
                if (!response.ok) return "ERROR:HTTP " + response.status;
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
                saveDataUrl(value, contentDisposition, mimeType, url)
            } catch (e: Exception) {
                Toast.makeText(this, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun downloadDataUrl(url: String, contentDisposition: String?, mimeType: String?) {
        saveDataUrl(url, contentDisposition, mimeType, url)
    }

    private fun saveDataUrl(dataUrl: String, contentDisposition: String?, mimeType: String?, sourceUrl: String = dataUrl) {
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

            var savedUri: String? = null

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
                savedUri = uri.toString()

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
                savedUri = target.toURI().toString()
                android.media.MediaScannerConnection.scanFile(
                    this, arrayOf(target.absolutePath), arrayOf(baseMime), null
                )
            }

            val completedUri = savedUri ?: throw IllegalStateException("Download location was not created")
            PhormiDownloadEngine.recordExternalCompleted(
                context = this,
                sourceUrl = sourceUrl,
                title = fileName,
                mimeType = baseMime,
                category = PhormiDownloadSupport.category(fileName, baseMime),
                localUri = completedUri,
                total = bytes.size.toLong()
            )

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
                menu.add(0, MENU_OPEN, 0, "Open here")
                menu.add(0, MENU_OPEN_NEW_TAB, 1, "Open in new tab")
                menu.add(0, MENU_OPEN_BACKGROUND_TAB, 2, "Open in background tab")
                menu.add(0, MENU_OPEN_GHOST_TAB, 3, "Open in Ghost tab")
                menu.add(0, MENU_COPY, 4, "Copy link")
                menu.add(0, MENU_DOWNLOAD, 5, "Download link")
            }
            WebView.HitTestResult.IMAGE_TYPE -> {
                contextMenuUrl = result.extra
                contextMenuIsImage = true
                menu.setHeaderTitle("Image")
                menu.add(0, MENU_OPEN, 0, "Open image here")
                menu.add(0, MENU_OPEN_NEW_TAB, 1, "Open image in new tab")
                menu.add(0, MENU_OPEN_BACKGROUND_TAB, 2, "Open image in background tab")
                menu.add(0, MENU_OPEN_GHOST_TAB, 3, "Open image in Ghost tab")
                menu.add(0, MENU_COPY, 4, "Copy image URL")
                menu.add(0, MENU_DOWNLOAD, 5, "Download image")
            }
        }
    }

    override fun onContextItemSelected(item: MenuItem): Boolean {
        val url = contextMenuUrl ?: return super.onContextItemSelected(item)
        when (item.itemId) {
            MENU_OPEN -> {
                activeWebView()?.loadUrl(url)
                return true
            }
            MENU_OPEN_NEW_TAB -> {
                createNewTab(url)
                return true
            }
            MENU_OPEN_BACKGROUND_TAB -> {
                createNewTab(url, activate = false)
                Toast.makeText(this, "Opened in a background tab", Toast.LENGTH_SHORT).show()
                return true
            }
            MENU_OPEN_GHOST_TAB -> {
                createGhostTab(url)
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
            setBackgroundColor(PhormiUiColors.background(this@MainActivity))
        }
        val title = TextView(this).apply {
            text = "Phormi is locked"
            setTextColor(PhormiUiColors.text(this@MainActivity))
            textSize = 24f
            gravity = android.view.Gravity.CENTER
        }
        val message = TextView(this).apply {
            text = "Authenticate with your device security to continue."
            setTextColor(PhormiUiColors.secondary(this@MainActivity))
            textSize = 14f
            gravity = android.view.Gravity.CENTER
            setPadding(0, 12.dp(), 0, 24.dp())
        }
        val retry = TextView(this).apply {
            text = "Unlock with device security"
            gravity = android.view.Gravity.CENTER
            setTextColor(PhormiUiColors.onAccent(this@MainActivity))
            textSize = 16f
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(PhormiUiColors.accent(this@MainActivity))
                cornerRadius = 24.dp().toFloat()
            }
            setPadding(24.dp(), 14.dp(), 24.dp(), 14.dp())
            setOnClickListener { authenticateBrowserLock() }
        }
        val pin = TextView(this).apply {
            text = "Unlock with Phormi PIN"
            gravity = android.view.Gravity.CENTER
            setTextColor(PhormiUiColors.accent(this@MainActivity))
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

    override fun onPause() {
        browserAiForeground = false
        downloadBadgeHandler.removeCallbacks(downloadBadgePoll)
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
            updateDownloadBadge()
        } else if (customView == null && !splitChromeLocked) {
            findViewById<View>(R.id.top_toolbar)?.visibility = View.VISIBLE
            updateHomeChromeVisibility()
        }
    }
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) enforceTabBudget(1)
    }

    override fun onDestroy() {
        budgetWork?.let(memoryHandler::removeCallbacks)
        discardChecks.clear()
        tabSaveRunnable?.let(tabSaveHandler::removeCallbacks)
        tabSaveRunnable = null
        synchronized(unifiedSearchLock) {
            unifiedSearchGeneration.incrementAndGet()
            unifiedSearchFutures.values.flatten().forEach { it.cancel(true) }
            unifiedSearchFutures.clear()
            unifiedSearchGenerations.clear()
        }
        unifiedSearchExecutor.shutdownNow()
        rendererCrashCounts.clear()
        rendererCrashTimes.clear()
        sitePermissions.cancel()
        filePathCallback?.onReceiveValue(null)
        filePathCallback = null
        runCatching { exitFullscreenVideo() }
        tabs.toList().forEach { tab ->
            tab.webView?.let { view ->
                (view.parent as? ViewGroup)?.removeView(view)
                runCatching { unregisterForContextMenu(view); view.stopLoading(); view.destroy() }
            }
        }
        tabs.clear()
        PhormiBrowserPerformance.clearAll()
        CookieManager.getInstance().flush()
        super.onDestroy()
    }

    private fun applyLiveWebSettings(webView: WebView) {
        webView.settings.apply {
            textZoom = PhormiBrowserPreferences.textZoom(this@MainActivity)
            javaScriptEnabled = prefs.getBoolean("security_javascript", true)
            mixedContentMode = if (prefs.getBoolean("security_mixed_content", false))
                WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE else WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, prefs.getBoolean("security_third_party_cookies", true))
    }

    @SuppressLint("WrongConstant")
    private fun configureWebView(webView: WebView) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(webView, PhormiTabMemoryPolicy.INSTALL_DIRTY_TRACKER, setOf("*"))
        }
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            webView.setRendererPriorityPolicy(
                WebView.RENDERER_PRIORITY_IMPORTANT,
                true
            )
        }
        webView.settings.apply {
            javaScriptEnabled = prefs.getBoolean("security_javascript", true)
            domStorageEnabled = true
            databaseEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            if (WebViewFeature.isFeatureSupported(WebViewFeature.COOKIE_INTERCEPT)) {
                runCatching { WebSettingsCompat.setCookiesIncludedInShouldInterceptRequest(this, true) }
            }
            if (WebViewFeature.isFeatureSupported(WebViewFeature.BACK_FORWARD_CACHE)) {
                runCatching { WebSettingsCompat.setBackForwardCacheEnabled(this, true) }
            }
            mediaPlaybackRequiresUserGesture = false
            setGeolocationEnabled(true)
            textZoom = PhormiBrowserPreferences.textZoom(this@MainActivity)
            mixedContentMode = if (prefs.getBoolean("security_mixed_content", false)) {
                android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            } else {
                android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            }
            // Let sites that use target="_blank" behave like a real browser tab.
            setSupportMultipleWindows(true)
            loadWithOverviewMode = true
            useWideViewPort = true
            setAllowFileAccess(false)
            setAllowContentAccess(true)
            builtInZoomControls = true
            displayZoomControls = false
            val desktop = isDesktopModeFor(webView.url.orEmpty())
            userAgentString = desktopUserAgent(desktop)
            // Mobile pages keep their device-width viewport. Desktop pages get a
            // wide layout viewport and overview scaling so the full desktop page
            // fits the screen instead of requiring immediate horizontal scrolling.
            useWideViewPort = true
            loadWithOverviewMode = true
            layoutAlgorithm = if (desktop) WebSettings.LayoutAlgorithm.NORMAL
            else WebSettings.LayoutAlgorithm.TEXT_AUTOSIZING
            applyDesktopUserAgentMetadata(this, desktop)
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: android.webkit.WebResourceRequest?
            ): android.webkit.WebResourceResponse? {
                val localSearch = request?.takeIf { it.isForMainFrame }?.url?.takeIf {
                    PhormiSearchHistory.isLocalSearchUrl(it.toString())
                }
                if (localSearch != null) {
                    val localUrl = localSearch.toString()
                    val owner = tabs.firstOrNull { it.webView === view }
                    val cached = owner?.searchDocuments?.get(localUrl)
                    if (cached != null) {
                        owner?.renderedSearchHtml = cached
                        return android.webkit.WebResourceResponse(
                            "text/html", "UTF-8",
                            java.io.ByteArrayInputStream(cached.toByteArray(Charsets.UTF_8))
                        )
                    }
                    val q = PhormiSearchHistory.queryFromUrl(localUrl)
                    if (!q.isNullOrBlank()) {
                        // Process death or LRU eviction can leave a restorable history URL
                        // without its in-memory document. Reconstruct it locally; never
                        // resolve phormi.local on the network.
                        val loading = unifiedSearchHtml(q, emptyList(), true, 0, PhormiSearchProviders.all.size)
                        owner?.searchDocuments?.put(PhormiSearchHistory.searchUrl(q), loading)
                        owner?.renderedSearchHtml = loading
                        runOnUiThread {
                            if (view != null && tabs.any { it.webView === view } && view == activeWebView()) runUnifiedSearch(q)
                        }
                        return android.webkit.WebResourceResponse(
                            "text/html", "UTF-8",
                            java.io.ByteArrayInputStream(loading.toByteArray(Charsets.UTF_8))
                        )
                    }
                    return android.webkit.WebResourceResponse(
                        "text/html", "UTF-8", java.io.ByteArrayInputStream(ByteArray(0))
                    )
                }
                request?.url?.toString()?.let {
                    PhormiDownloadEngine.rememberWebRequestHeaders(it, request.requestHeaders)
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?): Boolean {
                val targetUri = request?.url ?: return false
                val target = targetUri.toString()
                if (target.isBlank()) return false
                val scheme = targetUri.scheme?.lowercase(Locale.US).orEmpty()
                // The generated Phormi Search document uses a local origin. A "Refresh
                // results" link is an explicit request for fresh provider data; it must
                // not be handed to WebView as a real (and non-existent) navigation.
                if (PhormiSearchHistory.isLocalSearchUrl(target)) {
                    val q = PhormiSearchHistory.queryFromUrl(target)
                    // An explicit Refresh link always asks for fresh provider data.
                    // A gestured /search navigation is a new/refined search. A non-gestured
                    // /search navigation is Back/Forward or a restored history entry: let
                    // shouldInterceptRequest serve the cached document instead of refetching.
                    if (!q.isNullOrBlank() && (PhormiSearchHistory.isRefreshUrl(target) || request?.hasGesture() == true)) {
                        runUnifiedSearch(q)
                        return true
                    }
                    return false
                }
                // Reconcile desktop/mobile before an ordinary link navigation commits.
                // Omnibox/new-tab paths already do this; link clicks and cross-host
                // redirects must not inherit the previous site's UA/viewport by accident.
                if (view != null && request?.isForMainFrame == true && (scheme == "http" || scheme == "https")) {
                    ensureDesktopModeFor(view, target)
                }
                val host = PhormiSiteLockManager.normalizeHost(target)
                if (host != null) {
                    val sourceTabId = tabs.firstOrNull { it.webView === view }?.id ?: activeTabId
                    firstSiteLock(target, sourceTabId)?.let { lock ->
                        pendingLockedNavigationUrl = target
                        pendingLockedScopeType = lock.scopeType
                        pendingLockedScopeId = lock.scopeId
                        showSiteUnlockDialog(host)
                        return true
                    }
                }
                // File access is disabled in WebSettings, so allowing file: navigation
                // was contradictory. javascript: navigation is never a legitimate page
                // URL. data:/blob: are kept because web apps use them for downloads.
                if (scheme == "javascript" || scheme == "file") {
                    Toast.makeText(this@MainActivity, "Blocked unsafe $scheme link", Toast.LENGTH_SHORT).show()
                    return true
                }
                if (scheme.isBlank() || scheme == "http" || scheme == "https" ||
                    scheme == "data" || scheme == "blob") return false

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
                if (request?.isForMainFrame == true && view != null && view == activeWebView()) {
                    swipeRefresh.isRefreshing = false
                    val description = error?.description?.toString()?.trim().orEmpty()
                    val failedUrl = request.url?.toString().orEmpty()
                    if (failedUrl.startsWith("http", true)) {
                        // A full-page error surface with a retry action, not a toast
                        // over a blank white screen.
                        showNetworkErrorPage(view, failedUrl, description.ifBlank { "The page could not be loaded." })
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
                if (request?.isForMainFrame == true && view != null && view == activeWebView()) {
                    swipeRefresh.isRefreshing = false
                    val code = errorResponse?.statusCode ?: 0
                    if (code >= 400) {
                        val pageUrl = request?.url?.toString().orEmpty()
                        val key = "$pageUrl|$code"
                        val now = System.currentTimeMillis()
                        if (key != lastHttpErrorKey || now - lastHttpErrorAt > 30_000L) {
                            lastHttpErrorKey = key
                            lastHttpErrorAt = now
                            val message = when (code) {
                                400 -> "The server rejected this page request (HTTP 400 Bad Request)."
                                401 -> "This page requires authentication (HTTP 401 Unauthorized)."
                                403 -> "The server refused this page request (HTTP 403 Forbidden)."
                                404 -> "This page was not found (HTTP 404 Not Found)."
                                408 -> "The server timed out (HTTP 408 Request Timeout)."
                                429 -> "The server is rate-limiting this page (HTTP 429 Too Many Requests)."
                                500 -> "The website reported an internal server error (HTTP 500)."
                                502 -> "The website's gateway reported an error (HTTP 502 Bad Gateway)."
                                503 -> "The website is temporarily unavailable (HTTP 503 Service Unavailable)."
                                504 -> "The website's gateway timed out (HTTP 504 Gateway Timeout)."
                                else -> "The page request failed (HTTP $code)."
                            }
                            Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                val owner = tabs.firstOrNull { it.webView === view }
                if (owner?.deferredUrl != null) return
                owner?.savedState = null
                if (!url.isNullOrBlank()) owner?.let { started ->
                    started.lastKnownUrl = url
                    if (started.searchQuery == null) started.title = PhormiTabOverview.displayTitle(null, url)
                    refreshTabIdentity(started)
                }
                owner?.documentEpoch = (owner?.documentEpoch ?: 0) + 1
                owner?.hasDeviceAccess = false
                view?.let { sitePermissions.cancel(it) }
                if (view === activeWebView() && !urlBar.hasFocus() && url != NEW_TAB_URL && !url.orEmpty().startsWith("https://phormi.local/")) urlBar.setText(url.orEmpty())
                tabs.find { it.webView === view }?.let { PhormiBrowserPerformance.start(it.id) }
                super.onPageStarted(view, url, favicon)
                if (view == activeWebView()) updateSiteSecurityIndicator()
                val target = url.orEmpty()
                val startedTab = tabs.firstOrNull { it.webView === view }
                if (PhormiSearchHistory.isSearchUrl(target)) {
                    val restoredQuery = PhormiSearchHistory.queryFromUrl(target)
                    startedTab?.searchQuery = restoredQuery
                    startedTab?.lastSearchHtml = startedTab?.searchDocuments?.get(PhormiSearchHistory.searchUrl(restoredQuery.orEmpty()))
                    if (view === activeWebView() && !urlBar.hasFocus() && restoredQuery != null) urlBar.setText(restoredQuery)
                } else if (view != null && target.startsWith("http", true) &&
                    !target.startsWith("https://phormi.local/")) {
                    startedTab?.searchQuery = null
                } else if (target == NEW_TAB_URL || target == "about:blank") {
                    startedTab?.searchQuery = null
                }
                // Flicker fix: desktop mode now applied *before* loadUrl (navigate/createNewTab),
                // not mid-navigation. Changing UA/textZoom here caused a re-layout flash on every URL.
            }

            override fun onReceivedSslError(
                view: WebView?,
                handler: android.webkit.SslErrorHandler?,
                error: android.net.http.SslError?
            ) {
                handler?.cancel()
                if (view == activeWebView()) AlertDialog.Builder(this@MainActivity)
                    .setTitle("Connection blocked")
                    .setMessage("The certificate for ${error?.url ?: view?.url.orEmpty()} could not be verified. No insecure exception was allowed. Check the address and your device clock, or contact the website owner.")
                    .setPositiveButton("Close", null).show()
            }

            // SafeBrowsingResponse and the threat constants arrived in API 27,
            // while the app still supports API 26. WebView only invokes this
            // callback on 27+, so the guard makes the older platform safe and
            // the annotation tells lint the API-27 references are intentional.
            @SuppressLint("NewApi")
            override fun onSafeBrowsingHit(
                view: WebView?,
                request: WebResourceRequest?,
                threatType: Int,
                callback: android.webkit.SafeBrowsingResponse?
            ) {
                if (android.os.Build.VERSION.SDK_INT < 27) return
                // Take the safe action immediately, then explain it with real detail
                // instead of a transient toast.
                if (android.os.Build.VERSION.SDK_INT >= 27) callback?.backToSafety(true)
                else view?.stopLoading()
                val threat = when (threatType) {
                    WebViewClient.SAFE_BROWSING_THREAT_MALWARE -> "malware"
                    WebViewClient.SAFE_BROWSING_THREAT_PHISHING -> "phishing"
                    WebViewClient.SAFE_BROWSING_THREAT_UNWANTED_SOFTWARE -> "unwanted software"
                    else -> "an unsafe site"
                }
                runOnUiThread {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Unsafe page blocked")
                        .setMessage("Google Safe Browsing reports $threat on this page, so Phormi returned you to safety.\n\n${request?.url?.toString().orEmpty()}")
                        .setPositiveButton("OK", null)
                        .setCancelable(false)
                        .show()
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
                    if (tabs.firstOrNull { it.webView === view }?.deferredUrl != null) return
                    val t = view?.title ?: ""
                    val u = view?.url ?: url ?: ""
                    val tab = tabs.find { it.webView === view }
                    if (tab != null && !tab.isGhost && tab.searchQuery == null &&
                        (u.startsWith("https://") || u.startsWith("http://"))) {
                        HistoryActivity.record(this@MainActivity, t, u)
                    }

                if (view != null && url.orEmpty().startsWith("http", true) &&
                    desktopHost(url.orEmpty()) != "phormi.local" && isDesktopModeFor(url.orEmpty())) {
                    view.evaluateJavascript(PhormiDesktopMode.viewportScript(), null)
                }
                // A provider may finish while the initial local search navigation is still
                // committing. If the served document is older than the latest render, reload
                // the same history entry once instead of pushing a new one.
                if (tab != null && PhormiSearchHistory.isSearchUrl(u) && tab.searchQuery != null) {
                    val latest = tab.lastSearchHtml
                    if (latest != null && latest != tab.renderedSearchHtml) {
                        tab.searchDocuments.put(PhormiSearchHistory.searchUrl(tab.searchQuery.orEmpty()), latest)
                        view?.reload()
                    }
                }
                super.onPageFinished(view, url)
                if (view == activeWebView()) {
                    swipeRefresh.isRefreshing = false
                    updateStartPageVisibility()
                    // Do not let an old/current page completion overwrite text while
                    // the user is actively editing the unified URL/search field. WebView can
                    // finish a previous navigation after the user has already started typing;
                    // replacing the field here made it look as if Phormi jumped back to an old tab.
                    if (!urlBar.hasFocus()) {
                        val query = tabs.firstOrNull { it.webView === view }?.searchQuery
                        if (query != null) urlBar.setText(query)
                        else urlBar.setText(tabs.firstOrNull { it.webView === view }?.currentUrl
                            ?.takeIf { address -> address != NEW_TAB_URL }.orEmpty())
                    }
                    updateNavButtons()
                    updateFavoriteButton()
                    updateSiteSecurityIndicator()
                }
                tabs.find { it.webView === view }?.let { finished ->
                    if (u.isNotBlank()) finished.lastKnownUrl = u
                    finished.title = PhormiTabOverview.displayTitle(t.ifBlank { finished.title }, finished.currentUrl)
                    refreshTabIdentity(finished)
                    PhormiBrowserPerformance.finish(finished.id, u)
                    view?.evaluateJavascript(PhormiTabMemoryPolicy.INSTALL_DIRTY_TRACKER, null)
                    if (finished.savedScrollY > 0) {
                        val y = finished.savedScrollY; finished.savedScrollY = 0
                        view?.post { view.scrollTo(0, y) }
                    }
                }
                saveTabs(); scheduleTabBudget()
            }
        }

        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
            startDownload(url, contentDisposition, mimeType, userAgent, webView, contentLength)
        }
    }

    private fun buildChromeClient(source: WebView, onTitleChanged: (String?) -> Unit): WebChromeClient {
        return object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) { updatePageProgress(view, newProgress) }
            override fun onReceivedTitle(view: WebView?, title: String?) {
                super.onReceivedTitle(view, title)
                onTitleChanged(title)
            }

            /** Thin horizontal load indicator under the omnibox. */

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
                resumeDeferredTab(newTab)
                val newWebView = newTab.webView ?: return false

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
                fullscreenOwner = tabs.firstOrNull { it.webView === source }?.id ?: -1
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
                // Do not force landscape: onShowCustomView is also used for non-video
                // HTML fullscreen content, and rotating the whole activity was disruptive.
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
                sitePermissions.requestMedia(source, request)
            }
            override fun onPermissionRequestCanceled(request: PermissionRequest) {
                sitePermissions.cancelled(request)
            }
            override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: android.webkit.GeolocationPermissions.Callback?) {
                sitePermissions.requestLocation(source, origin, callback)
            }
            override fun onGeolocationPermissionsHidePrompt() { sitePermissions.cancel(source) }

            override fun onShowFileChooser(
                webViewParam: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback
                fileChooserOwner = tabs.firstOrNull { it.webView === source }?.id ?: -1
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
        sitePermissions.onResult(requestCode)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ_WALLPAPER) {
            if (resultCode == Activity.RESULT_OK && data?.data != null) {
                val uri = data.data!!
                try {
                    val takeFlags = data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    //noinspection WrongConstant
                    if (takeFlags != 0) contentResolver.takePersistableUriPermission(uri, takeFlags)
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
        if (requestCode == REQ_TABS_OVERVIEW && resultCode == RESULT_OK && data != null) {
            handleTabsOverviewResult(data)
            return
        }

        if (requestCode == REQ_MENU && resultCode == RESULT_OK) {
            when (data?.getStringExtra(MenuActivity.EXTRA_ACTION)) {
                MenuActivity.ACTION_NEW_TAB -> {
                    createNewTab(NEW_TAB_URL)
                    focusNewTabSearch()
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
                MenuActivity.ACTION_TABS_RESULT -> data?.let(::handleTabsOverviewResult)
                MenuActivity.ACTION_HOME -> showStartPage()
                MenuActivity.ACTION_BACK -> activeWebView()?.let { navigateBack(it) }
                MenuActivity.ACTION_FORWARD -> activeWebView()?.let { navigateForward(it) }
                MenuActivity.ACTION_REFRESH -> refreshActivePage()
                MenuActivity.ACTION_SAVE_PDF -> saveCurrentPageAsPdf()
                MenuActivity.ACTION_SPLIT_SCREEN -> setSplitMode(!splitMode)
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
                MenuActivity.ACTION_INSTALL_SITE -> addCurrentSiteToHomeScreen()
                MenuActivity.ACTION_FAVORITE -> addCurrentPageToBookmarks()
                MenuActivity.ACTION_HELP -> startActivity(Intent(this, HelpActivity::class.java))
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
                MenuActivity.ACTION_SETTINGS -> startActivity(Intent(this, PhormiBrowserSettingsActivity::class.java))
                MenuActivity.ACTION_KEYBOARD -> startActivity(Intent(this, PhormiKeyboardSettingsActivity::class.java))
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

    private fun handleTabsOverviewResult(data: Intent) {
            when (data.getStringExtra("action")) {
                "new_tab" -> {
                    createNewTab(NEW_TAB_URL)
                    focusNewTabSearch()
                }
                "select" -> {
                    val tabId = data.getIntExtra("tab_id", -1)
                    tabs.firstOrNull { it.id == tabId }?.let { switchToTab(it.id) }
                }
                "close" -> {
                    val tabId = data.getIntExtra("tab_id", -1)
                    tabs.firstOrNull { it.id == tabId }?.let { closeTab(it.id) }
                }
                "close_many" -> {
                    // Resolve each id at the moment it is closed: closeTab() mutates
                    // the list and can spawn a replacement tab when it empties.
                    val ids = data.getIntArrayExtra("tab_ids") ?: intArrayOf()
                    ids.distinct().forEach { id ->
                        if (tabs.any { it.id == id }) closeTab(id)
                    }
                }
                "toggle_split" -> setSplitMode(!splitMode)
                "assign_group" -> {
                    val groupId = data.getStringExtra("group_id").orEmpty()
                    val tabId = data.getIntExtra("tab_id", -1)
                    if (groupId.isNotBlank() && tabId > 0) {
                        TabGroupManager(this).assignTab(groupId, tabId, tabs.find { it.id == tabId }?.currentUrl.orEmpty())
                    }
                }
                "unassign_group" -> {
                    val tabId = data.getIntExtra("tab_id", -1)
                    if (tabId > 0) TabGroupManager(this).unassignTab(tabId)
                }
                "assign_group_many" -> {
                    val groupId = data.getStringExtra("group_id").orEmpty()
                    val ids = (data.getIntArrayExtra("tab_ids") ?: intArrayOf()).filter { id -> tabs.any { it.id == id } }
                    if (groupId.isNotBlank() && ids.isNotEmpty()) TabGroupManager(this).assignTabs(groupId, ids)
                }
                "unassign_group_many" -> {
                    val ids = (data.getIntArrayExtra("tab_ids") ?: intArrayOf()).filter { id -> tabs.any { it.id == id } }
                    if (ids.isNotEmpty()) TabGroupManager(this).unassignTabs(ids)
                }
                "open_group" -> openGroupById(data.getStringExtra("group_id").orEmpty())
                "close_group" -> closeGroupById(data.getStringExtra("group_id").orEmpty())
                "reassign_env" -> {
                    val tabId = data.getIntExtra("tab_id", -1)
                    val profile = data.getStringExtra("profile").orEmpty()
                    if (tabId > 0 && profile.isNotBlank()) reassignTabEnvironment(tabId, profile)
                }
            }
    }

    private fun openGroupById(groupId: String) {
        if (groupId.isBlank()) return
        val group = TabGroupManager(this).groupForId(groupId) ?: return
        val target = group.tabIds.firstOrNull { id -> tabs.any { it.id == id } }
        if (target != null) switchToTab(target)
        else Toast.makeText(this, "${group.name} has no open tabs.", Toast.LENGTH_SHORT).show()
    }

    private fun closeGroupById(groupId: String) {
        if (groupId.isBlank()) return
        val group = TabGroupManager(this).groupForId(groupId) ?: return
        group.tabIds.filter { id -> tabs.any { it.id == id } }.distinct().forEach { id -> closeTab(id) }
        TabGroupManager(this).prune(tabs.map { it.id })
    }

    fun openAiFromBottom(view: View) {
        startActivity(Intent(this, AiActivity::class.java).putExtra("target_tab_id", activeTabId))
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
            updateHomeChromeVisibility()
        }
    }

    override fun onBackPressed() {
        if (customView != null) {
            exitFullscreenVideo()
            return
        }
        val webView = activeWebView()
        if (webView != null) navigateBack(webView)
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
                if (ev.pointerCount == 3 && customView == null) {
                    threeFingerTracking = true
                    threeFingerStartX = ev.getX(0)
                }
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                if (threeFingerTracking && ev.pointerCount >= 3) {
                    val dx = ev.getX(0) - threeFingerStartX
                    if (kotlin.math.abs(dx) >= 120f && customView == null) {
                        val activeIndex = tabs.indexOfFirst { it.id == activeTabId }
                        if (activeIndex >= 0) {
                            val groupManager = TabGroupManager(this)
                            val activeGroup = groupManager.groupForTab(activeTabId)
                            val pool = if (activeGroup != null) {
                                tabs.filter { groupManager.groupForTab(it.id)?.id == activeGroup.id }
                            } else {
                                tabs
                            }
                            val current = pool.indexOfFirst { it.id == activeTabId }
                            val target = if (dx < 0) current + 1 else current - 1
                            pool.getOrNull(target)?.let { switchToTab(it.id) }
                        }
                        threeFingerTracking = false
                    }
                }
            }
            android.view.MotionEvent.ACTION_POINTER_UP,
            android.view.MotionEvent.ACTION_UP,
            android.view.MotionEvent.ACTION_CANCEL -> threeFingerTracking = false
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
                fun retry(remaining: Int) {
                    if (anchorWebView.url == anchor.url) {
                        PhormiNavigationLens.focus(anchorWebView, anchor.locator) { found ->
                            if (!found && remaining > 0) anchorWebView.postDelayed({ retry(remaining - 1) }, 500)
                            else if (!found) Toast.makeText(this, "This page object has changed or is no longer available.", Toast.LENGTH_SHORT).show()
                        }
                    } else if (remaining > 0) {
                        anchorWebView.postDelayed({ retry(remaining - 1) }, 500)
                    }
                }
                retry(8)
            }, 500)
        }.setNegativeButton("Close", null).show()
    }

    private fun openSamePageSplit() {
        if (splitMode) {
            Toast.makeText(this, "Already in split view", Toast.LENGTH_SHORT).show()
            return
        }
        val source = tabs.find { it.id == activeTabId } ?: return
        val currentUrl = source.currentUrl.takeIf { it.startsWith("http") } ?: return
        val originalId = source.id
        createNewTab(currentUrl, source.profileName, forceGhost = false)
        val duplicateId = activeTabId
        if (duplicateId == originalId || tabs.none { it.id == duplicateId }) return
        splitTopTabId = originalId
        splitBottomTabId = duplicateId
        setSplitMode(true)
    }

    private fun showSiteLockDialog() {
        val tab = tabs.firstOrNull { it.id == activeTabId } ?: return
        val view = tab.webView ?: return
        val url = view.url.orEmpty()
        if (!url.startsWith("http")) return
        val host = runCatching { Uri.parse(url).host.orEmpty() }.getOrDefault("")
        if (host.isBlank()) return
        val group = TabGroupManager(this).groupForTab(tab.id)
        val scopes = mutableListOf(PhormiSiteLockManager.SCOPE_TAB to "Current tab")
        if (group != null) scopes += PhormiSiteLockManager.SCOPE_GROUP to "Group: ${group.name}"
        if (!tab.profileName.equals(DEFAULT_PROFILE_NAME, ignoreCase = true)) scopes += PhormiSiteLockManager.SCOPE_ENVIRONMENT to "Environment: ${tab.profileName}"

        AlertDialog.Builder(this).setTitle("Site protection for $host")
            .setItems(scopes.map { it.second }.toTypedArray()) { _, which ->
                val scopeType = scopes[which].first
                val scopeId = when (scopeType) {
                    PhormiSiteLockManager.SCOPE_TAB -> tab.id.toString()
                    PhormiSiteLockManager.SCOPE_GROUP -> group?.id.orEmpty()
                    else -> tab.profileName
                }
                val existing = PhormiSiteLockManager.get(this, host, scopeType, scopeId)
                val actions = mutableListOf("15 minutes", "1 hour", "Until removed")
                if (existing != null) actions += "Turn off"
                AlertDialog.Builder(this).setTitle("$host • ${scopes[which].second}")
                    .setItems(actions.toTypedArray()) { _, action ->
                        if (actions[action] == "Turn off") {
                            PhormiSiteLockManager.unlock(this, host, scopeType, scopeId)
                            Toast.makeText(this, "Site protection off", Toast.LENGTH_SHORT).show()
                        } else {
                            val expiry = when (actions[action]) {
                                "15 minutes" -> System.currentTimeMillis() + 15 * 60 * 1000L
                                "1 hour" -> System.currentTimeMillis() + 60 * 60 * 1000L
                                else -> Long.MAX_VALUE
                            }
                            PhormiSiteLockManager.lock(this, host, expiry, scopeType, scopeId)
                            Toast.makeText(this, "Site protection enabled", Toast.LENGTH_SHORT).show()
                        }
                    }.show()
            }.show()
    }

    private fun showSiteUnlockDialog(host: String) {
        val continueOnce = {
            val url = pendingLockedNavigationUrl
            pendingLockedNavigationUrl = null
            pendingLockedScopeType = PhormiSiteLockManager.SCOPE_TAB
            pendingLockedScopeId = ""
            if (!url.isNullOrBlank()) activeWebView()?.loadUrl(url)
        }
        val cancel = {
            pendingLockedNavigationUrl = null
            pendingLockedScopeType = PhormiSiteLockManager.SCOPE_TAB
            pendingLockedScopeId = ""
        }
        if (browserLockManager.isPinConfigured(prefs)) {
            val input = EditText(this).apply {
                hint = "Phormi PIN"
                inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
                setSingleLine(true)
            }
            AlertDialog.Builder(this)
                .setTitle("Site protected")
                .setMessage("$host is protected. Unlocking here is one-time; protection stays on.")
                .setView(input)
                .setNegativeButton("Cancel") { _, _ -> cancel() }
                .setPositiveButton("Unlock once") { _, _ ->
                    if (browserLockManager.verifyPin(prefs, input.text.toString())) continueOnce()
                    else { cancel(); Toast.makeText(this, "Incorrect PIN", Toast.LENGTH_SHORT).show() }
                }.show()
        } else {
            AlertDialog.Builder(this)
                .setTitle("Site protected")
                .setMessage("$host is protected. Authenticate to continue once.")
                .setNegativeButton("Cancel") { _, _ -> cancel() }
                .setPositiveButton("Authenticate") { _, _ -> browserLockManager.authenticate(continueOnce) { cancel() } }
                .show()
        }
    }

    /** Full-page network error surface with a real retry action. */
    private fun showNetworkErrorPage(view: WebView, url: String, detail: String) {
        val safeUrl = Html.escapeHtml(url).replace("'", "&#39;")
        val safeDetail = Html.escapeHtml(detail)
        val bg = cssColor(PhormiUiColors.background(this))
        val text = cssColor(PhormiUiColors.text(this))
        val muted = cssColor(PhormiUiColors.secondary(this))
        val accent = cssColor(PhormiUiColors.accent(this))
        val onAccent = cssColor(PhormiUiColors.onAccent(this))
        val page = """
            <!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'>
            <style>body{margin:0;background:$bg;color:$text;font-family:sans-serif;padding:28px}
            h1{font-size:20px;color:$text}p{font-size:14px;line-height:1.5;color:$text}
            .url{font-size:12px;color:$muted;word-break:break-all;margin:12px 0}
            a{display:inline-block;margin-top:18px;padding:11px 16px;border-radius:12px;background:$accent;color:$onAccent;text-decoration:none;font-size:14px}</style>
            </head><body><h1>This page isn't available</h1><p>$safeDetail</p>
            <div class='url'>$safeUrl</div><p>Check your connection, then try again.</p>
            <a href='$safeUrl'>Try again</a></body></html>
        """.trimIndent()
        view?.loadDataWithBaseURL(url, page, "text/html", "UTF-8", null)
    }

    private fun updateSiteSecurityIndicator() {
        val indicator = findViewById<TextView>(R.id.btn_site_info) ?: return
        val page = activeWebView()
        val url = page?.url.orEmpty()
        when {
            url.startsWith("https://phormi.local/") -> {
                indicator.text = "⌕"
                indicator.contentDescription = "Phormi Search results"
                indicator.setTextColor(PhormiUiColors.accent(this))
            }
            url.startsWith("https://") && page?.certificate != null -> {
                indicator.text = "🔒"
                indicator.contentDescription = "Secure HTTPS connection. Tap for site information."
                indicator.setTextColor(PhormiUiColors.success(this))
            }
            url.startsWith("http://") -> {
                indicator.text = "⚠"
                indicator.contentDescription = "Not secure HTTP connection. Tap for site information."
                indicator.setTextColor(PhormiUiColors.warning(this))
            }
            else -> {
                indicator.text = "ⓘ"
                indicator.contentDescription = "Site information and connection security"
                indicator.setTextColor(PhormiUiColors.secondary(this@MainActivity))
            }
        }
    }

    /** Chrome-style site information: URL, connection security and per-site choices. */
    private fun showSiteInfo() {
        val view = activeWebView()
        val url = view?.url.orEmpty()
        val secure = url.startsWith("https://") && view?.certificate != null
        val host = runCatching { Uri.parse(url).host }.getOrNull().orEmpty().ifBlank { "New tab" }
        val origin = PhormiSitePermissions.secureOrigin(url) ?: PhormiSitePermissions.normalizeOrigin(url)
        val geo = PhormiSitePermissions.decision(sitePermissionStore.read(origin))
        val geoLabel = when (geo) { true -> "Allowed"; false -> "Blocked"; else -> "Ask every time" }
        val message = buildString {
            if (url.startsWith("https://phormi.local/")) {
                append("Phormi Search results are generated on this device from public search-provider pages.")
                append("\n\nConnection: Local results page (no remote site certificate)")
            } else {
                append(if (url.isBlank()) "No page loaded." else url)
                append("\n\nConnection: ")
                append(if (url.isBlank()) "—" else if (secure) "HTTPS (encrypted)" else "Not secure (HTTP or local content)")
            }
            append("\nLocation for this site: ").append(geoLabel)
            append("\n\nSite permission decisions are stored on this device only.")
        }
        AlertDialog.Builder(this)
            .setTitle("Site information · $host")
            .setMessage(message)
            .setPositiveButton("Close", null)
            .setNegativeButton("Reload / end access") { _, _ -> sitePermissions.cancel(); view?.reload() }
            .setNeutralButton("Reset site permissions") { _, _ ->
                if (origin.isNotBlank()) {
                    PhormiSitePermissions.forget(origin, sitePermissionStore)
                    sitePermissions.cancel(); view?.reload()
                    Toast.makeText(this, "Site permissions reset for $host", Toast.LENGTH_SHORT).show()
                    updateSiteSecurityIndicator()
                }
            }
            .show()
    }

    private fun showFindInPage() {
        val view = activeWebView() ?: return
        val input = EditText(this).apply { hint = "Find text"; setSingleLine(true) }
        val counter = TextView(this).apply {
            text = "Enter text to search this page"
            setPadding(24, 8, 24, 8)
            setTextColor(PhormiUiColors.secondary(this@MainActivity))
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(input)
            addView(counter)
        }
        view.setFindListener { activeMatchOrdinal, numberOfMatches, _ ->
            counter.text = if (numberOfMatches == 0) "No matches"
            else "${activeMatchOrdinal + 1} of $numberOfMatches matches"
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Find in page")
            .setView(panel)
            .setPositiveButton("Find") { _, _ ->
                val q = input.text.toString()
                if (q.isNotBlank()) view.findAllAsync(q)
            }
            .setNegativeButton("Close") { _, _ -> view.clearMatches(); view.setFindListener(null) }
            .setNeutralButton("Next", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val q = input.text.toString()
                if (q.isNotBlank()) view.findNext(true)
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val q = input.text.toString()
                if (q.isNotBlank()) view.findAllAsync(q)
            }
        }
        dialog.show()
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
        val added = PhormiFavorites.toggle(this, view.title.orEmpty(), url)
        Toast.makeText(
            this,
            if (added) "Added to favorites" else "Removed from favorites",
            Toast.LENGTH_SHORT
        ).show()
        loadQuickAccessRows()
        updateFavoriteButton()
    }

    private fun desktopUserAgent(enabled: Boolean): String =
        PhormiDesktopMode.userAgent(WebSettings.getDefaultUserAgent(this), enabled)

    private fun desktopHost(url: String): String? = PhormiDesktopMode.normalizeHost(url)

    /**
     * Desktop mode is no longer restricted to a hand-picked site list. There is a
     * global default plus explicit per-host overrides in both directions, so a
     * site can opt out of a global desktop setting (or opt in on its own).
     * Phormi Search's local results renderer is always mobile-first.
     */
    private fun isDesktopModeFor(url: String): Boolean {
        if (desktopHost(url) == PhormiSearchHistory.HOST) return false
        return PhormiDesktopMode.resolve(
            url,
            prefs.getBoolean(KEY_DESKTOP_ALL, false),
            prefs.getStringSet(KEY_DESKTOP_SITES, emptySet()).orEmpty(),
            prefs.getStringSet(KEY_DESKTOP_SITES_OFF, emptySet()).orEmpty()
        )
    }

    private fun setDesktopModeForHost(host: String, enabled: Boolean) {
        val (on, off) = PhormiDesktopMode.toggleHost(
            host,
            enabled,
            prefs.getStringSet(KEY_DESKTOP_SITES, emptySet()).orEmpty(),
            prefs.getStringSet(KEY_DESKTOP_SITES_OFF, emptySet()).orEmpty()
        )
        prefs.edit()
            .putStringSet(KEY_DESKTOP_SITES, on)
            .putStringSet(KEY_DESKTOP_SITES_OFF, off)
            .apply()
    }

    /** Apply the resolved mode before a navigation when the target host differs. */
    private fun ensureDesktopModeFor(webView: WebView, url: String) {
        val enabled = isDesktopModeFor(url)
        if (webView.settings.userAgentString != desktopUserAgent(enabled)) {
            applyDesktopMode(webView, enabled)
        }
    }

    private fun navigateBack(webView: WebView) {
        if (!webView.canGoBack()) return
        val history = webView.copyBackForwardList()
        val target = history.getItemAtIndex(history.currentIndex - 1)?.url
        if (target != null) ensureDesktopModeFor(webView, target)
        webView.goBack()
    }

    private fun navigateForward(webView: WebView) {
        if (!webView.canGoForward()) return
        val history = webView.copyBackForwardList()
        val target = history.getItemAtIndex(history.currentIndex + 1)?.url
        if (target != null) ensureDesktopModeFor(webView, target)
        webView.goForward()
    }

    private fun setDesktopModeForAll(enabled: Boolean) {
        prefs.edit()
            .putBoolean(KEY_DESKTOP_ALL, enabled)
            .putStringSet(KEY_DESKTOP_SITES, emptySet())
            .putStringSet(KEY_DESKTOP_SITES_OFF, emptySet())
            .putBoolean(PhormiDesktopMode.KEY_POLICY_DIRTY, true)
            .apply()
    }

    /** Push the UA/viewport setting to every open tab. Live website tabs are reloaded
     * so a global change is visible immediately; dormant tabs apply it on resume. */
    private fun applyDesktopModeToAllTabs(reloadLiveSites: Boolean = false) {
        tabs.forEach { tab ->
            val view = tab.webView ?: return@forEach
            val url = tab.currentUrl
            runCatching {
                applyDesktopMode(view, isDesktopModeFor(url))
                if (reloadLiveSites && tab.deferredUrl == null &&
                    (url.startsWith("https://") || url.startsWith("http://")) &&
                    desktopHost(url) != PhormiSearchHistory.HOST) {
                    view.reload()
                }
            }
        }
    }

    private fun applyDesktopUserAgentMetadata(settings: WebSettings, enabled: Boolean) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) return
        runCatching {
            val major = Regex("Chrome/(\\d+)").find(WebSettings.getDefaultUserAgent(this))
                ?.groupValues?.getOrNull(1) ?: "120"
            val builder = androidx.webkit.UserAgentMetadata.Builder()
                .setMobile(!enabled)
                .setPlatform(if (enabled) "Linux" else "Android")
                .setPlatformVersion("")
                .setArchitecture(if (enabled) "x86" else "")
                .setBitness(if (enabled) 64 else androidx.webkit.UserAgentMetadata.BITNESS_DEFAULT)
                .setModel("")
                .setBrandVersionList(
                    listOf(
                        androidx.webkit.UserAgentMetadata.BrandVersion.Builder()
                            .setBrand("Chromium").setMajorVersion(major).setFullVersion("$major.0.0.0").build(),
                        androidx.webkit.UserAgentMetadata.BrandVersion.Builder()
                            .setBrand("Not.A/Brand").setMajorVersion("99").setFullVersion("99.0.0.0").build(),
                        androidx.webkit.UserAgentMetadata.BrandVersion.Builder()
                            .setBrand("Google Chrome").setMajorVersion(major).setFullVersion("$major.0.0.0").build()
                    )
                )
            if (WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA_FORM_FACTORS)) {
                builder.setFormFactors(
                    listOf(
                        if (enabled) androidx.webkit.UserAgentMetadata.FORM_FACTOR_DESKTOP
                        else androidx.webkit.UserAgentMetadata.FORM_FACTOR_MOBILE
                    )
                )
            }
            WebSettingsCompat.setUserAgentMetadata(settings, builder.build())
        }
    }

    private fun applyDesktopMode(webView: WebView, enabled: Boolean) {
        val settings = webView.settings
        settings.userAgentString = desktopUserAgent(enabled)
        settings.useWideViewPort = true
        // Overview scaling fits the wide desktop layout to the screen; pinch zoom
        // remains available. Mobile pages still use their device-width viewport.
        settings.loadWithOverviewMode = true
        settings.textZoom = PhormiBrowserPreferences.textZoom(this)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.layoutAlgorithm = if (enabled) WebSettings.LayoutAlgorithm.NORMAL
        else WebSettings.LayoutAlgorithm.TEXT_AUTOSIZING
        webView.setInitialScale(0)
        applyDesktopUserAgentMetadata(settings, enabled)
    }

    private fun addCurrentSiteToHomeScreen() {
        val view = activeWebView() ?: return
        val pageUrl = view.url.orEmpty()
        if (!pageUrl.startsWith("http://") && !pageUrl.startsWith("https://")) {
            Toast.makeText(this, "Open a website first.", Toast.LENGTH_SHORT).show()
            return
        }
        val titleFallback = view.title.orEmpty().ifBlank {
            Uri.parse(pageUrl).host.orEmpty().ifBlank { "Phormi site" }
        }
        // An ordinary Android home-screen shortcut can still borrow the useful PWA
        // metadata that is available locally: the page title and its declared site icon.
        // It remains a shortcut into Phormi; it is deliberately not presented as a WebAPK.
        view.evaluateJavascript("(document.title || '')") { rawTitle ->
            val title = runCatching {
                org.json.JSONTokener(rawTitle).nextValue() as? String
            }.getOrNull().orEmpty().ifBlank { titleFallback }

            Thread {
                val icon = fetchSiteIcon(pageUrl)
                runOnUiThread {
                    val id = "site_" + Integer.toHexString(pageUrl.hashCode())
                    val intent = Intent(this, MainActivity::class.java).apply {
                        action = Intent.ACTION_VIEW
                        data = Uri.parse(pageUrl)
                        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    }
                    val shortcut = ShortcutInfo.Builder(this, id)
                        .setShortLabel(title.take(25))
                        .setLongLabel("Open $title in Phormi")
                        .setActivity(ComponentName(this, MainActivity::class.java))
                        .setIcon(icon?.let { Icon.createWithBitmap(it) }
                            ?: Icon.createWithResource(this, R.mipmap.ic_launcher))
                        .setIntent(intent)
                        .build()

                    val manager = getSystemService(ShortcutManager::class.java)
                    if (manager.isRequestPinShortcutSupported) {
                        manager.requestPinShortcut(shortcut, null)
                        Toast.makeText(this, "Choose where to add the site shortcut.", Toast.LENGTH_SHORT).show()
                    } else {
                        manager.dynamicShortcuts = (manager.dynamicShortcuts + shortcut)
                            .take(manager.maxShortcutCountPerActivity.coerceAtLeast(1))
                        Toast.makeText(this, "Site shortcut added to Phormi shortcuts.", Toast.LENGTH_SHORT).show()
                    }
                }
            }.start()
        }
    }

    private fun fetchSiteIcon(pageUrl: String): Bitmap? {
        fun loadBitmap(iconUrl: String): Bitmap? = runCatching {
            val connection = (URL(iconUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 5000
                instanceFollowRedirects = true
                requestMethod = "GET"
                setRequestProperty("User-Agent", WebSettings.getDefaultUserAgent(this@MainActivity))
            }
            connection.inputStream.use { BitmapFactory.decodeStream(it) }.also { connection.disconnect() }
        }.getOrNull()

        val base = URL(pageUrl)
        val candidates = mutableListOf<String>()
        // Prefer the site's declared icon instead of assuming /favicon.ico. This covers
        // platforms that use a manifest/icon path or a non-root favicon.
        runCatching {
            val connection = (URL(pageUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 5000
                instanceFollowRedirects = true
                requestMethod = "GET"
                setRequestProperty("User-Agent", WebSettings.getDefaultUserAgent(this@MainActivity))
                setRequestProperty("Accept", "text/html,*/*;q=0.8")
            }
            val html = connection.inputStream.bufferedReader().use { it.readText().take(1_000_000) }.also { connection.disconnect() }
            val iconMatches = Regex("""<link[^>]+rel=["'][^"']*icon[^"']*["'][^>]+href=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .findAll(html).map { it.groupValues[1] }.toList()
            iconMatches.forEach { href -> candidates += URL(base, href).toString() }
        }
        candidates += URL(base, "/favicon.ico").toString()
        candidates += "https://www.google.com/s2/favicons?sz=128&domain_url=${URLEncoder.encode(pageUrl, "UTF-8")}"
        return candidates.distinct().asSequence().mapNotNull(::loadBitmap).firstOrNull()
    }

    private fun fetchWebManifest(pageUrl: String, manifestHref: String): JSONObject? {
        val candidates = mutableListOf<String>()
        if (manifestHref.isNotBlank()) {
            candidates += runCatching { URL(URL(pageUrl), manifestHref).toString() }.getOrDefault(manifestHref)
        }
        val base = URL(pageUrl)
        candidates += URL(base, "/manifest.json").toString()
        candidates += URL(base, "/site.webmanifest").toString()
        return candidates.distinct().asSequence().mapNotNull { url ->
            runCatching {
                val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 5000
                    readTimeout = 5000
                    instanceFollowRedirects = true
                    requestMethod = "GET"
                    setRequestProperty("Accept", "application/manifest+json,application/json,*/*")
                    setRequestProperty("User-Agent", WebSettings.getDefaultUserAgent(this@MainActivity))
                }
                val body = connection.inputStream.bufferedReader().use { it.readText().take(1_000_000) }
                    .also { connection.disconnect() }
                JSONObject(body)
            }.getOrNull()
        }.firstOrNull()
    }

    private fun fetchBitmap(iconUrl: String): Bitmap? = runCatching {
        val connection = (URL(iconUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5000
            readTimeout = 5000
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("User-Agent", WebSettings.getDefaultUserAgent(this@MainActivity))
        }
        connection.inputStream.use { BitmapFactory.decodeStream(it) }.also { connection.disconnect() }
    }.getOrNull()

    /**
     * One-tap desktop-site toggle for the current website. This intentionally does
     * not open a scope chooser: the menu row is the switch. The global default lives
     * in Browser settings so the common action stays immediate and predictable.
     */
    private fun toggleDesktopMode() {
        val view = activeWebView() ?: return
        val currentUrl = view.url.orEmpty()
        val host = desktopHost(currentUrl)
        if (host == null || host == PhormiSearchHistory.HOST ||
            (!currentUrl.startsWith("https://") && !currentUrl.startsWith("http://"))) {
            Toast.makeText(this, "Open a website first, then use Desktop site.", Toast.LENGTH_SHORT).show()
            return
        }
        val next = !isDesktopModeFor(currentUrl)
        setDesktopModeForHost(host, next)
        tabs.forEach { tab ->
            val target = tab.webView ?: return@forEach
            if (target === view || desktopHost(tab.currentUrl) == host) {
                applyDesktopMode(target, next)
                if (tab.deferredUrl == null &&
                    (tab.currentUrl.startsWith("https://") || tab.currentUrl.startsWith("http://"))) {
                    target.reload()
                }
            }
        }
        val state = if (next) "on" else "off"
        Toast.makeText(this, "Desktop site $state for $host", Toast.LENGTH_SHORT).show()
    }

    fun handleCentralHubCommand(command: JSONObject): JSONObject {
        return try {
            when (command.optString("command")) {
                "memory_state" -> tabMemoryDiagnostics().put("ok", true)
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
                "back" -> { activeWebView()?.let { navigateBack(it) }; JSONObject().put("ok", true) }
                "forward" -> { activeWebView()?.let { navigateForward(it) }; JSONObject().put("ok", true) }
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
