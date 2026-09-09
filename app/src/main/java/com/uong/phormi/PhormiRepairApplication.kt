package com.uong.phormi

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import java.lang.reflect.Method

/** Runtime bridge for nested browser surfaces and safe environment lifecycle work. */
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
        // Do this before MainActivity creates any WebViews. ProfileStore refuses to delete
        // profiles that are already loaded in memory, so startup is the reliable cleanup point.
        runCatching { PhormiEnvironmentManager.cleanupExpired(this, emptySet()) }
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
