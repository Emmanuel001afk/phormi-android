package com.uong.phormi

import android.app.Application
import android.content.Context
import android.view.View
import android.webkit.WebView
import android.widget.EditText
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/** Reproduce late previous-tab completions while the user edits a new Home URL. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class BrowserHomeNavigationTest {
    private lateinit var controller: ActivityController<MainActivity>
    private val prefs get() = RuntimeEnvironment.getApplication().getSharedPreferences("phormi_tabs", Context.MODE_PRIVATE)
    @Before fun setup() {
        prefs.edit().clear().putBoolean("news_enabled", false).commit()
        val now = System.currentTimeMillis()
        BrowserSessionStore(prefs).write(BrowserSession(listOf(
            BrowserTabSnapshot(1, "https://old.example/page", "Yesterday's work", now, now, "Default", null),
            BrowserTabSnapshot(2, "https://background.example/doc", "Background work", now, now, "Default", null)
        ), 1, false), true)
        controller = Robolectric.buildActivity(MainActivity::class.java).create().start().resume().visible()
    }
    @After fun cleanup() { controller.pause().stop().destroy() }
    private fun views(): List<WebView> = ReflectionHelpers.getField<List<Any>>(controller.get(), "tabs").map {
        ReflectionHelpers.getField<WebView>(it, "webView")
    }
    @Test fun `three late completions cannot dismiss Home or replace its typed URL`() {
        val activity = controller.get()
        activity.findViewById<View>(R.id.btn_home).performClick()
        val search = activity.findViewById<EditText>(R.id.start_page_search)
        search.requestFocus(); search.setText("https://my-new-address.example")
        val views = views()
        repeat(3) { index ->
            val view = views[index % views.size]
            view.webViewClient.onPageStarted(view, "https://old.example/late-$index", null)
            view.webViewClient.onPageFinished(view, "https://old.example/late-$index")
            requireNotNull(view.webChromeClient).onReceivedTitle(view, "Old page title $index")
            assertFalse(requireNotNull(view.webChromeClient).onCreateWindow(view, false, false, null))
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.start_page_container).visibility)
            assertEquals("https://my-new-address.example", search.text.toString())
            assertEquals(1, ReflectionHelpers.getField<Int>(activity, "activeTabId"))
        }
    }
    @Test fun `AI queue is not drained before the lifecycle reaches Resumed`() {
        controller.pause()
        val context = RuntimeEnvironment.getApplication()
        PhormiAiPendingTask.enqueue(context, "tab:1", "Write a draft")
        ReflectionHelpers.callInstanceMethod<Unit>(controller.get(), "runPendingAiTask")
        assertEquals(1, PhormiAiPendingTask.pendingCount(context))
        PhormiAiPendingTask.cancelAll(context)
        controller.resume()
    }
    @Test fun `unloaded restore preserves addresses instead of saving blank New tab pages`() {
        val restored = BrowserSessionStore(prefs).read()!!.tabs
        assertEquals(listOf("https://old.example/page", "https://background.example/doc"), restored.map { it.url })
        assertEquals(listOf(1, 2), restored.map { it.id })
        assertEquals(listOf("Yesterday's work", "Background work"), restored.map { it.title })
    }
}
