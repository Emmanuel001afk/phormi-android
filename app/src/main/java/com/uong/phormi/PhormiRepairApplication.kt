package com.uong.phormi

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.lang.reflect.Method
import java.util.WeakHashMap

/** Runtime bridge for nested browser surfaces, safe environment lifecycle work, browser AI handoff, downloads, and fullscreen media controls. */
class PhormiRepairApplication : Application() {
    private val handler = Handler(Looper.getMainLooper())
    private var resumedMain: MainActivity? = null
    private var lastEnvironmentCleanup = 0L
    private var aiTaskRunning = false
    private val interceptedDownloads = WeakHashMap<WebView, Boolean>()
    private val mediaHosts = WeakHashMap<FrameLayout, Boolean>()
    private val mediaTouchInstalled = WeakHashMap<View, Boolean>()
    private val mediaBrightness = WeakHashMap<Activity, Float>()
    private val poll = object : Runnable {
        override fun run() {
            resumedMain?.let { process(it) }
            handler.postDelayed(this, 350L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        runCatching { PhormiEnvironmentManager.cleanupExpired(this, emptySet()) }
        PhormiKeyboardAiBridge.start(this)
        PhormiDownloadService.resumePending(this)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                if (activity is MainActivity) {
                    resumedMain = activity
                    process(activity)
                }
            }
            override fun onActivityPaused(activity: Activity) { if (activity === resumedMain) resumedMain = null }
            override fun onActivityCreated(a: Activity, b: Bundle?) = Unit
            override fun onActivityStarted(a: Activity) = Unit
            override fun onActivityStopped(a: Activity) = Unit
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) = Unit
            override fun onActivityDestroyed(a: Activity) { if (a === resumedMain) resumedMain = null }
        })
        handler.post(poll)
    }

    private fun process(activity: MainActivity) {
        installDownloadInterceptors(activity)
        installFullscreenMediaControls(activity)

        val activeProfiles = mutableSetOf<String>()
        (getField(activity, "tabs") as? MutableList<*>)?.forEach { tab ->
            (getField(tab, "profileName") as? String)?.let { activeProfiles += it }
        }
        val now = System.currentTimeMillis()
        if (now - lastEnvironmentCleanup >= 60_000L) {
            lastEnvironmentCleanup = now
            PhormiEnvironmentManager.cleanupExpired(activity, activeProfiles)
        }
        PhormiCommandBus.drain(activity).forEach { command -> runCatching { dispatch(activity, command.action, command.extras) } }
        startPendingAiTask(activity)
    }

    /** Replace the built-in DownloadManager listener with Phormi's resumable downloader. */
    private fun installDownloadInterceptors(activity: MainActivity) {
        val tabs = getField(activity, "tabs") as? Iterable<*> ?: return
        tabs.forEach { tab ->
            val webView = getField(tab, "webView") as? WebView ?: return@forEach
            if (interceptedDownloads.containsKey(webView)) return@forEach
            interceptedDownloads[webView] = true
            webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                if (url.isNullOrBlank()) return@setDownloadListener
                when {
                    url.startsWith("blob:", true) -> {
                        invoke(activity, "downloadBlobUrl", url, contentDisposition, mimeType)
                    }
                    url.startsWith("data:", true) -> {
                        invoke(activity, "downloadDataUrl", url, contentDisposition, mimeType)
                    }
                    else -> {
                        val effectiveAgent = userAgent?.takeIf { it.isNotBlank() } ?: webView.settings.userAgentString
                        val referer = webView.url ?: url
                        val cookies = runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()
                        val info = runCatching {
                            PhormiDownloadSupport.resolve(url, contentDisposition, mimeType, effectiveAgent, referer, cookies)
                        }.getOrNull()
                        if (info != null) {
                            PhormiDownloadService.enqueue(applicationContext, info)
                            android.widget.Toast.makeText(applicationContext, "Downloading ${info.fileName}", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }

    /**
     * MainActivity's WebChromeClient owns fullscreen web-video views. The standalone
     * PhormiMediaViewerActivity cannot control those views, so install the requested
     * controls directly on the real fullscreen container once it appears.
     */
    private fun installFullscreenMediaControls(activity: MainActivity) {
        val container = getField(activity, "fullscreenContainer") as? FrameLayout ?: return
        if (mediaHosts.containsKey(container)) return
        mediaHosts[container] = true

        // MainActivity currently forces landscape when entering a web video's fullscreen
        // mode. Override that to sensor orientation so the viewer genuinely auto-rotates.
        runCatching { activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR }

        val auto = TextView(activity).apply {
            text = "↻ AUTO"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(dp(activity, 14), dp(activity, 8), dp(activity, 14), dp(activity, 8))
            setBackgroundColor(0x88000000.toInt())
            contentDescription = "Toggle automatic rotation"
            setOnClickListener {
                val enabled = text.toString().contains("AUTO")
                if (enabled) {
                    activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    text = "↻ LOCK"
                } else {
                    activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR
                    text = "↻ AUTO"
                }
            }
        }
        container.addView(auto, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, dp(activity, 48)).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = dp(activity, 10)
            rightMargin = dp(activity, 10)
        })

        val hint = TextView(activity).apply {
            text = "Left: brightness  •  Right: volume"
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(0xDDFFFFFF.toInt())
            setPadding(dp(activity, 14), dp(activity, 7), dp(activity, 14), dp(activity, 7))
            setBackgroundColor(0x66000000.toInt())
        }
        container.addView(hint, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, dp(activity, 38)).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(activity, 14)
        })
        handler.postDelayed({ hint.visibility = View.GONE }, 3500L)

        val customView = getField(activity, "customView") as? View ?: return
        if (!mediaTouchInstalled.containsKey(customView)) {
            mediaTouchInstalled[customView] = true
            val state = floatArrayOf(0f, 0f, 0f, 0f)
            customView.setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        state[0] = event.x
                        state[1] = event.y
                        state[2] = event.y
                        state[3] = 0f
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dy = event.y - state[2]
                        if (kotlin.math.abs(dy) > 1f && view.height > 0) {
                            val leftSide = state[0] < view.width * 0.5f
                            if (leftSide) adjustActivityBrightness(activity, -dy / view.height.toFloat())
                            else adjustActivityVolume(activity, -dy / view.height.toFloat())
                            state[3] = 1f
                        }
                        state[2] = event.y
                    }
                }
                // Return false so the site's native video controls still receive taps and
                // the browser's existing custom-view handling remains intact.
                false
            }
        }
    }

    private fun adjustActivityBrightness(activity: Activity, delta: Float) {
        val current = mediaBrightness[activity] ?: activity.window.attributes.screenBrightness.takeIf { it >= 0f } ?: 0.5f
        val next = (current + delta).coerceIn(0.02f, 1f)
        mediaBrightness[activity] = next
        activity.window.attributes = activity.window.attributes.apply { screenBrightness = next }
        showMediaIndicator(activity, "☀ ${((next * 100).toInt())}%")
    }

    private fun adjustActivityVolume(activity: Activity, delta: Float) {
        val audio = activity.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        val max = audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val current = audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
        val next = (current + delta * max).toInt().coerceIn(0, max)
        audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, next, 0)
        showMediaIndicator(activity, "🔊 ${((next * 100f) / max).toInt()}%")
    }

    private fun showMediaIndicator(activity: Activity, text: String) {
        val root = getField(activity, "fullscreenContainer") as? FrameLayout ?: return
        val existing = root.findViewWithTag<TextView>("phormi_browser_media_indicator")
        val indicator = existing ?: TextView(activity).apply {
            tag = "phormi_browser_media_indicator"
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(dp(activity, 24), dp(activity, 14), dp(activity, 24), dp(activity, 14))
            setBackgroundColor(0xDD111827.toInt())
            root.addView(this, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER })
        }
        indicator.text = text
        indicator.visibility = View.VISIBLE
        handler.postDelayed({ indicator.visibility = View.GONE }, 850L)
    }

    private fun dp(activity: Activity, value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()

    private fun startPendingAiTask(activity: MainActivity) {
        if (aiTaskRunning) return
        val task = PhormiAiPendingTask.take(activity) ?: return
        val controller = AiController(applicationContext)
        if (!controller.isActive()) {
            PhormiAiPendingTask.saveStatus(activity, "AI is inactive. Enable a configured provider first.")
            return
        }
        val tabs = getField(activity, "tabs") as? List<*> ?: run {
            PhormiAiPendingTask.saveStatus(activity, "Browser tabs are not ready yet.")
            return
        }

        if (task.target.isNotBlank()) {
            val target = task.target.trim().lowercase()
            val match = tabs.mapNotNull { tab ->
                val id = getField(tab, "id") as? Int ?: return@mapNotNull null
                val title = (getField(tab, "title") as? String).orEmpty()
                val webView = getField(tab, "webView") as? WebView
                val url = webView?.url.orEmpty()
                val haystack = "$title $url".lowercase()
                val score = when {
                    haystack == target -> 100
                    title.lowercase() == target -> 90
                    url.lowercase() == target -> 90
                    haystack.contains(target) -> 60
                    target.startsWith("http") && url.lowercase().contains(target) -> 80
                    else -> 0
                }
                if (score > 0) Triple(score, id, title) else null
            }.maxByOrNull { it.first }

            if (match != null) {
                invoke(activity, "switchToTab", match.second)
            } else if (task.target.startsWith("http://") || task.target.startsWith("https://")) {
                invoke(activity, "createNewTab", task.target)
            } else {
                PhormiAiPendingTask.saveStatus(activity, "AI target tab not found: ${task.target.take(180)}")
                return
            }
        }

        aiTaskRunning = true
        activity.lifecycleScope.launch {
            try {
                delay(500L)
                controller.runTask(task.instruction) { status ->
                    PhormiAiPendingTask.saveStatus(applicationContext, status)
                }
            } catch (t: Throwable) {
                PhormiAiPendingTask.saveStatus(applicationContext, "AI task failed: ${t.message ?: "unknown error"}")
            } finally {
                aiTaskRunning = false
            }
        }
    }

    private fun dispatch(activity: MainActivity, action: String, extras: Map<String, String>) {
        when (action) {
            "new_tab" -> invoke(activity, "createNewTab", "about:blank")
            "ghost" -> activity.startActivity(Intent(activity, GhostActivity::class.java))
            "browser_lock" -> invoke(activity, "showBrowserLockOverlay")
            "theme" -> invoke(activity, "showAppearanceChooser")
            "tab_retention" -> invoke(activity, "showTabRetentionChooser")
            "pull_to_refresh" -> invoke(activity, "showPullToRefreshChooser")
            "favorite" -> invoke(activity, "addCurrentPageToBookmarks")
            "keep_screen_on" -> {
                val prefs = activity.getSharedPreferences("phormi_tabs", Context.MODE_PRIVATE)
                val enabled = !prefs.getBoolean("keep_screen_on", false)
                prefs.edit().putBoolean("keep_screen_on", enabled).apply()
                invoke(activity, "applyKeepScreenOn", enabled)
            }
            "desktop_mode" -> invoke(activity, "toggleDesktopMode")
            "find" -> invoke(activity, "showFindInPage")
            "share" -> invoke(activity, "shareCurrentPage")
            "select" -> extras["tab_id"]?.toIntOrNull()?.let { invoke(activity, "switchToTab", it) }
            "close" -> extras["tab_id"]?.toIntOrNull()?.let { invoke(activity, "closeTab", it) }
            "assign_group" -> TabGroupManager(activity).assignTab(extras["group_id"].orEmpty(), extras["tab_id"]?.toIntOrNull() ?: 0, extras["url"].orEmpty())
            "reassign_env" -> reassignEnvironment(activity, extras)
            "toggle_split", "split_screen", "same_page_split" -> invoke(activity, "setSplitMode", !(getField(activity, "splitMode") as? Boolean ?: false))
            "navigation_lens" -> invoke(activity, "showNavigationLens")
            "object_anchors" -> invoke(activity, "showObjectAnchors")
            "security" -> activity.startActivity(Intent(activity, PhormiSecurityCenterActivity::class.java))
            "keyboard" -> activity.startActivity(Intent(activity, PhormiKeyboardSettingsActivity::class.java))
            "tab_groups" -> activity.startActivity(Intent(activity, TabGroupsActivity::class.java))
            "open_url" -> extras["open_url"]?.takeIf { it.isNotBlank() }?.let { invoke(activity, "createNewTab", it) }
        }
    }

    private fun reassignEnvironment(activity: MainActivity, extras: Map<String, String>) {
        val index = extras["index"]?.toIntOrNull() ?: return
        val profile = extras["profile"].orEmpty()
        if (profile.isBlank()) return
        val tabs = getField(activity, "tabs") as? MutableList<*> ?: return
        val tab = tabs.getOrNull(index) ?: return
        val id = getField(tab, "id") as? Int ?: return
        invoke(activity, "reassignTabEnvironment", id, profile)
    }

    private fun invoke(target: Any, name: String, vararg args: Any?) = invokeResultOrNull(target, name, *args)
    private fun invokeResultOrNull(target: Any, name: String, vararg args: Any?): Any? {
        val method = findMethod(target.javaClass, name, args) ?: return null
        return runCatching { method.isAccessible = true; method.invoke(target, *args) }.getOrNull()
    }
    private fun findMethod(type: Class<*>, name: String, args: Array<out Any?>): Method? =
        generateSequence(type) { it.superclass }.flatMap { it.declaredMethods.asSequence() }.firstOrNull {
            it.name == name && it.parameterTypes.size == args.size && it.parameterTypes.withIndex().all { (i, p) -> compatible(p, args[i]) }
        }
    private fun compatible(type: Class<*>, value: Any?): Boolean {
        if (value == null) return !type.isPrimitive
        if (!type.isPrimitive) return type.isAssignableFrom(value.javaClass)
        return when (type) {
            java.lang.Boolean.TYPE -> value is Boolean
            java.lang.Integer.TYPE -> value is Int
            java.lang.Long.TYPE -> value is Long
            java.lang.Float.TYPE -> value is Float
            java.lang.Double.TYPE -> value is Double
            else -> false
        }
    }
    private fun getField(target: Any?, name: String): Any? {
        if (target == null) return null
        var type: Class<*>? = target.javaClass
        while (type != null) {
            val field = runCatching { type.getDeclaredField(name).apply { isAccessible = true } }.getOrNull()
            if (field != null) return runCatching { field.get(target) }.getOrNull()
            type = type.superclass
        }
        return null
    }
}
