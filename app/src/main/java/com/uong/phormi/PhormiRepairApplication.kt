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
 * Runtime bridge for nested browser surfaces. MainActivity is still the source of
 * truth for tab state; this bridge only forwards queued actions to its real methods.
 */
class PhormiRepairApplication : Application() {
    private val handler = Handler(Looper.getMainLooper())
    private var resumedMain: MainActivity? = null
    private var lastEnvironmentCleanup = 0L
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
            "toggle_split", "split_screen", "same_page_split" -> invoke(activity, "setSplitMode", !(getField(activity, "splitMode") as? Boolean ?: false))
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
        invoke(activity, "toggleDesktopMode")
    }

    private fun findInPage(activity: MainActivity) { invoke(activity, "showFindInPage") }

    private fun shareCurrentPage(activity: MainActivity) { invoke(activity, "shareCurrentPage") }

    private fun selectTab(activity: MainActivity, tabId: Int?) {
        tabId?.let { invoke(activity, "switchToTab", it) }
    }

    private fun closeTab(activity: MainActivity, tabId: Int?) {
        tabId?.let { invoke(activity, "closeTab", it) }
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
    private fun invokeResult(target: Any, name: String, vararg args: Any?): Any? = invokeResultOrNull(target, name, *args).let { if (it === NO_RESULT) null else it }
    private fun invokeResultOrNull(target: Any, name: String, vararg args: Any?): Any? {
        val method = findMethod(target.javaClass, name, args) ?: return NO_RESULT
        return runCatching { method.isAccessible = true; method.invoke(target, *args) }.getOrElse { NO_RESULT }
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
    private object NO_RESULT
}
