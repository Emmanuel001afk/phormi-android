package com.uong.phormi

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.WebView
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import org.json.JSONArray
import java.lang.reflect.Method

/**
 * Runtime repair bridge. Existing browser surfaces already return actions, but older
 * code paths did not have a central consumer. This bridge consumes those commands
 * without duplicating the browser UI or replacing MainActivity's state model.
 */
class PhormiRepairApplication : Application() {
    private val handler = Handler(Looper.getMainLooper())
    private var resumedMain: MainActivity? = null
    private val poll = object : Runnable {
        override fun run() {
            resumedMain?.let { process(it) }
            handler.postDelayed(this, 350L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        PhormiKeyboardAiBridge.start(this)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                if (activity is MainActivity) {
                    resumedMain = activity
                    process(activity)
                }
            }
            override fun onActivityPaused(activity: Activity) {
                if (activity === resumedMain) resumedMain = null
            }
            override fun onActivityCreated(a: Activity, b: Bundle?) = Unit
            override fun onActivityStarted(a: Activity) = Unit
            override fun onActivityStopped(a: Activity) = Unit
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) = Unit
            override fun onActivityDestroyed(a: Activity) { if (a === resumedMain) resumedMain = null }
        })
        handler.post(poll)
    }

    private fun process(activity: MainActivity) {
        PhormiCommandBus.drain(activity).forEach { command -> runCatching { dispatch(activity, command.action, command.extras) } }
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
            "desktop_mode" -> toggleDesktop(activity)
            "find" -> findInPage(activity)
            "share" -> shareCurrentPage(activity)
            "select" -> selectTab(activity, extras["tab_id"]?.toIntOrNull())
            "close" -> closeTab(activity, extras["tab_id"]?.toIntOrNull())
            "assign_group" -> TabGroupManager(activity).assignTab(extras["group_id"].orEmpty(), extras["tab_id"]?.toIntOrNull() ?: 0, extras["url"].orEmpty())
            "reassign_env" -> reassignEnvironment(activity, extras)
            "toggle_split", "split_screen", "same_page_split" -> toggleSplit(activity)
            "navigation_lens" -> activity.startActivity(Intent(activity, AiActivity::class.java).putExtra("mode", "navigation_lens"))
            "object_anchors" -> activity.startActivity(Intent(activity, AiActivity::class.java).putExtra("mode", "object_anchors"))
            "security" -> activity.startActivity(Intent(activity, PhormiSecurityCenterActivity::class.java))
            "keyboard" -> activity.startActivity(Intent(activity, PhormiKeyboardSettingsActivity::class.java))
            "tab_groups" -> activity.startActivity(Intent(activity, TabGroupsActivity::class.java))
            "open_url" -> extras["open_url"]?.takeIf { it.isNotBlank() }?.let { invoke(activity, "createNewTab", it) }
            "site_lock" -> invoke(activity, "showBrowserLockOverlay")
        }
    }

    private fun activeWebView(activity: Activity): WebView? = invokeResult(activity, "activeWebView") as? WebView

    private fun toggleDesktop(activity: MainActivity) {
        val web = activeWebView(activity) ?: return
        val prefs = activity.getSharedPreferences("phormi_tabs", Context.MODE_PRIVATE)
        val enabled = !prefs.getBoolean("desktop_mode", false)
        prefs.edit().putBoolean("desktop_mode", enabled).apply()
        web.settings.userAgentString = if (enabled) "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36" else null
        web.reload()
    }

    private fun findInPage(activity: MainActivity) {
        val web = activeWebView(activity) ?: return
        val input = EditText(activity).apply { hint = "Find in page"; setSingleLine(true) }
        AlertDialog.Builder(activity).setTitle("Find in page").setView(input).setNegativeButton("Cancel", null).setPositiveButton("Find") { _, _ ->
            input.text.toString().trim().takeIf { it.isNotBlank() }?.let(web::findAllAsync)
        }.show()
    }

    private fun shareCurrentPage(activity: MainActivity) {
        val web = activeWebView(activity) ?: return
        val url = web.url ?: return
        activity.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
            putExtra(Intent.EXTRA_TITLE, web.title ?: url)
        }, "Share page"))
    }

    private fun selectTab(activity: MainActivity, tabId: Int?) {
        val id = tabId ?: return
        val tabs = getField(activity, "tabs") as? MutableList<*> ?: return
        val tab = tabs.firstOrNull { getField(it, "id") == id } ?: return
        setField(activity, "activeTabId", id)
        val container = getField(activity, "webViewContainer") as? android.widget.FrameLayout
        val web = getField(tab, "webView") as? WebView
        if (container != null && web != null) {
            (web.parent as? android.view.ViewGroup)?.removeView(web)
            container.removeAllViews()
            container.addView(web, android.widget.FrameLayout.LayoutParams(-1, -1))
            web.visibility = View.VISIBLE
        }
        invokeFirst(activity, listOf("showActiveTab", "renderActiveTab", "updateActiveTab", "updateTabStrip", "updateNavButtons", "updateUrlBar"))
    }

    private fun closeTab(activity: MainActivity, tabId: Int?) {
        val id = tabId ?: return
        val tabs = getField(activity, "tabs") as? MutableList<Any?> ?: return
        val index = tabs.indexOfFirst { getField(it, "id") == id }
        if (index < 0) return
        val removed = tabs.removeAt(index)
        (getField(removed, "webView") as? WebView)?.destroy()
        if (tabs.isEmpty()) invoke(activity, "createNewTab", "about:blank")
        else (tabs.getOrNull(index.coerceAtMost(tabs.lastIndex)) ?: tabs.last()).let { next -> (getField(next, "id") as? Int)?.let { selectTab(activity, it) } }
        invokeFirst(activity, listOf("saveTabs", "updateTabStrip", "updateNavButtons"))
    }

    private fun reassignEnvironment(activity: MainActivity, extras: Map<String, String>) {
        val index = extras["index"]?.toIntOrNull() ?: return
        val profile = extras["profile"].orEmpty()
        val prefs = activity.getSharedPreferences("phormi_tabs", Context.MODE_PRIVATE)
        val arr = runCatching { JSONArray(prefs.getString("tab_profiles", "[]") ?: "[]") }.getOrElse { JSONArray() }
        while (arr.length() <= index) arr.put("Default")
        arr.put(index, profile)
        prefs.edit().putString("tab_profiles", arr.toString()).apply()
        val tabs = getField(activity, "tabs") as? MutableList<*> ?: return
        tabs.getOrNull(index)?.let { setField(it, "profileName", profile) }
        invokeFirst(activity, listOf("saveTabs", "updateTabStrip"))
    }

    private fun toggleSplit(activity: MainActivity) {
        val current = getField(activity, "splitMode") as? Boolean ?: false
        setField(activity, "splitMode", !current)
        if (!current) {
            val tabs = getField(activity, "tabs") as? MutableList<*> ?: return
            if (tabs.size >= 2) {
                setField(activity, "splitTopTabId", getField(tabs[0], "id") as? Int ?: -1)
                setField(activity, "splitBottomTabId", getField(tabs[1], "id") as? Int ?: -1)
            }
        }
        invokeFirst(activity, listOf("renderSplit", "updateSplitUi", "applySplitMode", "refreshSplitLayout", "updateResponsiveChrome"))
    }

    private fun invokeFirst(target: Any, names: List<String>) {
        names.firstOrNull { invokeResultOrNull(target, it) !== NO_RESULT }
    }

    private fun invoke(target: Any, name: String, vararg args: Any?) = invokeResultOrNull(target, name, *args)
    private fun invokeResult(target: Any, name: String, vararg args: Any?): Any? = invokeResultOrNull(target, name, *args).let { if (it === NO_RESULT) null else it }
    private fun invokeResultOrNull(target: Any, name: String, vararg args: Any?): Any? {
        val method = findMethod(target.javaClass, name, args) ?: return NO_RESULT
        return runCatching { method.isAccessible = true; method.invoke(target, *args) }.getOrElse { NO_RESULT }
    }
    private fun findMethod(type: Class<*>, name: String, args: Array<out Any?>): Method? = generateSequence(type) { it.superclass }.flatMap { it.declaredMethods.asSequence() }.firstOrNull {
        it.name == name && it.parameterTypes.size == args.size && it.parameterTypes.withIndex().all { (i, p) -> compatible(p, args[i]) }
    }
    private fun compatible(type: Class<*>, value: Any?): Boolean {
        if (value == null) return !type.isPrimitive
        if (!type.isPrimitive) return type.isAssignableFrom(value.javaClass)
        return when (type) { java.lang.Boolean.TYPE -> value is Boolean; java.lang.Integer.TYPE -> value is Int; java.lang.Long.TYPE -> value is Long; java.lang.Float.TYPE -> value is Float; java.lang.Double.TYPE -> value is Double; else -> false }
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
    private fun setField(target: Any, name: String, value: Any?) {
        var type: Class<*>? = target.javaClass
        while (type != null) {
            val field = runCatching { type.getDeclaredField(name).apply { isAccessible = true } }.getOrNull()
            if (field != null) { field.set(target, value); return }
            type = type.superclass
        }
    }
    private object NO_RESULT
}
